// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import app.getknit.spool.protocol.A_CHUNK_BYTES
import app.getknit.spool.protocol.Achunk
import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ahas
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.Cast
import app.getknit.spool.protocol.CastInfo
import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.CommonsInfo
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Ent
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.ID_BYTES
import app.getknit.spool.protocol.Limits
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pow
import app.getknit.spool.protocol.PowStamp
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.RoomInfo
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.TurnCred
import app.getknit.spool.protocol.TurnReq
import app.getknit.spool.protocol.TurnUris
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeoutException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Grace window in which a record that must NOT arrive is awaited (uploader echo, duplicate event). */
private const val EVENT_GRACE_MS = 500L

/** A cast's payload in the cast checks: about a sealed walkie chunk. */
private const val CAST_PROBE_BYTES = 797

/** The burst a cast budget allows, as a multiple of its advertised rate (SPOOL_PROTOCOL.md §7.7). */
private const val CAST_BURST_FACTOR = 4

/** How many casts past the burst `cast-rate` sends. */
private const val CAST_RATE_EXCESS = 10

/** Generous CBOR envelope allowance for a push record beyond its `data` payload. */
private const val PUSH_RECORD_OVERHEAD = 128L

/** Ceiling on the applied `maxFrames` this suite will fill past to force an eviction. */
private const val MAX_EVICTION_FILL = 32

/** Ceiling on `maxScopes` the destructive quota check will try to exhaust. */
private const val MAX_QUOTA_PROBE = 4_096

/** Ceiling on `maxPull` the overflow check will exceed (each id costs 34 encoded bytes). */
private const val MAX_PULL_PROBE = 65_536

/** A `maxScopes` above this is not probed: the over-cap `sub` would not fit `maxRecord` anyway. */
private const val MAX_SCOPES_PROBE = 65_536

/** A TURN user: `<exp>:<16 base32 chars>` (proposed §7.6, S-7.6-6). */
private val TURN_USER = Regex("^\\d+:[a-z2-7]{16}$")

private const val MS_PER_S = 1_000L

/** The longest credential lifetime this suite accepts as sane: a week. */
private const val MAX_TURN_TTL_S = 604_800L

/** A `stun:`/`stuns:` URI, and no `@`: a STUN list carries no credential (S-7.6-13). */
private val STUN_URI = Regex("^stuns?:[^\\s,@]+$")

/** Tickets this suite mints start a minute ago and last a day: well inside any honest skew and window. */
private const val TICKET_BACKDATE_S = 60L
private const val TICKET_LIFETIME_S = 86_400L

/** An audience no spool is named: `.invalid` is reserved for exactly this (RFC 2606). */
private const val FOREIGN_AUDIENCE = "conformance.invalid"

/** A `turnreq` host no spool can list: `.invalid` is reserved (RFC 2606), so it matches no entry. */
private const val UNKNOWN_TURN_HOST = "turn-hosts-select.invalid:3478"

/** The most TURN servers `turnHosts` may name (S-7.6-48). */
private const val MAX_TURN_HOSTS = 4

/** Upper bound on pushes blasted by the rate-limit probe. */
private const val RATE_BLAST_MAX = 1_000

/**
 * Pushes the rate-limit probe's second blast leaves unanswered at once. Each push past the budget
 * is a strike, and a spool closes 4003 after a few (this repo's daemon: 8 in 10 s,
 * `RATE_STRIKE_LIMIT`), so this many cost too few strikes for a close. It is also this many per
 * round trip, which is why the first blast is unbounded: a far spool with a generous budget refills
 * faster than four pushes a round trip can drain it.
 */
private const val RATE_BLAST_WINDOW = 4

/** Final drain window for the rate-limit probe after the blast. */
private const val RATE_DRAIN_MS = 2_000L

/** Small, valid bounds every non-eviction check declares — far under any sane spool cap. */
private fun defaultBounds(): ScopeBounds = ScopeBounds(maxFrames = 4, ttlMs = 120_000, maxBlob = 1024)

/**
 * One conformance check: passing = [run] returning, failing = it throwing (with an expected-vs-got
 * message), skipping = it throwing [SkipCheck], an advisory-only shortfall = [Advisory].
 */
class Check(
    val name: String,
    val must: Boolean = true,
    val destructive: Boolean = false,
    val run: suspend (Ctx) -> Unit,
)

/**
 * Everything a check needs: connection factories (tokened and bare), the server [Hello] captured by
 * the probe connection, the run's knobs, and shared helpers (fresh scopes, blobs, PoW mining).
 */
class Ctx(
    val client: SpoolClient,
    val bareClient: SpoolClient,
    val serverHello: Hello,
    val timeoutMs: Long,
    val powLimit: Int,
    val hasToken: Boolean,
    /** A secret per `--commons-invite`, one per room; empty — the checks that need a room's id skip then. */
    val commonsSecrets: List<ByteArray> = emptyList(),
    /** The TURN secret from `--turn-secret-file`, or null — `turn-hmac` skips then. */
    val turnSecret: String? = null,
    /** The ticket signing key from `--ticket-key-file`, or null — the checks that need an accepted ticket skip. */
    val ticketSigningKey: ByteArray? = null,
    /** The audience from `--ticket-audience`; set exactly when [ticketSigningKey] is. */
    val ticketAudience: String? = null,
    /** The spool's second name from `--ticket-audience-alias`, or null — `ent-audience-alias` skips then. */
    val ticketAudienceAlias: String? = null,
) {
    /** Mined stamps by scope hex — a stamp is reusable across connections (the spool caches too). */
    private val stamps = HashMap<String, PowStamp>()

    fun randomScope(): ByteArray = Random.nextBytes(32)

    fun randomBlobId(): ByteArray = Random.nextBytes(32)

    /** Pairs SHA-256(data) with data — the id/payload of a valid push (§6.2). */
    fun blob(data: ByteArray): Pair<ByteArray, ByteArray> = MessageDigest.getInstance("SHA-256").digest(data) to data

    fun randomBlob(size: Int = 48): Pair<ByteArray, ByteArray> = blob(Random.nextBytes(size))

    fun limits(): Limits = serverHello.limits ?: throw CheckFailure("expected limits in the server hello, got none")

    /**
     * An `ent` the spool should accept: this run's key and audience, a window around now, a fresh
     * pseudonym. Each claim can be bent for the checks that want a refusal. [SkipCheck] without a key.
     */
    fun ent(
        q: Long,
        signingKey: ByteArray = ticketSigningKey ?: throw SkipCheck("no --ticket-key-file; no ticket this spool accepts can be made"),
        aud: String = ticketAudience ?: FOREIGN_AUDIENCE,
        nbfS: Long = System.currentTimeMillis() / MS_PER_S - TICKET_BACKDATE_S,
        expS: Long = nbfS + TICKET_LIFETIME_S,
        fl: Long = Tickets.FL_TURN,
        ab: Long? = null,
        am: Long? = null,
    ): Ent {
        val ticket =
            Ticket.forKey(
                signingKey,
                aud = aud,
                nbf = nbfS,
                exp = expS,
                ps = Random.nextBytes(Tickets.PS_BYTES),
                fl = fl,
                ab = ab,
                am = am,
            )
        val (tk, sig) = Tickets.issue(signingKey, ticket)
        return Ent(t = RecordType.ENT, q = q, tk = tk, sig = sig)
    }

    /** Whether this spool requires a ticket for [flag] (S-7.6-13): `entGates`, or 9 beside a bare `ent`. */
    fun gated(flag: Long): Boolean = serverHello.ticketGates and flag != 0L

    /**
     * A stamp for [scope] at the advertised difficulty: null when PoW is off, [SkipCheck] when the
     * difficulty exceeds `--pow-limit`, otherwise mined (once — repeats hit the cache).
     */
    fun mineStamp(scope: ByteArray): PowStamp? {
        val bits = serverHello.powBits ?: 0
        if (bits <= 0) return null
        if (bits > powLimit) throw SkipCheck("powBits $bits above --pow-limit $powLimit")
        return stamps.getOrPut(hex(scope)) {
            val day = Pow.utcDay(System.currentTimeMillis())
            val n =
                Pow.stamp(scopeId = scope, day = day, bits = bits)
                    ?: throw CheckFailure("PoW miner gave up before finding a $bits-bit stamp")
            PowStamp(n = n, d = day)
        }
    }

    /** Subscribes [scope] on [session] (mining a stamp when required) and returns its digest. */
    suspend fun subscribeFresh(
        session: Session,
        scope: ByteArray,
        bounds: ScopeBounds = defaultBounds(),
    ): Digest {
        session.send(
            Sub(
                t = RecordType.SUB,
                q = session.nextQ(),
                subs = listOf(ScopeSub(scope = scope, bounds = bounds, pow = mineStamp(scope))),
            ),
        )
        return session.expectDigestFor(scope)
    }
}

/** The check registry, in TAP order. */
fun allChecks(): List<Check> =
    listOf(
        helloFirst(),
        helloVersionMismatch(),
        malformedPreHello(),
        authRequired(),
        notSubscribed(),
        subDigestEmptyScope(),
        boundsClamped(),
        pushOkThenList(),
        pushBadId(),
        pushTooLarge(),
        pullBlobThenOkMissing(),
        pullOverflowTolerated(),
        eventFanout(),
        duplicatePushIdempotent(),
        tombstoneRefusal(),
        evictionDigest(),
        powGate(),
        unknownRecordTolerance(),
        unknownFieldTolerance(),
        subOffLengthScope(),
        subOverMaxScopes(),
        subDuplicateScope(),
        textFrameMalformed(),
        qCorrelation(),
        // rate-limit runs before quota-scopes: the quota probe fills the scope table, after
        // which no later check can create the fresh scope it needs.
        attachmentRoundTrip(),
        attachmentBadId(),
        attachmentFirstWriteWins(),
        attachmentGetTruncated(),
        aputTotalOverQuota(),
        // rate-limit runs before quota-scopes: the quota probe fills the scope table, after
        // which no later check can create the fresh scope it needs.
        commonsAdvertisement(),
        commonsBoundsPinned(),
        commonsFanout(),
        moderationAdvertisement(),
        // Experimental (proposed §7.6). Before rate-limit, which leaves this address's buckets dry.
        turnRequiresSub(),
        turnCredShape(),
        turnHmac(),
        turnHostsSelect(),
        // Experimental (SPOOL_PROTOCOL.md §7.6): entitlement tickets and the STUN list.
        entAdvertised(),
        entForeignKey(),
        entRequired(),
        entAccepts(),
        entExpired(),
        entAudience(),
        entAudienceAlias(),
        stunShape(),
        // Experimental (SPOOL_PROTOCOL.md §7.6, S-7.6-22/23): which capabilities a ticket gates, and files.
        entGates(),
        filesTicket(),
        filesReceive(),
        filesSize(),
        // Experimental (SPOOL_PROTOCOL.md §7.7): casts. cast-rate last of them, since it drains this
        // address's cast budget.
        castAdvertised(),
        castFanout(),
        castSilentRefusal(),
        castTicket(),
        castRate(),
        rateLimit(),
        quotaScopes(),
    )

