// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.BuildInfo
import app.getknit.spool.protocol.Achunk
import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ahas
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.CommonsInfo
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.Limits
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pow
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.Sub
import app.getknit.spool.shortHex
import app.getknit.spool.store.AputResult
import app.getknit.spool.store.DigestInfo
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.PushResult
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
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
private const val RATE_STRIKE_WINDOW_MS = 10_000L

/** Idle per-IP limiter state older than this is pruned by the sweeper. */
private const val IP_IDLE_MS = 600_000L

/**
 * `Retry-After` on a capacity refusal. Long enough that a rejected client spends the interval on
 * its other spools rather than re-dialing this one, short enough that a spool which empties out is
 * usable again quickly. Clients are expected to jitter around it; a whole population returning on
 * the same second is the reconnect storm this limit exists to prevent.
 */
private const val RETRY_AFTER_SECONDS = 30

/** The one WebSocket route (spec §7.1); also what the capacity gate matches on. */
private const val SPOOL_PATH = "/spool/v1"

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * The spool daemon, SPOOL_PROTOCOL.md §6–§8: one WebSocket route at `/spool/v1`, one CBOR record
 * per binary message, hello negotiation first in both directions, then sub/list/pull/push against
 * the scope store with live `event` fan-out to the scope's other subscribers. Plus the operator
 * surface: `/healthz`, `/metrics`, per-connection and per-IP rate limits, the global storage
 * watermark, and a periodic sweeper that expires blobs and re-anchors subscribers by digest.
 *
 * TLS is left to a fronting reverse proxy; with `trustProxy` the per-IP limits key on the
 * proxy-appended `X-Forwarded-For` hop instead of the socket address.
 */
