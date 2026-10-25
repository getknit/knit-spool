// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.BuildInfo
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Ent
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.PowStamp
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
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
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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
    tokenNext: String? = null,
    metricsToken: String? = null,
    powBits: Int = 0,
    maxRecord: Int = 8_192,
    maxPull: Int = 4,
    hardLimits: HardLimits = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 4),
    maxBytes: Long = 0L,
    sweepMs: Long = 3_600_000L,
    statusMs: Long = 0L,
    trustProxy: Boolean = false,
    maxConns: Int = 0,
    maxConnsPerIp: Int = 16,
    rateRecords: Int = 1_000,
    ratePushes: Int = 1_000,
    rateNewScopesPerMin: Int = 10_000,
    requireModeration: Boolean = false,
    sourceUrl: String = BuildInfo.UPSTREAM_SOURCE_URL,
    commons: SpoolServer.CommonsConfig? = null,
    rooms: List<SpoolServer.CommonsConfig> = listOfNotNull(commons),
    commonsRateTotal: Int = 10_000,
    turn: SpoolServer.TurnConfig? = null,
    stunUrls: List<String> = emptyList(),
    ticket: SpoolServer.TicketConfig? = null,
    rateCasts: Int = 0,
    maxCast: Int = 2_048,
    rateCastsTotal: Int = 200,
): SpoolServer.Config =
    SpoolServer.Config(
        port = 0,
        token = token,
        tokenNext = tokenNext,
        metricsToken = metricsToken,
        powBits = powBits,
        maxRecord = maxRecord,
        maxPull = maxPull,
        hardLimits = hardLimits,
        maxBytes = maxBytes,
        // Effectively off by default — tests drive expiry deterministically via sweepTick().
        sweepMs = sweepMs,
        // Off by default — StatusLineTest drives statusTick() itself.
        statusMs = statusMs,
        trustProxy = trustProxy,
        maxConns = maxConns,
        maxConnsPerIp = maxConnsPerIp,
        rateRecords = rateRecords,
        ratePushes = ratePushes,
        rateNewScopesPerMin = rateNewScopesPerMin,
        requireModeration = requireModeration,
        sourceUrl = sourceUrl,
        rooms = rooms,
        commonsRateTotal = commonsRateTotal,
        turn = turn,
        stunUrls = stunUrls,
        ticket = ticket,
        rateCasts = rateCasts,
        maxCast = maxCast,
        rateCastsTotal = rateCastsTotal,
    )

/** Whether [needle] occurs anywhere in this array — for asserting what an encoded record does *not* carry. */
fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

/** A fixed invite, so a test can derive the same commons scope id the server was configured with. */
val TEST_COMMONS_SECRET = ByteArray(Commons.SECRET_BYTES) { (it + 1).toByte() }

fun commonsScope(): ByteArray = Commons.scopeId(TEST_COMMONS_SECRET)

/** A second room's invite, for the tests that run two. */
val TEST_COMMONS_SECRET_2 = ByteArray(Commons.SECRET_BYTES) { (it + 101).toByte() }

fun commonsScope2(): ByteArray = Commons.scopeId(TEST_COMMONS_SECRET_2)

fun testCommons(
    name: String? = "test commons",
    maxFrames: Int = 5,
    ttlMs: Long = 60_000L,
    maxBlob: Int = 1_024,
    attach: Boolean = false,
    ratePushes: Int = 1_000,
    scopeId: ByteArray = commonsScope(),
    number: Int = 1,
): SpoolServer.CommonsConfig =
    SpoolServer.CommonsConfig(
        scopeId = scopeId,
        name = name,
        bounds = ScopeBounds(maxFrames = maxFrames, ttlMs = ttlMs, maxBlob = maxBlob),
        attach = attach,
        ratePushes = ratePushes,
        number = number,
    )

/** A TURN secret long enough for the parser, and distinctive enough to grep a log for. */
const val TEST_TURN_SECRET = "test-turn-secret-0123456789abcdefghij"

fun testTurn(
    urls: List<String> = listOf("turn:spool.example:3478?transport=udp", "turns:spool.example:5349?transport=tcp"),
    ttlS: Long = 300L,
    ratePer10Min: Int = 60,
): SpoolServer.TurnConfig = SpoolServer.TurnConfig(secret = TEST_TURN_SECRET, urls = urls, ttlS = ttlS, ratePer10Min = ratePer10Min)