/**
 * §7.4: whatever a spool says about its commons rooms must be self-consistent and inside its own
 * limits. `commons` alone is a one-room spool from before `rooms` (S-7.4-1); with `rooms`, it lists
 * every room by a distinct eight-byte tag and `commons` repeats the first (S-7.4-15).
 *
 * Runs with or without an invite, because it needs no scope id — which is the point being checked.
 * A spool that leaked an id into `hello` would be advertising a room anybody who connects could
 * flood, and neither [CommonsInfo] nor [RoomInfo] has a field for it to leak into but the one-way tag.
 *
 * Names on a spool this run reached without a token are an advisory shortfall: S-7.4-19 is a SHOULD,
 * and anyone who connects reads that `hello`.
 */
private fun commonsAdvertisement(): Check =
    Check(name = "commons-advertisement", must = false) { ctx ->
        val rooms = ctx.serverHello.rooms
        val commons = ctx.serverHello.commons
        if (commons == null) {
            ensure(rooms == null) { "hello carries `rooms` without `commons`, which repeats the first room (S-7.4-1)" }
            throw SkipCheck("spool advertises no commons (§7.4)")
        }
        val limits = ctx.limits()
        val entries =
            listOf("commons" to Bounds(commons.maxFrames, commons.ttlMs, commons.maxBlob, commons.attach)) +
                rooms.orEmpty().mapIndexed { i, room -> "rooms[$i]" to Bounds(room.maxFrames, room.ttlMs, room.maxBlob, room.attach) }
        for ((label, room) in entries) {
            ensure(
                room.maxFrames in 1..limits.maxFramesCap,
            ) { "$label maxFrames ${room.maxFrames} outside the advertised 1..${limits.maxFramesCap}" }
            ensure(room.ttlMs in 1..limits.maxTtlMs) { "$label ttlMs ${room.ttlMs} outside the advertised 1..${limits.maxTtlMs}" }
            ensure(room.maxBlob in 1..limits.maxBlob) { "$label maxBlob ${room.maxBlob} outside the advertised 1..${limits.maxBlob}" }
            ensure(!room.attach || limits.attachments) { "$label advertises attachments on a spool that advertises no attachment limits" }
        }
        if (rooms != null) {
            ensure(rooms.isNotEmpty()) { "`rooms` is empty; a spool with no rooms sends neither field (S-7.4-15)" }
            ensure(rooms.all { it.tag.size == Commons.TAG_BYTES }) { "a room's tag is not ${Commons.TAG_BYTES} bytes (B-7.4-16)" }
            ensure(rooms.map { it.tag.toHexString() }.toSet().size == rooms.size) { "two rooms share a tag (S-7.4-15)" }
            val first = rooms.first()
            ensure(
                commons.name == first.name && entries[0].second == Bounds(first.maxFrames, first.ttlMs, first.maxBlob, first.attach),
            ) { "`commons` is not `rooms[0]` without its tag (S-7.4-15)" }
        }
        val named = listOfNotNull(commons.name) + rooms.orEmpty().mapNotNull { it.name }
        if (!ctx.hasToken && named.isNotEmpty()) {
            throw Advisory("room names in the hello of a spool reached without a token, which anyone can read (S-7.4-19)")
        }
    }

/** What [commonsAdvertisement] checks of each room, `commons` and `rooms` alike. */
private data class Bounds(
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
    val attach: Boolean,
)

/**
 * §7.5, S-7.5-1: a spool that requires send-side moderation says `moderation: true`, and one that
 * does not says nothing. `false` on the wire is the one shape the spec rules out — presence is the
 * signal, so a client is entitled to read the key's existence alone. Nothing else is checkable from
 * outside: the spool cannot enforce the request, and S-7.5-2 says it must not try.
 */
private fun moderationAdvertisement(): Check =
    Check(name = "moderation-advertisement", must = false) { ctx ->
        val moderation = ctx.serverHello.moderation ?: throw SkipCheck("spool advertises no moderation requirement (§7.5)")
        ensure(moderation) { "hello carries moderation=false; S-7.5-1 omits the field when the spool does not require it" }
    }

/** Skips every TURN check on a spool whose `hello` carried no `turn` (C-7.6-3, experimental). */
private fun Ctx.requireTurn() {
    if (serverHello.turn == null) throw SkipCheck("spool advertises no turn (proposed §7.6)")
    ensure(serverHello.turn == true) { "hello carries turn=false; presence is the signal and false is never sent" }
}

/**
 * Earns one credential the way this spool asks (a ticket where `hello` gates `turn`, a fresh
 * subscription otherwise), then asks for it, naming [host] when given — the shape every TURN check
 * needs. Call once per connection: a second call presents or subscribes again.
 */
private suspend fun Session.fetchTurnCred(
    ctx: Ctx,
    host: String? = null,
): TurnCred {
    if (ctx.gated(Tickets.FL_TURN)) {
        val ent = ctx.ent(nextQ())
        send(ent)
        ensure(expect<Ok>(RecordType.OK).q == ent.q) { "expected ok q=${ent.q} for the ticket" }
    } else {
        ctx.subscribeFresh(this, ctx.randomScope())
    }
    val q = nextQ()
    send(TurnReq(t = RecordType.TURNREQ, q = q, host = host))
    val cred = expect<TurnCred>(RecordType.TURNCRED)
    ensure(cred.q == q) { "expected turncred q=$q echoed, got ${cred.q}" }
    return cred
}

/**
 * Proposed §7.6 (S-7.6-4): a connection holding no subscription is refused `not_subscribed`, echoing
 * `q` and naming no scope — the request has none, and the refusal must not invent one. Where `hello`
 * gates `turn` (S-7.6-13), the ticket gate replaces this one (S-7.6-16) and the refusal is `ticket`.
 */
private fun turnRequiresSub(): Check =
    Check(name = "turn-requires-sub") { ctx ->
        ctx.requireTurn()
        ctx.client.connect {
            hello()
            val q = nextQ()
            send(TurnReq(t = RecordType.TURNREQ, q = q))
            val err = expectErr(if (ctx.gated(Tickets.FL_TURN)) ErrCode.TICKET else ErrCode.NOT_SUBSCRIBED)
            ensure(err.q == q) { "expected err q=$q echoed, got ${err.q}" }
            ensure(err.scope == null) { "a turnreq names no scope, but the refusal carried one" }
        }
    }

/**
 * Proposed §7.6 (S-7.6-6…8): a credential in the coturn REST shape — `turn:`/`turns:` URIs, a user
 * of `<exp>:<16 base32>`, an expiry in the future and inside a week — and a fresh suffix per issue.
 */
private fun turnCredShape(): Check =
    Check(name = "turn-cred-shape") { ctx ->
        ctx.requireTurn()
        ctx.client.connect {
            hello()
            val first = fetchTurnCred(ctx)
            ensure(first.urls.isNotEmpty()) { "turncred carried no urls" }
            first.urls.forEach { url ->
                ensure(url.startsWith("turn:") || url.startsWith("turns:")) { "expected a turn:/turns: URI, got \"$url\"" }
            }
            ensure(TURN_USER.matches(first.user)) { "expected user <exp>:<16 base32 chars>, got \"${first.user}\"" }
            ensure(first.user.substringBefore(':') == first.exp.toString()) {
                "the user's prefix ${first.user.substringBefore(':')} is not exp ${first.exp}"
            }
            val nowS = System.currentTimeMillis() / MS_PER_S
            ensure(first.exp in (nowS + 1)..(nowS + MAX_TURN_TTL_S)) { "exp ${first.exp} is not within a week of now ($nowS)" }
            val q = nextQ()
            send(TurnReq(t = RecordType.TURNREQ, q = q))
            val second = expect<TurnCred>(RecordType.TURNCRED)
            val suffix = first.user.substringAfter(':')
            if (ctx.gated(Tickets.FL_TURN)) {
                // S-7.6-17: one ticket, one pseudonym, one suffix — what lets the TURN server's
                // per-user quota count the holder rather than the credential.
                ensure(second.user.substringAfter(':') == suffix) { "two issues under one ticket must share a suffix (S-7.6-17)" }
            } else {
                ensure(second.user != first.user) { "two issues shared a user; the suffix must be fresh per issue (S-7.6-7)" }
            }
        }
    }

/** Proposed §7.6 (S-7.6-6): `pass = base64(HMAC-SHA1(secret, user))`, checkable only with the operator's secret. */
private fun turnHmac(): Check =
    Check(name = "turn-hmac") { ctx ->
        ctx.requireTurn()
        val secret = ctx.turnSecret ?: throw SkipCheck("no --turn-secret-file; the credential cannot be verified")
        ctx.client.connect {
            hello()
            val cred = fetchTurnCred(ctx)
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val expected = Base64.getEncoder().encodeToString(mac.doFinal(cred.user.toByteArray(Charsets.UTF_8)))
            ensure(cred.pass == expected) { "pass does not verify as base64(HMAC-SHA1(secret, user)) under the given secret" }
        }
    }

/**
 * S-7.6-48…50: a spool that names its TURN servers in `turnHosts` signs for the one `turnreq`'s `host`
 * names, and answers an unknown `host` exactly as it answers none, with server 1, which is
 * `turnHosts`' first entry. A credential's server is read from its UDP URL's entry, as a client reads it.
 *
 * Three credentials over two connections: the last entry and then an unknown host on one, no host on
 * the other. That keeps each connection inside its burst of 2, and the address inside its 8 beside the
 * three `turn-cred-shape` and `turn-hmac` take.
 */
private fun turnHostsSelect(): Check =
    Check(name = "turn-hosts-select") { ctx ->
        ctx.requireTurn()
        val hosts = ctx.serverHello.turnHosts ?: throw SkipCheck("spool advertises no turnHosts (S-7.6-48)")
        ensure(hosts.isNotEmpty()) { "hello carries turnHosts=[]; S-7.6-48 omits it rather than send it empty" }
        ensure(hosts.size <= MAX_TURN_HOSTS && hosts.toSet().size == hosts.size) {
            "turnHosts must hold 1 to $MAX_TURN_HOSTS distinct entries, got $hosts"
        }
        val last = hosts.last()
        lateinit var unknown: TurnCred
        ctx.client.connect {
            hello()
            val named = fetchTurnCred(ctx, host = last)
            val entry = TurnUris.udpEntry(named.urls)
            ensure(entry == last) { "turnreq named $last, but the credential's UDP URL has entry $entry (S-7.6-50)" }
            val q = nextQ()
            send(TurnReq(t = RecordType.TURNREQ, q = q, host = UNKNOWN_TURN_HOST))
            unknown = expect<TurnCred>(RecordType.TURNCRED)
            ensure(unknown.q == q) { "expected turncred q=$q echoed, got ${unknown.q}" }
        }
        ctx.client.connect {
            hello()
            val none = fetchTurnCred(ctx)
            ensure(TurnUris.udpEntry(none.urls) == hosts.first()) {
                "a turnreq with no host got ${none.urls}, not server 1's, whose entry is ${hosts.first()} (S-7.6-49)"
            }
            ensure(unknown.urls == none.urls) {
                "an unknown host got ${unknown.urls}, but no host got ${none.urls}; both must be server 1's (S-7.6-49)"
            }
        }
    }