class SpoolServer(
    private val config: Config,
    private val store: ScopeStore = InMemoryScopeStore(config.hardLimits),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Config(
        val port: Int,
        val token: String?,
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
        /** The §13 corresponding-source URL served at `GET /source`; a fork overrides it. */
        val sourceUrl: String = BuildInfo.UPSTREAM_SOURCE_URL,
        /** The commons (spec §7.4), or null when this spool does not run one. */
        val commons: CommonsConfig? = null,
    ) {
        /**
         * Every resolved value on one line, `k=v`, for the boot log.
         *
         * Defaults included on purpose: the value an operator misremembers is always the one they
         * never set, and a fleet makes that a support call rather than a shrug. One line and no
         * spaces in any value, so it splits the same way the status line does.
         *
         * Secrets are reported as `set`/`unset` and never printed — `SECURITY.md` makes a token in
         * this daemon's log a reportable vulnerability. The commons is reported as a truncated id
         * for the same reason `hello` never carries it at all: publishing it would turn a room only
         * invite holders can find into one anybody can subscribe to. Its bounds are already on the
         * `commons enabled:` line, so they are not repeated here.
         */
        fun describe(): String =
            listOf(
                "port" to port,
                "token" to secret(token),
                "metricsToken" to secret(metricsToken),
                "pow" to powBits,
                "maxRecord" to maxRecord,
                "maxBlob" to hardLimits.maxBlob,
                "maxFrames" to hardLimits.maxFramesCap,
                "maxTtlMs" to hardLimits.maxTtlMs,
                "maxScopes" to hardLimits.maxScopes,
                "maxPull" to maxPull,
                "maxAget" to maxAget,
                "attachBytes" to hardLimits.maxAttachBytes,
                "maxAChunk" to hardLimits.maxAChunk,
                "maxBytes" to maxBytes,
                "sweepMs" to sweepMs,
                "statusMs" to statusMs,
                "trustProxy" to trustProxy,
                "maxConns" to maxConns,
                "maxConnsPerIp" to maxConnsPerIp,
                "rateRecords" to rateRecords,
                "ratePushes" to ratePushes,
                "rateNewScopes" to rateNewScopesPerMin,
                "commons" to (commons?.let { shortHex(it.scopeId) } ?: "off"),
                "source" to sourceUrl,
            ).joinToString(" ") { (key, value) -> "$key=$value" }

        private fun secret(value: String?): String = if (value == null) "unset" else "set"
    }

    /**
     * One operator-declared scope shared by everyone on the spool who holds the invite.
     *
     * [scopeId] is `SHA-256("knit/spool/v1/commons" ‖ secret)`. The secret never reaches the spool,
     * so this daemon relays a room it structurally cannot read — it holds a hash of the invite and
     * the sealed frames, and no code path here turns one into the other.
     *
     * [bounds] are *pinned*, not declared. The store applies whatever the most recent subscriber
     * asked for, which is right for a conversation whose members negotiate among themselves and
     * wrong for a room shared with strangers: one member subscribing with `maxFrames = 1` would
     * evict everyone else's history. So the commons ignores what a client declares and answers with
     * the truth in the `digest` it returns.
     */
    class CommonsConfig(
        val scopeId: ByteArray,
        val name: String?,
        val bounds: ScopeBounds,
        val attach: Boolean,
        val ratePushes: Int,
    )

    private val log = LoggerFactory.getLogger(SpoolServer::class.java)

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
            maxBytes = config.maxBytes,
            maxConns = config.maxConns,
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

    /** Accepted PoW cache: "scopeHex:day" → day (spec §8); pruned by the sweeper on day rollover. */
    private val powAccepted = ConcurrentHashMap<String, Long>()

    /** Per-client-IP limiter state; idle entries pruned by the sweeper. */
    private val ips = ConcurrentHashMap<String, IpState>()

    /**
     * The commons is a shared write surface, which the per-connection push bucket does not bound:
     * 200 members at 10/s each is 2,000 pushes/s into one scope, churning a 500-frame room four
     * times a second and amplifying through fan-out to 400k frames/s. This bucket is spool-wide, so
     * the room has a total budget no number of members can exceed.
     */
    private val commonsPushBucket =
        config.commons?.let { TokenBucket(it.ratePushes.toDouble(), 4.0 * it.ratePushes, clock) }

    /** Latches the "over watermark, nothing left to shed" warning so it logs on entry, not per sweep. */
    private var watermarkStuck = false

    /** The commons' key into [subscribers], hoisted out of the ops paths that read it. */
    private val commonsHex = config.commons?.let { hex(it.scopeId) }

    /**
     * What `/metrics` checks `?k=` against: [Config.metricsToken] if set, otherwise [Config.token].
     *
     * They are separate because the two credentials answer to different people. On a hosted spool
     * the customer holds `SPOOL_TOKEN`, and `/metrics` carries scope counts and traffic shape that
     * are the operator's business, not theirs — while a fleet-wide Prometheus should not need every
     * customer's connect secret in its scrape config to read them. Null here means a public spool
     * with no metrics token, and `/metrics` is open exactly as it was.
     */
    private val metricsGate = config.metricsToken ?: config.token

    /**
     * The §13 answer, assembled once: it cannot change while the process runs, and an
     * unauthenticated route should not rebuild a fixed document per request.
     */
    private val sourceJson: String =
        """{"name":"knit-spool","version":"${jsonString(BuildInfo.version)}",""" +
            """"commit":"${jsonString(BuildInfo.commit)}",""" +
            """"source":"${jsonString(config.sourceUrl)}",""" +
            """"license":"AGPL-3.0-or-later"}"""

    private val activeSessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    private var appScope: CoroutineScope? = null

    private inner class Conn(
        val session: DefaultWebSocketServerSession,
        val ipState: IpState,
    ) {
        val out: SendChannel<Frame> get() = session.outgoing

        /** scope hex → the bounds this connection last declared (drives the push-recreate path). */
        val subscriptions = HashMap<String, ScopeBounds>()
        val recordBucket = TokenBucket(config.rateRecords.toDouble(), 4.0 * config.rateRecords, clock)
        val pushBucket = TokenBucket(config.ratePushes.toDouble(), 4.0 * config.ratePushes, clock)
        val strikes = StrikeWindow(RATE_STRIKE_LIMIT, RATE_STRIKE_WINDOW_MS)
        val eventMisses = AtomicInteger(0)
    }

    fun start(wait: Boolean = true): EmbeddedServer<*, *> {
        createCommons()
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
                    if (call.request.path() == SPOOL_PATH && atCapacity()) {
                        metrics.connsRefusedTotal.increment()
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
                        if (metricsGate != null && !constantTimeEquals(call.request.queryParameters["k"], metricsGate)) {
                            call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                            return@get
                        }
                        val (scopeCount, liveBytes) = withStore { store.scopeCount() to store.totalBytes() }
                        call.respondText(
                            metrics.render(scopeCount, liveBytes, config.maxConns, commonsSubscribers()),
                            ContentType.Text.Plain,
                        )
                    }
                }
            }
        engine = server
        log.info("knit-spool {} (commit {}) — source {}", BuildInfo.version, BuildInfo.commit, config.sourceUrl)
        log.info("effective config: {}", config.describe())
        log.info("knit-spool listening on :{} (pow={} bits, token={})", config.port, config.powBits, config.token != null)
        return server.start(wait = wait)
    }

    /** Graceful shutdown: close every session 1001, stop the engine, close the store. */
    fun stop() {
        val server = engine ?: return
        activeSessions.forEach { session ->
            runCatching {
                runBlocking {
                    withTimeout(1_000) {
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

    /** True for the one scope [Config.commons] declares. A 32-byte compare, cheaper than hex. */
    private fun isCommons(scope: ByteArray): Boolean = config.commons?.scopeId?.contentEquals(scope) == true

    /**
     * Creates the commons scope before the first client can connect.
     *
     * Everything else about the commons follows from its already existing: `isUnknownScope` is
     * false forever, so neither the PoW gate nor the per-IP new-scope bucket ever fires for it and
     * members join with a plain `sub` even on a spool mining at 20 bits (§6.4).
     */
    private fun createCommons() {
        val commons = config.commons ?: return
        val result = runBlocking { withStore { store.subscribe(commons.scopeId, commons.bounds, clock()) } }
        if (result is SubscribeResult.QuotaExceeded) {
            // Reachable on a persistent store that already holds maxScopes scopes from before the
            // commons was configured (or under a previous SPOOL_COMMONS_ID). Refuse to start: a
            // spool silently up without the room it advertises is the worse outcome.
            throw IllegalStateException(
                "commons scope cannot be created: the store already holds SPOOL_MAX_SCOPES " +
                    "(${config.hardLimits.maxScopes}) scopes. Raise SPOOL_MAX_SCOPES, or clear SPOOL_DATA_DIR.",
            )
        }
        log.info(
            "commons enabled: {} frames, ttl {} ms, attachments {}",
            commons.bounds.maxFrames,
            commons.bounds.ttlMs,
            if (commons.attach) "on" else "off",
        )
    }

    private suspend fun acceptConnection(session: DefaultWebSocketServerSession) {
        val ip = session.call.request.origin.remoteHost
        val ipState = ips.computeIfAbsent(ip) { IpState(config.rateNewScopesPerMin, clock) }
        ipState.lastSeen = clock()
        if (ipState.connections.incrementAndGet() > config.maxConnsPerIp) {
            ipState.connections.decrementAndGet()
            session.close(CloseReason(CloseCode.ABUSE.toShort(), "too many connections"))
            return
        }
        try {
            if (config.token != null &&
                !constantTimeEquals(session.call.request.queryParameters["k"], config.token)
            ) {
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

    private suspend fun serveConnection(
        session: DefaultWebSocketServerSession,
        ipState: IpState,
    ) {
        val conn = Conn(session, ipState)
        activeSessions.add(session)
        try {
            conn.out.send(binary(RecordCodec.encode(serverHello())))
            var helloed = false
            for (frame in session.incoming) {
                val bytes = (frame as? Frame.Binary)?.readBytes() ?: continue
                metrics.recordsTotal.increment()
                if (!helloed) {
                    val hello = RecordCodec.decode<Hello>(bytes)
                    if (hello == null || RecordCodec.peekType(bytes) != RecordType.HELLO) {
                        session.close(CloseReason(CloseCode.MALFORMED.toShort(), "hello first"))
                        return
                    }
                    if (hello.v !in 1..RECORD_VERSION) {
                        session.close(CloseReason(CloseCode.VERSION.toShort(), "no version overlap"))
                        return
                    }
                    helloed = true
                    continue
                }
                val retryMs = conn.recordBucket.take()
                if (retryMs > 0) {
                    if (rateLimited(conn, q = null, scope = null, retryMs = retryMs)) return
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
            if (e.message != PING_TIMEOUT_MESSAGE) throw e
            log.info("connection dropped: ping timeout")
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

    /** Wraps a record handler so an unexpected failure surfaces as `err internal`, not a dead task. */
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
            log.error("record handling failed", e)
            sendErr(conn, ErrCode.INTERNAL, q = q)
        }
    }

    private suspend fun handleSub(
        conn: Conn,
        sub: Sub,
    ) {
        val now = clock()
        for (scopeSub in sub.subs) {
            val scopeHex = hex(scopeSub.scope)
            if (scopeHex !in conn.subscriptions && withStore { store.isUnknownScope(scopeSub.scope) }) {
                if (!newScopeGates(conn, scopeSub.scope, scopeSub.pow?.d, scopeSub.pow?.n, sub.q, now)) continue
            }
            // The commons declares its own bounds. Substituted rather than refused — the `digest`
            // reply already carries the applied bounds, so the client learns the truth in the
            // answer it was going to read anyway. Same shape as a truncated `pull` (§7.2).
            val declared = config.commons?.bounds?.takeIf { isCommons(scopeSub.scope) } ?: scopeSub.bounds
            when (val result = withStore { store.subscribe(scopeSub.scope, declared, now) }) {
                is SubscribeResult.Subscribed -> {
                    // The *applied* bounds, so the push-recreate path below re-subscribes with them.
                    conn.subscriptions[scopeHex] = declared
                    subscribers.computeIfAbsent(scopeHex) { ConcurrentHashMap.newKeySet() }.add(conn)
                    conn.out.send(binary(RecordCodec.encode(digestRecord(scopeSub.scope, result.digest))))
                }

                SubscribeResult.QuotaExceeded -> {
                    sendErr(conn, ErrCode.QUOTA, q = sub.q, scope = scopeSub.scope)
                }
            }
        }
    }

    private suspend fun handleList(
        conn: Conn,
        list: ScopeList,
    ) {
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
        if (!requireSub(conn, pull.scope, pull.q)) return
        val wanted = pull.blobIds.take(config.maxPull)
        val served = withStore { store.pull(pull.scope, wanted, clock()) }
        val servedHex = served.map { hex(it.first) }.toSet()
        for ((blobId, data) in served) {
            out(conn, Blob(t = RecordType.BLOB, scope = pull.scope, blobId = blobId, data = data))
        }
        val missing = wanted.filter { hex(it) !in servedHex }
        out(conn, Ok(t = RecordType.OK, q = pull.q, missing = missing.ifEmpty { null }))
    }

    private suspend fun handleAhave(
        conn: Conn,
        ahave: Ahave,
    ) {
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

    private suspend fun handleAget(
        conn: Conn,
        aget: Aget,
    ) {
        if (!requireAttachments(conn, aget.q, aget.scope)) return
        if (!requireSub(conn, aget.scope, aget.q)) return
        // Truncated, never an error — the `pull` rule of §7.2, reapplied.
        val chunks = withStore { store.attachmentGet(aget.scope, aget.aid, aget.from, minOf(aget.n, config.maxAget), clock()) }
        for (chunk in chunks) {
            out(
                conn,
                Achunk(
                    t = RecordType.ACHUNK,
                    scope = aget.scope,
                    aid = aget.aid,
                    idx = chunk.idx,
                    total = chunk.total,
                    cid = chunk.cid,
                    data = chunk.data,
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
        if (!requireAttachments(conn, aput.q, aput.scope)) return
        if (!requireSub(conn, aput.scope, aput.q)) return
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
            if (!newScopeGates(conn, aput.scope, aput.pow?.d, aput.pow?.n, aput.q, now)) return
            val declared = conn.subscriptions[hex(aput.scope)] ?: return
            when (val result = withStore { store.subscribe(aput.scope, declared, now) }) {
                is SubscribeResult.Subscribed -> {
                    conn.out.send(binary(RecordCodec.encode(digestRecord(aput.scope, result.digest))))
                }

                SubscribeResult.QuotaExceeded -> {
                    sendErr(conn, ErrCode.QUOTA, q = aput.q, scope = aput.scope)
                    return
                }
            }
        }
        when (val result = withStore { store.attachmentPut(aput.scope, aput.aid, aput.idx, aput.total, aput.cid, aput.data, now) }) {
            is AputResult.Stored -> {
                metrics.attachChunksStoredTotal.increment()
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
        // A public room is where a 16 MiB upload costs the operator the most and is worth the
        // least, so the commons carries its own switch and defaults off. Advertised in `hello`
        // alongside the bounds, so a conforming client never gets here.
        val allowed = config.hardLimits.attachments && (!isCommons(scope) || config.commons?.attach == true)
        if (allowed) return true
        sendErr(conn, ErrCode.MALFORMED, q = q, scope = scope)
        return false
    }

    private suspend fun handlePush(
        conn: Conn,
        push: Push,
    ) {
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
            if (!newScopeGates(conn, push.scope, push.pow?.d, push.pow?.n, push.q, now)) return
            val declared = conn.subscriptions[hex(push.scope)] ?: return
            when (val result = withStore { store.subscribe(push.scope, declared, now) }) {
                is SubscribeResult.Subscribed -> {
                    conn.out.send(binary(RecordCodec.encode(digestRecord(push.scope, result.digest))))
                }

                SubscribeResult.QuotaExceeded -> {
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

    /** The unknown-scope gates, cheapest first: per-IP creation rate, then PoW (spec §6.4). */
    private suspend fun newScopeGates(
        conn: Conn,
        scope: ByteArray,
        powDay: Long?,
        powN: Long?,
        q: Long,
        now: Long,
    ): Boolean {
        val retryMs = conn.ipState.newScopeBucket.take()
        if (retryMs > 0) {
            rateLimited(conn, q = q, scope = scope, retryMs = retryMs)
            return false
        }
        if (!powGate(scope, powDay, powN, now)) {
            sendErr(conn, ErrCode.POW, q = q, scope = scope)
            return false
        }
        return true
    }

    /**
     * Sends `err rate` and strikes the abuse window; true means the connection was closed 4003.
     *
     * [strike] is false for the commons' spool-wide bucket. That limit is congestion on a shared
     * room, not evidence this client is ignoring backpressure — striking it would close well-behaved
     * members 4003 for the crime of talking while others were talking.
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
     * Takes from the spool-wide commons budget. Returns false when the room is saturated and the
     * caller must drop the record; ordinary scopes always pass.
     */
    private suspend fun commonsBudget(
        conn: Conn,
        scope: ByteArray,
        q: Long,
    ): Boolean {
        val bucket = commonsPushBucket?.takeIf { isCommons(scope) } ?: return true
        val retryMs = bucket.take()
        if (retryMs == 0L) {
            metrics.commonsPushesTotal.increment()
            return true
        }
        metrics.commonsRateLimitedTotal.increment()
        rateLimited(conn, q = q, scope = scope, retryMs = retryMs, strike = false)
        return false
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
                withStore { store.shedOldestScope(config.commons?.scopeId) } ?: run {
                    // Only reachable when the commons alone is over the watermark, which
                    // configFromEnv refuses at boot — so this means the arithmetic there and the
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

    /** One sweeper pass: expiry broadcasts, cache pruning, watermark. Exposed for tests. */
    internal suspend fun sweepTick() {
        val now = clock()
        val changes = withStore { store.sweep(now) }
        changes.forEach { broadcastDigest(it.scopeId, it.digest) }
        val minDay = Pow.utcDay(now) - 1
        powAccepted.entries.removeIf { it.value < minDay }
        ips.entries.removeIf { it.value.connections.get() == 0 && now - it.value.lastSeen > IP_IDLE_MS }
        maybeShed()
    }

    /** One status line: gauges read from the store, counters diffed since the last line. */
    internal suspend fun statusTick() {
        val now = clock()
        val (scopeCount, liveBytes) = withStore { store.scopeCount() to store.totalBytes() }
        val commonsFrames = config.commons?.let { withStore { store.digest(it.scopeId, now)?.count } } ?: 0
        statusLog.info(statusLine.render(now, scopeCount, liveBytes, commonsSubscribers(), commonsFrames))
    }

    /**
     * Live commons members, or null on a spool with no commons.
     *
     * An aggregate for the operator, who can already count connections — not a roster, and never
     * offered to clients: who is in the room is a delivery fact the spool does not deal in (§1).
     */
    private fun commonsSubscribers(): Int? = commonsHex?.let { subscribers[it]?.size ?: 0 }

    private suspend fun <T> withStore(block: () -> T): T = withContext(storeDispatcher) { block() }

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
        powAccepted[cacheKey] = day
        return true
    }

    private fun serverHello(): Hello =
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
            // Bounds and a label — never `scopeId`. The id comes from the invite, and a spool that
            // published it would turn a room only invite holders can find into one that anybody
            // who connects can subscribe to and flood.
            commons =
                config.commons?.let {
                    CommonsInfo(
                        name = it.name,
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
    ) {
        metrics.err(code)
        out(conn, Err(t = RecordType.ERR, code = code, q = q, scope = scope, retryMs = retryMs))
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
     * formats escape different sets, and one helper would be wrong for one of them.
     */
    private fun jsonString(raw: String): String =
        buildString(raw.length) {
            raw.forEach { c ->
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c.code < 0x20 -> append("\\u").append("%04x".format(c.code))
                    else -> append(c)
                }
            }
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
