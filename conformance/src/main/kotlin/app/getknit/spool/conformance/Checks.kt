// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import app.getknit.spool.protocol.Achunk
import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ahas
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.CommonsInfo
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.Limits
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pow
import app.getknit.spool.protocol.PowStamp
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random

/** Grace window in which a record that must NOT arrive is awaited (uploader echo, duplicate event). */
private const val EVENT_GRACE_MS = 500L

/** Generous CBOR envelope allowance for a push record beyond its `data` payload. */
private const val PUSH_RECORD_OVERHEAD = 128L

/** Ceiling on the applied `maxFrames` this suite will fill past to force an eviction. */
private const val MAX_EVICTION_FILL = 32

/** Ceiling on `maxScopes` the destructive quota check will try to exhaust. */
private const val MAX_QUOTA_PROBE = 4_096

/** Ceiling on `maxPull` the overflow check will exceed (each id costs 34 encoded bytes). */
private const val MAX_PULL_PROBE = 65_536

/** Upper bound on pushes blasted by the rate-limit probe. */
private const val RATE_BLAST_MAX = 1_000

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
    /** The commons invite secret from `--commons-invite`, or null — the commons checks skip then. */
    val commonsSecret: ByteArray? = null,
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
        qCorrelation(),
        // rate-limit runs before quota-scopes: the quota probe fills the scope table, after
        // which no later check can create the fresh scope it needs.
        attachmentRoundTrip(),
        attachmentBadId(),
        attachmentFirstWriteWins(),
        attachmentGetTruncated(),
        // rate-limit runs before quota-scopes: the quota probe fills the scope table, after
        // which no later check can create the fresh scope it needs.
        commonsAdvertisement(),
        commonsBoundsPinned(),
        commonsFanout(),
        rateLimit(),
        quotaScopes(),
    )

/**
 * §7.4: whatever a spool says about its commons must be self-consistent and inside its own limits.
 *
 * Runs with or without an invite, because it needs no scope id — which is the point being checked.
 * A spool that leaked the id into `hello` would be advertising a room anybody who connects could
 * flood, and there is nothing in [CommonsInfo] for it to leak into.
 */
private fun commonsAdvertisement(): Check =
    Check(name = "commons-advertisement", must = false) { ctx ->
        val commons = ctx.serverHello.commons ?: throw SkipCheck("spool advertises no commons (§7.4)")
        val limits = ctx.limits()
        ensure(commons.maxFrames in 1..limits.maxFramesCap) {
            "commons maxFrames ${commons.maxFrames} outside the advertised 1..${limits.maxFramesCap}"
        }
        ensure(commons.ttlMs in 1..limits.maxTtlMs) {
            "commons ttlMs ${commons.ttlMs} outside the advertised 1..${limits.maxTtlMs}"
        }
        ensure(commons.maxBlob in 1..limits.maxBlob) {
            "commons maxBlob ${commons.maxBlob} outside the advertised 1..${limits.maxBlob}"
        }
        ensure(!commons.attach || limits.attachments) {
            "commons advertises attachments on a spool that advertises no attachment limits"
        }
    }

/**
 * §7.4: the commons applies the operator's bounds, not the subscriber's.
 *
 * The load-bearing one. A spool that honored a member's declaration would let any member subscribe
 * with `maxFrames = 1` and evict the whole room's history on the way in.
 */
private fun commonsBoundsPinned(): Check =
    Check(name = "commons-bounds-pinned", must = false) { ctx ->
        val commons = ctx.commonsRoom()
        ctx.client.connect {
            hello()
            send(
                Sub(
                    t = RecordType.SUB,
                    q = nextQ(),
                    // Deliberately hostile, and deliberately legal: every field is inside the
                    // spool's caps, so a clamp would let it through.
                    subs = listOf(ScopeSub(scope = commons.scope, bounds = ScopeBounds(1, 1L, 1), pow = null)),
                ),
            )
            val digest = expectDigestFor(commons.scope)
            ensure(digest.bounds.maxFrames == commons.info.maxFrames) {
                "expected the advertised commons maxFrames ${commons.info.maxFrames}, got ${digest.bounds.maxFrames}"
            }
            ensure(digest.bounds.ttlMs == commons.info.ttlMs) {
                "expected the advertised commons ttlMs ${commons.info.ttlMs}, got ${digest.bounds.ttlMs}"
            }
        }
    }