/** Skips every ticket check on a spool whose `hello` carried no `ent` (S-7.6-13, experimental). */
private fun Ctx.requireEnt() {
    if (serverHello.ent == null) throw SkipCheck("spool advertises no ent (SPOOL_PROTOCOL.md §7.6)")
    ensure(serverHello.ent == true) { "hello carries ent=false; presence is the signal and false is never sent" }
}

/** S-7.6-13: `ent` is present and true, never `false`. */
private fun entAdvertised(): Check = Check(name = "ent-advertised") { ctx -> ctx.requireEnt() }

/**
 * S-7.6-14: a ticket signed by a key the spool does not hold is refused `ticket`, echoing `q`. Needs
 * no secret — any spool that advertises `ent` must refuse a stranger's ticket.
 */
private fun entForeignKey(): Check =
    Check(name = "ent-foreign-key") { ctx ->
        ctx.requireEnt()
        ctx.client.connect {
            hello()
            val ent = ctx.ent(nextQ(), signingKey = Tickets.generateSigningKey())
            send(ent)
            ensure(expectErr(ErrCode.TICKET).q == ent.q) { "expected the ticket refusal to echo q=${ent.q}" }
        }
    }

/** S-7.6-16: where `turn` is gated, a subscription does not substitute for a ticket. */
private fun entRequired(): Check =
    Check(name = "ent-required") { ctx ->
        ctx.requireEnt()
        ctx.requireTurn()
        if (!ctx.gated(Tickets.FL_TURN)) throw SkipCheck("entGates leaves turn open")
        ctx.client.connect {
            hello()
            ctx.subscribeFresh(this, ctx.randomScope())
            val q = nextQ()
            send(TurnReq(t = RecordType.TURNREQ, q = q))
            ensure(expectErr(ErrCode.TICKET).q == q) { "expected the ticket refusal to echo q=$q" }
        }
    }

/** S-7.6-14/15: a ticket under the operator's key, naming this spool, inside its window, is accepted. */
private fun entAccepts(): Check =
    Check(name = "ent-accepts") { ctx ->
        ctx.requireEnt()
        ctx.client.connect {
            hello()
            val ent = ctx.ent(nextQ())
            send(ent)
            ensure(expect<Ok>(RecordType.OK).q == ent.q) { "expected ok q=${ent.q}" }
        }
    }

/** S-7.6-14: a ticket whose window ended well past the skew allowance is refused. */
private fun entExpired(): Check =
    Check(name = "ent-expired") { ctx ->
        ctx.requireEnt()
        ctx.client.connect {
            hello()
            val nbf = System.currentTimeMillis() / MS_PER_S - 2 * TICKET_LIFETIME_S
            send(ctx.ent(nextQ(), nbfS = nbf, expS = nbf + TICKET_LIFETIME_S))
            expectErr(ErrCode.TICKET)
        }
    }

/** S-7.6-14: a good ticket for some other spool is refused here. */
private fun entAudience(): Check =
    Check(name = "ent-audience") { ctx ->
        ctx.requireEnt()
        // Without the key there is no valid signature to put on the wrong audience.
        if (ctx.ticketSigningKey == null) throw SkipCheck("no --ticket-key-file; no ticket this spool accepts can be made")
        ctx.client.connect {
            hello()
            send(ctx.ent(nextQ(), aud = FOREIGN_AUDIENCE))
            expectErr(ErrCode.TICKET)
        }
    }

/**
 * S-7.6-14, S-7.6-47: a spool being renamed takes a ticket for either of its names, on one
 * connection, one after the other. Only the operator knows the second name, so it skips without one.
 */
private fun entAudienceAlias(): Check =
    Check(name = "ent-audience-alias") { ctx ->
        ctx.requireEnt()
        val alias = ctx.ticketAudienceAlias ?: throw SkipCheck("no --ticket-audience-alias; the spool's second name is not known")
        ctx.client.connect {
            hello()
            val second = ctx.ent(nextQ(), aud = alias)
            send(second)
            ensure(expect<Ok>(RecordType.OK).q == second.q) { "expected ok q=${second.q} for a ticket naming $alias" }
            val first = ctx.ent(nextQ())
            send(first)
            ensure(expect<Ok>(RecordType.OK).q == first.q) { "expected ok q=${first.q} for a ticket naming the audience" }
        }
    }

/** S-7.6-13: a `stun` list is non-empty, and every entry a `stun:`/`stuns:` URI with nothing to sign in. */
private fun stunShape(): Check =
    Check(name = "stun-shape") { ctx ->
        val stun = ctx.serverHello.stun ?: throw SkipCheck("spool advertises no stun (SPOOL_PROTOCOL.md §7.6)")
        ensure(stun.isNotEmpty()) { "hello carries an empty stun list; omitted is the off state" }
        stun.forEach { url ->
            ensure(STUN_URI.matches(url)) { "expected a stun:/stuns: URI with no credential, got \"$url\"" }
        }
    }

/** S-7.6-13: `entGates` rides beside `ent`, always, and never without it. */
private fun entGates(): Check =
    Check(name = "ent-gates") { ctx ->
        val gates = ctx.serverHello.entGates
        if (ctx.serverHello.ent == null) {
            ensure(gates == null) { "hello carries entGates=$gates without ent" }
            throw SkipCheck("spool advertises no ent (SPOOL_PROTOCOL.md §7.6)")
        }
        ctx.requireEnt()
        val present = gates ?: throw CheckFailure("hello carries ent without entGates; S-7.6-13 sends them together")
        ensure(present >= 0) { "entGates=$present is negative" }
    }

/** Skips a files check unless this spool gates uploads and this run can mint a files ticket for it. */
private fun Ctx.requireFilesGate() {
    requireAttachments()
    requireEnt()
    if (!gated(Tickets.FL_FILES)) throw SkipCheck("entGates leaves files open")
    if (ticketSigningKey == null) throw SkipCheck("no --ticket-key-file; no files ticket can be made")
}

/**
 * S-7.6-22: where `files` is gated, an unticketed `aput` is refused `ticket`, echoing its `q` and
 * scope, even while another subscriber of the scope holds a files ticket; the uploader's own admits it.
 */
private fun filesTicket(): Check =
    Check(name = "files-ticket") { ctx ->
        ctx.requireFilesGate()
        val scope = ctx.randomScope()
        val aid = ctx.randomScope()
        val (cid, data) = ctx.randomBlob(64)
        val aput = { q: Long -> Aput(t = RecordType.APUT, q = q, scope = scope, aid = aid, idx = 0, total = 1, cid = cid, data = data) }
        ctx.client.connect {
            val free = this
            free.hello()
            ctx.subscribeFresh(free, scope)
            ctx.client.connect {
                val paid = this
                paid.hello()
                ctx.unlockFiles(paid)
                ctx.subscribeFresh(paid, scope)
                val q = free.nextQ()
                free.send(aput(q))
                val err = free.expectErr(ErrCode.TICKET)
                ensure(err.q == q) { "expected the ticket refusal to echo q=$q, got ${err.q}" }
                ensure(err.scope?.contentEquals(scope) == true) { "expected the ticket refusal to carry the aput's scope" }
            }
            ctx.unlockFiles(free)
            val q = free.nextQ()
            free.send(aput(q))
            ensure(free.expect<Ok>(RecordType.OK).q == q) { "expected ok q=$q once the uploader holds a files ticket" }
        }
    }

/** S-7.6-23: a connection with no ticket fetches a chunk a files-ticket holder stored. */
private fun filesReceive(): Check =
    Check(name = "files-receive") { ctx ->
        ctx.requireFilesGate()
        val scope = ctx.randomScope()
        val aid = ctx.randomScope()
        val (cid, data) = ctx.randomBlob(64)
        ctx.client.connect {
            hello()
            ctx.unlockFiles(this)
            ctx.subscribeFresh(this, scope)
            val putQ = nextQ()
            send(Aput(t = RecordType.APUT, q = putQ, scope = scope, aid = aid, idx = 0, total = 1, cid = cid, data = data))
            ensure(expect<Ok>(RecordType.OK).q == putQ) { "expected aput ok q=$putQ" }
            ctx.client.connect {
                hello()
                ctx.subscribeFresh(this, scope)
                val getQ = nextQ()
                send(Aget(t = RecordType.AGET, q = getQ, scope = scope, aid = aid, from = 0, n = 1))
                ensure(expect<Achunk>(RecordType.ACHUNK).data.contentEquals(data)) { "achunk data differs from what was put" }
                ensure(expect<Ok>(RecordType.OK).q == getQ) { "expected aget ok q=$getQ for a member with no ticket" }
            }
        }
    }

/**
 * S-7.6-31: where `files` is gated, a ticket's `am` caps an attachment at `⌊am / aChunkBytes⌋` chunks.
 * Under `am` = one chunk, an `aput` declaring `total` 2 is refused `ticket` with `msg` `size`, echoing
 * its `q` and scope, and one declaring `total` 1 is stored: the refusal locks nothing.
 */
private fun filesSize(): Check =
    Check(name = "files-size") { ctx ->
        ctx.requireFilesGate()
        val scope = ctx.randomScope()
        val (cid, data) = ctx.randomBlob(64)
        ctx.client.connect {
            hello()
            val ent = ctx.ent(nextQ(), fl = Tickets.FL_FILES, am = A_CHUNK_BYTES.toLong())
            send(ent)
            ensure(expect<Ok>(RecordType.OK).q == ent.q) { "expected ok q=${ent.q} for a files ticket with am" }
            ctx.subscribeFresh(this, scope)
            // The last chunk of each, so neither leans on how a spool treats a short non-final chunk.
            val bigQ = nextQ()
            send(Aput(t = RecordType.APUT, q = bigQ, scope = scope, aid = ctx.randomScope(), idx = 1, total = 2, cid = cid, data = data))
            val err = expectErr(ErrCode.TICKET)
            ensure(err.q == bigQ) { "expected the size refusal to echo q=$bigQ, got ${err.q}" }
            ensure(err.scope?.contentEquals(scope) == true) { "expected the size refusal to carry the aput's scope" }
            ensure(err.msg == Tickets.Reason.SIZE.word) { "expected msg \"size\" for total 2 under am = one chunk, got \"${err.msg}\"" }
            val smallQ = nextQ()
            send(Aput(t = RecordType.APUT, q = smallQ, scope = scope, aid = ctx.randomScope(), idx = 0, total = 1, cid = cid, data = data))
            ensure(expect<Ok>(RecordType.OK).q == smallQ) { "expected ok q=$smallQ for total 1 under am = one chunk" }
        }
    }

/** Skips every cast check on a spool whose `hello` carried no `cast` (experimental, SPOOL_PROTOCOL.md §7.7). */
private fun Ctx.requireCast(): CastInfo = serverHello.cast ?: throw SkipCheck("spool advertises no cast (SPOOL_PROTOCOL.md §7.7)")

/**
 * Lets [session] cast where the spool sells casts: on a spool whose `hello` gates `cast`, a cast
 * ticket under this run's key. Without the key no cast is admissible there, so the check skips.
 */
private suspend fun Ctx.unlockCasts(session: Session) {
    if (!gated(Tickets.FL_CAST)) return
    val ent = ent(session.nextQ(), fl = Tickets.FL_CAST)
    session.send(ent)
    ensure(session.expect<Ok>(RecordType.OK).q == ent.q) { "expected ok q=${ent.q} for a cast ticket" }
}