/** RFC 8032 §7.1 TEST 1 as `seed ‖ public key` — the pair the protocol's ticket vectors use. */
val TEST_TICKET_SIGNING_KEY =
    (
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60" +
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    ).hexToByteArray()

const val TEST_TICKET_AUD = "spool.test"

/**
 * A ticket config verifying [signingKey]'s tickets, and [nextSigningKey]'s as `SPOOL_TICKET_KEY_NEXT`,
 * for [audience] and, as `SPOOL_TICKET_AUDIENCE_ALIAS`, [audienceAlias].
 * The per-pseudonym TURN rate is off unless a test asks, so the per-connection TURN tests keep
 * measuring the bucket they are about.
 */
fun testTicketConfig(
    audience: String = TEST_TICKET_AUD,
    gates: Long = Tickets.LEGACY_GATES,
    attachBytes: Long = SpoolServer.TicketConfig.DEFAULT_ATTACH_BYTES,
    signingKey: ByteArray = TEST_TICKET_SIGNING_KEY,
    nextSigningKey: ByteArray? = null,
    turnRatePer10Min: Int = 0,
    audienceAlias: String? = null,
): SpoolServer.TicketConfig {
    val public = Tickets.publicHalf(signingKey)
    return SpoolServer.TicketConfig(
        key = Tickets.publicKey(public),
        kid = Tickets.kid(public),
        audience = audience,
        audienceAlias = audienceAlias,
        gates = gates,
        attachBytes = attachBytes,
        next =
            nextSigningKey?.let {
                val nextPublic = Tickets.publicHalf(it)
                SpoolServer.TicketKey(Tickets.publicKey(nextPublic), Tickets.kid(nextPublic))
            },
        turnRatePer10Min = turnRatePer10Min,
    )
}

/**
 * A signed `ent` good from a minute before [nowMs] for a day, under the test key. Every claim can be
 * bent, so a test states only the one it is about.
 */
fun testEnt(
    nowMs: Long,
    q: Long = 9L,
    ps: ByteArray = ByteArray(Tickets.PS_BYTES) { 0x11 },
    fl: Long = Tickets.FL_TURN,
    aud: String = TEST_TICKET_AUD,
    nbfS: Long = nowMs / 1_000 - 60,
    expS: Long = nowMs / 1_000 + 86_400,
    signingKey: ByteArray = TEST_TICKET_SIGNING_KEY,
    ab: Long? = null,
    am: Long? = null,
): Ent {
    val (tk, sig) =
        Tickets.issue(
            signingKey,
            Ticket.forKey(signingKey, aud = aud, nbf = nbfS, exp = expS, ps = ps, fl = fl, ab = ab, am = am),
        )
    return Ent(t = RecordType.ENT, q = q, tk = tk, sig = sig)
}

class TestServer(
    val spool: SpoolServer,
    val store: ScopeStore,
    val clock: FakeClock,
    val port: Int,
    val http: HttpClient,
) {
    /** [forwardedFor] is sent as `X-Forwarded-For`, which the server honours only with `trustProxy`. */
    suspend fun connect(
        token: String? = null,
        forwardedFor: String? = null,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) {
        val url = "ws://127.0.0.1:$port/spool/v1" + token?.let { "?k=$it" }.orEmpty()
        http.webSocket(url, request = { forwardedFor?.let { header(HttpHeaders.XForwardedFor, it) } }) { block() }
    }
}

/** Boots a real server on an ephemeral port, hands the test a client, tears both down. */
fun withServer(
    config: SpoolServer.Config = testConfig(),
    clock: FakeClock = FakeClock(),
    store: ScopeStore = InMemoryScopeStore(config.hardLimits),
    clientTableCap: Int = 16_384,
    powCacheCap: Int = 4_096,
    block: suspend TestServer.() -> Unit,
) = runBlocking {
    val spool = SpoolServer(config, store, clock::now, clientTableCap, powCacheCap)
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

/** A single-chunk `aput` (idx 0 of 1) — the smallest attachment record a test can send. */
fun aput(
    q: Long,
    scope: ByteArray,
    aid: ByteArray,
    cid: ByteArray,
    data: ByteArray,
): Aput =
    Aput(
        t = RecordType.APUT,
        q = q,
        scope = scope,
        aid = aid,
        idx = 0,
        total = 1,
        cid = cid,
        data = data,
    )

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
