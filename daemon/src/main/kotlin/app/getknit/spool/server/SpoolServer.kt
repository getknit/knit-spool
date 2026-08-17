// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Achunk
import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ahas
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.CloseCode
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
import app.getknit.spool.store.AputResult
import app.getknit.spool.store.DigestInfo
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.PushResult
import app.getknit.spool.store.ScopeStore
import app.getknit.spool.store.SubscribeResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Headroom over `maxRecord` for the WebSocket frame envelope before the transport kills it (1009). */
private const val FRAME_SLACK = 1024L

/** Consecutive undeliverable events before a subscriber is closed 4003 (spec §7.2). */
private const val SLOW_CONSUMER_LIMIT = 8

private const val RATE_STRIKE_LIMIT = 8
private const val RATE_STRIKE_WINDOW_MS = 10_000L

/** Idle per-IP limiter state older than this is pruned by the sweeper. */
private const val IP_IDLE_MS = 600_000L

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
        val powBits: Int,
        val maxRecord: Int,
        val maxPull: Int,
        val hardLimits: HardLimits,
        val maxAget: Int = 32,
        val maxBytes: Long = 268_435_456L,
        val sweepMs: Long = 60_000L,
        val trustProxy: Boolean = false,
        val maxConnsPerIp: Int = 16,
        val rateRecords: Int = 50,
        val ratePushes: Int = 10,
        val rateNewScopesPerMin: Int = 6,
    )

    private val log = LoggerFactory.getLogger(SpoolServer::class.java)

    val metrics = Metrics()

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
                routing {
                    webSocket("/spool/v1") {
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
                    get("/metrics") {
                        if (config.token != null && !constantTimeEquals(call.request.queryParameters["k"], config.token)) {
                            call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                            return@get
                        }
                        val (scopeCount, liveBytes) = withStore { store.scopeCount() to store.totalBytes() }
                        call.respondText(metrics.render(scopeCount, liveBytes), ContentType.Text.Plain)
                    }
                }
            }
        engine = server
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
        conn.out.send(binary(RecordCodec.encode(serverHello())))
        try {
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
            when (val result = withStore { store.subscribe(scopeSub.scope, scopeSub.bounds, now) }) {
                is SubscribeResult.Subscribed -> {
                    conn.subscriptions[scopeHex] = scopeSub.bounds
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
        if (config.hardLimits.attachments) return true
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

    /** Sends `err rate` and strikes the abuse window; true means the connection was closed 4003. */
    private suspend fun rateLimited(
        conn: Conn,
        q: Long?,
        scope: ByteArray?,
        retryMs: Long,
    ): Boolean {
        metrics.rateLimitedTotal.increment()
        sendErr(conn, ErrCode.RATE, q = q, scope = scope, retryMs = retryMs)
        if (conn.strikes.strike(clock())) {
            conn.session.close(CloseReason(CloseCode.ABUSE.toShort(), "rate abuse"))
            return true
        }
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
            val shed = withStore { store.shedOldestScope() } ?: break
            metrics.shedsTotal.increment()
            log.warn("watermark: shed scope {} ({} bytes freed)", hex(shed.scopeId), shed.freedBytes)
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
