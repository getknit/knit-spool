// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.BuildInfo
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
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.RoomInfo
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.Sig
import app.getknit.spool.protocol.SigInfo
import app.getknit.spool.protocol.Sub
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.TurnCred
import app.getknit.spool.protocol.TurnReq
import app.getknit.spool.protocol.TurnUris
import app.getknit.spool.shortHex
import app.getknit.spool.store.AputResult
import app.getknit.spool.store.DigestInfo
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.PushResult
import app.getknit.spool.store.ScopeBatch
import app.getknit.spool.store.ScopeStore
import app.getknit.spool.store.SubscribeResult
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/** `SPOOL_TURN_RATE` counts issues per ten minutes; a [TokenBucket] wants them per second. */
private const val TURN_RATE_WINDOW_S = 600.0

/** A client may fetch a credential for a call and one more for an immediate redial. */
private const val TURN_CONN_BURST = 2.0

private const val MS_PER_S = 1000L

/** Headroom over `maxRecord` for the WebSocket frame envelope before the transport kills it (1009). */
private const val FRAME_SLACK = 1024L

/**
 * Message Ktor's pinger attaches when a peer stops answering pings: it calls
 * `sendCloseSequence(reason, IOException("Ping timeout"))`, which closes both `incoming` and
 * `outgoing` with that cause. Matched by text because Ktor exposes no distinct exception type.
 */
private const val PING_TIMEOUT_MESSAGE = "Ping timeout"

/** Consecutive undeliverable events before a subscriber is closed 4003 (spec §7.2). */
private const val SLOW_CONSUMER_LIMIT = 8

private const val RATE_STRIKE_LIMIT = 8

/**
 * A cast or sig bucket's burst as a multiple of its rate: the four used everywhere, and what `hello`'s
 * `rate` implies for both lanes.
 */
private const val LIVE_BURST_FACTOR = 4.0

/** A commons budget's burst as a multiple of its rate, per room and spool-wide alike (S-7.4-4, §12.2). */
private const val COMMONS_BURST_FACTOR = 4.0
private const val RATE_STRIKE_WINDOW_MS = 10_000L

/** Idle per-IP limiter state older than this is pruned by the sweeper. */
private const val IP_IDLE_MS = 600_000L

/** Pages one [ScopeStore.reclaimSpace] call frees: 8 MiB at SQLite's default 4 KiB page. */
private const val RECLAIM_BATCH_PAGES = 2_048

/** Batches per sweep: up to 512 MiB handed back a tick, the rest on the next one. */
private const val RECLAIM_BATCHES_PER_TICK = 64

/**
 * Scopes one [ScopeStore.sweep] call covers: a batch of scopes with nothing due is a fraction of a
 * millisecond, and one whose every scope is due stays in the tens of ms on a VPS core (ADR
 * 2026-10.d9ez has the measurements). Internal for SweeperTest.
 */
internal const val SWEEP_BATCH_SCOPES = 512

/**
 * Most client entries the per-IP table holds before an accept sheds every idle one. Entries with a
 * live connection number at most the connections, so the idle ones are what a client rotating
 * addresses could grow without bound — one per address, each held [IP_IDLE_MS] — and this bounds
 * them. About 250 bytes each, so 4 MiB at the cap.
 */
private const val CLIENT_TABLE_CAP = 16_384

/**
 * Most `(scope, day)` stamps the PoW cache holds. Legitimately it holds one per scope created in
 * the last two days, a few hundred at any default; an attacker mining stamps at 8 bits could fill
 * heap with it. A full cache verifies instead — one SHA-256 — so what fills it changes nothing.
 */
private const val POW_CACHE_CAP = 4_096

/** How often `guarded` writes a stack trace; the failures between two are counted, not logged. */
private const val INTERNAL_TRACE_MS = 60_000L

/**
 * `Retry-After` on a capacity refusal. Long enough that a rejected client spends the interval on
 * its other spools rather than re-dialing this one, short enough that a spool which empties out is
 * usable again quickly. Clients are expected to jitter around it; a whole population returning on
 * the same second is the reconnect storm this limit exists to prevent.
 */
private const val RETRY_AFTER_SECONDS = 30

/** The one WebSocket route (spec §7.1); also what the capacity gate matches on. */
private const val SPOOL_PATH = "/spool/v1"

/** How long [SpoolServer.stop] waits for each session's 1001 close to go out before moving on. */
private const val GOING_AWAY_CLOSE_MS = 1_000L

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * The spool daemon, SPOOL_PROTOCOL.md §6–§8: one WebSocket route at `/spool/v1`, one CBOR record
 * per binary message, hello negotiation first in both directions, then sub/list/pull/push against
 * the scope store with live `event` fan-out to the scope's other subscribers. Plus the operator
 * surface: `/healthz`, `/metrics`, per-connection and per-IP rate limits, the global storage
 * watermark, and a periodic sweeper that expires blobs and re-anchors subscribers by digest.
 *
 * TLS is left to a fronting reverse proxy; with `trustProxy` the per-IP limits key on the
 * proxy-appended `X-Forwarded-For` hop instead of the socket address. Either way the key is the
 * address literal — never a resolved name — and an IPv6 client is its /64; see [clientKey].
 */
