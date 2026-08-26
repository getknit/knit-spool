// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.BuildInfo
import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.PowStamp
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.ScopeStore
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Mutable test clock injected through the server's existing `clock` seam. */
class FakeClock(
    @Volatile var now: Long = 1_000_000L,
) {
    fun advance(ms: Long) {
        now += ms
    }
}

fun testConfig(
    token: String? = null,
    metricsToken: String? = null,
    powBits: Int = 0,
    maxRecord: Int = 8_192,
    maxPull: Int = 4,
    hardLimits: HardLimits = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 4),
    maxBytes: Long = 0L,
    statusMs: Long = 0L,
    maxConns: Int = 0,
    maxConnsPerIp: Int = 16,
    rateRecords: Int = 1_000,
    ratePushes: Int = 1_000,
    rateNewScopesPerMin: Int = 10_000,
    sourceUrl: String = BuildInfo.UPSTREAM_SOURCE_URL,
    commons: SpoolServer.CommonsConfig? = null,
): SpoolServer.Config =
    SpoolServer.Config(
        port = 0,
        token = token,
        metricsToken = metricsToken,
        powBits = powBits,
        maxRecord = maxRecord,
        maxPull = maxPull,
        hardLimits = hardLimits,
        maxBytes = maxBytes,
        // Effectively off — tests drive expiry deterministically via sweepTick().
        sweepMs = 3_600_000L,
        // Off by default — StatusLineTest drives statusTick() itself.
        statusMs = statusMs,
        trustProxy = false,
        maxConns = maxConns,
        maxConnsPerIp = maxConnsPerIp,
        rateRecords = rateRecords,
        ratePushes = ratePushes,
        rateNewScopesPerMin = rateNewScopesPerMin,
        sourceUrl = sourceUrl,
        commons = commons,
    )

/** A fixed invite, so a test can derive the same commons scope id the server was configured with. */
val TEST_COMMONS_SECRET = ByteArray(Commons.SECRET_BYTES) { (it + 1).toByte() }

fun commonsScope(): ByteArray = Commons.scopeId(TEST_COMMONS_SECRET)

fun testCommons(
    name: String? = "test commons",
    maxFrames: Int = 5,
    ttlMs: Long = 60_000L,
    maxBlob: Int = 1_024,
    attach: Boolean = false,
    ratePushes: Int = 1_000,
): SpoolServer.CommonsConfig =
    SpoolServer.CommonsConfig(
        scopeId = commonsScope(),
        name = name,
        bounds = ScopeBounds(maxFrames = maxFrames, ttlMs = ttlMs, maxBlob = maxBlob),
        attach = attach,
        ratePushes = ratePushes,
    )

class TestServer(
    val spool: SpoolServer,
    val store: ScopeStore,
    val clock: FakeClock,
    val port: Int,
    val http: HttpClient,
) {
    suspend fun connect(
        token: String? = null,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) {
        val url = "ws://127.0.0.1:$port/spool/v1" + (token?.let { "?k=$it" } ?: "")
        http.webSocket(url) { block() }
    }
}

/** Boots a real server on an ephemeral port, hands the test a client, tears both down. */
fun withServer(
    config: SpoolServer.Config = testConfig(),
    clock: FakeClock = FakeClock(),
    store: ScopeStore = InMemoryScopeStore(config.hardLimits),
    block: suspend TestServer.() -> Unit,
) = runBlocking {
    val spool = SpoolServer(config, store, clock::now)
    val engine = spool.start(wait = false)
    val port =
        engine.engine
            .resolvedConnectors()
            .first()
            .port
    val http =
        HttpClient(CIO) {
            install(WebSockets)
        }
    try {
        TestServer(spool, store, clock, port, http).block()
    } finally {
        http.close()
        engine.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }
}

suspend inline fun <reified T> WebSocketSession.sendRecord(record: T) {
    send(Frame.Binary(true, RecordCodec.encode(record)))
}

suspend fun WebSocketSession.sendRaw(bytes: ByteArray) {
    send(Frame.Binary(true, bytes))
}

suspend fun WebSocketSession.nextBinary(timeoutMs: Long = 5_000L): ByteArray =
    withTimeout(timeoutMs) {
        var bytes: ByteArray? = null
        while (bytes == null) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) bytes = frame.readBytes()
        }
        bytes
    }

suspend inline fun <reified T> WebSocketSession.expectRecord(
    type: String,
    timeoutMs: Long = 5_000L,
): T {
    val bytes = nextBinary(timeoutMs)
    assertEquals(type, RecordCodec.peekType(bytes), "unexpected record type")
    return assertNotNull(RecordCodec.decode<T>(bytes), "record failed to decode as ${T::class.simpleName}")
}

/** Reads the server hello (arrives unprompted), answers with a client hello, returns the server's. */
suspend fun WebSocketSession.helloHandshake(v: Int = RECORD_VERSION): Hello {
    val serverHello = expectRecord<Hello>(RecordType.HELLO)
    sendRecord(Hello(t = RecordType.HELLO, v = v))
    return serverHello
}

suspend fun DefaultClientWebSocketSession.awaitClose(
    expectedCode: Int,
    timeoutMs: Long = 5_000L,
) {
    withTimeout(timeoutMs) {
        try {
            while (true) incoming.receive()
        } catch (_: ClosedReceiveChannelException) {
            // drained; the close reason is now available
        }
        assertEquals(expectedCode.toShort(), closeReason.await()?.code, "unexpected close code")
    }
}

suspend fun WebSocketSession.subscribe(
    scope: ByteArray,
    bounds: ScopeBounds = testBounds(),
    q: Long = 1L,
    pow: PowStamp? = null,
): Digest {
    sendRecord(Sub(t = RecordType.SUB, q = q, subs = listOf(ScopeSub(scope = scope, bounds = bounds, pow = pow))))
    return expectRecord<Digest>(RecordType.DIGEST)
}

fun testBounds(): ScopeBounds = ScopeBounds(maxFrames = 3, ttlMs = 10_000L, maxBlob = 1_024)

fun testScope(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

fun testBlob(seed: Int): Pair<ByteArray, ByteArray> {
    val data = ByteArray(40) { ((it * 7 + seed) and 0xFF).toByte() }
    data[0] = (seed ushr 8).toByte()
    data[1] = seed.toByte()
    return MessageDigest.getInstance("SHA-256").digest(data) to data
}

/**
 * Runs [block] with a [ListAppender] attached to [loggerName] and returns what it logged.
 *
 * [level] temporarily re-levels that one logger, which is the only way to reach a line guarded by
 * `isDebugEnabled` — the daemon ships at INFO and the full-id lines are deliberately below it.
 * Both the level and the appender are restored, so tests stay order-independent.
 */
fun withLogCapture(
    loggerName: String,
    level: Level? = null,
    block: () -> Unit,
): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(loggerName) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val original = logger.level
    logger.addAppender(appender)
    if (level != null) logger.level = level
    try {
        block()
    } finally {
        logger.level = original
        logger.detachAppender(appender)
    }
    return appender.list.toList()
}