private fun Ctx.castOf(
    scope: ByteArray,
    size: Int = CAST_PROBE_BYTES,
): Cast = Cast(t = RecordType.CAST, scope = scope, data = Random.nextBytes(size))

/**
 * Proves nothing was sent to [this] ahead of a `list` for [scope]: the spool handles a connection's
 * records in order, so anything a cast drew would arrive before the listing. Digests are skipped.
 */
private suspend fun Session.nextIsListing(scope: ByteArray): ScopeList {
    val q = nextQ()
    send(ScopeList(t = RecordType.LIST, q = q, scope = scope))
    while (true) {
        val bytes = receiveBytes()
        when (val t = RecordCodec.peekType(bytes)) {
            RecordType.DIGEST -> {
                Unit
            }

            RecordType.LIST -> {
                val list = RecordCodec.decode<ScopeList>(bytes) ?: throw CheckFailure("expected a listing that decodes")
                ensure(list.q == q) { "expected the listing for q=$q, got q=${list.q}" }
                return list
            }

            else -> {
                throw CheckFailure("expected nothing before the listing, got '$t'")
            }
        }
    }
}

/** The next cast at [this], skipping digests, with what it should carry. */
private suspend fun Session.expectCast(sent: Cast) {
    val got = expect<Cast>(RecordType.CAST)
    ensure(got.scope.contentEquals(sent.scope)) { "expected the cast for scope ${hex(sent.scope)}, got ${hex(got.scope)}" }
    ensure(got.data.contentEquals(sent.data)) { "expected the cast's data unchanged" }
}

/** A `cast` advertisement is a positive size inside a record and a positive rate. */
private fun castAdvertised(): Check =
    Check(name = "cast-advertised") { ctx ->
        val cast = ctx.requireCast()
        ensure(cast.maxBytes in 1..ctx.limits().maxRecord) { "cast maxBytes ${cast.maxBytes} outside 1..maxRecord" }
        ensure(cast.rate >= 1) { "cast rate ${cast.rate}; omitted is the off state, never 0" }
    }

/**
 * A cast reaches the scope's other subscriber unchanged and never comes back to its caster, not even
 * as an answer, and leaves nothing behind: the listing stays empty.
 */
private fun castFanout(): Check =
    Check(name = "cast-fanout") { ctx ->
        ctx.requireCast()
        val scope = ctx.randomScope()
        ctx.client.connect {
            val a = this
            a.hello()
            ctx.unlockCasts(a)
            ctx.subscribeFresh(a, scope)
            ctx.client.connect {
                val b = this
                b.hello()
                ctx.subscribeFresh(b, scope)
                val sent = ctx.castOf(scope)
                a.send(sent)
                val listing = a.nextIsListing(scope)
                b.expectCast(sent)
                ensure(listing.blobIds.isNullOrEmpty()) { "expected a cast never to be listed, got ${listing.blobIds?.size} ids" }
                ensure(listing.tombstones.isNullOrEmpty()) { "expected a cast never to leave a tombstone" }
            }
        }
    }

/** Casts the spool will not carry are dropped without a word, and the connection stays usable. */
private fun castSilentRefusal(): Check =
    Check(name = "cast-silent-refusal") { ctx ->
        val cast = ctx.requireCast()
        val scope = ctx.randomScope()
        ctx.client.connect {
            hello()
            ctx.unlockCasts(this)
            ctx.subscribeFresh(this, scope)
            send(ctx.castOf(ctx.randomScope())) // a scope this connection never subscribed
            send(ctx.castOf(scope, size = cast.maxBytes + 1))
            nextIsListing(scope)
        }
    }

/**
 * On a spool that sells casts, a scope nobody holds a cast ticket for carries none; once one
 * subscriber presents one, an unticketed subscriber's cast is heard.
 */
private fun castTicket(): Check =
    Check(name = "cast-ticket") { ctx ->
        ctx.requireCast()
        ctx.requireEnt()
        if (!ctx.gated(Tickets.FL_CAST)) throw SkipCheck("entGates leaves cast open")
        if (ctx.ticketSigningKey == null) throw SkipCheck("no --ticket-key-file; no cast ticket can be made")
        val scope = ctx.randomScope()
        ctx.client.connect {
            val a = this
            a.hello()
            ctx.subscribeFresh(a, scope)
            ctx.client.connect {
                val b = this
                b.hello()
                ctx.subscribeFresh(b, scope)
                a.send(ctx.castOf(scope))
                a.nextIsListing(scope)
                b.nextIsListing(scope)
                ctx.unlockCasts(b)
                val heard = ctx.castOf(scope)
                a.send(heard)
                b.expectCast(heard)
            }
        }
    }

/**
 * Advisory: a burst well past `rate × 4` fans out no more than the burst, give or take what refills
 * while it is sent. A spool may hold casts to a tighter budget than it advertises; never a looser one.
 */
private fun castRate(): Check =
    Check(name = "cast-rate", must = false) { ctx ->
        val cast = ctx.requireCast()
        val burst = CAST_BURST_FACTOR * cast.rate
        val scope = ctx.randomScope()
        ctx.client.connect {
            val a = this
            a.hello()
            ctx.unlockCasts(a)
            ctx.subscribeFresh(a, scope)
            ctx.client.connect {
                val b = this
                b.hello()
                ctx.subscribeFresh(b, scope)
                val started = System.currentTimeMillis()
                repeat(burst + CAST_RATE_EXCESS) { a.send(ctx.castOf(scope)) }
                a.nextIsListing(scope)
                val elapsedS = (System.currentTimeMillis() - started + MS_PER_S - 1) / MS_PER_S
                val allowed = burst + cast.rate * elapsedS
                var heard = 0
                val q = b.nextQ()
                b.send(ScopeList(t = RecordType.LIST, q = q, scope = scope))
                while (true) {
                    val bytes = b.receiveBytes()
                    when (RecordCodec.peekType(bytes)) {
                        RecordType.CAST -> heard++
                        RecordType.LIST -> break
                        else -> Unit
                    }
                }
                if (heard > allowed) throw Advisory("$heard casts fanned out of a burst the advertised rate allows $allowed of")
            }
        }
    }

/**
 * §7.4: each commons room applies its own operator's bounds, not the subscriber's and not another
 * room's (S-7.4-2).
 *
 * The load-bearing one. A spool that honored a member's declaration would let any member subscribe
 * with `maxFrames = 1` and evict the whole room's history on the way in.
 */
private fun commonsBoundsPinned(): Check =
    Check(name = "commons-bounds-pinned", must = false) { ctx ->
        for (room in ctx.commonsRooms()) {
            ctx.client.connect {
                hello()
                send(
                    Sub(
                        t = RecordType.SUB,
                        q = nextQ(),
                        // Deliberately hostile, and deliberately legal: every field is inside the
                        // spool's caps, so a clamp would let it through.
                        subs = listOf(ScopeSub(scope = room.scope, bounds = ScopeBounds(1, 1L, 1), pow = null)),
                    ),
                )
                val digest = expectDigestFor(room.scope)
                ensure(digest.bounds.maxFrames == room.maxFrames) {
                    "expected ${room.label}'s advertised maxFrames ${room.maxFrames}, got ${digest.bounds.maxFrames}"
                }
                ensure(digest.bounds.ttlMs == room.ttlMs) {
                    "expected ${room.label}'s advertised ttlMs ${room.ttlMs}, got ${digest.bounds.ttlMs}"
                }
            }
        }
    }

/** §7.4: each commons room relays like any other scope — one member's push reaches the others. */
private fun commonsFanout(): Check =
    Check(name = "commons-fanout", must = false) { ctx ->
        for (room in ctx.commonsRooms()) roomFanout(ctx, room.scope)
    }

private suspend fun roomFanout(
    ctx: Ctx,
    scope: ByteArray,
) {
    ctx.client.connect {
        val a = this
        a.hello()
        a.subscribeCommons(scope)
        ctx.client.connect {
            val b = this
            b.hello()
            b.subscribeCommons(scope)
            val (blobId, data) = ctx.randomBlob()
            a.send(Push(t = RecordType.PUSH, q = a.nextQ(), scope = scope, blobId = blobId, data = data))
            while (true) {
                when (val t = RecordCodec.peekType(a.receiveBytes())) {
                    RecordType.OK -> break
                    RecordType.EVENT, RecordType.DIGEST -> Unit
                    else -> throw CheckFailure("expected ok after a commons push, got '$t'")
                }
            }
            // The room may be busy with other members, so match on the blob rather than
            // assuming the next event is ours.
            var seen = false
            while (!seen) {
                val bytes = b.receiveBytes()
                if (RecordCodec.peekType(bytes) != RecordType.EVENT) continue
                val event = RecordCodec.decode<Event>(bytes) ?: continue
                seen = event.scope.contentEquals(scope) && event.blobId.contentEquals(blobId)
            }
        }
    }
}

/** A commons room as a check sees it: the bounds `hello` advertised for it, and the id derived from its invite. */
private class CommonsRoom(
    val label: String,
    val scope: ByteArray,
    val maxFrames: Int,
    val ttlMs: Long,
)

/**
 * The rooms this run holds invites for, each matched to what `hello` says of it (C-7.4-17): by tag
 * when the spool sends `rooms`, and as `commons` from a one-room spool that sends `commons` alone.
 *
 * An invite the spool advertises no room for fails rather than skips. The operator handed it to the
 * run as one of this spool's rooms, and a member holding it would be told the room is not run there.
 */
private fun Ctx.commonsRooms(): List<CommonsRoom> {
    val commons = serverHello.commons ?: throw SkipCheck("spool advertises no commons (§7.4)")
    if (commonsSecrets.isEmpty()) throw SkipCheck("no --commons-invite; no room's scope id can be derived")
    val rooms = serverHello.rooms
    return commonsSecrets.mapIndexed { i, secret ->
        val scope = Commons.scopeId(secret)
        val label = "invite ${i + 1}'s room"
        if (rooms == null) {
            ensure(i == 0) { "${commonsSecrets.size} invites, but the spool sends `commons` alone, which describes one room" }
            CommonsRoom(label, scope, commons.maxFrames, commons.ttlMs)
        } else {
            val tag = Commons.tag(scope)
            val room = rooms.firstOrNull { it.tag.contentEquals(tag) } ?: throw CheckFailure("no room in hello carries $label's tag")
            CommonsRoom(label, scope, room.maxFrames, room.ttlMs)
        }
    }
}

/** No stamp: the commons exists from boot, so it is never an unknown scope and never gated (§6.4). */
private suspend fun Session.subscribeCommons(scope: ByteArray): Digest {
    send(
        Sub(
            t = RecordType.SUB,
            q = nextQ(),
            subs = listOf(ScopeSub(scope = scope, bounds = ScopeBounds(maxFrames = 4, ttlMs = 120_000, maxBlob = 1024))),
        ),
    )
    return expectDigestFor(scope)
}

/**
 * Skips every attachment check on a spool that did not advertise the §7.3 limits. Attachment support
 * is optional; what is NOT optional is that a spool without it is never sent these records, which is
 * exactly what this skip models on the client side.
 */