class SpoolServer(
    config: Config,
    private val store: ScopeStore = InMemoryScopeStore(config.hardLimits),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Test seams for the two client-keyed tables' bounds; see [CLIENT_TABLE_CAP] and [POW_CACHE_CAP]. */
    private val clientTableCap: Int = CLIENT_TABLE_CAP,
    private val powCacheCap: Int = POW_CACHE_CAP,
    /** Test seam for [uploadBudgets]' bound; see [UPLOAD_BUDGETS_CAP]. */
    uploadBudgetsCap: Int = UPLOAD_BUDGETS_CAP,
) {
    /**
     * The live configuration, swapped as a whole object by [reload].
     *
     * Volatile and replaced rather than mutated field-by-field, so any single read sees one
     * generation and never a half-applied one. A handler that reads several related values should
     * take a local snapshot first for the same reason; the ones here that matter — the watermark's
     * ceiling and low-water mark — already do.
     *
     * What a reload can and cannot change is [Config.reloadable]. The rest is captured at boot by
     * something that cannot be rebuilt underneath a live connection: the store is constructed from
     * `hardLimits`, the sweeper and status line are scheduled from their cadences, and the port is
     * bound. The commons rooms reload, but through [roomTable], which carries their budgets.
     */
    @Volatile
    private var config: Config = config

    /**
     * The limits the store was built from, which no reload moves. A reload's rooms are checked
     * against these, not against a boot-only limit its file names and the reload ignores.
     */
    internal val hardLimits: HardLimits = config.hardLimits

    class Config(
        val port: Int,
        val token: String?,
        /**
         * A second connect credential, accepted alongside [token] and never announced.
         *
         * Rotation without it is a cutover: change the one token and every client is locked out
         * until it updates, which in a fleet means scheduling an outage to change a secret. With
         * it the sequence is add-new, let clients migrate, promote, retire — and nothing is
         * refused at any point.
         *
         * Deliberately two named values rather than a delimited list. A separator is a character
         * a token may legally contain, and the failure mode of getting that wrong is a spool that
         * accepts a credential nobody issued.
         */
        val tokenNext: String? = null,
        /**
         * The scrape credential, deliberately not the connect credential. Null falls back to
         * [token], which is how every spool behaved before this existed.
         */
        val metricsToken: String? = null,
        val powBits: Int,
        val maxRecord: Int,
        val maxPull: Int,
        val hardLimits: HardLimits,
        val maxAget: Int = 32,
        val maxBytes: Long = 268_435_456L,
        val sweepMs: Long = 60_000L,
        /** Cadence of the periodic status log line; 0 switches it off. */
        val statusMs: Long = 300_000L,
        val trustProxy: Boolean = false,
        /**
         * Total live connections the spool will hold; 0 is unlimited. Unlike every other limit
         * here this one is not in the spec — it is a property of the box, not the protocol — so
         * it refuses at the transport (503 on the upgrade) rather than inventing a close code.
         */
        val maxConns: Int = 0,
        val maxConnsPerIp: Int = 16,
        val rateRecords: Int = 50,
        val ratePushes: Int = 10,
        val rateNewScopesPerMin: Int = 6,
        /**
         * Ask clients to run their on-device content screen before sending and to withhold what
         * it flags (spec §7.5). Advertised as `moderation: true` in `hello` and omitted when off.
         *
         * A request, not a check: this spool holds ciphertext and cannot tell whether a client
         * complied, and it must not try. What the flag buys the operator is that every conforming
         * client on the spool refuses the same content the same way, with no "send anyway".
         */
        val requireModeration: Boolean = false,
        /** The §13 corresponding-source URL served at `GET /source`; a fork overrides it. */
        val sourceUrl: String = BuildInfo.UPSTREAM_SOURCE_URL,
        /** The commons rooms (spec §7.4), first to last; empty when this spool runs none. */
        val rooms: List<CommonsConfig> = emptyList(),
        /**
         * Pushes per second into every room together, with a burst of four times that (S-7.4-4).
         * Each room's own budget bounds that room; this bounds what all of them send out at once,
         * since egress is every room's rate times its members.
         */
        val commonsRateTotal: Int = 40,
        /** TURN credential issuing (experimental, SPOOL_PROTOCOL.md §7.6), or null when off. */
        val turn: TurnConfig? = null,
        /**
         * `stun:`/`stuns:` URIs advertised in `hello` to every client, with no credential
         * (experimental, SPOOL_PROTOCOL.md §7.6). Independent of [turn]: a spool that issues nothing
         * can still tell a free phone where to learn its own public address.
         */
        val stunUrls: List<String> = emptyList(),
        /** Entitlement-ticket checking (experimental, SPOOL_PROTOCOL.md §7.6), or null when off. */
        val ticket: TicketConfig? = null,
        /**
         * Casts each connection may send per second, with a burst of four times that (experimental,
         * SPOOL_PROTOCOL.md §7.7). 0, the default, turns casts off: `hello` omits `cast` and every
         * cast is dropped. Beside a ticket key, a cast needs a scope a cast ticket has unlocked.
         */
        val rateCasts: Int = 0,
        /** The largest cast `data` this spool fans out, in bytes. At most [maxRecord]. */
        val maxCast: Int = 2_048,
        /**
         * Casts per second the whole spool accepts, with a burst of four times that. The
         * per-connection bucket bounds one client; this bounds all of them together, the way the
         * commons budget bounds one room. Each accepted cast leaves once per other subscriber of
         * its scope, so egress is this times the cast size times that fan-out.
         */
        val rateCastsTotal: Int = 200,
        /**
         * Sigs each connection may send per second, with a burst of four times that
         * (SPOOL_PROTOCOL.md §7.8). On by default: no ticket gates a sig (S-7.8-4), so a spool carries
         * every phone's typing cues with no configuration. 0 turns signals off: `hello` omits `sig`
         * and every sig is dropped.
         */
        val rateSigs: Int = 2,
        /** The largest sig `data` this spool fans out, in bytes. At most [maxRecord]. */
        val maxSig: Int = 8_192,
        /**
         * Sigs per second the whole spool accepts, with a burst of four times that: [rateCastsTotal]'s
         * bound for the signal lane, and a budget of its own, so a walkie never starves a typing cue.
         * Egress is this times the sig size times each scope's other subscribers.
         */
        val rateSigsTotal: Int = 200,
    ) {
        /**
         * Every resolved value on one line, `k=v`, for the boot log.
         *
         * Defaults included on purpose: the value an operator misremembers is always the one they
         * never set, and a fleet makes that a support call rather than a shrug. One line and no
         * spaces in any value, so it splits the same way the status line does.
         *
         * Secrets are reported as `set`/`unset` and never printed — `SECURITY.md` makes a token in
         * this daemon's log a reportable vulnerability. Each commons room is reported as a truncated
         * id for the same reason `hello` never carries one at all: publishing it would turn a room
         * only invite holders can find into one anybody can subscribe to. Their bounds are already on
         * the `commons enabled:` lines, so they are not repeated here.
         */
        fun describe(): String = (bootOnly() + reloadable()).entries.joinToString(" ") { (key, value) -> "$key=$value" }

        /**
         * The half a restart is required to change, because something is already built from it: the
         * store from `hardLimits`, the sweeper and status line from their cadences, the listener from
         * the port.
         *
         * A reload that names any of these keeps the boot value and says so.
         */
        internal fun bootOnly(): Map<String, Any?> =
            linkedMapOf(
                "port" to port,
                "maxBlob" to hardLimits.maxBlob,
                "maxFrames" to hardLimits.maxFramesCap,
                "maxTtlMs" to hardLimits.maxTtlMs,
                "maxScopes" to hardLimits.maxScopes,
                "attachBytes" to hardLimits.maxAttachBytes,
                "maxAChunk" to hardLimits.maxAChunk,
                "sweepMs" to sweepMs,
                "statusMs" to statusMs,
                "trustProxy" to trustProxy,
                // Boot-only: each secret changes in lockstep with its TURN server's, and the
                // per-connection and per-IP buckets are built from the rate when a client arrives.
                // One URL count and one secret per server, comma-joined, so a reload that adds or
                // drops a server is reported, and a one-server spool reads as it always has.
                "turn" to
                    (
                        turn?.let { t -> "${t.servers.joinToString(",") { "${it.urls.size}urls" }}/${t.ttlS}s/${t.ratePer10Min}per10min" }
                            ?: "off"
                    ),
                "turnSecret" to (turn?.servers?.joinToString(",") { secret(it.secret) } ?: secret(null)),
                "source" to sourceUrl,
            )

        /** The half `SIGHUP` may replace. Everything here is read live off the volatile config. */
        internal fun reloadable(): Map<String, Any?> =
            linkedMapOf(
                "token" to secret(token),
                "tokenNext" to secret(tokenNext),
                "metricsToken" to secret(metricsToken),
                "pow" to powBits,
                "maxRecord" to maxRecord,
                "maxPull" to maxPull,
                "maxAget" to maxAget,
                "maxBytes" to maxBytes,
                "maxConns" to maxConns,
                "maxConnsPerIp" to maxConnsPerIp,
                "rateRecords" to rateRecords,
                "ratePushes" to ratePushes,
                "rateNewScopes" to rateNewScopesPerMin,
                "moderation" to requireModeration,
                // A count: describe() splits on spaces and URIs are not secrets, but a list in one
                // k=v field is noise. The values are in the env, and in every client's hello.
                "stun" to stunUrls.size,
                // The key is public, but reported like a secret: presence is what an operator
                // checks, and a 43-character value would drown the line. Read live by every `ent`;
                // whether a connection may present one at all is fixed by the hello it was sent.
                "ticketKey" to if (ticket == null) "unset" else "set",
                "ticketKeyNext" to if (ticket?.next == null) "unset" else "set",
                "ticketAud" to (ticket?.audience ?: "unset"),
                // Read live by every `ent`, like the key: never announced, so nothing waits on a hello.
                "ticketAudAlias" to (ticket?.audienceAlias ?: "unset"),
                // Snapshotted per connection, like the key's presence: a reload reaches new ones only.
                "ticketGates" to (ticket?.gateWords() ?: "unset"),
                // Read live by every gated `aput`, so a lower cap bites on a pseudonym's next chunk.
                "ticketAttachBytes" to (ticket?.attachBytes ?: "unset"),
                // Read live by every ticketed `turnreq`; a holder's bucket is rebuilt when it moves.
                "ticketTurnRate" to (ticket?.turnRatePer10Min ?: "unset"),
                "rateCasts" to rateCasts,
                "maxCast" to maxCast,
                "rateCastsTotal" to rateCastsTotal,
                // Snapshotted per connection like the cast trio, with the same live kill switch at 0.
                "rateSigs" to rateSigs,
                "maxSig" to maxSig,
                "rateSigsTotal" to rateSigsTotal,
                // Comma-joined, first room first, so describe() keeps one field however many there are.
                // Reloadable as a set with the total budget (ADR 2026-10.hs75); a reload logs each room it
                // changes on its own line, since an id alone does not show a bounds change.
                "commons" to (rooms.joinToString(",") { shortHex(it.scopeId) }.ifEmpty { "off" }),
                "commonsRateTotal" to commonsRateTotal,
            )

        /**
         * This config's boot half with [candidate]'s reloadable half — what a `SIGHUP` actually
         * installs. Built by naming every field rather than by copying, so adding one to [Config]
         * without deciding which half it belongs to fails to compile here.
         *
         * [rooms] and [commonsRateTotal] are passed apart because they install as one unit or not at
         * all: a room the store cannot create keeps the running set and its total budget (see
         * [SpoolServer.reload]), while the rest of [candidate] still applies.
         */
        internal fun withRuntimeOf(
            candidate: Config,
            rooms: List<CommonsConfig> = candidate.rooms,
            commonsRateTotal: Int = candidate.commonsRateTotal,
        ): Config =
            Config(
                port = port,
                hardLimits = hardLimits,
                sweepMs = sweepMs,
                statusMs = statusMs,
                trustProxy = trustProxy,
                sourceUrl = sourceUrl,
                rooms = rooms,
                commonsRateTotal = commonsRateTotal,
                turn = turn,
                token = candidate.token,
                tokenNext = candidate.tokenNext,
                metricsToken = candidate.metricsToken,
                powBits = candidate.powBits,
                maxRecord = candidate.maxRecord,
                maxPull = candidate.maxPull,
                maxAget = candidate.maxAget,
                maxBytes = candidate.maxBytes,
                maxConns = candidate.maxConns,
                maxConnsPerIp = candidate.maxConnsPerIp,
                rateRecords = candidate.rateRecords,
                ratePushes = candidate.ratePushes,
                rateNewScopesPerMin = candidate.rateNewScopesPerMin,
                requireModeration = candidate.requireModeration,
                stunUrls = candidate.stunUrls,
                ticket = candidate.ticket,
                rateCasts = candidate.rateCasts,
                maxCast = candidate.maxCast,
                rateCastsTotal = candidate.rateCastsTotal,
                rateSigs = candidate.rateSigs,
                maxSig = candidate.maxSig,
                rateSigsTotal = candidate.rateSigsTotal,
            )

        private fun secret(value: String?): String = if (value == null) "unset" else "set"
    }

    /**
     * Where and how this spool issues TURN credentials ([TurnCredentials]): up to four servers, each
     * signed for with its own secret, under one lifetime and one set of budgets (ADR 2026-10.u5f4).
     */
    class TurnConfig(
        /** Server 1 first, in number order; never empty. */
        val servers: List<TurnServer>,
        /**
         * How long a credential can start a relay. coturn checks expiry only at a session's first
         * authentication, so a call outlives it; a short window is what keeps a leaked one cheap.
         */
        val ttlS: Long,
        /** Issues per connection per ten minutes; the per-IP budget is four times this. */
        val ratePer10Min: Int,
    ) {
        init {
            require(servers.isNotEmpty()) { "a TURN config needs server 1" }
        }

        /** `hello`'s `turnHosts`: each server's entry, in server order, or null when none has one (S-7.6-48). */
        val hosts: List<String>? = servers.mapNotNull { it.entry }.takeIf { it.isNotEmpty() }

        /**
         * The server [host] names, byte for byte, or server 1 for an absent or unmatched one (S-7.6-49).
         * Server 1 is what every client that predates `host` already gets, so a miss costs it nothing.
         */
        fun select(host: String?): TurnServer = servers.firstOrNull { host != null && it.entry == host } ?: servers.first()
    }

    /**
     * One TURN server this spool signs for. The spool never relays media: [secret] is shared with
     * that server, which verifies what this hands out.
     *
     * A plain class rather than a data class, so no `toString` can put [secret] in a log line.
     */
    class TurnServer(
        /** 1 for `SPOOL_TURN_*`, n for `SPOOL_TURN_<n>_*`: the metrics' `server` label. */
        val number: Int,
        val secret: String,
        /** RFC 7065 `turn:`/`turns:` URIs, sent to clients verbatim, in their configured order. */
        val urls: List<String>,
    ) {
        /** The `host:port` of its UDP URL ([TurnUris.entry]), or null when it has none. */
        val entry: String? = TurnUris.udpEntry(urls)
    }

    /** One ticket-verifying public key and its [Tickets.kid], computed once rather than on every `ent`. */
    class TicketKey(
        val key: PublicKey,
        val kid: ByteArray,
    )

    /**
     * What entitlement tickets ([Tickets]) are checked against (experimental, SPOOL_PROTOCOL.md
     * §7.6): the issuer's public key and the names a ticket's audience must be one of to be good
     * here. Only the public half — whoever issues tickets holds the signing key, and nothing in the
     * daemon can mint.
     */
    class TicketConfig(
        val key: PublicKey,
        /** [Tickets.kid] of [key], computed once rather than on every `ent`. */
        val kid: ByteArray,
        val audience: String,
        /**
         * `SPOOL_TICKET_AUDIENCE_ALIAS`: a second name accepted beside [audience] while the spool is
         * renamed and both names reach it. Never announced.
         */
        val audienceAlias: String? = null,
        /**
         * The [Ticket.fl] bits a connection needs a ticket for, `SPOOL_TICKET_GATES` (ADR 2026-10.h6pt).
         * Advertised as `entGates` and snapshotted per connection from the hello it was sent.
         */
        val gates: Long = Tickets.LEGACY_GATES,
        /** `SPOOL_TICKET_ATTACH_BYTES`: the most a pseudonym may upload in a day, whatever its `ab` claims. */
        val attachBytes: Long = DEFAULT_ATTACH_BYTES,
        /**
         * `SPOOL_TICKET_KEY_NEXT`: the key being rotated in, accepted beside [key] (ADR 2026-10.x9gq).
         * A ticket's `kid` picks which one verifies it.
         */
        val next: TicketKey? = null,
        /**
         * `SPOOL_TICKET_TURN_RATE`: TURN issues per pseudonym per ten minutes, on top of the
         * per-connection and per-address buckets; 0 leaves only those (ADR 2026-10.x9gq).
         */
        val turnRatePer10Min: Int = DEFAULT_TURN_RATE,
    ) {
        /** Every name a ticket's `aud` may give: [audience], and [audienceAlias] mid-rename. */
        val audiences: Set<String> = setOfNotNull(audience, audienceAlias)

        /** Every key a ticket may be signed by, with its `kid`: [key] first, then [next]. */
        val verifiers: List<Pair<PublicKey, ByteArray>> = listOfNotNull(key to kid, next?.let { it.key to it.kid })

        /** [gates] as the words `SPOOL_TICKET_GATES` takes, comma-joined so describe() keeps one field. */
        fun gateWords(): String =
            GATE_WORDS
                .filterValues { gates and it != 0L }
                .keys
                .joinToString(",")
                .ifEmpty { "none" }

        companion object {
            /** Two full scopes a day at the default `SPOOL_MAX_ATTACH_BYTES`, sized against the disk (ADR 2026-10.h6pt). */
            const val DEFAULT_ATTACH_BYTES = 33_554_432L

            /**
             * One address's TURN budget at the default `SPOOL_TURN_RATE` of 4: a holder's phones,
             * wherever they are, get what one household gets.
             */
            const val DEFAULT_TURN_RATE = 16

            /** What `SPOOL_TICKET_GATES` accepts. `create` is refused: creation stays open (ADR 2026-10.x9gq). */
            val GATE_WORDS = linkedMapOf("turn" to Tickets.FL_TURN, "files" to Tickets.FL_FILES, "cast" to Tickets.FL_CAST)
        }
    }

    /**
     * One commons room: an operator-declared scope shared by everyone on the spool who holds its
     * invite. A spool runs up to eight, each with its own invite, bounds and budget.
     *
     * [scopeId] is `SHA-256("knit/spool/v1/commons" ‖ secret)`. The secret never reaches the spool,
     * so this daemon relays a room it structurally cannot read — it holds a hash of the invite and
     * the sealed frames, and no code path here turns one into the other.
     *
     * [bounds] are *pinned*, not declared. The store applies whatever the most recent subscriber
     * asked for, which is right for a conversation whose members negotiate among themselves and
     * wrong for a room shared with strangers: one member subscribing with `maxFrames = 1` would
     * evict everyone else's history. So a room ignores what a client declares and answers with
     * the truth in the `digest` it returns.
     */
    class CommonsConfig(
        val scopeId: ByteArray,
        val name: String?,
        val bounds: ScopeBounds,
        val attach: Boolean,
        val ratePushes: Int,
        /**
         * The room's place in the environment: 1 for `SPOOL_COMMONS_*`, n for `SPOOL_COMMONS_<n>_*`.
         * What metrics and logs name it by, never its id or its name.
         */
        val number: Int = 1,
    ) {
        /** The room's `tag` in `hello`'s `rooms` (B-7.4-16), one-way from [scopeId]. */
        val tag: ByteArray = Commons.tag(scopeId)

        /**
         * The most bytes the room can hold: its frames at their largest, plus a scope's worth of
         * attachments when it takes them. It is pinned against the watermark, so this is what boot
         * checks against `SPOOL_MAX_BYTES`.
         */
        fun footprint(hardLimits: HardLimits): Long =
            bounds.maxFrames.toLong() * bounds.maxBlob + if (attach) hardLimits.maxAttachBytes.toLong() else 0L

        /** [footprint]'s arithmetic in words, for the refusal that quotes it. */
        fun footprintTerms(hardLimits: HardLimits): String =
            "${bounds.maxFrames} frames x ${bounds.maxBlob}" + if (attach) " plus ${hardLimits.maxAttachBytes} of attachments" else ""

        /**
         * Whether [other] is this room as a member's `hello` describes it: the same scope, bounds and
         * `attach`, which a member reads and keeps to (C-7.4-17). A connection told one shape is closed
         * when the room takes another (S-7.4-21). The name is left out: it changes nothing a member
         * sends. Field by field because neither this class nor [ScopeBounds] has value equality.
         */
        internal fun sameShape(other: CommonsConfig): Boolean =
            scopeId.contentEquals(other.scopeId) &&
                bounds.maxFrames == other.bounds.maxFrames &&
                bounds.ttlMs == other.bounds.ttlMs &&
                bounds.maxBlob == other.bounds.maxBlob &&
                attach == other.attach

        /** [sameShape], and the same name, budget and number too: a reload with nothing to install. */
        internal fun sameAs(other: CommonsConfig): Boolean =
            sameShape(other) && name == other.name && ratePushes == other.ratePushes && number == other.number
    }

    private val log = LoggerFactory.getLogger(SpoolServer::class.java)

    /**
     * [guarded]'s trace sampler: when the next stack trace may be written (never before the first)
     * and how many failures went unwritten since the last, both under [internalTraceLock]. See
     * [logInternal].
     */
    private val internalTraceLock = Any()
    private var internalTraceDueAt = Long.MIN_VALUE
    private var internalSuppressed = 0L

    /**
     * The periodic status line logs under its own name so an operator can re-level or silence just
     * that line in logback — `SPOOL_LOG_LEVEL` is the root level and would take the rest with it.
     */
    private val statusLog = LoggerFactory.getLogger("app.getknit.spool.Status")

    val metrics = Metrics()

    private val statusLine =
        StatusLine(
            metrics = metrics,
            maxScopes = config.hardLimits.maxScopes,
            maxBytes = { config.maxBytes },
            maxConns = { config.maxConns },
            castsOn = { config.rateCasts > 0 },
            sigsOn = { config.rateSigs > 0 },
            startedAtMs = clock(),
        )

    /**
     * Every store call is serialized through one worker thread off the CIO event loops: the store
     * implementations lock anyway (SQLite is single-writer), and this keeps their blocking I/O
     * from ever stalling a connection coroutine.
     */
    private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** scope hex → live subscriber connections (event and digest fan-out targets). */
    private val subscribers = ConcurrentHashMap<String, MutableSet<Conn>>()

    /**
     * scope hex → `sub`s between their store call and their [subscribers] entry. Counted rather
     * than added to [subscribers] early, so no `event` reaches a connection ahead of its `digest`.
     */
    private val subscribing = ConcurrentHashMap<String, Int>()

    /**
     * Accepted PoW cache: "scopeHex:day" → day (spec §8); pruned by the sweeper on day rollover
     * and never grown past [powCacheCap].
     */
    private val powAccepted = ConcurrentHashMap<String, Long>()

    /**
     * Per-client limiter state, keyed by [clientKey]. Idle entries are pruned by the sweeper after
     * [IP_IDLE_MS], and all at once by an accept that finds the table at [clientTableCap]; see
     * [pruneIdleClients] for why an entry with a live connection is never dropped.
     */
    private val ips = ConcurrentHashMap<String, IpState>()

    /** Each ticket holder's uploads today where `files` is gated; pruned by the sweeper (ADR 2026-10.h6pt). */
    private val uploadBudgets = UploadBudgets(uploadBudgetsCap)

    /** Each ticket holder's TURN issues, `SPOOL_TICKET_TURN_RATE`; pruned by the sweeper (ADR 2026-10.x9gq). */
    private val turnBudgets = TurnBudgets(clock)

    /**
     * A commons room as the server holds it: its config, its key into [subscribers], and its own push
     * budget.
     *
     * A room is a shared write surface, which the per-connection push bucket does not bound: 200
     * members at 10/s each is 2,000 pushes/s into one scope, churning a 500-frame room four times a
     * second and amplifying through fan-out to 400k frames/s. [bucket] is the room's total, which no
     * number of members can exceed (S-7.4-4).
     */
    private class Room(
        val config: CommonsConfig,
        val hex: String,
        val bucket: TokenBucket,
    )

    /**
     * Every room this spool runs and the budget they share, swapped whole by [reload] (ADR 2026-10.hs75)
     * and never changed in place, so a handler that reads [roomTable] once sees one generation of
     * rooms. Reading it twice could see a room in the first read and not in the second.
     *
     * A connection keeps the table its `hello` was built from ([Conn.rooms]): that is how a reload
     * finds the connections whose `hello` it has made untrue.
     */
    private class RoomTable(
        val rooms: List<Room>,
        /**
         * Every room's pushes together (S-7.4-4, `SPOOL_COMMONS_RATE_TOTAL`): what bounds the spool's
         * commons egress, which is each room's rate times its members, summed.
         */
        val totalBucket: TokenBucket,
        val totalRate: Int,
    ) {
        /** What the watermark may never shed (S-7.4-3): every room's scope. */
        val pinned: List<ByteArray> = rooms.map { it.config.scopeId }

        /** The room [scope] is, or null for any other scope. At most eight 32-byte compares, cheaper than hex. */
        fun roomOf(scope: ByteArray): Room? = rooms.firstOrNull { it.config.scopeId.contentEquals(scope) }
    }

    /**
     * The rooms running now. Replaced, never mutated, and only under [roomLock] once the server is
     * up; read without it everywhere except the two places that decide whom a change closes.
     */
    @Volatile
    private var roomTable: RoomTable = roomTableFor(config.rooms, config.commonsRateTotal, previous = null)

    /**
     * Held across a reload's table swap and the scan for connections it makes stale, and across a
     * `sub`'s staleness check once its subscriber entry exists. Whichever runs second sees the
     * other's write, so a `sub` racing a rotation is closed by one of the two (S-7.4-21). Nothing
     * inside it does I/O.
     */
    private val roomLock = Any()

    /** One reload at a time: the JDK runs each signal on a thread of its own, and two would diff against one table. */
    private val reloadLock = Any()

    /**
     * The spool-wide cast budget ([Config.rateCastsTotal]). Rebuilt by [reload] when the rate moves,
     * since unlike a connection's bucket it outlives every client that could pick up a new one.
     */
    @Volatile
    private var castTotalBucket = liveBucketFor(config.rateCastsTotal)

    /** The spool-wide sig budget ([Config.rateSigsTotal]), rebuilt by [reload] for [castTotalBucket]'s reason. */
    @Volatile
    private var sigTotalBucket = liveBucketFor(config.rateSigsTotal)

    /** The source of TURN username suffixes; see [TurnCredentials] for why they are random. */
    private val turnRandom = SecureRandom()

    /** Latches the "over watermark, nothing left to shed" warning so it logs on entry, not per sweep. */
    private var watermarkStuck = false

    /**
     * What `/metrics` checks `?k=` against: [Config.metricsToken] if set, otherwise [Config.token].
     *
     * They are separate because the two credentials answer to different people. On a hosted spool
     * the customer holds `SPOOL_TOKEN`, and `/metrics` carries scope counts and traffic shape that
     * are the operator's business, not theirs — while a fleet-wide Prometheus should not need every
     * customer's connect secret in its scrape config to read them. Null here means a public spool
     * with no metrics token, and `/metrics` is open exactly as it was.
     */
    private val metricsGate: List<String>
        get() = listOfNotNull(config.metricsToken).ifEmpty { connectGate }

    /** Every credential `?k=` may present on a connect. Empty means an open spool. */
    private val connectGate: List<String>
        get() = listOfNotNull(config.token, config.tokenNext)

    /**
     * The §13 answer, assembled once: it cannot change while the process runs, and an
     * unauthenticated route should not rebuild a fixed document per request.
     */
    private val sourceJson: String =
        """{"name":"knit-spool","version":"${jsonString(BuildInfo.version)}",""" +
            """"commit":"${jsonString(BuildInfo.commit)}",""" +
            """"source":"${jsonString(config.sourceUrl)}",""" +
            """"license":"AGPL-3.0-or-later"}"""

    /**
     * Whether new connections are being refused while the live ones are left to finish.
     *
     * A rolling upgrade needs somewhere to stand between "serving" and "stopped": [stop] closes
     * every session at once, which on a hosted spool is every customer's device reconnecting on
     * the same second. Draining lets traffic be shifted first.
     */
    @Volatile
    private var draining = false

    private val activeSessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    private var appScope: CoroutineScope? = null

    private inner class Conn(
        val session: DefaultWebSocketServerSession,
        val ipState: IpState,
        /** The hello this connection was sent: what it fixes for the connection's life is read off it below. */
        hello: Hello,
        /**
         * The rooms its `hello` described, fixed for its life like [entAdvertised]. A reload that stops
         * running one of them, or changes its bounds or `attach`, closes this connection if it is in
         * that room, so it reads the room again from a new `hello` (S-7.4-21).
         */
        val rooms: RoomTable,
    ) {
        /**
         * Whether the hello this connection was sent carried `ent`. Fixed for its life: a SIGHUP
         * that adds or removes the ticket key must not start refusing `turnreq` on a connection
         * that was never told a ticket exists, nor waive the subscription gate on one that was.
         */
        val entAdvertised: Boolean = hello.ent == true

        /**
         * The `cast` bounds this connection was told, or null when its hello carried none. Fixed for
         * its life, like [entAdvertised]: a cast is never answered, so a client can only keep to the
         * bounds it was given, and a reload that tightened them under it would drop it silently.
         */
        val castInfo: CastInfo? = hello.cast

        /** The `sig` bounds this connection was told, or null when its hello carried none; fixed for [castInfo]'s reason. */
        val sigInfo: SigInfo? = hello.sig

        /**
         * The [Ticket.fl] bits this connection needs a ticket for: the `entGates` its hello carried,
         * fixed for its life like [entAdvertised], so a reload that gates `files` never starts refusing
         * uploads on a connection that was told they were open.
         */
        val gates: Long = hello.ticketGates

        val out: SendChannel<Frame> get() = session.outgoing

        /** scope hex → the bounds this connection last declared (drives the push-recreate path). */
        val subscriptions = HashMap<String, ScopeBounds>()
        val recordBucket = TokenBucket(config.rateRecords.toDouble(), 4.0 * config.rateRecords, clock)
        val pushBucket = TokenBucket(config.ratePushes.toDouble(), 4.0 * config.ratePushes, clock)
        val turnBucket = config.turn?.let { TokenBucket(it.ratePer10Min / TURN_RATE_WINDOW_S, TURN_CONN_BURST, clock) }
        val castBucket = castInfo?.let { liveBucketFor(it.rate) }
        val sigBucket = sigInfo?.let { liveBucketFor(it.rate) }
        val strikes = StrikeWindow(RATE_STRIKE_LIMIT, RATE_STRIKE_WINDOW_MS)
        val eventMisses = AtomicInteger(0)

        /**
         * The last ticket this connection presented that was accepted; replaced by the next one.
         * Volatile because other connections read it: a cast ticket here unlocks their casts too.
         */
        @Volatile
        var ticket: Ticket? = null
    }

    fun start(wait: Boolean = true): EmbeddedServer<*, *> {
        createRooms()
        val server =
            embeddedServer(CIO, port = config.port) {
                appScope = this
                install(WebSockets) {
                    maxFrameSize = config.maxRecord + FRAME_SLACK
                    pingPeriodMillis = 30_000
                    timeoutMillis = 60_000
                }
                if (config.trustProxy) {
                    install(XForwardedHeaders) {
                        useLastProxy()
                    }
                }
                launch {
                    while (isActive) {
                        delay(config.sweepMs)
                        runCatching { sweepTick() }.onFailure { log.error("sweep tick failed", it) }
                    }
                }
                if (config.statusMs > 0) {
                    launch {
                        while (isActive) {
                            delay(config.statusMs)
                            // Never let a status line take the daemon down: it is an eyeball aid,
                            // and /metrics remains the machine-readable surface either way.
                            runCatching { statusTick() }.onFailure { log.warn("status tick failed", it) }
                        }
                    }
                }
                // Capacity is refused before the upgrade, not after it. A spool that is full is
                // not a protocol failure — §7.1 has four close codes and none of them means "come
                // back later", and 4003 abuse would teach a client it misbehaved. 503 +
                // Retry-After is the transport saying "not now", which a multi-homing client
                // already handles: an unreachable spool is the case the design is built around.
                // It is also the cheapest possible no, costing neither a WebSocket session nor a
                // HELLO.
                //
                // Matched on the path here rather than scoped to the route below, because a
                // route-scoped `intercept` on an ApplicationCallPipeline phase installs on the
                // shared pipeline and would answer /healthz and /metrics with 503 as well — which
                // would fail the image's HEALTHCHECK and restart the spool at its busiest moment.
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.path() == SPOOL_PATH && refuseUpgrade()) {
                        if (draining) metrics.drainRefusedTotal.increment() else metrics.connsRefusedTotal.increment()
                        call.response.header(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS.toString())
                        call.respondText("at capacity", status = HttpStatusCode.ServiceUnavailable)
                        finish()
                    }
                }
                routing {
                    webSocket(SPOOL_PATH) {
                        acceptConnection(this)
                    }
                    get("/healthz") {
                        val alive = runCatching { withStore { store.scopeCount() } }.isSuccess
                        if (alive) {
                            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                        } else {
                            call.respondText(
                                """{"status":"fail"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable,
                            )
                        }
                    }
                    // AGPL §13: anyone whose client talks to this spool over a network is entitled
                    // to the corresponding source of the version they are talking to. That is an
                    // obligation to *users*, so unlike /metrics this route is deliberately not
                    // behind SPOOL_TOKEN — a private spool's users are still its users, and an
                    // offer nobody can read is not an offer. It discloses only what the operator
                    // already published: no scope counts, no traffic shape, nothing about who is
                    // connected.
                    get("/source") {
                        call.respondText(sourceJson, ContentType.Application.Json)
                    }
                    get("/metrics") {
                        if (!accepts(metricsGate, call.request.queryParameters["k"])) {
                            call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                            return@get
                        }
                        val (scopeCount, liveBytes) = withStore { store.scopeCount() to store.totalBytes() }
                        val fileBytes = withStore { store.storeFileBytes() }
                        call.respondText(
                            metrics.render(
                                scopeCount,
                                liveBytes,
                                config.maxConns,
                                commonsSubscribers(),
                                fileBytes,
                                turnServers = config.turn?.servers?.size ?: 0,
                                tickets = config.ticket != null,
                                casts = config.rateCasts > 0,
                                sigs = config.rateSigs > 0,
                            ),
                            ContentType.Text.Plain,
                        )
                    }
                }
            }
        engine = server
        log.info("knit-spool {} (commit {}) — source {}", BuildInfo.version, BuildInfo.commit, config.sourceUrl)
        log.info("effective config: {}", config.describe())
        log.info("knit-spool listening on :{} (pow={} bits, token={})", config.port, config.powBits, config.token != null)
        logWithheldNames(config)
        return server.start(wait = wait)
    }

    /**
     * Install [candidate]'s reloadable half, keeping this spool's boot half, and report what moved.
     *
     * Live connections are untouched, which is the entire point: changing a quota or rotating a
     * credential should not cost every client a reconnect. What that buys is bounded, and the
     * bound is worth stating rather than discovering:
     *
     *  - `maxConns`, `maxBytes` and the credentials are read on every use, so they bite immediately.
     *  - `powBits`, `maxRecord`, `maxPull`, `maxAget` and `requireModeration` are announced in
     *    `hello`, so they apply to connections opened after the reload. Existing ones keep the
     *    contract they negotiated.
     *  - `rateRecords` and `ratePushes` size a bucket built per connection, and
     *    `rateNewScopesPerMin` one built per client address, so both apply to *new* connections and
     *    addresses. An established client keeps the budget it was admitted with until it reconnects.
     *  - `rateCasts` and `maxCast` are announced in `hello` and snapshotted per connection, except
     *    that a rate of 0 stops every cast at once: it is the operator's kill switch.
     *    `rateCastsTotal` rebuilds the spool-wide bucket here, so it bites immediately.
     *  - `rateSigs`, `maxSig` and `rateSigsTotal` behave as the cast trio do, kill switch included.
     *  - The ticket gates are announced in `hello` as `entGates` and snapshotted per connection, so
     *    a reload that gates `files` leaves connections opened before it uploading freely. The
     *    attach budget is read on every gated `aput`, and the budgets already spent survive.
     *  - The commons rooms and `commonsRateTotal` install as one unit ([reloadRooms]). They are the
     *    one change that closes connections: the members of a room the reload stops running, or whose
     *    bounds or `attach` it changes, so that each reads the room again from a new `hello`
     *    (S-7.4-21). A room it adds is in the `hello` of connections opened after it.
     *
     * Returns what [candidate] asked for that was not installed, one line each, which the caller logs:
     * the boot-only fields it tried to change, and the rooms when the store could not create one.
     * Neither is fatal: refusing the whole reload over one would mean a stale `SPOOL_PORT` in an
     * overrides file, or a full store, could block every later change to a rate limit or a token.
     */
    fun reload(candidate: Config): List<String> =
        synchronized(reloadLock) {
            val current = config
            val ignored =
                current.bootOnly().mapNotNull { (key, value) ->
                    val proposed = candidate.bootOnly()[key]
                    if (proposed != value) "$key=$proposed (still $value) needs a restart" else null
                }
            val refused = reloadRooms(candidate)
            val roomsFrom = if (refused == null) candidate else current
            config = current.withRuntimeOf(candidate, rooms = roomsFrom.rooms, commonsRateTotal = roomsFrom.commonsRateTotal)
            if (candidate.rateCastsTotal != current.rateCastsTotal) castTotalBucket = liveBucketFor(candidate.rateCastsTotal)
            if (candidate.rateSigsTotal != current.rateSigsTotal) sigTotalBucket = liveBucketFor(candidate.rateSigsTotal)

            fun anyNamed(of: Config) = of.rooms.any { it.name != null }
            if ((candidate.token == null) != (current.token == null) || anyNamed(config) != anyNamed(current)) logWithheldNames(config)
            ignored + listOfNotNull(refused)
        }

    /**
     * Installs [candidate]'s rooms and their total budget as one unit, or none of it, and returns why
     * not (S-7.4-21, ADR 2026-10.hs75).
     *
     * Every store write and the swap happen in one hop to the store thread, so no `sub`, sweep or shed
     * sees the rooms half-installed. A room the store does not hold is created first, since only a
     * creation can be refused (`SPOOL_MAX_SCOPES`, with nothing bare to reclaim). A refusal stops there
     * and keeps the running unit. A scope it already created stays as an ordinary bare scope, which
     * the reclaim gives back, rather than earning a delete path of its own (ADR 2026-10.ywkw). A room
     * whose bounds moved is re-applied only after every creation, because re-applying a lower `ttlMs`
     * deletes frames at once.
     *
     * Then, under [roomLock], the members of each room the new table no longer runs, or runs in another
     * shape, are found and closed `1001`. Nobody else is touched: a room's members are the connections
     * subscribed to its scope, since nobody else holds the id.
     */
    private fun reloadRooms(candidate: Config): String? {
        val table = roomTable
        val wanted = candidate.rooms
        val unchanged =
            table.totalRate == candidate.commonsRateTotal &&
                table.rooms.size == wanted.size &&
                table.rooms.zip(wanted).all { (room, next) -> room.config.sameAs(next) }
        if (unchanged) return null
        val now = clock()
        val next = roomTableFor(wanted, candidate.commonsRateTotal, table)
        // New to the table, or in a new shape: what the store has to be told about.
        val touched = wanted.filter { room -> table.roomOf(room.scopeId)?.config?.sameShape(room) != true }
        // Retired, or reshaped: the rooms whose members were told something the new table makes untrue.
        val changed = table.rooms.filter { next.roomOf(it.config.scopeId)?.config?.sameShape(it.config) != true }
        val outcome =
            runBlocking {
                withStore {
                    // What holds a scope once the swap is done: the new rooms and every subscriber. A
                    // retiring room is not in it, so its scope, if bare, may make way for a new one.
                    val held = { scope: ByteArray -> wanted.any { it.scopeId.contentEquals(scope) } || isSubscribed(scope) }
                    val (create, reapply) = touched.partition { store.isUnknownScope(it.scopeId) }
                    for (room in create) {
                        val result = store.subscribe(room.scopeId, room.bounds, now, held)
                        if (result !is SubscribeResult.Subscribed) {
                            return@withStore RoomReload.Refused(
                                "commons room ${room.number} cannot be created: the store already holds SPOOL_MAX_SCOPES " +
                                    "(${hardLimits.maxScopes}) scopes, none of them empty; the running rooms and " +
                                    "SPOOL_COMMONS_RATE_TOTAL stay as they were",
                            )
                        }
                        noteReclaimed(result.reclaimed)
                    }
                    reapply.forEach { store.subscribe(it.scopeId, it.bounds, now, held) }
                    synchronized(roomLock) {
                        roomTable = next
                        RoomReload.Installed(
                            changed.associateWith { room ->
                                subscribers[room.hex]?.filter { toldOtherwise(it, room.config.scopeId, next) }.orEmpty()
                            },
                        )
                    }
                }
            }
        return when (outcome) {
            is RoomReload.Refused -> {
                outcome.why
            }

            is RoomReload.Installed -> {
                touched.forEach(::logRoom)
                for ((room, closing) in outcome.closing) {
                    val what =
                        when {
                            next.roomOf(room.config.scopeId) != null -> "changed its bounds or attachments"
                            wanted.any { it.number == room.config.number } -> "rotated to a new id"
                            else -> "removed"
                        }
                    log.info(
                        "commons room {} {}: closing {} member connection(s) so they read the rooms again",
                        room.config.number,
                        what,
                        closing.size,
                    )
                    closing.forEach(::closeForNewHello)
                }
                null
            }
        }
    }

    /** The reloadable half as it runs now: what a reload's log line reports, which a refused room set makes differ from what was asked. */
    internal val reloadable: Map<String, Any?> get() = config.reloadable()

    /** What [reloadRooms]' store hop did: the swap and whom it closes, or the creation that was refused. */
    private sealed interface RoomReload {
        class Installed(
            val closing: Map<Room, List<Conn>>,
        ) : RoomReload

        class Refused(
            val why: String,
        ) : RoomReload
    }

    /**
     * A table of [rooms] under a total budget of [totalRate]. A room keeps [previous]'s bucket while its
     * id and rate are unchanged, and the total bucket is kept while [totalRate] is, so a reload that
     * changes something else gives back no budget a member has already spent.
     */
    private fun roomTableFor(
        rooms: List<CommonsConfig>,
        totalRate: Int,
        previous: RoomTable?,
    ): RoomTable =
        RoomTable(
            rooms.map { room ->
                val kept = previous?.roomOf(room.scopeId)?.takeIf { it.config.ratePushes == room.ratePushes }
                val bucket = kept?.bucket ?: TokenBucket(room.ratePushes.toDouble(), COMMONS_BURST_FACTOR * room.ratePushes, clock)
                Room(room, hex(room.scopeId), bucket)
            },
            totalBucket =
                previous?.takeIf { it.totalRate == totalRate }?.totalBucket
                    ?: TokenBucket(totalRate.toDouble(), COMMONS_BURST_FACTOR * totalRate, clock),
            totalRate = totalRate,
        )

    /**
     * Whether [conn]'s `hello` described the room [scope] in a shape [table] no longer runs: gone, or
     * with other bounds or `attach` (S-7.4-21). False for a scope its `hello` did not list as a room.
     * Called under [roomLock], by a reload's scan and by a `sub` that joins such a room after it.
     */
    private fun toldOtherwise(
        conn: Conn,
        scope: ByteArray,
        table: RoomTable = roomTable,
    ): Boolean {
        val told = conn.rooms.roomOf(scope)?.config ?: return false
        return table.roomOf(scope)?.config?.sameShape(told) != true
    }

    /**
     * Closes [conn] `1001` so it reconnects and reads the rooms from a new `hello` (S-7.4-21). That is
     * the close a spool going away sends, which every client already answers by reconnecting under its
     * own backoff. Launched, because a reload runs on the signal thread. Abandoned after
     * [GOING_AWAY_CLOSE_MS] as [stop] abandons one: `close` waits behind a full outgoing queue, and a
     * member left open would stay in a room the spool no longer runs.
     */
    private fun closeForNewHello(conn: Conn) {
        appScope?.launch {
            withTimeoutOrNull(GOING_AWAY_CLOSE_MS) {
                conn.session.close(CloseReason(CloseReason.Codes.GOING_AWAY.code, "rooms changed"))
            } ?: conn.session.cancel()
        }
    }

    /**
     * Says whether `hello` is carrying the rooms' names, when any room has one. A spool with no token
     * leaves them out (S-7.4-19): anyone who connects reads its `hello`, so a room's name would be
     * public. Logged at boot and again when a reload adds or removes the token or the rooms' names,
     * since that is what moves it; a connection keeps the `hello` it was sent.
     */
    private fun logWithheldNames(config: Config) {
        if (config.rooms.none { it.name != null }) return
        if (config.token == null) {
            log.warn("commons names withheld from hello: no SPOOL_TOKEN, so anyone who connects would read them (spec S-7.4-19)")
        } else {
            log.info("commons names advertised in hello to token holders")
        }
    }

    /** Graceful shutdown: close every session 1001, stop the engine, close the store. */
    fun stop() {
        val server = engine ?: return
        activeSessions.forEach { session ->
            runCatching {
                runBlocking {
                    withTimeout(GOING_AWAY_CLOSE_MS) {
                        session.close(CloseReason(CloseReason.Codes.GOING_AWAY.code, "going away"))
                    }
                }
            }
        }
        server.stop(gracePeriodMillis = 2_000, timeoutMillis = 5_000)
        runCatching { store.close() }.onFailure { log.warn("store close failed", it) }
    }

    /**
     * Whether the spool is holding all the connections it was configured for.
     *
     * Reads the live gauge rather than reserving a slot, so upgrades already in flight can carry
     * the count a little past [Config.maxConns] — bounded by how many arrive between this check
     * and their increment, and never leaking a slot the way a reservation released on the wrong
     * path would. The cap is a budget for a box sized with headroom above it, not a hard fence.
     */
    private fun atCapacity(): Boolean = config.maxConns > 0 && metrics.connectionsCurrent.get() >= config.maxConns

    /**
     * Whether to turn a new connection away — draining or full.
     *
     * Both answer at the transport with `503` and a `Retry-After`, which a multi-homing client
     * already treats as one more unreachable spool. They are counted apart because a planned drain
     * and a box out of room look identical otherwise, and only one of them is a reason to buy
     * hardware.
     */
    private fun refuseUpgrade(): Boolean = draining || atCapacity()

    /**
     * Starts or stops draining. Live connections are untouched — this only closes the door.
     *
     * Deliberately does not touch `/healthz`: the container HEALTHCHECK, both CI pipelines and the
     * compose `service_healthy` gate all probe it, so a drain that failed it would restart the
     * container mid-drain and take the proxy's dependency with it. Readiness is a separate signal
     * and not one this daemon offers yet.
     */
    internal fun setDraining(on: Boolean) {
        if (draining == on) return
        draining = on
        if (on) {
            log.info("draining: refusing new connections; {} live", metrics.connectionsCurrent.get())
        } else {
            log.info("drain lifted: accepting connections again")
        }
    }

    /** Flips [setDraining]; what the SIGUSR1 handler calls, so one signal both starts and stops. */
    fun toggleDrain() = setDraining(!draining)

    /** The room [scope] is in the running table, or null for any other scope. */
    private fun roomOf(scope: ByteArray): Room? = roomTable.roomOf(scope)

    /**
     * Scopes the store must never reclaim however bare (ADR 2026-10.d2zb): every commons room, and
     * every scope [isSubscribed] covers.
     */
    private fun isHeld(scope: ByteArray): Boolean = roomOf(scope) != null || isSubscribed(scope)

    /**
     * Whether a live connection subscribes to [scope], or a `sub` is in the middle of subscribing to it
     * ([subscribing]), so a newcomer at the cap cannot evict a scope between its store call and its
     * subscriber entry. Read on the store thread; both maps are concurrent.
     */
    private fun isSubscribed(scope: ByteArray): Boolean {
        val key = hex(scope)
        return subscribers[key]?.isNotEmpty() == true || subscribing.containsKey(key)
    }

    private fun noteReclaimed(scope: ByteArray?) {
        if (scope == null) return
        metrics.reclaimsTotal.increment()
        log.debug("maxScopes: reclaimed bare scope {} for a new one", shortHex(scope))
    }

    /**
     * Creates every commons room's scope before the first client can connect.
     *
     * Everything else about a room follows from its already existing: `isUnknownScope` is false
     * forever, so neither the PoW gate nor the per-IP new-scope bucket ever fires for it and members
     * join with a plain `sub` even on a spool mining at 20 bits (§6.4, S-7.4-3).
     */
    private fun createRooms() {
        for (room in roomTable.rooms.map { it.config }) {
            val result = runBlocking { withStore { store.subscribe(room.scopeId, room.bounds, clock(), ::isHeld) } }
            if (result is SubscribeResult.QuotaExceeded) {
                // Reachable on a persistent store that already holds maxScopes scopes, none of them
                // bare, from before the room was configured (or under a previous id). Refuse to
                // start: a spool silently up without a room it advertises is the worse outcome.
                error(
                    "commons room ${room.number} cannot be created: the store already holds SPOOL_MAX_SCOPES " +
                        "(${config.hardLimits.maxScopes}) scopes, none of them empty. Raise SPOOL_MAX_SCOPES, or clear SPOOL_DATA_DIR.",
                )
            }
            logRoom(room)
        }
    }

    /** A room's bounds, at boot and when a reload creates or reshapes it; by number, never by id or name. */
    private fun logRoom(room: CommonsConfig) {
        // The first room's line reads as it did before rooms; the others say which they are.
        log.info(
            "commons enabled{}: {} frames, ttl {} ms, attachments {}",
            if (room.number == 1) "" else " (room ${room.number})",
            room.bounds.maxFrames,
            room.bounds.ttlMs,
            if (room.attach) "on" else "off",
        )
    }

    private suspend fun acceptConnection(session: DefaultWebSocketServerSession) {
        val ip = clientKey(session.call.request.origin.remoteAddress)
        val now = clock()
        // At the cap every idle entry goes, not just the ten-minute-old ones the sweeper takes:
        // the live entries number at most the connections, so this always makes room, and what
        // it forgets is a drained new-scope bucket a client that already left would have met on
        // its return. Amortised: the next `cap - live` new addresses cost no scan at all.
        if (ips.size >= clientTableCap && !ips.containsKey(ip)) pruneIdleClients(seenBefore = Long.MAX_VALUE)
        var connections = 0
        // Counted in under the entry's own map lock, so a prune that saw the entry idle a moment
        // ago cannot drop it now — it re-checks under the same lock — and this connection never
        // runs on an entry the table no longer holds.
        val ipState =
            checkNotNull(
                ips.compute(ip) { _, existing ->
                    (existing ?: IpState(config.rateNewScopesPerMin, clock, config.turn?.ratePer10Min)).also {
                        connections = it.connections.incrementAndGet()
                        it.lastSeen = now
                    }
                },
            )
        if (connections > config.maxConnsPerIp) {
            ipState.connections.decrementAndGet()
            session.close(CloseReason(CloseCode.ABUSE.toShort(), "too many connections"))
            return
        }
        try {
            if (!accepts(connectGate, session.call.request.queryParameters["k"])) {
                session.close(CloseReason(CloseCode.AUTH.toShort(), "auth"))
                return
            }
            metrics.connectionsTotal.increment()
            metrics.connectionsCurrent.incrementAndGet()
            try {
                serveConnection(session, ipState)
            } finally {
                metrics.connectionsCurrent.decrementAndGet()
            }
        } finally {
            ipState.connections.decrementAndGet()
            ipState.lastSeen = clock()
        }
    }

    // One `when` arm per record type: a switch table, and splitting it would hide that shape.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun serveConnection(
        session: DefaultWebSocketServerSession,
        ipState: IpState,
    ) {
        // One read: the rooms this hello describes are the rooms the connection remembers it was told.
        val rooms = roomTable
        val hello = serverHello(rooms)
        val conn = Conn(session, ipState, hello, rooms)
        activeSessions.add(session)
        try {
            conn.out.send(binary(RecordCodec.encode(hello)))
            var helloed = false
            for (frame in session.incoming) {
                // Only data frames reach `incoming` — Ktor answers pings and consumes pongs and the
                // close upstream — and a record is a *binary* message (B-7.1-4). A text frame is
                // not skipped: it is malformed traffic that costs what any malformed record costs,
                // close 4000 before hello and a record token then `err malformed` after. Skipping
                // it meant a client could send frames of `maxRecord` at line rate, each one
                // reassembled and dropped, without the record bucket ever seeing them.
                val bytes = frame.readBytes()
                val binary = frame is Frame.Binary
                metrics.recordsTotal.increment()
                if (!helloed) {
                    if (!acceptClientHello(session, bytes, binary)) return
                    helloed = true
                    continue
                }
                val retryMs = conn.recordBucket.take()
                if (retryMs > 0) {
                    if (rateLimited(conn, q = null, scope = null, retryMs = retryMs)) return
                    continue
                }
                if (!binary) {
                    sendErr(conn, ErrCode.MALFORMED)
                    continue
                }
                if (bytes.size > config.maxRecord) {
                    sendErr(conn, ErrCode.TOO_LARGE)
                    continue
                }
                when (RecordCodec.peekType(bytes)) {
                    // A second hello is malformed traffic, but 4000 is pre-hello only (§7.1):
                    // answer in-band and keep the connection.
                    RecordType.HELLO -> {
                        sendErr(conn, ErrCode.MALFORMED)
                    }

                    RecordType.SUB -> {
                        val sub = RecordCodec.decode<Sub>(bytes)
                        if (sub == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, sub.q) { handleSub(conn, sub) }
                    }

                    RecordType.LIST -> {
                        val list = RecordCodec.decode<ScopeList>(bytes)
                        if (list == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, list.q) { handleList(conn, list) }
                    }

                    RecordType.PULL -> {
                        val pull = RecordCodec.decode<Pull>(bytes)
                        if (pull == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, pull.q) { handlePull(conn, pull) }
                    }

                    RecordType.PUSH -> {
                        val push = RecordCodec.decode<Push>(bytes)
                        if (push == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, push.q) { handlePush(conn, push) }
                    }

                    RecordType.AHAVE -> {
                        val ahave = RecordCodec.decode<Ahave>(bytes)
                        if (ahave == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, ahave.q) { handleAhave(conn, ahave) }
                    }

                    RecordType.AGET -> {
                        val aget = RecordCodec.decode<Aget>(bytes)
                        if (aget == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, aget.q) { handleAget(conn, aget) }
                    }

                    RecordType.APUT -> {
                        val aput = RecordCodec.decode<Aput>(bytes)
                        if (aput == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, aput.q) { handleAput(conn, aput) }
                    }

                    RecordType.TURNREQ -> {
                        val req = RecordCodec.decode<TurnReq>(bytes)
                        if (req == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, req.q) { handleTurnReq(conn, req) }
                    }

                    RecordType.ENT -> {
                        val ent = RecordCodec.decode<Ent>(bytes)
                        if (ent == null) sendErr(conn, ErrCode.MALFORMED) else guarded(conn, ent.q) { handleEnt(conn, ent) }
                    }

                    // Never answered, not even malformed: see handleCast.
                    RecordType.CAST -> {
                        handleCast(conn, bytes)
                    }

                    // Never answered either (S-7.8-2): see handleSig.
                    RecordType.SIG -> {
                        handleSig(conn, bytes)
                    }

                    null -> {
                        sendErr(conn, ErrCode.MALFORMED)
                    }

                    else -> {
                        // unknown t: additive evolution — skip
                    }
                }
            }
        } catch (e: IOException) {
            // A peer that stopped answering pings — sleep, network drop, killed process. Expected
            // churn, and the finally below still unwinds the connection, so don't let it reach
            // Ktor's handler and land as ERROR-with-stack-trace. Anything else still propagates.
            // Routine enough to stay off the default log: one line per sleeping client buries the
            // lines that mean something, and the status line's `conns` gauge already shows churn.
            if (e.message != PING_TIMEOUT_MESSAGE) throw e
            log.debug("connection dropped: ping timeout")
        } finally {
            activeSessions.remove(session)
            conn.subscriptions.keys.forEach { scopeHex ->
                subscribers.computeIfPresent(scopeHex) { _, set ->
                    set.remove(conn)
                    set.ifEmpty { null }
                }
            }
        }
    }

    /**
     * The client's half of the hello exchange (spec §7.1): its first record must be a binary
     * `hello` with a version this spool speaks, or the connection is closed — 4000 or 4002 — and
     * false says so. Out of [serveConnection]'s loop only to keep that dispatch table one screen.
     */
    private suspend fun acceptClientHello(
        session: DefaultWebSocketServerSession,
        bytes: ByteArray,
        binary: Boolean,
    ): Boolean {
        val hello = if (binary) RecordCodec.decode<Hello>(bytes) else null
        if (hello == null || RecordCodec.peekType(bytes) != RecordType.HELLO) {
            session.close(CloseReason(CloseCode.MALFORMED.toShort(), "hello first"))
            return false
        }
        if (hello.v !in 1..RECORD_VERSION) {
            session.close(CloseReason(CloseCode.VERSION.toShort(), "no version overlap"))
            return false
        }
        return true
    }

    /** Wraps a record handler so an unexpected failure surfaces as `err internal`, not a dead task. */
    @Suppress("TooGenericExceptionCaught") // the per-record boundary: that is the point of it
    private suspend fun guarded(
        conn: Conn,
        q: Long?,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logInternal(e)
            sendErr(conn, ErrCode.INTERNAL, q = q)
        }
    }

    /**
     * Writes [guarded]'s stack trace at most once per [INTERNAL_TRACE_MS]; failures in between are
     * counted onto the next trace's line rather than logged. A handler failure a client can
     * provoke arrives at the record rate — 50 a second per connection — and the plain compose file
     * rotates nothing, so one trace per failure is a disk-filling primitive handed to whoever finds
     * the next such bug. The sample loses nothing an operator acts on: every failure still answers
     * `err internal`, and every one is counted in `knit_spool_errs_total{code="internal"}`.
     */
    private fun logInternal(e: Exception) {
        val now = clock()
        val suppressed =
            synchronized(internalTraceLock) {
                if (now < internalTraceDueAt) {
                    internalSuppressed++
                    return
                }
                internalTraceDueAt = now + INTERNAL_TRACE_MS
                internalSuppressed.also { internalSuppressed = 0 }
            }
        if (suppressed == 0L) {
            log.error("record handling failed", e)
        } else {
            log.error("record handling failed ({} more since the last trace)", suppressed, e)
        }
    }

    /**
     * A `sub` costs one record token at the dispatch loop and one more for every scope past the
     * first. Each entry is a store transaction — the unknown-scope check, then the row read, sweep
     * and write inside [ScopeStore.subscribe] — plus a `digest`, all on the one store thread every
     * connection shares, so the record's cost is its entry count: 2,000 entries in one record at the
     * default `maxRecord` measured 747 ms of store time for a single token. The list is bounded
     * before anything is charged. More entries than `maxScopes` — the spool's advertised total, which
     * a conforming client batches under — or the same scope twice, which leaves S-6.2-2 no "most
     * recent declaration" to apply, is a malformed record, refused whole ahead of any token or hop
     * exactly as an off-length id is; the cap is also what bounds the un-tokened `err rate` tail
     * below to `maxScopes − 1` replies per record.
     *
     * Once the bucket is dry the rest of the record is refused `rate` per scope, with `retryMs`, and
     * the entries before it stand. A client drops a scope from its table on a scoped `err` and
     * re-subs it on its next round (C-9.1), so a partial answer converges where a whole-record
     * `rate` would loop for ever on a spool whose burst is below the client's batch. One record
     * strikes the abuse window at most once, whichever bucket refused it: a strike is evidence of a
     * client ignoring backpressure, and one that has sent a single record has had none to ignore
     * yet — [commonsBudget]'s reasoning. A conforming client is untouched: its batch is at most
     * `maxScopes` entries, under the burst of 4 × `rateRecords` at every default.
     *
     * One level deeper than the other handlers by nature — the batch, the entry, its rate limit,
     * and the rest of the batch that limit applies to — hence the suppression.
     */
    @Suppress("NestedBlockDepth")
    private suspend fun handleSub(
        conn: Conn,
        sub: Sub,
    ) {
        if (!requireIds(conn, sub.q, scope = null, ids = sub.subs.map { it.scope })) return
        if (sub.subs.size > config.hardLimits.maxScopes) {
            sendErr(conn, ErrCode.MALFORMED, q = sub.q)
            return
        }
        val hexes = sub.subs.map { hex(it.scope) }
        if (hexes.toHashSet().size != hexes.size) {
            sendErr(conn, ErrCode.MALFORMED, q = sub.q)
            return
        }
        val now = clock()
        var struck = false
        for ((index, scopeSub) in sub.subs.withIndex()) {
            val scopeHex = hexes[index]
            if (index > 0) {
                val retryMs = conn.recordBucket.take()
                if (retryMs > 0) {
                    // This entry and every one after it, at the same retryMs; one strike at most.
                    for (rest in sub.subs.subList(index, sub.subs.size)) {
                        if (rateLimited(conn, q = sub.q, scope = rest.scope, retryMs = retryMs, strike = !struck)) return
                        struck = true
                    }
                    return
                }
            }
            // The store, not this connection's table, says whether the sub creates the scope: one the
            // connection already holds may have been shed since (S-6.2-8), and recreating it meets
            // the creation gates exactly as the push and aput recreate paths apply them (S-6.2-9).
            // A refresh of a scope the store still holds costs the one extra hop, and no gate.
            if (withStore { store.isUnknownScope(scopeSub.scope) }) {
                val gate = newScopeGates(conn, scopeSub.scope, scopeSub.pow?.d, scopeSub.pow?.n, sub.q, now, strike = !struck)
                if (gate == Gate.CLOSED) return
                if (gate == Gate.RATE_REFUSED) struck = true
                if (gate != Gate.PASSED) continue
            }
            // A commons room declares its own bounds (S-7.4-2). Substituted rather than refused — the
            // `digest` reply already carries the applied bounds, so the client learns the truth in
            // the answer it was going to read anyway. Same shape as a truncated `pull` (§7.2).
            subscribing.merge(scopeHex, 1, Int::plus)
            try {
                // The room is looked up on the store thread, beside the write: a reload re-applies a
                // reshaped room's bounds there, so a `sub` that read the table before it cannot write the
                // old bounds back after it.
                val (declared, result) =
                    withStore {
                        val applied = roomOf(scopeSub.scope)?.config?.bounds ?: scopeSub.bounds
                        applied to store.subscribe(scopeSub.scope, applied, now, ::isHeld)
                    }
                when (result) {
                    is SubscribeResult.Subscribed -> {
                        noteReclaimed(result.reclaimed)
                        // The *applied* bounds, so the push-recreate path below re-subscribes with them.
                        conn.subscriptions[scopeHex] = declared
                        subscribers.computeIfAbsent(scopeHex) { ConcurrentHashMap.newKeySet() }.add(conn)
                        // A room this connection's hello described that a reload has since retired or
                        // reshaped: after the subscriber entry, so the reload's own scan or this check
                        // sees the other (S-7.4-21). The rest of the batch would be answered to a
                        // connection that is closing, so it is dropped.
                        if (conn.rooms.roomOf(scopeSub.scope) != null && synchronized(roomLock) { toldOtherwise(conn, scopeSub.scope) }) {
                            closeForNewHello(conn)
                            return
                        }
                        conn.out.send(binary(RecordCodec.encode(digestRecord(scopeSub.scope, result.digest))))
                    }

                    SubscribeResult.QuotaExceeded -> {
                        metrics.newScopesQuotaRefusedTotal.increment()
                        sendErr(conn, ErrCode.QUOTA, q = sub.q, scope = scopeSub.scope)
                    }
                }
            } finally {
                subscribing.computeIfPresent(scopeHex) { _, n -> (n - 1).takeIf { it > 0 } }
            }
        }
    }

    private suspend fun handleList(
        conn: Conn,
        list: ScopeList,
    ) {
        if (!requireIds(conn, list.q, list.scope)) return
        if (!requireSub(conn, list.scope, list.q)) return
        val info = withStore { store.list(list.scope, clock()) }
        // A subscribed-but-shed scope answers empty (nullable fields omitted), not an error: the
        // client's next push recreates it.
        out(
            conn,
            ScopeList(
                t = RecordType.LIST,
                q = list.q,
                scope = list.scope,
                blobIds = info?.blobIds?.ifEmpty { null },
                tombstones = info?.tombstones?.ifEmpty { null },
            ),
        )
    }

    private suspend fun handlePull(
        conn: Conn,
        pull: Pull,
    ) {
        if (!requireIds(conn, pull.q, pull.scope, pull.blobIds)) return
        if (!requireSub(conn, pull.scope, pull.q)) return
        val wanted = pull.blobIds.take(config.maxPull)
        // One sweep, then one blob fetched-and-sent at a time: `out` suspends when the client stops
        // reading, and while it is suspended this holds a single blob, not the whole result (up to
        // maxPull × maxBlob). A blob that raced away between the sweep and its fetch drops to
        // missing, the same answer the client would get had it never been there. F6 of the review.
        val live = withStore { store.pull(pull.scope, wanted, clock()) }
        val served = HashSet<String>(live.size)
        for (blobId in live) {
            val data = withStore { store.blob(pull.scope, blobId, clock()) } ?: continue
            served.add(hex(blobId))
            out(conn, Blob(t = RecordType.BLOB, scope = pull.scope, blobId = blobId, data = data))
        }
        // Missing is every wanted id not actually sent: not live at the sweep, or raced away before
        // its fetch. `served` is exactly what left, so it answers both.
        val missing = wanted.filter { hex(it) !in served }
        out(conn, Ok(t = RecordType.OK, q = pull.q, missing = missing.ifEmpty { null }))
    }

    private suspend fun handleAhave(
        conn: Conn,
        ahave: Ahave,
    ) {
        if (!requireIds(conn, ahave.q, ahave.scope, listOf(ahave.aid))) return
        if (!requireAttachments(conn, ahave.q, ahave.scope)) return
        if (!requireSub(conn, ahave.scope, ahave.q)) return
        val info = withStore { store.attachmentPresence(ahave.scope, ahave.aid, clock()) }
        out(
            conn,
            Ahas(
                t = RecordType.AHAS,
                q = ahave.q,
                scope = ahave.scope,
                aid = ahave.aid,
                total = info.total,
                bits = info.bits,
                dead = info.dead,
            ),
        )
    }

    /**
     * Issues one TURN credential (experimental, SPOOL_PROTOCOL.md §7.6).
     *
     * The request names no scope, and nothing here asks which: holding *some* subscription is the
     * whole gate, so the spool never learns which conversation is about to call. A refusal for rate
     * is not a strike — asking for a credential is not abuse of the scope plane, and a client that
     * redials a dropped call should not be closed 4003 for it. Neither half of the credential is
     * ever logged.
     *
     * On a connection that was told about tickets (S-7.6-16), a live ticket granting TURN replaces
     * the subscription gate, and the username's suffix comes from its pseudonym rather than from
     * fresh randomness (S-7.6-17): every credential one holder fetches then shares a suffix, which
     * is what makes coturn's `user-quota` count the holder instead of the credential.
     *
     * On a spool with several servers, `host` picks the one signed for (S-7.6-49/50), after every
     * gate and budget: a request costs the same whichever server it names, and an unknown one gets
     * server 1 rather than a refusal, because a call is waiting.
     */
    private suspend fun handleTurnReq(
        conn: Conn,
        req: TurnReq,
    ) {
        val turn = config.turn
        // A spool without turn omits it from hello and a conforming client never asks (C-7.6-3),
        // but the request's `q` should still get an answer rather than hang until timeout.
        if (turn == null) {
            sendErr(conn, ErrCode.MALFORMED, q = req.q)
            return
        }
        // Ungated, a live TURN ticket still earns the pseudonym suffix and waives the subscription gate,
        // as it does gated: the gate decides only whether a connection without one is refused.
        val ticket =
            if (conn.gates and Tickets.FL_TURN != 0L) {
                liveTurnTicket(conn, req.q) ?: return
            } else {
                conn.ticket?.takeIf { ticketShortfall(it, Tickets.FL_TURN) == null }
            }
        if (ticket == null && conn.subscriptions.isEmpty()) {
            sendErr(conn, ErrCode.NOT_SUBSCRIBED, q = req.q)
            return
        }
        for (bucket in listOfNotNull(conn.turnBucket, conn.ipState.turnBucket)) {
            val retryMs = bucket.take()
            if (retryMs > 0) {
                rateLimited(conn, q = req.q, scope = null, retryMs = retryMs, strike = false)
                return
            }
        }
        // Last, so a request the connection's own buckets refuse never spends the holder's share.
        // Read live, like the attach budget: a reload that moves the rate reaches the next issue.
        val holderRate = config.ticket?.turnRatePer10Min ?: 0
        if (ticket != null && holderRate > 0) {
            val retryMs = turnBudgets.take(ticket, holderRate, clock() / MS_PER_S)
            if (retryMs > 0) {
                rateLimited(conn, q = req.q, scope = null, retryMs = retryMs, strike = false)
                return
            }
        }
        // Chosen last: the gates and budgets above are the same whichever server is asked for.
        val server = turn.select(req.host)
        val expS = clock() / MS_PER_S + turn.ttlS
        val suffix =
            ticket?.let { TurnCredentials.pseudonymSuffix(it.ps) } ?: ByteArray(TurnCredentials.SUFFIX_BYTES).also(turnRandom::nextBytes)
        val user = TurnCredentials.user(expS, suffix)
        metrics.turnIssuedTotal.increment(server.number)
        if (ticket != null) metrics.turnIssuedTicketedTotal.increment(server.number)
        out(
            conn,
            TurnCred(
                t = RecordType.TURNCRED,
                q = req.q,
                urls = server.urls,
                user = user,
                pass = TurnCredentials.pass(server.secret, user),
                exp = expS,
            ),
        )
    }

    /**
     * Checks an entitlement ticket and binds it to the connection (experimental, SPOOL_PROTOCOL.md
     * §7.6, S-7.6-14/15). The verifying keys and the audiences are read live, so a reload that
     * rotates a key or renames the spool judges the next `ent`. A refusal is never a strike — an
     * expired ticket is ordinary life for a phone that slept through its refresh — and leaves any
     * earlier accepted ticket in place, so a failed renewal cannot take away what the connection
     * already holds. Neither the ticket nor its pseudonym is ever logged (S-7.6-18).
     */
    private suspend fun handleEnt(
        conn: Conn,
        ent: Ent,
    ) {
        // A connection that was never told about tickets has no business sending one: the same
        // in-band `malformed` an unconfigured spool gives `turnreq`.
        if (!conn.entAdvertised) {
            sendErr(conn, ErrCode.MALFORMED, q = ent.q)
            return
        }
        val tickets = config.ticket
        val outcome =
            if (tickets == null) {
                // Advertised at hello, then the key was reloaded away: nothing left to check against.
                Tickets.Outcome.Refused(Tickets.Reason.KEY)
            } else {
                Tickets.check(ent.tk, ent.sig, tickets.verifiers, tickets.audiences, clock() / MS_PER_S)
            }
        when (outcome) {
            is Tickets.Outcome.Accepted -> {
                conn.ticket = outcome.ticket
                metrics.ticketAcceptedTotal.increment()
                out(conn, Ok(t = RecordType.OK, q = ent.q))
            }

            is Tickets.Outcome.Refused -> {
                refuseTicket(conn, ent.q, outcome.reason)
            }
        }
    }

    /** The connection's ticket if it is live and grants TURN; otherwise refuses [q] and returns null. */
    private suspend fun liveTurnTicket(
        conn: Conn,
        q: Long,
    ): Ticket? {
        val ticket = conn.ticket
        val reason = ticketShortfall(ticket, Tickets.FL_TURN) ?: return ticket
        refuseTicket(conn, q, reason)
        return null
    }

    /**
     * Why [ticket] does not grant [flag] now, or null when it does: held, inside its window plus
     * [Tickets.SKEW_S], and carrying the bit.
     */
    private fun ticketShortfall(
        ticket: Ticket?,
        flag: Long,
    ): Tickets.Reason? =
        when {
            ticket == null -> Tickets.Reason.MISSING
            clock() / MS_PER_S - Tickets.SKEW_S > ticket.exp -> Tickets.Reason.EXPIRED
            ticket.fl and flag == 0L -> Tickets.Reason.FLAGS
            else -> null
        }

    private suspend fun refuseTicket(
        conn: Conn,
        q: Long,
        reason: Tickets.Reason,
        scope: ByteArray? = null,
    ) {
        metrics.ticketRefused(reason)
        sendErr(conn, ErrCode.TICKET, q = q, scope = scope, msg = reason.word)
    }

    /**
     * Whether this `aput` may proceed where `files` is gated, and whose budget pays (S-7.6-22, ADR
     * 2026-10.h6pt): the uploading connection's own live files ticket, an attachment no larger than its
     * `am` allows (S-7.6-31, ADR 2026-10.h4ps), and room left for this chunk under
     * `min(ab, SPOOL_TICKET_ATTACH_BYTES)`. Another subscriber's ticket never admits an upload, unlike
     * casts' `castUnlocked`: that is the sponsorship the ADR rejected. Refuses with a scoped
     * `err ticket`, never a strike, and returns false; on true, [payer] is what [handleAput] charges.
     *
     * The size is checked on every chunk, a re-sent one included: chunks of one `aid` may arrive under
     * different tickets, and each uploader is held to its own.
     */
    private suspend fun filesAdmitted(
        conn: Conn,
        aput: Aput,
        payer: Ticket?,
    ): Boolean {
        if (conn.gates and Tickets.FL_FILES == 0L) return true
        // Read live, so a lowered cap bites on the next chunk. A key reloaded away leaves the gate on
        // for connections told about it (their snapshot), metered at the default.
        val cap = config.ticket?.attachBytes ?: TicketConfig.DEFAULT_ATTACH_BYTES
        val shortfall = ticketShortfall(payer, Tickets.FL_FILES)
        // Bytes of `A` rounded down to whole chunks, so a cap never admits more than it names.
        val maxTotal = payer?.am?.let { it / A_CHUNK_BYTES }
        val reason =
            when {
                shortfall != null -> shortfall
                maxTotal != null && aput.total > maxTotal -> Tickets.Reason.SIZE
                payer != null && uploadBudgets.admits(payer, ScopeStore.chunkCharge(aput.data.size), cap) -> return true
                else -> Tickets.Reason.BUDGET
            }
        refuseTicket(conn, aput.q, reason, scope = aput.scope)
        return false
    }

    private fun chargeUpload(
        payer: Ticket,
        bytes: Long,
        nowMs: Long,
    ) {
        uploadBudgets.charge(payer, bytes, nowMs / MS_PER_S)
        metrics.ticketAttachBytesChargedTotal.add(bytes)
        if (payer.am != null) metrics.ticketAttachBytesChargedAmTotal.add(bytes)
    }

    private suspend fun handleAget(
        conn: Conn,
        aget: Aget,
    ) {
        if (!requireIds(conn, aget.q, aget.scope, listOf(aget.aid))) return
        if (!requireAttachments(conn, aget.q, aget.scope)) return
        if (!requireSub(conn, aget.scope, aget.q)) return
        // Truncated, never an error — the `pull` rule of §7.2, reapplied.
        // One sweep for the range's headers, then each chunk's bytes fetched-and-sent one at a time:
        // while `out` is suspended on a stalled reader this holds one chunk, not the whole range (up
        // to maxAget × maxAChunk). A chunk that raced away before its fetch is simply omitted, which
        // is exactly what an absent index looks like to the client. F6 of the review.
        val chunks = withStore { store.attachmentGet(aget.scope, aget.aid, aget.from, minOf(aget.n, config.maxAget), clock()) }
        for (chunk in chunks) {
            val data = withStore { store.attachmentChunk(aget.scope, aget.aid, chunk.idx, clock()) } ?: continue
            out(
                conn,
                Achunk(
                    t = RecordType.ACHUNK,
                    scope = aget.scope,
                    aid = aget.aid,
                    idx = chunk.idx,
                    total = chunk.total,
                    cid = chunk.cid,
                    data = data,
                ),
            )
        }
        // A bare ok: indices we lack simply do not arrive, and the client already knows which from
        // the bitmap, so there is nothing to enumerate back (§7.3).
        out(conn, Ok(t = RecordType.OK, q = aget.q))
    }

    private suspend fun handleAput(
        conn: Conn,
        aput: Aput,
    ) {
        if (!requireIds(conn, aput.q, aput.scope, listOf(aput.aid, aput.cid))) return
        if (!requireAttachments(conn, aput.q, aput.scope)) return
        if (!requireSub(conn, aput.scope, aput.q)) return
        // Read once: the chunk is charged to the ticket that admitted it, even if a newer `ent` lands.
        val payer = conn.ticket.takeIf { conn.gates and Tickets.FL_FILES != 0L }
        // Before every bucket, so a refused upload spends no push token, no new-scope token and no PoW.
        if (!filesAdmitted(conn, aput, payer)) return
        val retryMs = conn.pushBucket.take()
        if (retryMs > 0) {
            rateLimited(conn, q = aput.q, scope = aput.scope, retryMs = retryMs)
            return
        }
        if (!commonsBudget(conn, aput.scope, aput.q)) return
        val now = clock()
        // An aput can recreate a shed scope exactly as a push can (§6.2/§6.4), so it passes the same
        // creation gates and re-subscribes the connection's remembered bounds before storing.
        if (withStore { store.isUnknownScope(aput.scope) }) {
            if (newScopeGates(conn, aput.scope, aput.pow?.d, aput.pow?.n, aput.q, now) != Gate.PASSED) return
            val declared = conn.subscriptions[hex(aput.scope)] ?: return
            when (val result = withStore { store.subscribe(aput.scope, declared, now, ::isHeld) }) {
                is SubscribeResult.Subscribed -> {
                    noteReclaimed(result.reclaimed)
                    conn.out.send(binary(RecordCodec.encode(digestRecord(aput.scope, result.digest))))
                }

                SubscribeResult.QuotaExceeded -> {
                    sendErr(conn, ErrCode.QUOTA, q = aput.q, scope = aput.scope)
                    return
                }
            }
        }
        when (withStore { store.attachmentPut(aput.scope, aput.aid, aput.idx, aput.total, aput.cid, aput.data, now) }) {
            is AputResult.Stored -> {
                metrics.attachChunksStoredTotal.increment()
                // Charged only once held: a duplicate or refused chunk costs its uploader nothing.
                if (payer != null) chargeUpload(payer, ScopeStore.chunkCharge(aput.data.size), now)
                out(conn, Ok(t = RecordType.OK, q = aput.q))
                maybeShed()
            }

            AputResult.Duplicate -> {
                out(conn, Ok(t = RecordType.OK, q = aput.q))
            }

            AputResult.Conflict -> {
                sendErr(conn, ErrCode.CONFLICT, q = aput.q, scope = aput.scope)
            }

            AputResult.Tombstoned -> {
                sendErr(conn, ErrCode.TOMBSTONED, q = aput.q, scope = aput.scope)
            }

            AputResult.TooLarge -> {
                sendErr(conn, ErrCode.TOO_LARGE, q = aput.q, scope = aput.scope)
            }

            AputResult.BadId -> {
                sendErr(conn, ErrCode.BAD_ID, q = aput.q, scope = aput.scope)
            }

            AputResult.QuotaExceeded -> {
                sendErr(conn, ErrCode.QUOTA, q = aput.q, scope = aput.scope)
            }
        }
    }

    /**
     * Refuses an attachment record on a spool with attachments switched off. Such a spool omits the
     * limits from HELLO and a conforming client never sends these — but "the client should not have"
     * is not a reason to leave its `q` hanging until timeout.
     */
    private suspend fun requireAttachments(
        conn: Conn,
        q: Long,
        scope: ByteArray,
    ): Boolean {
        // A shared room is where a 16 MiB upload costs the operator the most and is worth the
        // least, so each commons room carries its own switch and defaults off (S-7.4-5). Advertised
        // in `hello` beside the room's bounds, so a conforming client never gets here.
        val allowed = config.hardLimits.attachments && roomOf(scope).let { it == null || it.config.attach }
        if (allowed) return true
        sendErr(conn, ErrCode.MALFORMED, q = q, scope = scope)
        return false
    }

    private suspend fun handlePush(
        conn: Conn,
        push: Push,
    ) {
        if (!requireIds(conn, push.q, push.scope, listOf(push.blobId))) return
        if (!requireSub(conn, push.scope, push.q)) return
        val retryMs = conn.pushBucket.take()
        if (retryMs > 0) {
            rateLimited(conn, q = push.q, scope = push.scope, retryMs = retryMs)
            return
        }
        if (!commonsBudget(conn, push.scope, push.q)) return
        metrics.pushesTotal.increment()
        val now = clock()
        if (withStore { store.isUnknownScope(push.scope) }) {
            // The scope was shed or expired away while this connection stayed subscribed. A push
            // recreates it — exactly §6.4's "first SUB or PUSH for an unknown scope", so the
            // new-scope gates apply, then the remembered bounds re-subscribe.
            if (newScopeGates(conn, push.scope, push.pow?.d, push.pow?.n, push.q, now) != Gate.PASSED) return
            val declared = conn.subscriptions[hex(push.scope)] ?: return
            when (val result = withStore { store.subscribe(push.scope, declared, now, ::isHeld) }) {
                is SubscribeResult.Subscribed -> {
                    noteReclaimed(result.reclaimed)
                    conn.out.send(binary(RecordCodec.encode(digestRecord(push.scope, result.digest))))
                }

                SubscribeResult.QuotaExceeded -> {
                    metrics.newScopesQuotaRefusedTotal.increment()
                    sendErr(conn, ErrCode.QUOTA, q = push.q, scope = push.scope)
                    return
                }
            }
        }
        when (val result = withStore { store.push(push.scope, push.blobId, push.data, now) }) {
            is PushResult.Stored -> {
                out(conn, Ok(t = RecordType.OK, q = push.q))
                fanOut(conn, push)
                if (result.evictedOrExpired) {
                    // The live set changed beyond the pushed blob: re-anchor every subscriber,
                    // uploader included (§6.2 — the eviction-pressure signal).
                    broadcastDigest(push.scope, result.digest)
                }
                maybeShed()
            }

            // Idempotent: the blob is already held, ack it, but never re-broadcast an event.
            PushResult.Duplicate -> {
                out(conn, Ok(t = RecordType.OK, q = push.q))
            }

            PushResult.Tombstoned -> {
                sendErr(conn, ErrCode.TOMBSTONED, q = push.q, scope = push.scope)
            }

            PushResult.TooLarge -> {
                sendErr(conn, ErrCode.TOO_LARGE, q = push.q, scope = push.scope)
            }

            PushResult.BadId -> {
                sendErr(conn, ErrCode.BAD_ID, q = push.q, scope = push.scope)
            }
        }
    }

    /** What [newScopeGates] made of a record. Only [PASSED] lets the scope be created. */
    private enum class Gate { PASSED, POW_REFUSED, RATE_REFUSED, CLOSED }

    /**
     * The unknown-scope gates, cheapest first: per-IP creation rate, then PoW (spec §6.4). A bucket
     * refusal strikes the abuse window only when [strike] says so — a `sub` naming many new scopes
     * is one record and strikes once for all of them — and [Gate.CLOSED] reports that the strike
     * closed the session, so a caller in a loop stops sending into it.
     *
     * A stamp-less record is turned away with `err pow` before the bucket: clients mine only when
     * asked (C-8-4), so taking a token here would charge every new scope twice, once for the ask
     * and again for the stamped retry. The refusal creates nothing and hashes nothing, so it needs
     * no limit; a record that does carry a stamp still pays the bucket before it is verified.
     */
    private suspend fun newScopeGates(
        conn: Conn,
        scope: ByteArray,
        powDay: Long?,
        powN: Long?,
        q: Long,
        now: Long,
        strike: Boolean = true,
    ): Gate {
        if (config.powBits > 0 && (powDay == null || powN == null)) {
            sendErr(conn, ErrCode.POW, q = q, scope = scope)
            return Gate.POW_REFUSED
        }
        val retryMs = conn.ipState.newScopeBucket.take()
        if (retryMs > 0) {
            val closed = rateLimited(conn, q = q, scope = scope, retryMs = retryMs, strike = strike)
            return if (closed) Gate.CLOSED else Gate.RATE_REFUSED
        }
        if (!powGate(scope, powDay, powN, now)) {
            sendErr(conn, ErrCode.POW, q = q, scope = scope)
            return Gate.POW_REFUSED
        }
        return Gate.PASSED
    }

    /**
     * Sends `err rate` and strikes the abuse window; true means the connection was closed 4003.
     *
     * [strike] is false for the commons budgets. Those limits are congestion on a shared room, not
     * evidence this client is ignoring backpressure — striking them would close well-behaved members
     * 4003 for the crime of talking while others were talking.
     */
    private suspend fun rateLimited(
        conn: Conn,
        q: Long?,
        scope: ByteArray?,
        retryMs: Long,
        strike: Boolean = true,
    ): Boolean {
        metrics.rateLimitedTotal.increment()
        sendErr(conn, ErrCode.RATE, q = q, scope = scope, retryMs = retryMs)
        if (!strike) return false
        if (conn.strikes.strike(clock())) {
            conn.session.close(CloseReason(CloseCode.ABUSE.toShort(), "rate abuse"))
            return true
        }
        return false
    }

    /**
     * Takes from a commons room's budget and the spool-wide one (S-7.4-4). Returns false when either
     * is saturated and the caller must drop the record; ordinary scopes always pass.
     *
     * A push one budget refuses is not charged to the other: the room's token is given back when the
     * spool-wide budget refuses, and the spool-wide one is only taken from once the room's allowed.
     * The `retryMs` is the longer of the two waits, so a member that honours it is not refused again
     * by the budget it did not hear about (ADR 2026-10.5cwn).
     */
    private suspend fun commonsBudget(
        conn: Conn,
        scope: ByteArray,
        q: Long,
    ): Boolean {
        // One read, so the room's bucket and the total it is charged beside are one generation's.
        val table = roomTable
        val room = table.roomOf(scope) ?: return true
        val roomRetryMs = room.bucket.take()
        if (roomRetryMs > 0L) {
            metrics.commonsRateLimited(room.config.number, CommonsBudget.ROOM)
            val retryMs = maxOf(roomRetryMs, table.totalBucket.peek())
            rateLimited(conn, q = q, scope = scope, retryMs = retryMs, strike = false)
            return false
        }
        val totalRetryMs = table.totalBucket.take()
        if (totalRetryMs > 0L) {
            room.bucket.refund()
            metrics.commonsRateLimited(room.config.number, CommonsBudget.SPOOL)
            rateLimited(conn, q = q, scope = scope, retryMs = totalRetryMs, strike = false)
            return false
        }
        metrics.commonsPushed(room.config.number)
        return true
    }

    private fun fanOut(
        uploader: Conn,
        push: Push,
    ) {
        val event =
            RecordCodec.encode(
                Event(t = RecordType.EVENT, scope = push.scope, blobId = push.blobId, data = push.data),
            )
        subscribers[hex(push.scope)]?.forEach { subscriber ->
            if (subscriber === uploader) return@forEach
            // Best-effort (spec §7.2): a slow consumer heals via anti-entropy on reconnect —
            // but one that never drains is disconnected 4003 rather than starved forever.
            if (subscriber.out.trySend(binary(event)).isSuccess) {
                subscriber.eventMisses.set(0)
                metrics.eventsTotal.increment()
            } else if (subscriber.eventMisses.incrementAndGet() >= SLOW_CONSUMER_LIMIT) {
                appScope?.launch {
                    subscriber.session.close(CloseReason(CloseCode.ABUSE.toShort(), "slow consumer"))
                }
            }
        }
    }

    /**
     * Fans one cast out to its scope's other live subscribers and keeps nothing (experimental,
     * SPOOL_PROTOCOL.md §7.7; ADR 2026-10.3wdy).
     *
     * It reads only the connection's subscriptions and [subscribers], never the store. So a cast
     * cannot create or recreate a scope, hold one against `maxScopes`, fold a digest, leave a
     * tombstone or reach the disk, and a scope the store has shed still carries casts while its
     * subscribers hold it.
     *
     * Every refusal is a silent drop, counted by reason and never answered. A shipped client reads
     * a scoped `err` with no `q` as a refused `sub` and would drop the conversation, and the
     * client was told the bounds in `hello`, so an answer would tell it nothing it could act on.
     * No refusal strikes: the record bucket already strikes a flood, record by record. Nothing
     * about a cast is logged.
     */
    private fun handleCast(
        conn: Conn,
        bytes: ByteArray,
    ) {
        metrics.castsTotal.increment()
        val cast = RecordCodec.decode<Cast>(bytes)?.takeIf { it.scope.size == ID_BYTES }
        val refusal = if (cast == null) CastDrop.MALFORMED else castRefusal(conn, cast)
        if (refusal != null) {
            metrics.castDropped(refusal)
            return
        }
        fanOutCast(conn, checkNotNull(cast))
    }

    /** Why [cast] is dropped, or null when it fans out. Cheap checks first; the buckets last, so a refusal spends no token. */
    private fun castRefusal(
        conn: Conn,
        cast: Cast,
    ): CastDrop? {
        val info = conn.castInfo
        val scopeHex = hex(cast.scope)
        return when {
            // Off now, or never advertised to this connection: the live check is the operator's
            // kill switch, which reaches connections a reload cannot re-hello.
            info == null || config.rateCasts <= 0 -> CastDrop.OFF

            scopeHex !in conn.subscriptions -> CastDrop.NOT_SUBSCRIBED

            // A shared room is the one place one cast reaches everyone; casts are for a pair.
            roomOf(cast.scope) != null -> CastDrop.COMMONS

            cast.data.size > info.maxBytes -> CastDrop.TOO_LARGE

            conn.gates and Tickets.FL_CAST != 0L && !castUnlocked(conn, scopeHex) -> CastDrop.TICKET

            !castBudget(conn) -> CastDrop.RATE

            else -> null
        }
    }

    /**
     * Whether some live subscriber of the scope holds a cast ticket: the caster itself, checked
     * first, or anyone else subscribed to it here. One ticket holder unlocks the scope both ways, as
     * one is enough for TURN, so a friend without a ticket can talk back. It ties the holder's pseudonym to
     * nothing the spool did not already tie it to: a ticketed connection's subscriptions.
     */
    private fun castUnlocked(
        conn: Conn,
        scopeHex: String,
    ): Boolean {
        if (ticketShortfall(conn.ticket, Tickets.FL_CAST) == null) return true
        return subscribers[scopeHex]?.any { ticketShortfall(it.ticket, Tickets.FL_CAST) == null } == true
    }

    /** A bucket of [rate] casts or sigs a second, with the burst of four times the rate used everywhere. */
    private fun liveBucketFor(rate: Int): TokenBucket = TokenBucket(rate.toDouble(), LIVE_BURST_FACTOR * rate, clock)

    /** One token from the connection's, the address's and the spool's cast buckets, in that order. */
    private fun castBudget(conn: Conn): Boolean {
        val ipBucket = conn.ipState.castBucket(config.rateCasts)
        return listOfNotNull(conn.castBucket, ipBucket, castTotalBucket).all { it.take() == 0L }
    }

    private fun fanOutCast(
        caster: Conn,
        cast: Cast,
    ) {
        val record = RecordCodec.encode(Cast(t = RecordType.CAST, scope = cast.scope, data = cast.data))
        fanOutLive(caster, cast.scope, record, metrics.castsFannedTotal) { metrics.castDropped(CastDrop.FULL) }
    }

    /**
     * Fans one sig out to its scope's other live subscribers and keeps nothing (SPOOL_PROTOCOL.md
     * §7.8; ADR 2026-10.2q49). A cast's lane, with no ticket anywhere in it (S-7.8-4): any subscriber
     * may signal, whatever the spool's `ent`, its `entGates` or this connection's ticket, so a free
     * phone's typing cue crosses a spool.
     *
     * Everything else is [handleCast]'s: it reads only the connection's subscriptions and
     * [subscribers], never the store (S-7.8-5), and every refusal is a silent drop counted by reason,
     * never an answer (S-7.8-2) and never a strike (S-7.8-7). Nothing about a sig, or the scope it
     * names, is logged (S-7.8-8).
     */
    private fun handleSig(
        conn: Conn,
        bytes: ByteArray,
    ) {
        metrics.sigsTotal.increment()
        val sig = RecordCodec.decode<Sig>(bytes)?.takeIf { it.scope.size == ID_BYTES }
        val refusal = if (sig == null) SigDrop.MALFORMED else sigRefusal(conn, sig)
        if (refusal != null) {
            metrics.sigsDropped.increment(refusal)
            return
        }
        fanOutSig(conn, checkNotNull(sig))
    }

    /** Why [sig] is dropped, or null when it fans out (S-7.8-3): [castRefusal]'s order, with no ticket check. */
    private fun sigRefusal(
        conn: Conn,
        sig: Sig,
    ): SigDrop? {
        val info = conn.sigInfo
        return when {
            // The live check is the kill switch, as for casts: it reaches connections a reload cannot re-hello.
            info == null || config.rateSigs <= 0 -> SigDrop.OFF

            hex(sig.scope) !in conn.subscriptions -> SigDrop.NOT_SUBSCRIBED

            // One sig there would reach the whole room.
            roomOf(sig.scope) != null -> SigDrop.COMMONS

            sig.data.size > info.maxBytes -> SigDrop.TOO_LARGE

            !sigBudget(conn) -> SigDrop.RATE

            else -> null
        }
    }

    /**
     * One token from the connection's, the address's and the spool's sig buckets, in that order. The
     * address's is eight times one connection's rather than casts' four: typing is every phone's, and
     * a carrier NAT puts many phones behind one address.
     */
    private fun sigBudget(conn: Conn): Boolean {
        val ipBucket = conn.ipState.sigBucket(config.rateSigs)
        return listOfNotNull(conn.sigBucket, ipBucket, sigTotalBucket).all { it.take() == 0L }
    }

    /** Relays [sig] unchanged to its scope's other subscribers, the sender's connection excluded. */
    private fun fanOutSig(
        sender: Conn,
        sig: Sig,
    ) {
        val record = RecordCodec.encode(Sig(t = RecordType.SIG, scope = sig.scope, data = sig.data))
        fanOutLive(sender, sig.scope, record, metrics.sigsFannedTotal) { metrics.sigsDropped.increment(SigDrop.FULL) }
    }

    /**
     * Hands [record] to every live subscriber of [scope] but [sender]: the fan-out of casts and sigs.
     * Best-effort, like [fanOut], with one difference: a subscriber whose channel is full loses this
     * copy and nothing else, counted by [lost]. It is not counted toward the slow-consumer close
     * (S-7.8-6), because both lanes are live or lost by design, and a phone in the middle of a pull
     * must not be closed 4003 for the crime of being talked to.
     */
    private inline fun fanOutLive(
        sender: Conn,
        scope: ByteArray,
        record: ByteArray,
        fanned: LongAdder,
        lost: () -> Unit,
    ) {
        subscribers[hex(scope)]?.forEach { subscriber ->
            if (subscriber === sender) return@forEach
            if (subscriber.out.trySend(binary(record)).isSuccess) fanned.increment() else lost()
        }
    }

    /** Server-initiated digest re-anchor to every subscriber of [scope] (no `q`, best-effort). */
    private fun broadcastDigest(
        scope: ByteArray,
        info: DigestInfo,
    ) {
        val record = RecordCodec.encode(digestRecord(scope, info))
        subscribers[hex(scope)]?.forEach { it.out.trySend(binary(record)) }
    }

    /** The storage watermark: over `maxBytes`, shed least-recently-active scopes to 90%. */
    private suspend fun maybeShed() {
        if (config.maxBytes <= 0) return
        if (withStore { store.totalBytes() } <= config.maxBytes) return
        val lowWater = config.maxBytes / 10 * 9
        while (withStore { store.totalBytes() } > lowWater) {
            val shed =
                // The pinned set is read on the store thread, where a reload swaps the rooms.
                withStore { store.shedOldestScope(roomTable.pinned) } ?: run {
                    // Only reachable when the commons rooms alone are over the watermark, which
                    // configFromEnv refuses at boot and on a reload — so this means the arithmetic there and the
                    // bytes here have drifted apart, and the operator needs to know rather than
                    // watch the watermark quietly stop working.
                    if (!watermarkStuck) {
                        watermarkStuck = true
                        log.warn("watermark: over SPOOL_MAX_BYTES with only the pinned commons left to shed")
                    }
                    return
                }
            watermarkStuck = false
            metrics.shedsTotal.increment()
            log.warn("watermark: shed scope {} ({} bytes freed)", shortHex(shed.scopeId), shed.freedBytes)
            if (log.isDebugEnabled) log.debug("watermark: shed scope {} in full", hex(shed.scopeId))
            // Re-anchor still-connected subscribers on the now-empty scope so they refill it (§9.1).
            broadcastDigest(shed.scopeId, DigestInfo(digest = 0L, count = 0, full = false, bounds = shed.bounds))
        }
    }

    /**
     * One sweeper pass: expiry broadcasts, idle-scope reclaim, cache pruning, watermark. Exposed for
     * tests. Expiry and the reclaim each run [inBatches], like [reclaimSpace], so records queued
     * behind them run between batches instead of waiting out a pass that grows with the scope count
     * (ADR 2026-10.d9ez).
     */
    internal suspend fun sweepTick() {
        val now = clock()
        inBatches({ store.sweep(now, it, SWEEP_BATCH_SCOPES) }) { broadcastDigest(it.scopeId, it.digest) }
        var reclaimed = 0L
        inBatches({ store.reclaimIdle(now, ::isHeld, it, SWEEP_BATCH_SCOPES) }) { reclaimed++ }
        if (reclaimed > 0) {
            metrics.reclaimsTotal.add(reclaimed)
            log.debug("sweep: reclaimed {} idle bare scope(s)", reclaimed)
        }
        val minDay = Pow.utcDay(now) - 1
        powAccepted.entries.removeIf { it.value < minDay }
        uploadBudgets.prune(now / MS_PER_S)
        turnBudgets.prune(now / MS_PER_S)
        pruneIdleClients(seenBefore = now - IP_IDLE_MS)
        maybeShed()
        reclaimSpace()
    }

    /**
     * Runs a pass over every scope one store call per batch, cursor to cursor, handing each item to
     * [each] as its batch lands. The store dispatcher runs FIFO, so whatever queued during one batch
     * runs before the next.
     */
    private suspend fun <T> inBatches(
        batch: (after: ByteArray?) -> ScopeBatch<T>,
        each: (T) -> Unit,
    ) {
        var cursor: ByteArray? = null
        do {
            val done = withStore { batch(cursor) }
            done.items.forEach(each)
            cursor = done.next
        } while (cursor != null)
    }

    /**
     * Hands freed file space back to the disk (ADR 2026-10.us34), after the expiry, reclaim and shed
     * above have freed it. One store call per batch, so records queued behind the sweep run between
     * batches instead of waiting out the whole pass.
     */
    private suspend fun reclaimSpace() {
        var batches = 0
        while (batches++ < RECLAIM_BATCHES_PER_TICK && withStore { store.reclaimSpace(RECLAIM_BATCH_PAGES) }) continue
    }

    /**
     * Drops every client entry with no live connection that was last seen before [seenBefore].
     *
     * The idle check is repeated under the entry's map lock, where [acceptConnection] counts a
     * connection in. A plain `removeIf` tests the count outside that lock and then removes by
     * identity, so an accept landing between the two would leave its connection on an entry the
     * table had dropped — and the next connection from that address on a fresh one, with the
     * per-address cap counting neither against the other.
     */
    private fun pruneIdleClients(seenBefore: Long) {
        for ((key, state) in ips) {
            if (state.connections.get() != 0 || state.lastSeen >= seenBefore) continue
            ips.computeIfPresent(key) { _, current -> if (current.connections.get() == 0) null else current }
        }
    }

    /** How many client entries the table holds. Exposed for tests. */
    internal fun clientTableSize(): Int = ips.size

    /** How many pseudonyms hold an upload budget entry. Exposed for tests. */
    internal fun uploadBudgetCount(): Int = uploadBudgets.size

    /** How many pseudonyms hold a TURN budget entry. Exposed for tests. */
    internal fun turnBudgetCount(): Int = turnBudgets.size

    /** Live subscribers of [scope]: what a test waits on to know a closed connection has left the fan-out table. */
    internal fun subscriberCount(scope: ByteArray): Int = subscribers[hex(scope)]?.size ?: 0

    /** One status line: gauges read from the store, counters diffed since the last line. */
    internal suspend fun statusTick() {
        val now = clock()
        val (scopeCount, liveBytes) = withStore { store.scopeCount() to store.totalBytes() }
        val commons =
            roomTable.rooms.map { room ->
                RoomStatus(subscribers[room.hex]?.size ?: 0, withStore { store.digest(room.config.scopeId, now)?.count } ?: 0)
            }
        statusLog.info(statusLine.render(now, scopeCount, liveBytes, commons, draining))
    }

    /**
     * Live members of each commons room by the room's number, first room first; null on a spool with
     * no commons.
     *
     * An aggregate for the operator, who can already count connections — not a roster, and never
     * offered to clients: who is in a room is a delivery fact the spool does not deal in (§1).
     */
    private fun commonsSubscribers(): Map<Int, Int>? =
        roomTable.rooms.takeIf { it.isNotEmpty() }?.associate { it.config.number to (subscribers[it.hex]?.size ?: 0) }

    private suspend fun <T> withStore(block: () -> T): T = withContext(storeDispatcher) { block() }

    /**
     * Refuses a record carrying any id that is not exactly [ID_BYTES] long: every `scope`, `blobId`,
     * `aid` and `cid` is `bstr32` (spec B-2-6, §7.2, §7.3). Length is the one shape the codec cannot
     * check — a CBOR byte string of any length decodes into a `ByteArray` — and past this point an id
     * becomes a map key and a row key, copied into every dependent row, index and tombstone, while only
     * `data` is metered. An off-length id makes the whole record malformed: nothing in it — not the good
     * entries of a `sub`, not the first `maxPull` of a `pull` — is processed.
     *
     * First line of every handler, ahead of [requireAttachments], [requireSub] and the push bucket: a
     * record that could never be stored spends no token, never reaches [hex] (which allocates twice the
     * id), and never hops to the store thread. [scope] is echoed only when it is itself [ID_BYTES] long —
     * `err.scope` is `bstr32` too, so an oversized scope is never reflected back. `sub` names no single
     * scope and passes null. In-band and stateless like every other `malformed` (B-7.1-7): no strike,
     * no log line.
     */
    private suspend fun requireIds(
        conn: Conn,
        q: Long,
        scope: ByteArray?,
        ids: List<ByteArray> = emptyList(),
    ): Boolean {
        val scopeOk = scope == null || scope.size == ID_BYTES
        if (scopeOk && ids.all { it.size == ID_BYTES }) return true
        sendErr(conn, ErrCode.MALFORMED, q = q, scope = scope?.takeIf { scopeOk })
        return false
    }

    private suspend fun requireSub(
        conn: Conn,
        scope: ByteArray,
        q: Long,
    ): Boolean {
        if (hex(scope) in conn.subscriptions) return true
        sendErr(conn, ErrCode.NOT_SUBSCRIBED, q = q, scope = scope)
        return false
    }

    private fun powGate(
        scope: ByteArray,
        day: Long?,
        n: Long?,
        now: Long,
    ): Boolean {
        if (config.powBits <= 0) return true
        if (day == null || n == null) return false
        val cacheKey = "${hex(scope)}:$day"
        if (powAccepted.containsKey(cacheKey)) return true
        if (!Pow.dayInWindow(day, now)) return false
        if (!Pow.verify(scope, day, n, config.powBits)) return false
        metrics.powVerifiedTotal.increment()
        // A full cache is not grown: the next stamp for this pair is hashed again instead, which
        // is all a miss ever costs, so the cache can hold an attacker's entries without an honest
        // client paying more than one SHA-256 for it.
        if (powAccepted.size < powCacheCap) powAccepted[cacheKey] = day
        return true
    }

    private fun serverHello(table: RoomTable): Hello =
        Hello(
            t = RecordType.HELLO,
            v = RECORD_VERSION,
            min = 1,
            limits =
                Limits(
                    maxBlob = config.hardLimits.maxBlob,
                    maxRecord = config.maxRecord,
                    maxScopes = config.hardLimits.maxScopes,
                    maxPull = config.maxPull,
                    maxFramesCap = config.hardLimits.maxFramesCap,
                    maxTtlMs = config.hardLimits.maxTtlMs,
                    // All three, or none: their presence IS the attachment capability signal (§7.3),
                    // and a client that sees them absent never sends us an attachment record.
                    maxAttachBytes = config.hardLimits.maxAttachBytes.takeIf { config.hardLimits.attachments },
                    maxAChunk = config.hardLimits.maxAChunk.takeIf { config.hardLimits.attachments },
                    maxAget = config.maxAget.takeIf { config.hardLimits.attachments },
                ),
            powBits = config.powBits,
            // Present only when on (§7.5, S-7.5-1): omitted is the off state, and `false` is never
            // sent. A request to clients, not something this spool can check.
            moderation = true.takeIf { config.requireModeration },
            // Present only when this spool issues credentials, for moderation's reason: presence is
            // the signal, and a client that sees it absent never sends `turnreq` (C-7.6-3).
            turn = true.takeIf { config.turn != null },
            // Only beside `turn`, and omitted rather than empty (S-7.6-48): a client names one of
            // these in `turnreq`, and with none it asks as it always has.
            turnHosts = config.turn?.hosts,
            // Present only with a key (S-7.6-13). The connection snapshots it, so what it was told
            // here is what its `turnreq` gate follows for its whole life.
            ent = true.takeIf { config.ticket != null },
            // Only beside `ent`, and always beside it (S-7.6-13), so a client never guesses the gates.
            entGates = config.ticket?.gates,
            // Omitted when empty, never `[]` (S-7.6-13). Read per connection, so a reload reaches
            // every client that connects after it.
            stun = config.stunUrls.takeIf { it.isNotEmpty() },
            // Omitted when off (rate 0). The connection snapshots it: a cast is never answered, so
            // the bounds it was told are the only ones it can keep to.
            cast = CastInfo(maxBytes = config.maxCast, rate = config.rateCasts).takeIf { config.rateCasts > 0 },
            // Omitted when off (S-7.8-1: rate 0 is omitted), and snapshotted for `cast`'s reason. Never
            // beside a ticket gate: `entGates` has no bit for it (S-7.8-4).
            sig = SigInfo(maxBytes = config.maxSig, rate = config.rateSigs).takeIf { config.rateSigs > 0 },
            // Bounds, a tag and a label — never `scopeId`. The id comes from the invite, and a spool
            // that published it would turn a room only invite holders can find into one that anybody
            // who connects can subscribe to and flood. `commons` repeats the first room for clients
            // that predate `rooms` (S-7.4-15). Names only behind a token (S-7.4-19): without one,
            // whoever connects reads this, and the room's name rides its invite link instead.
            // Decided per connection, since the token reloads (ADR 2026-10.5cwn). The rooms come from
            // `table`, the one the connection keeps, so what it was told is what a reload compares.
            commons =
                table.rooms.firstOrNull()?.config?.let {
                    CommonsInfo(
                        name = it.name.takeIf { config.token != null },
                        maxFrames = it.bounds.maxFrames,
                        ttlMs = it.bounds.ttlMs,
                        maxBlob = it.bounds.maxBlob,
                        attach = it.attach,
                    )
                },
            rooms =
                table.rooms.takeIf { it.isNotEmpty() }?.map { it.config }?.map {
                    RoomInfo(
                        tag = it.tag,
                        name = it.name.takeIf { config.token != null },
                        maxFrames = it.bounds.maxFrames,
                        ttlMs = it.bounds.ttlMs,
                        maxBlob = it.bounds.maxBlob,
                        attach = it.attach,
                    )
                },
        )

    private fun digestRecord(
        scope: ByteArray,
        info: DigestInfo,
    ): Digest =
        Digest(
            t = RecordType.DIGEST,
            scope = scope,
            digest = ScopeDigest.toBytes(info.digest),
            count = info.count,
            full = info.full,
            bounds = info.bounds,
        )

    private suspend inline fun <reified T> out(
        conn: Conn,
        record: T,
    ) {
        conn.out.send(binary(RecordCodec.encode(record)))
    }

    private suspend fun sendErr(
        conn: Conn,
        code: String,
        q: Long? = null,
        scope: ByteArray? = null,
        retryMs: Long? = null,
        msg: String? = null,
    ) {
        metrics.err(code)
        out(conn, Err(t = RecordType.ERR, code = code, q = q, scope = scope, msg = msg, retryMs = retryMs))
    }

    /**
     * Every outbound record is built here — hello, digest, blob, event, attachment chunk, ok, err —
     * so this is the one place egress can be counted without a call site being able to escape it.
     */
    private fun binary(bytes: ByteArray): Frame.Binary {
        metrics.egressBytesTotal.add(bytes.size.toLong())
        return Frame.Binary(true, bytes)
    }

    // On the hot path several times per record — twice per push, and once per requested *and* served
    // blob id in `handlePull`, so 128 calls for a full 64-id pull. `String.format` per byte measured
    // ~100x a nibble table (≈6 us vs ≈0.05 us for a 32-byte scope id).
    @Suppress("MagicNumber") // nibble arithmetic
    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
        }
        return String(out)
    }

    /**
     * Escapes a JSON string body. There is no JSON serializer on the HTTP side — /healthz is two
     * literals and kotlinx here is CBOR, for the wire — and one is not worth adding for a single
     * fixed document. Nothing in it is attacker-supplied, but `SPOOL_SOURCE_URL` is
     * operator-supplied and the build stamp is whatever `-PspoolVersion` was handed, so neither is
     * a literal. Deliberately not shared with the Prometheus label escaper in [Metrics]: the two
     * formats escape different sets, and one helper would be wrong for one of them. What this one
     * escapes is RFC 8259 §7's set: the quote, the backslash, and the controls below U+0020.
     */
    private fun jsonString(raw: String): String =
        buildString(raw.length) {
            raw.forEach { c ->
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' -> append("\\u").append("%04x".format(Locale.ROOT, c.code))
                    else -> append(c)
                }
            }
        }

    /**
     * Whether [candidate] matches any of [accepted]; an empty [accepted] admits everyone, which is
     * what an unconfigured spool is.
     *
     * Every candidate is compared with no early exit once one matches — `or`, not `||` — so the
     * time taken does not say *which* credential was presented. What it still says is how long the
     * accepted ones are, since [constantTimeEquals] returns on a length mismatch; that was already
     * true of the single-token version and is not made worse by there being two.
     */
    private fun accepts(
        accepted: List<String>,
        candidate: String?,
    ): Boolean {
        if (accepted.isEmpty()) return true
        var ok = false
        for (token in accepted) ok = ok or constantTimeEquals(candidate, token)
        return ok
    }

    private fun constantTimeEquals(
        a: String?,
        b: String,
    ): Boolean {
        if (a == null || a.length != b.length) return false
        var diff = 0
        for (i in b.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
