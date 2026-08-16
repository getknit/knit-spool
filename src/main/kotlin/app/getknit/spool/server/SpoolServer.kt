// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

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
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.Sub
import app.getknit.spool.store.InMemoryScopeStore
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The spool daemon, SPOOL_PROTOCOL.md §6–§8: one WebSocket route at `/spool/v1`, one CBOR record
 * per binary message, hello negotiation first in both directions, then sub/list/pull/push against
 * the scope store with live `event` fan-out to the scope's other subscribers.
 *
 * Skeleton state (tracked in the README): in-memory storage only, no per-IP rate limits, no
 * global storage watermark, TLS left to a fronting reverse proxy.
 */
class SpoolServer(
    private val config: Config,
    private val store: InMemoryScopeStore = InMemoryScopeStore(config.hardLimits),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Config(
        val port: Int,
        val token: String?,
        val powBits: Int,
        val maxRecord: Int,
        val maxPull: Int,
        val hardLimits: InMemoryScopeStore.HardLimits,
    )

    private val log = LoggerFactory.getLogger(SpoolServer::class.java)

    /** scope hex → outbound channels of its live subscribers (event fan-out targets). */
    private val subscribers = ConcurrentHashMap<String, MutableSet<SendChannel<Frame>>>()

    /** Accepted PoW cache: "scopeHex:day" (spec §8 — one stamp per scope per day). */
    private val powAccepted = ConcurrentHashMap.newKeySet<String>()

    fun start(wait: Boolean = true): EmbeddedServer<*, *> {
        val server =
            embeddedServer(CIO, port = config.port) {
                install(WebSockets)
                routing {
                    webSocket("/spool/v1") {
                        if (config.token != null && !constantTimeEquals(call.request.queryParameters["k"], config.token)) {
                            close(CloseReason(CloseCode.AUTH.toShort(), "auth"))
                            return@webSocket
                        }
                        serveConnection(this)
                    }
                }
            }
        log.info("knit-spool listening on :{} (pow={} bits, token={})", config.port, config.powBits, config.token != null)
        return server.start(wait = wait)
    }

    private suspend fun serveConnection(session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        val out = session.outgoing
        out.send(binary(RecordCodec.encode(serverHello())))
        val mySubscriptions = mutableSetOf<String>()
        try {
            var helloed = false
            for (frame in session.incoming) {
                val bytes = (frame as? Frame.Binary)?.readBytes() ?: continue
                if (bytes.size > config.maxRecord) {
                    out.send(binary(RecordCodec.encode(err(ErrCode.TOO_LARGE))))
                    continue
                }
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
                when (RecordCodec.peekType(bytes)) {
                    RecordType.SUB -> RecordCodec.decode<Sub>(bytes)?.let { handleSub(it, out, mySubscriptions) }
                    RecordType.LIST -> RecordCodec.decode<ScopeList>(bytes)?.let { handleList(it, out, mySubscriptions) }
                    RecordType.PULL -> RecordCodec.decode<Pull>(bytes)?.let { handlePull(it, out, mySubscriptions) }
                    RecordType.PUSH -> RecordCodec.decode<Push>(bytes)?.let { handlePush(it, out, mySubscriptions) }
                    null -> out.send(binary(RecordCodec.encode(err(ErrCode.MALFORMED))))
                    else -> Unit // unknown t: additive evolution — skip
                }
            }
        } finally {
            mySubscriptions.forEach { subscribers[it]?.remove(out) }
        }
    }

    private suspend fun handleSub(
        sub: Sub,
        out: SendChannel<Frame>,
        mySubscriptions: MutableSet<String>,
    ) {
        val now = clock()
        for (scopeSub in sub.subs) {
            val scopeHex = hex(scopeSub.scope)
            if (scopeHex !in mySubscriptions && store.isUnknownScope(scopeSub.scope)) {
                if (store.scopeCount() >= config.hardLimits.maxScopes) {
                    out.send(binary(RecordCodec.encode(err(ErrCode.QUOTA, q = sub.q, scope = scopeSub.scope))))
                    continue
                }
                if (!powGate(scopeSub.scope, scopeSub.pow?.d, scopeSub.pow?.n, now)) {
                    out.send(binary(RecordCodec.encode(err(ErrCode.POW, q = sub.q, scope = scopeSub.scope))))
                    continue
                }
            }
            val info = store.subscribe(scopeSub.scope, scopeSub.bounds, now)
            mySubscriptions.add(scopeHex)
            subscribers.getOrPut(scopeHex) { ConcurrentHashMap.newKeySet() }.add(out)
            out.send(binary(RecordCodec.encode(digestRecord(scopeSub.scope, info))))
        }
    }

    private suspend fun handleList(
        list: ScopeList,
        out: SendChannel<Frame>,
        mySubscriptions: MutableSet<String>,
    ) {
        if (!requireSub(list.scope, list.q, out, mySubscriptions)) return
        val info = store.list(list.scope, clock()) ?: return
        out.send(
            binary(
                RecordCodec.encode(
                    ScopeList(
                        t = RecordType.LIST,
                        q = list.q,
                        scope = list.scope,
                        blobIds = info.blobIds,
                        tombstones = info.tombstones,
                    ),
                ),
            ),
        )
    }

    private suspend fun handlePull(
        pull: Pull,
        out: SendChannel<Frame>,
        mySubscriptions: MutableSet<String>,
    ) {
        if (!requireSub(pull.scope, pull.q, out, mySubscriptions)) return
        val wanted = pull.blobIds.take(config.maxPull)
        val served = store.pull(pull.scope, wanted, clock())
        val servedHex = served.map { hex(it.first) }.toSet()
        for ((blobId, data) in served) {
            out.send(binary(RecordCodec.encode(Blob(t = RecordType.BLOB, scope = pull.scope, blobId = blobId, data = data))))
        }
        val missing = wanted.filter { hex(it) !in servedHex }
        out.send(
            binary(
                RecordCodec.encode(
                    Ok(t = RecordType.OK, q = pull.q, missing = missing.ifEmpty { null }),
                ),
            ),
        )
    }

    private suspend fun handlePush(
        push: Push,
        out: SendChannel<Frame>,
        mySubscriptions: MutableSet<String>,
    ) {
        if (!requireSub(push.scope, push.q, out, mySubscriptions)) return
        when (store.push(push.scope, push.blobId, push.data, clock())) {
            is InMemoryScopeStore.PushResult.Stored, InMemoryScopeStore.PushResult.Duplicate -> {
                out.send(binary(RecordCodec.encode(Ok(t = RecordType.OK, q = push.q))))
                fanOut(push, out)
            }
            InMemoryScopeStore.PushResult.Tombstoned ->
                out.send(binary(RecordCodec.encode(err(ErrCode.TOMBSTONED, q = push.q, scope = push.scope))))
            InMemoryScopeStore.PushResult.TooLarge ->
                out.send(binary(RecordCodec.encode(err(ErrCode.TOO_LARGE, q = push.q, scope = push.scope))))
            InMemoryScopeStore.PushResult.BadId ->
                out.send(binary(RecordCodec.encode(err(ErrCode.BAD_ID, q = push.q, scope = push.scope))))
        }
    }

    private fun fanOut(
        push: Push,
        uploader: SendChannel<Frame>,
    ) {
        val event = RecordCodec.encode(Event(t = RecordType.EVENT, scope = push.scope, blobId = push.blobId, data = push.data))
        subscribers[hex(push.scope)]?.forEach { subscriber ->
            if (subscriber !== uploader) {
                // Best-effort (spec §7.2): a slow consumer heals via anti-entropy on reconnect.
                subscriber.trySend(binary(event))
            }
        }
    }

    private suspend fun requireSub(
        scope: ByteArray,
        q: Long,
        out: SendChannel<Frame>,
        mySubscriptions: MutableSet<String>,
    ): Boolean {
        if (hex(scope) in mySubscriptions) return true
        out.send(binary(RecordCodec.encode(err(ErrCode.NOT_SUBSCRIBED, q = q, scope = scope))))
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
        if (cacheKey in powAccepted) return true
        if (!Pow.dayInWindow(day, now)) return false
        if (!Pow.verify(scope, day, n, config.powBits)) return false
        powAccepted.add(cacheKey)
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
                ),
            powBits = config.powBits,
        )

    private fun digestRecord(
        scope: ByteArray,
        info: InMemoryScopeStore.DigestInfo,
    ): Digest =
        Digest(
            t = RecordType.DIGEST,
            scope = scope,
            digest = ScopeDigest.toBytes(info.digest),
            count = info.count,
            full = info.full,
            bounds = info.bounds,
        )

    private fun err(
        code: String,
        q: Long? = null,
        scope: ByteArray? = null,
    ): Err = Err(t = RecordType.ERR, code = code, q = q, scope = scope)

    private fun binary(bytes: ByteArray): Frame.Binary = Frame.Binary(true, bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

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