private fun Ctx.requireAttachments(): app.getknit.spool.protocol.Limits {
    val limits = limits()
    if (!limits.attachments) throw SkipCheck("spool advertises no attachment limits (§7.3)")
    return limits
}

/**
 * Lets [session] upload where the spool gates files (S-7.6-22): a files ticket under this run's key.
 * Without the key no upload is admissible there, so the check skips rather than fails.
 */
private suspend fun Ctx.unlockFiles(session: Session) {
    if (!gated(Tickets.FL_FILES)) return
    val ent = ent(session.nextQ(), fl = Tickets.FL_FILES)
    session.send(ent)
    ensure(session.expect<Ok>(RecordType.OK).q == ent.q) { "expected ok q=${ent.q} for a files ticket" }
}

/** §4.5/§7.3: an attachment chunk stores, shows up in the presence bitmap, and reads back byte-exact. */
private fun attachmentRoundTrip(): Check =
    Check(name = "attachment-round-trip") { ctx ->
        ctx.requireAttachments()
        ctx.client.connect {
            hello()
            ctx.unlockFiles(this)
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val aid = ctx.randomScope()
            val (cid, data) = ctx.randomBlob(64)

            val putQ = nextQ()
            send(Aput(t = RecordType.APUT, q = putQ, scope = scope, aid = aid, idx = 1, total = 2, cid = cid, data = data))
            val ok = expect<Ok>(RecordType.OK)
            ensure(ok.q == putQ) { "expected aput ok q=$putQ, got ${ok.q}" }

            send(Ahave(t = RecordType.AHAVE, q = nextQ(), scope = scope, aid = aid))
            val has = expect<Ahas>(RecordType.AHAS)
            ensure(has.total == 2) { "expected ahas total=2, got ${has.total}" }
            ensure(!has.dead) { "expected ahas dead=false for a live attachment" }
            // Chunk 1 only: MSB-first bit 1 of byte 0 ⇒ 0b0100_0000.
            ensure(has.bits.size == 1 && (has.bits[0].toInt() and 0xFF) == 0x40) {
                "expected bitmap 0x40 for chunk 1 of 2, got ${hex(has.bits)}"
            }

            val getQ = nextQ()
            send(Aget(t = RecordType.AGET, q = getQ, scope = scope, aid = aid, from = 0, n = 2))
            val chunk = expect<Achunk>(RecordType.ACHUNK)
            ensure(chunk.idx == 1) { "expected the only stored chunk at idx 1, got ${chunk.idx}" }
            ensure(chunk.total == 2) { "expected achunk total=2, got ${chunk.total}" }
            ensure(chunk.cid.contentEquals(cid)) { "expected cid ${hex(cid)}, got ${hex(chunk.cid)}" }
            ensure(chunk.data.contentEquals(data)) { "achunk data differs from what was put" }
            // Indices the spool lacks simply do not arrive; the aget terminates with a bare ok.
            val done = expect<Ok>(RecordType.OK)
            ensure(done.q == getQ) { "expected aget ok q=$getQ, got ${done.q}" }
        }
    }

/** §6.5: an aput whose cid is not SHA-256(data) is refused with bad_id. */
private fun attachmentBadId(): Check =
    Check(name = "attachment-bad-id") { ctx ->
        ctx.requireAttachments()
        ctx.client.connect {
            hello()
            ctx.unlockFiles(this)
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val (_, data) = ctx.randomBlob(32)
            send(
                Aput(
                    t = RecordType.APUT,
                    q = nextQ(),
                    scope = scope,
                    aid = ctx.randomScope(),
                    idx = 0,
                    total = 1,
                    cid = ByteArray(32),
                    data = data,
                ),
            )
            expectErr(ErrCode.BAD_ID)
        }
    }

/** §6.5: first write wins — an identical re-put is acked, a differing one is `conflict`. */
private fun attachmentFirstWriteWins(): Check =
    Check(name = "attachment-first-write-wins") { ctx ->
        ctx.requireAttachments()
        ctx.client.connect {
            hello()
            ctx.unlockFiles(this)
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val aid = ctx.randomScope()
            val (cid, data) = ctx.randomBlob(48)
            val (otherCid, otherData) = ctx.randomBlob(48)
            send(Aput(t = RecordType.APUT, q = nextQ(), scope = scope, aid = aid, idx = 0, total = 1, cid = cid, data = data))
            expect<Ok>(RecordType.OK)

            // Byte-identical: the deterministic seal means honest members re-push exactly this.
            send(Aput(t = RecordType.APUT, q = nextQ(), scope = scope, aid = aid, idx = 0, total = 1, cid = cid, data = data))
            expect<Ok>(RecordType.OK)

            send(
                Aput(
                    t = RecordType.APUT,
                    q = nextQ(),
                    scope = scope,
                    aid = aid,
                    idx = 0,
                    total = 1,
                    cid = otherCid,
                    data = otherData,
                ),
            )
            expectErr(ErrCode.CONFLICT)
        }
    }

/** §7.3: an aget beyond `maxAget` is truncated, never an error — the `pull` rule reapplied. */
private fun attachmentGetTruncated(): Check =
    Check(name = "attachment-get-truncated") { ctx ->
        val limits = ctx.requireAttachments()
        val maxAget = limits.maxAget ?: throw SkipCheck("no maxAget advertised")
        val maxAttachBytes = limits.maxAttachBytes ?: throw SkipCheck("no maxAttachBytes advertised")
        val total = maxAget + 2
        // A spool may refuse a `total` no attachment inside its quota could have (§4.5, S-6.5-3);
        // this probe needs one past maxAget, which a small quota cannot admit.
        val maxTotal = (maxAttachBytes.toLong() + A_CHUNK_BYTES - 1) / A_CHUNK_BYTES
        if (total > maxTotal) {
            throw SkipCheck("maxAttachBytes=$maxAttachBytes admits at most $maxTotal chunks, fewer than the $total this probe declares")
        }
        ctx.client.connect {
            hello()
            ctx.unlockFiles(this)
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val aid = ctx.randomScope()
            val (cid, data) = ctx.randomBlob(32)
            send(
                Aput(t = RecordType.APUT, q = nextQ(), scope = scope, aid = aid, idx = 0, total = total, cid = cid, data = data),
            )
            expect<Ok>(RecordType.OK)

            val getQ = nextQ()
            send(Aget(t = RecordType.AGET, q = getQ, scope = scope, aid = aid, from = 0, n = total))
            val chunk = expect<Achunk>(RecordType.ACHUNK)
            ensure(chunk.idx == 0) { "expected chunk 0, got ${chunk.idx}" }
            val ok = expect<Ok>(RecordType.OK)
            ensure(ok.q == getQ) { "an over-long aget must be truncated and acked, not refused" }
        }
    }

/**
 * §4.5/S-6.5-3: chunking is structural (`total = ceil(|A| / aChunkBytes)`), so an `aput` declaring
 * more chunks than `⌈maxAttachBytes / aChunkBytes⌉` describes an attachment larger than the whole
 * quota — one that "cannot fit the budget even alone" and MUST be refused `quota`. Every `ahave`
 * for an accepted header allocates and sends a bitmap of `⌈total / 8⌉` bytes, which is what a
 * client-chosen `total` sizes. Advisory: the spec states the allocation bound as a member's duty
 * (C-4.5-8) and numbers no spool-side refusal by `total`; which code to use is the spool's call —
 * `quota` is what this repo's daemon says. Answered in-band on a connection that keeps working.
 */
private fun aputTotalOverQuota(): Check =
    Check(name = "aput-total-over-quota", must = false) { ctx ->
        ctx.requireAttachments()
        ctx.client.connect {
            hello()
            ctx.unlockFiles(this)
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val aid = ctx.randomScope()
            val (cid, data) = ctx.randomBlob(32)
            val q = nextQ()
            send(Aput(t = RecordType.APUT, q = q, scope = scope, aid = aid, idx = 0, total = Int.MAX_VALUE, cid = cid, data = data))
            val bytes =
                try {
                    receiveBytes()
                } catch (_: TimeoutException) {
                    throw CheckFailure("expected err for an aput declaring ${Int.MAX_VALUE} chunks, got timeout after $timeoutMs ms")
                }
            val t = RecordCodec.peekType(bytes)
            ensure(t != RecordType.OK) {
                "expected err for an aput declaring ${Int.MAX_VALUE} chunks, got ok: the spool stored a header no attachment could fill"
            }
            ensure(t == RecordType.ERR) { "expected err for an aput declaring ${Int.MAX_VALUE} chunks, got '${t ?: "undecodable record"}'" }
            val err = RecordCodec.decode<Err>(bytes) ?: throw CheckFailure("expected a decodable err, got one that does not decode")
            ensure(err.q == q) { "expected err q=$q echoed, got ${err.q}" }
            ensure(err.code == ErrCode.QUOTA) { "expected err code=quota for an aput declaring ${Int.MAX_VALUE} chunks, got ${err.code}" }
            // Still a working connection: the refusal was in-band, not a close.
            ctx.subscribeFresh(this, ctx.randomScope())
        }
    }

/** §7.1: the spool's hello arrives unprompted and advertises sane min/limits/powBits. */
private fun helloFirst(): Check =
    Check(name = "hello-first") { ctx ->
        ctx.client.connect {
            val hello = readServerHello()
            ensure(hello.v >= 1) { "expected hello v >= 1, got ${hello.v}" }
            val min = hello.min
            ensure(min != null && min <= hello.v) { "expected hello min != null and <= v=${hello.v}, got $min" }
            val limits = hello.limits ?: throw CheckFailure("expected hello limits, got none")
            val fields =
                listOf(
                    "maxBlob" to limits.maxBlob.toLong(),
                    "maxRecord" to limits.maxRecord.toLong(),
                    "maxScopes" to limits.maxScopes.toLong(),
                    "maxPull" to limits.maxPull.toLong(),
                    "maxFramesCap" to limits.maxFramesCap.toLong(),
                    "maxTtlMs" to limits.maxTtlMs,
                )
            for ((field, value) in fields) {
                ensure(value >= 1) { "expected limits.$field >= 1, got $value" }
            }
            val powBits = hello.powBits
            ensure(powBits != null && powBits >= 0) { "expected hello powBits >= 0, got $powBits" }
        }
    }

/** §7.1: a client hello outside `[min, v]` has no version overlap — close 4002. */
private fun helloVersionMismatch(): Check =
    Check(name = "hello-version-mismatch") { ctx ->
        ctx.client.connect {
            readServerHello()
            send(Hello(t = RecordType.HELLO, v = 99))
            expectClose(CloseCode.VERSION)
        }
    }

/** §7.1: any record other than hello as the client's first — close 4000. */
private fun malformedPreHello(): Check =
    Check(name = "malformed-pre-hello") { ctx ->
        ctx.client.connect {
            readServerHello()
            send(
                Pull(
                    t = RecordType.PULL,
                    q = 1,
                    scope = ctx.randomScope(),
                    blobIds = emptyList(),
                ),
            )
            expectClose(CloseCode.MALFORMED)
        }
    }