/** §7.4: the commons relays like any other scope — one member's push reaches the others. */
private fun commonsFanout(): Check =
    Check(name = "commons-fanout", must = false) { ctx ->
        val commons = ctx.commonsRoom()
        ctx.client.connect {
            val a = this
            a.hello()
            a.subscribeCommons(commons.scope)
            ctx.client.connect {
                val b = this
                b.hello()
                b.subscribeCommons(commons.scope)
                val (blobId, data) = ctx.randomBlob()
                a.send(Push(t = RecordType.PUSH, q = a.nextQ(), scope = commons.scope, blobId = blobId, data = data))
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
                    seen = event.scope.contentEquals(commons.scope) && event.blobId.contentEquals(blobId)
                }
            }
        }
    }

/** The commons as a check sees it: what `hello` advertised, plus the id derived from the invite. */
private class CommonsRoom(
    val info: CommonsInfo,
    val scope: ByteArray,
)

private fun Ctx.commonsRoom(): CommonsRoom {
    val info = serverHello.commons ?: throw SkipCheck("spool advertises no commons (§7.4)")
    val secret = commonsSecret ?: throw SkipCheck("no --commons-invite; the scope id cannot be derived")
    return CommonsRoom(info, Commons.scopeId(secret))
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

/** §4.5/§7.3: an attachment chunk stores, shows up in the presence bitmap, and reads back byte-exact. */
private fun attachmentRoundTrip(): Check =
    Check(name = "attachment-round-trip") { ctx ->
        ctx.requireAttachments()
        ctx.client.connect {
            hello()
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
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)
            val aid = ctx.randomScope()
            val (cid, data) = ctx.randomBlob(32)
            val total = maxAget + 2
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
            val ids = list.blobIds ?: emptyList()
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
            val missing = ok.missing ?: emptyList()
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

/** §6.4 (advisory): rapid pushes eventually answer err rate with a positive retryMs. */
private fun rateLimit(): Check =
    Check(name = "rate-limit", must = false, destructive = true) { ctx ->
        ctx.client.connect {
            hello()
            val scope = ctx.randomScope()
            ctx.subscribeFresh(this, scope)

            fun rateErrOrNull(bytes: ByteArray): Err? {
                if (RecordCodec.peekType(bytes) != RecordType.ERR) return null
                return RecordCodec.decode<Err>(bytes)?.takeIf { it.code == ErrCode.RATE }
            }

            var rateErr: Err? = null
            var sent = 0
            try {
                while (sent < RATE_BLAST_MAX && rateErr == null) {
                    val (blobId, data) = ctx.randomBlob(16)
                    send(
                        Push(
                            t = RecordType.PUSH,
                            q = nextQ(),
                            scope = scope,
                            blobId = blobId,
                            data = data,
                        ),
                    )
                    sent++
                    while (rateErr == null) {
                        val frame = ws.incoming.tryReceive().getOrNull() ?: break
                        val bytes = (frame as? Frame.Binary)?.readBytes() ?: continue
                        rateErr = rateErrOrNull(bytes)
                    }
                }
                if (rateErr == null) {
                    val deadline = System.currentTimeMillis() + RATE_DRAIN_MS
                    while (rateErr == null) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break
                        val bytes = receiveBytesOrNull(remaining) ?: break
                        rateErr = rateErrOrNull(bytes)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The spool may close 4003 mid-blast; judge on whatever arrived before that.
            }
            val err = rateErr ?: throw CheckFailure("no err rate observed within $sent rapid pushes")
            val retryMs = err.retryMs
            ensure(retryMs != null && retryMs > 0) { "expected err rate with retryMs > 0, got retryMs=$retryMs" }
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

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