/** §7.1: a private spool closes 4001 before hello when the `k` token is absent or wrong. */
private fun authRequired(): Check =
    Check(name = "auth-required") { ctx ->
        if (!ctx.hasToken) throw SkipCheck("no --token given")
        ctx.bareClient.connect {
            expectClose(CloseCode.AUTH)
        }
    }

/** §7.1: every scope operation requires a prior sub on the same connection. */
private fun notSubscribed(): Check =
    Check(name = "not-subscribed") { ctx ->
        ctx.client.connect {
            hello()
            val (blobId, data) = ctx.randomBlob()
            val q = nextQ()
            send(
                Push(
                    t = RecordType.PUSH,
                    q = q,
                    scope = ctx.randomScope(),
                    blobId = blobId,
                    data = data,
                ),
            )
            val err = expectErr(ErrCode.NOT_SUBSCRIBED)
            val errQ = err.q
            ensure(errQ == q) { "expected err q=$q echoed, got $errQ" }
        }
    }

/** §6.3: a fresh scope digests to 8 zero bytes with count 0 and full=false. */
private fun subDigestEmptyScope(): Check =
    Check(name = "sub-digest-empty-scope") { ctx ->
        ctx.client.connect {
            hello()
            val digest = ctx.subscribeFresh(this, ctx.randomScope())
            ensure(digest.count == 0) { "expected digest count=0 for a fresh scope, got ${digest.count}" }
            ensure(digest.digest.contentEquals(ByteArray(ScopeDigest.DIGEST_BYTES))) {
                "expected the empty-set digest (8 zero bytes), got ${hex(digest.digest)}"
            }
            ensure(!digest.full) { "expected full=false for a fresh scope, got true" }
        }
    }

/** §6.2: declared bounds are clamped to the HELLO caps and echoed back applied. */
private fun boundsClamped(): Check =
    Check(name = "bounds-clamped") { ctx ->
        val limits = ctx.limits()
        ctx.client.connect {
            hello()
            val digest =
                ctx.subscribeFresh(
                    this,
                    ctx.randomScope(),
                    ScopeBounds(maxFrames = Int.MAX_VALUE, ttlMs = Long.MAX_VALUE, maxBlob = Int.MAX_VALUE),
                )
            val bounds = digest.bounds
            ensure(bounds.maxFrames in 1..limits.maxFramesCap) {
                "expected applied maxFrames in 1..${limits.maxFramesCap}, got ${bounds.maxFrames}"
            }
            ensure(bounds.ttlMs in 1..limits.maxTtlMs) {
                "expected applied ttlMs in 1..${limits.maxTtlMs}, got ${bounds.ttlMs}"
            }
            ensure(bounds.maxBlob in 1..limits.maxBlob) {
                "expected applied maxBlob in 1..${limits.maxBlob}, got ${bounds.maxBlob}"
            }
        }
    }

/** §6.2/§6.3: a stored push is listed, and the digest is the FNV fold of the listed ids. */
private fun pushOkThenList(): Check =
    Check(name = "push-ok-then-list") { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val (blobId, data) = ctx.randomBlob()
            val pushQ = nextQ()
            send(
                Push(
                    t = RecordType.PUSH,
                    q = pushQ,
                    scope = scope,
                    blobId = blobId,
                    data = data,
                ),
            )
            val ok = expect<Ok>(RecordType.OK)
            ensure(ok.q == pushQ) { "expected push ok q=$pushQ, got ${ok.q}" }
            val listQ = nextQ()
            send(ScopeList(t = RecordType.LIST, q = listQ, scope = scope))
            val list = expect<ScopeList>(RecordType.LIST)
            val ids = list.blobIds.orEmpty()
            ensure(ids.any { it.contentEquals(blobId) }) {
                "expected list blobIds to contain ${hex(blobId)}, got ${ids.joinToString { hex(it) }}"
            }
            val folded = ScopeDigest.toBytes(ScopeDigest.fold(ids))
            val digest = ctx.subscribeFresh(this, scope)
            ensure(digest.digest.contentEquals(folded)) {
                "expected re-sub digest ${hex(folded)} (FNV fold of the listed ids), got ${hex(digest.digest)}"
            }
        }
    }

/** §6.2: a push whose blobId is not SHA-256(data) is refused with bad_id. */
private fun pushBadId(): Check =
    Check(name = "push-bad-id") { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val (_, data) = ctx.randomBlob(16)
            send(
                Push(
                    t = RecordType.PUSH,
                    q = nextQ(),
                    scope = scope,
                    blobId = ByteArray(32),
                    data = data,
                ),
            )
            expectErr(ErrCode.BAD_ID)
        }
    }

/** §6.2: a blob over the HELLO maxBlob cap is refused with too_large. */
private fun pushTooLarge(): Check =
    Check(name = "push-too-large") { ctx ->
        val limits = ctx.limits()
        val size = limits.maxBlob.toLong() + 1
        if (size + PUSH_RECORD_OVERHEAD > limits.maxRecord) {
            throw SkipCheck("maxBlob+1 ($size bytes) plus record overhead exceeds maxRecord ${limits.maxRecord}")
        }
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(
                this,
                scope,
                ScopeBounds(maxFrames = 4, ttlMs = 120_000, maxBlob = Int.MAX_VALUE),
            )
            val (blobId, data) = ctx.blob(Random.nextBytes(size.toInt()))
            send(
                Push(
                    t = RecordType.PUSH,
                    q = nextQ(),
                    scope = scope,
                    blobId = blobId,
                    data = data,
                ),
            )
            expectErr(ErrCode.TOO_LARGE)
        }
    }

/** §7.2: pull answers with blob* then ok, unknown ids reported in ok.missing. */
private fun pullBlobThenOkMissing(): Check =
    Check(name = "pull-blob-then-ok-missing") { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val (blobId, data) = ctx.randomBlob()
            send(
                Push(
                    t = RecordType.PUSH,
                    q = nextQ(),
                    scope = scope,
                    blobId = blobId,
                    data = data,
                ),
            )
            expect<Ok>(RecordType.OK)
            val unknown = ctx.randomBlobId()
            val pullQ = nextQ()
            send(
                Pull(
                    t = RecordType.PULL,
                    q = pullQ,
                    scope = scope,
                    blobIds = listOf(blobId, unknown),
                ),
            )
            val served = expect<Blob>(RecordType.BLOB)
            ensure(served.blobId.contentEquals(blobId)) {
                "expected pulled blob ${hex(blobId)}, got ${hex(served.blobId)}"
            }
            ensure(served.data.contentEquals(data)) { "expected pulled blob data to match the pushed bytes" }
            val ok = expect<Ok>(RecordType.OK)
            ensure(ok.q == pullQ) { "expected pull ok q=$pullQ, got ${ok.q}" }
            val missing = ok.missing.orEmpty()
            ensure(missing.any { it.contentEquals(unknown) }) {
                "expected ok.missing to contain ${hex(unknown)}, got ${missing.joinToString { hex(it) }}"
            }
        }
    }

/** §7.2: a pull over maxPull must not kill the connection — an ok or err, then normal service. */
private fun pullOverflowTolerated(): Check =
    Check(name = "pull-overflow-tolerated") { ctx ->
        val limits = ctx.limits()
        if (limits.maxPull > MAX_PULL_PROBE) throw SkipCheck("maxPull ${limits.maxPull} too large to overflow")
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val record =
                Pull(
                    t = RecordType.PULL,
                    q = nextQ(),
                    scope = scope,
                    blobIds = List(limits.maxPull + 1) { ctx.randomBlobId() },
                )
            val encoded = RecordCodec.encode(record)
            if (encoded.size > limits.maxRecord) {
                throw SkipCheck("maxPull+1 ids encode to ${encoded.size} bytes, above maxRecord ${limits.maxRecord}")
            }
            sendRaw(encoded)
            while (true) {
                val bytes = receiveBytes()
                val t = RecordCodec.peekType(bytes)
                when (t) {
                    RecordType.OK, RecordType.ERR -> break
                    RecordType.BLOB, RecordType.DIGEST, RecordType.EVENT -> Unit
                    else -> throw CheckFailure("expected ok or err after an overlong pull, got '$t'")
                }
            }
            val listQ = nextQ()
            send(ScopeList(t = RecordType.LIST, q = listQ, scope = scope))
            val list = expect<ScopeList>(RecordType.LIST)
            ensure(list.q == listQ) { "expected the follow-up list response q=$listQ, got ${list.q}" }
        }
    }

/** §7.2: a push fans out one event to every other subscriber, never back to the uploader. */
private fun eventFanout(): Check =
    Check(name = "event-fanout") { ctx ->
        ctx.client.connect {
            val a = this
            a.hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(a, scope)
            ctx.client.connect {
                val b = this
                b.hello()
                ctx.subscribeFresh(b, scope)
                val (blobId, data) = ctx.randomBlob()
                a.send(
                    Push(
                        t = RecordType.PUSH,
                        q = a.nextQ(),
                        scope = scope,
                        blobId = blobId,
                        data = data,
                    ),
                )
                var selfEvent = false
                while (true) {
                    val bytes = a.receiveBytes()
                    val t = RecordCodec.peekType(bytes)
                    when (t) {
                        RecordType.OK -> break
                        RecordType.EVENT -> selfEvent = true
                        RecordType.DIGEST -> Unit
                        else -> throw CheckFailure("expected ok after push, got '$t'")
                    }
                }
                val event = b.expect<Event>(RecordType.EVENT)
                ensure(event.blobId.contentEquals(blobId)) {
                    "expected event blobId ${hex(blobId)} at the second subscriber, got ${hex(event.blobId)}"
                }
                ensure(event.data.contentEquals(data)) { "expected event data to match the pushed blob" }
                val late = a.receiveBytesOrNull(EVENT_GRACE_MS)
                if (late != null && RecordCodec.peekType(late) == RecordType.EVENT) selfEvent = true
                ensure(!selfEvent) { "expected no event echoed to the uploader, got one" }
            }
        }
    }

/** §6.2: re-pushing a held blob acks ok (idempotent); advisory: no second event fans out. */
private fun duplicatePushIdempotent(): Check =
    Check(name = "duplicate-push-idempotent") { ctx ->
        ctx.client.connect {
            val a = this
            a.hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(a, scope)
            ctx.client.connect {
                val b = this
                b.hello()
                ctx.subscribeFresh(b, scope)
                val (blobId, data) = ctx.randomBlob()
                a.send(
                    Push(
                        t = RecordType.PUSH,
                        q = a.nextQ(),
                        scope = scope,
                        blobId = blobId,
                        data = data,
                    ),
                )
                a.expect<Ok>(RecordType.OK)
                b.expect<Event>(RecordType.EVENT)
                val dupQ = a.nextQ()
                a.send(
                    Push(
                        t = RecordType.PUSH,
                        q = dupQ,
                        scope = scope,
                        blobId = blobId,
                        data = data,
                    ),
                )
                val ok = a.expect<Ok>(RecordType.OK)
                ensure(ok.q == dupQ) { "expected duplicate push ok q=$dupQ, got ${ok.q}" }
                var secondEvent = false
                while (!secondEvent) {
                    val bytes = b.receiveBytesOrNull(EVENT_GRACE_MS) ?: break
                    if (RecordCodec.peekType(bytes) == RecordType.EVENT) secondEvent = true
                }
                if (secondEvent) throw Advisory("duplicate push re-broadcast an event to the second subscriber")
            }
        }
    }

/** §6.2: filling past the applied maxFrames evicts the oldest, whose re-push is refused tombstoned. */
private fun tombstoneRefusal(): Check =
    Check(name = "tombstone-refusal") { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            val digest =
                ctx.subscribeFresh(
                    this,
                    scope,
                    ScopeBounds(maxFrames = 2, ttlMs = 120_000, maxBlob = 1024),
                )
            val applied = digest.bounds.maxFrames
            if (applied > MAX_EVICTION_FILL) {
                throw SkipCheck("spool applied maxFrames=$applied to a declared 2; too many pushes to force eviction")
            }
            val blobs = List(applied + 1) { ctx.randomBlob() }
            for ((blobId, data) in blobs) {
                send(
                    Push(
                        t = RecordType.PUSH,
                        q = nextQ(),
                        scope = scope,
                        blobId = blobId,
                        data = data,
                    ),
                )
                expect<Ok>(RecordType.OK)
            }
            val (firstId, firstData) = blobs.first()
            send(
                Push(
                    t = RecordType.PUSH,
                    q = nextQ(),
                    scope = scope,
                    blobId = firstId,
                    data = firstData,
                ),
            )
            expectErr(ErrCode.TOMBSTONED)
        }
    }

/** §6.2 (MAY): eviction pressure reaches other subscribers as an unsolicited digest with full=true. */
private fun evictionDigest(): Check =
    Check(name = "eviction-digest", must = false) { ctx ->
        ctx.client.connect {
            val a = this
            a.hello()
            val scope = ctx.randomScope()
            val bounds = ScopeBounds(maxFrames = 2, ttlMs = 120_000, maxBlob = 1024)
            val digest = ctx.subscribeFresh(a, scope, bounds)
            val applied = digest.bounds.maxFrames
            if (applied > MAX_EVICTION_FILL) {
                throw SkipCheck("spool applied maxFrames=$applied to a declared 2; too many pushes to force eviction")
            }
            ctx.client.connect {
                val b = this
                b.hello()
                ctx.subscribeFresh(b, scope, bounds)
                repeat(applied + 1) {
                    val (blobId, data) = ctx.randomBlob()
                    a.send(
                        Push(
                            t = RecordType.PUSH,
                            q = a.nextQ(),
                            scope = scope,
                            blobId = blobId,
                            data = data,
                        ),
                    )
                    a.expect<Ok>(RecordType.OK)
                }
                val deadline = System.currentTimeMillis() + ctx.timeoutMs
                var seen = false
                while (!seen) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val bytes = b.receiveBytesOrNull(remaining) ?: break
                    if (RecordCodec.peekType(bytes) != RecordType.DIGEST) continue
                    val update = RecordCodec.decode<Digest>(bytes) ?: continue
                    if (update.scope.contentEquals(scope) && update.full) seen = true
                }
                ensure(seen) { "no unsolicited digest with full=true reached the second subscriber (spec MAY)" }
            }
        }
    }

/** §6.4/§8: an unknown scope demands a valid, in-window stamp; missing/garbage/stale are refused. */
private fun powGate(): Check =
    Check(name = "pow-gate") { ctx ->
        val bits = ctx.serverHello.powBits ?: 0
        if (bits <= 0) throw SkipCheck("PoW disabled (powBits=$bits)")
        if (bits > ctx.powLimit) throw SkipCheck("powBits $bits above --pow-limit ${ctx.powLimit}")
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            val bounds = defaultBounds()
            send(
                Sub(
                    t = RecordType.SUB,
                    q = nextQ(),
                    subs = listOf(ScopeSub(scope = scope, bounds = bounds)),
                ),
            )
            expectErr(ErrCode.POW)
            send(
                Sub(
                    t = RecordType.SUB,
                    q = nextQ(),
                    subs = listOf(ScopeSub(scope = scope, bounds = bounds, pow = PowStamp(n = 0, d = 1))),
                ),
            )
            expectErr(ErrCode.POW)
            ctx.subscribeFresh(this, scope, bounds)
            val staleScope = ctx.randomScope()
            val staleDay = Pow.utcDay(System.currentTimeMillis()) - 2
            val n =
                Pow.stamp(scopeId = staleScope, day = staleDay, bits = bits)
                    ?: throw CheckFailure("PoW miner gave up before finding a $bits-bit stamp")
            send(
                Sub(
                    t = RecordType.SUB,
                    q = nextQ(),
                    subs =
                        listOf(
                            ScopeSub(scope = staleScope, bounds = bounds, pow = PowStamp(n = n, d = staleDay)),
                        ),
                ),
            )
            expectErr(ErrCode.POW)
        }
    }

/** §7.2 evolution: a record with an unknown `t` is skipped; the connection keeps working. */
private fun unknownRecordTolerance(): Check =
    Check(name = "unknown-record-tolerance") { ctx ->
        ctx.client.connect {
            hello()
            send(Ok(t = "x-future", q = 1))
            val digest = ctx.subscribeFresh(this, ctx.randomScope())
            ensure(digest.count == 0) { "expected a working sub after an unknown record, got count=${digest.count}" }
        }
    }

/** §7.2 evolution: an unknown field inside a known record is ignored; the request is answered. */
private fun unknownFieldTolerance(): Check =
    Check(name = "unknown-field-tolerance") { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val listQ = nextQ()
            sendRaw(
                TinyCbor.map(
                    listOf(
                        "t" to RecordType.LIST,
                        "q" to listQ,
                        "scope" to scope,
                        "novel" to 7L,
                    ),
                ),
            )
            val list = expect<ScopeList>(RecordType.LIST)
            ensure(list.q == listQ) { "expected list response q=$listQ despite an unknown field, got ${list.q}" }
            ensure(list.scope.contentEquals(scope)) {
                "expected list response for scope ${hex(scope)}, got ${hex(list.scope)}"
            }
        }
    }

/**
 * B-2-6/§7.2: a scope id is `bstr32`. A `sub` naming a 31-byte scope cannot be answered with a
 * `digest` — that record's own `scope` is `bstr32` too — so the answer is an `err` echoing the
 * request's `q` and carrying no off-length scope, on a connection that keeps working (B-7.1-7).
 * Advisory: the spec types the field but numbers no refusal, and which code to use is the spool's
 * call — `malformed` is what this repo's daemon says.
 */
private fun subOffLengthScope(): Check =
    Check(name = "sub-off-length-scope", must = false) { ctx ->
        ctx.client.connect {
            hello()
            val scope = Random.nextBytes(ID_BYTES - 1)
            val q = nextQ()
            // Stamped, so a PoW spool has nothing but the length left to refuse.
            send(
                Sub(
                    t = RecordType.SUB,
                    q = q,
                    subs = listOf(ScopeSub(scope = scope, bounds = defaultBounds(), pow = ctx.mineStamp(scope))),
                ),
            )
            val bytes =
                try {
                    receiveBytes()
                } catch (_: TimeoutException) {
                    throw CheckFailure("expected err for a ${ID_BYTES - 1}-byte scope id, got timeout after $timeoutMs ms")
                }
            val t = RecordCodec.peekType(bytes)
            ensure(t != RecordType.DIGEST) { "expected err for a ${ID_BYTES - 1}-byte scope id, got digest: the spool subscribed it" }
            ensure(t == RecordType.ERR) { "expected err for a ${ID_BYTES - 1}-byte scope id, got '${t ?: "undecodable record"}'" }
            val err = RecordCodec.decode<Err>(bytes) ?: throw CheckFailure("expected a decodable err, got one that does not decode")
            ensure(err.q == q) { "expected err q=$q echoed, got ${err.q}" }
            val echoed = err.scope
            ensure(echoed == null || echoed.size == ID_BYTES) { "expected err.scope absent or $ID_BYTES bytes, got ${echoed?.size} bytes" }
            ensure(err.code == ErrCode.MALFORMED) { "expected err code=malformed for a ${ID_BYTES - 1}-byte scope id, got ${err.code}" }
            // Still a working connection: the refusal was in-band, not a close.
            ctx.subscribeFresh(this, ctx.randomScope())
        }
    }

/**
 * §7.1/§6.4: `maxScopes` is the spool's advertised total, and a conforming client batches a `sub`
 * under it. A record naming more scopes than that is one the spool could never honour and, entry
 * for entry, a store transaction each — so the answer is a single `err` echoing the request's `q`
 * and naming no scope, ahead of any of it, on a connection that keeps working (B-7.1-7). Unstamped
 * on purpose: the refusal precedes the creation gates, so a PoW spool that instead answers `err pow`
 * for the first entry has processed the record rather than refused it. Advisory: the spec numbers
 * no such refusal, and which code to use is the spool's call — `malformed` is what this repo's
 * daemon says.
 */
private fun subOverMaxScopes(): Check =
    Check(name = "sub-over-max-scopes", must = false) { ctx ->
        val limits = ctx.limits()
        if (limits.maxScopes > MAX_SCOPES_PROBE) throw SkipCheck("maxScopes ${limits.maxScopes} too large to overflow")
        val entries = limits.maxScopes + 1
        ctx.client.connect {
            hello()
            val q = nextQ()
            val encoded =
                RecordCodec.encode(
                    Sub(
                        t = RecordType.SUB,
                        q = q,
                        subs = List(entries) { ScopeSub(scope = ctx.randomScope(), bounds = defaultBounds()) },
                    ),
                )
            if (encoded.size > limits.maxRecord) {
                throw SkipCheck("maxScopes+1 entries encode to ${encoded.size} bytes, above maxRecord ${limits.maxRecord}")
            }
            sendRaw(encoded)
            val bytes =
                try {
                    receiveBytes()
                } catch (_: TimeoutException) {
                    throw CheckFailure("expected err for a sub naming $entries scopes, got timeout after $timeoutMs ms")
                }
            val t = RecordCodec.peekType(bytes)
            ensure(t != RecordType.DIGEST) { "expected err for a sub naming $entries scopes, got digest: the spool subscribed it" }
            ensure(t == RecordType.ERR) { "expected err for a sub naming $entries scopes, got '${t ?: "undecodable record"}'" }
            val err = RecordCodec.decode<Err>(bytes) ?: throw CheckFailure("expected a decodable err, got one that does not decode")
            ensure(err.q == q) { "expected err q=$q echoed, got ${err.q}" }
            ensure(err.scope == null) { "expected a whole-record err naming no scope, got scope ${err.scope?.let(::hex)}" }
            ensure(err.code == ErrCode.MALFORMED) { "expected err code=malformed for an over-cap sub, got ${err.code}" }
            // Still a working connection: the refusal was in-band, not a close.
            ctx.subscribeFresh(this, ctx.randomScope())
        }
    }

/**
 * S-6.2-2: applied bounds are "the most recent SUB's declaration", which two entries for one scope
 * in a single record leave undefined — and a repeated scope is a way to buy the same store
 * transaction many times for one record token. Refused whole, like an off-length id: one `err`
 * echoing `q`, no scope subscribed, connection intact. Stamped, so a PoW spool has nothing but the
 * repeat left to refuse. Advisory, as above.
 */
private fun subDuplicateScope(): Check =
    Check(name = "sub-duplicate-scope", must = false) { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            val q = nextQ()
            val entry = ScopeSub(scope = scope, bounds = defaultBounds(), pow = ctx.mineStamp(scope))
            send(Sub(t = RecordType.SUB, q = q, subs = listOf(entry, entry)))
            val bytes =
                try {
                    receiveBytes()
                } catch (_: TimeoutException) {
                    throw CheckFailure("expected err for a sub naming one scope twice, got timeout after $timeoutMs ms")
                }
            val t = RecordCodec.peekType(bytes)
            ensure(t != RecordType.DIGEST) { "expected err for a sub naming one scope twice, got digest: the spool subscribed it" }
            ensure(t == RecordType.ERR) { "expected err for a sub naming one scope twice, got '${t ?: "undecodable record"}'" }
            val err = RecordCodec.decode<Err>(bytes) ?: throw CheckFailure("expected a decodable err, got one that does not decode")
            ensure(err.q == q) { "expected err q=$q echoed, got ${err.q}" }
            ensure(err.code == ErrCode.MALFORMED) { "expected err code=malformed for a duplicated scope, got ${err.code}" }
            // Still a working connection, and the scope itself was never the problem.
            ctx.subscribeFresh(this, scope)
        }
    }

/**
 * B-7.1-4: a record is one CBOR record per *binary* message. A text frame is not a record, so a
 * spool cannot process it and should not silently drop it either — dropped, it is a frame of
 * `maxRecord` a client can send at line rate past every per-record limit. The answer is an
 * in-band `err` with no `q` (there is no record to take one from) on a connection that keeps
 * working (B-7.1-7). Advisory: the spec names the frame type but numbers no refusal, and which code
 * to use is the spool's call — `malformed` is what this repo's daemon says.
 */
private fun textFrameMalformed(): Check =
    Check(name = "text-frame-malformed", must = false) { ctx ->
        ctx.client.connect {
            hello()
            sendFrame(Frame.Text("not a record"))
            val bytes =
                try {
                    receiveBytes()
                } catch (_: TimeoutException) {
                    throw CheckFailure("expected err for a text frame, got timeout after $timeoutMs ms: the spool dropped it")
                }
            val t = RecordCodec.peekType(bytes)
            ensure(t == RecordType.ERR) { "expected err for a text frame, got '${t ?: "undecodable record"}'" }
            val err = RecordCodec.decode<Err>(bytes) ?: throw CheckFailure("expected a decodable err, got one that does not decode")
            ensure(err.q == null) { "expected err with no q for a frame that is not a record, got q=${err.q}" }
            ensure(err.code == ErrCode.MALFORMED) { "expected err code=malformed for a text frame, got ${err.code}" }
            // Still a working connection: the refusal was in-band, not a close.
            ctx.subscribeFresh(this, ctx.randomScope())
        }
    }

/** §7.1: terminal responses echo the request's `q` — two in-flight pulls come back as {101, 102}. */
private fun qCorrelation(): Check =
    Check(name = "q-correlation") { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            send(
                Pull(
                    t = RecordType.PULL,
                    q = 101,
                    scope = scope,
                    blobIds = listOf(ctx.randomBlobId()),
                ),
            )
            send(
                Pull(
                    t = RecordType.PULL,
                    q = 102,
                    scope = scope,
                    blobIds = listOf(ctx.randomBlobId()),
                ),
            )
            val qs = mutableSetOf<Long>()
            repeat(2) { qs.add(expect<Ok>(RecordType.OK).q) }
            ensure(qs == setOf(101L, 102L)) { "expected ok q values {101, 102}, got $qs" }
        }
    }

/** §6.4: scope creation past maxScopes is refused with quota (destructive: fills the spool). */
private fun quotaScopes(): Check =
    Check(name = "quota-scopes", destructive = true) { ctx ->
        val limits = ctx.limits()
        if (limits.maxScopes > MAX_QUOTA_PROBE) {
            throw SkipCheck("maxScopes ${limits.maxScopes} too large to exhaust in a test run")
        }
        ctx.client.connect {
            hello()
            val bounds = ScopeBounds(maxFrames = 1, ttlMs = 60_000, maxBlob = 1024)
            var accepted = 0
            var quotaSeen = false
            while (!quotaSeen && accepted <= limits.maxScopes) {
                val scope = ctx.randomScope()
                val stamp = ctx.mineStamp(scope)
                var settled = false
                while (!settled) {
                    send(
                        Sub(
                            t = RecordType.SUB,
                            q = nextQ(),
                            subs = listOf(ScopeSub(scope = scope, bounds = bounds, pow = stamp)),
                        ),
                    )
                    var responded = false
                    while (!responded) {
                        val bytes = receiveBytes()
                        val t = RecordCodec.peekType(bytes)
                        when (t) {
                            RecordType.DIGEST -> {
                                val digest = RecordCodec.decode<Digest>(bytes)
                                if (digest != null && digest.scope.contentEquals(scope)) {
                                    accepted++
                                    settled = true
                                    responded = true
                                }
                            }

                            RecordType.ERR -> {
                                responded = true
                                val err =
                                    RecordCodec.decode<Err>(bytes)
                                        ?: throw CheckFailure("expected a decodable err record, got garbage")
                                when (err.code) {
                                    ErrCode.QUOTA -> {
                                        quotaSeen = true
                                        settled = true
                                    }

                                    ErrCode.RATE -> {
                                        delay(err.retryMs ?: 1_000L)
                                    }

                                    else -> {
                                        throw CheckFailure(
                                            "expected digest, err quota, or err rate while filling scopes, " +
                                                "got err '${err.code}'",
                                        )
                                    }
                                }
                            }

                            RecordType.EVENT -> {
                                // Unsolicited fan-out — irrelevant here, keep waiting.
                            }

                            else -> {
                                throw CheckFailure("expected digest or err after sub, got '$t'")
                            }
                        }
                    }
                }
            }
            ensure(quotaSeen) {
                "expected err quota within ${limits.maxScopes + 1} scope creations (maxScopes=${limits.maxScopes})"
            }
        }
    }

/**
 * §6.4 (advisory): rapid pushes eventually answer err rate with a positive retryMs.
 *
 * The first blast is unbounded, and a spool may strike it out with a 4003 before its `err rate` is
 * read. The reset that close can bring takes the unread record with it, so a blast that died with
 * none read is repeated on a fresh connection with [RATE_BLAST_WINDOW] pushes unanswered at most,
 * which stops short of a close. Every push is answered with its `q`, `ok` or `err`.
 */
private fun rateLimit(): Check =
    Check(name = "rate-limit", must = false, destructive = true) { ctx ->
        val scope = ctx.randomScope()
        var blast = ctx.client.connect { rateBlast(ctx, scope, window = null) }
        if (blast.rateErr == null && blast.died) {
            blast = ctx.client.connect { rateBlast(ctx, scope, window = RATE_BLAST_WINDOW) }
        }
        val err = blast.rateErr ?: throw CheckFailure("no err rate observed within ${blast.sent} rapid pushes")
        val retryMs = err.retryMs
        ensure(retryMs != null && retryMs > 0) { "expected err rate with retryMs > 0, got retryMs=$retryMs" }
    }

/**
 * Pushes into [scope] until the first `err rate`, [RATE_BLAST_MAX] pushes, or the connection's end,
 * with at most [window] unanswered (none: as fast as the socket takes them).
 */
private suspend fun Session.rateBlast(
    ctx: Ctx,
    scope: ByteArray,
    window: Int?,
): RateBlast {
    hello()
    ctx.subscribeFresh(this, scope)
    val blast = RateBlast()
    try {
        while (blast.sent < RATE_BLAST_MAX && blast.rateErr == null) {
            if (window != null && blast.unanswered >= window) {
                blast.account(receiveBytesOrNull(timeoutMs) ?: break)
                continue
            }
            val (blobId, data) = ctx.randomBlob(16)
            send(Push(t = RecordType.PUSH, q = nextQ(), scope = scope, blobId = blobId, data = data))
            blast.sent++
            blast.unanswered++
            blast.takeArrived(this)
        }
        val deadline = System.currentTimeMillis() + RATE_DRAIN_MS
        while (blast.rateErr == null) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            blast.account(receiveBytesOrNull(remaining) ?: break)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The spool may close 4003 mid-blast; judge on whatever arrived before that.
        blast.died = true
    }
    return blast
}

/** One rate-limit blast: what it sent, its replies still owed, and the first `err rate` among them. */
private class RateBlast {
    var sent = 0
    var unanswered = 0
    var rateErr: Err? = null
    var died = false

    /** Accounts every record already received, without waiting, until the first `err rate`. */
    fun takeArrived(session: Session) {
        while (rateErr == null) {
            val frame =
                session.ws.incoming
                    .tryReceive()
                    .getOrNull() ?: return
            if (frame is Frame.Binary) account(frame.readBytes())
        }
    }

    fun account(bytes: ByteArray) {
        when (RecordCodec.peekType(bytes)) {
            RecordType.OK -> {
                unanswered--
            }

            RecordType.ERR -> {
                val err = RecordCodec.decode<Err>(bytes)
                if (err?.q != null) unanswered--
                if (err?.code == ErrCode.RATE && rateErr == null) rateErr = err
            }

            else -> {
                Unit // digest, event: unsolicited fan-out
            }
        }
    }
}

/**
 * A minimal definite-length CBOR writer (§2 profile subset) for the unknown-field check — enough
 * to hand-build a known record type carrying a field no schema declares.
 */
private object TinyCbor {
    private const val MAJOR_UINT = 0
    private const val MAJOR_BSTR = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_MAP = 5

    /** A definite-length map; values may be String (text), Long (uint), or ByteArray (bstr). */
    fun map(entries: List<Pair<String, Any>>): ByteArray {
        val out = ByteArrayOutputStream()
        head(out, MAJOR_MAP, entries.size.toLong())
        for ((key, value) in entries) {
            text(out, key)
            when (value) {
                is String -> {
                    text(out, value)
                }

                is Long -> {
                    head(out, MAJOR_UINT, value)
                }

                is ByteArray -> {
                    head(out, MAJOR_BSTR, value.size.toLong())
                    out.write(value)
                }

                else -> {
                    throw IllegalArgumentException("unsupported CBOR value type: ${value::class.simpleName}")
                }
            }
        }
        return out.toByteArray()
    }

    private fun text(
        out: ByteArrayOutputStream,
        value: String,
    ) {
        val bytes = value.toByteArray()
        head(out, MAJOR_TEXT, bytes.size.toLong())
        out.write(bytes)
    }

    private fun head(
        out: ByteArrayOutputStream,
        major: Int,
        argument: Long,
    ) {
        val base = major shl 5
        when {
            argument < 24 -> {
                out.write(base or argument.toInt())
            }

            argument < 256 -> {
                out.write(base or 24)
                out.write(argument.toInt())
            }

            argument < 65_536 -> {
                out.write(base or 25)
                out.write((argument ushr 8).toInt())
                out.write((argument and 0xFF).toInt())
            }

            else -> {
                throw IllegalArgumentException("argument too large for this tiny writer: $argument")
            }
        }
    }
}

private fun hex(bytes: ByteArray): String = bytes.toHexString()
