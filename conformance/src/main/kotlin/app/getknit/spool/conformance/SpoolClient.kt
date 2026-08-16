// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/**
 * A thin conformance-side wrapper over the ktor CIO WebSocket client: one [connect] per check
 * connection, one CBOR record per binary frame (spec §7.1), every receive fenced by the run's
 * timeout.
 */
class SpoolClient(
    private val httpClient: HttpClient,
    private val url: String,
    private val timeoutMs: Long,
) {
    /** Opens a WebSocket to the spool, runs [block] against a fresh [Session], then closes it. */
    suspend fun <T> connect(block: suspend Session.() -> T): T {
        val ws =
            try {
                withTimeout(timeoutMs) { httpClient.webSocketSession(url) }
            } catch (e: TimeoutCancellationException) {
                throw CheckFailure("could not open a WebSocket within $timeoutMs ms")
            }
        try {
            return Session(ws = ws, timeoutMs = timeoutMs).block()
        } finally {
            withTimeoutOrNull(1_000) {
                runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL.code, "done")) }
            }
            ws.cancel()
        }
    }
}

/** One live spool connection: typed send/expect helpers over binary CBOR frames. */
class Session(
    val ws: DefaultClientWebSocketSession,
    val timeoutMs: Long,
) {
    private var q = 0L

    /** The next request correlation id (spec §7.1: monotonically increasing per connection). */
    fun nextQ(): Long = ++q

    /** Encodes [record] and sends it as one binary frame. */
    suspend inline fun <reified T> send(record: T) {
        sendRaw(RecordCodec.encode(record))
    }

    suspend fun sendRaw(bytes: ByteArray) {
        ws.send(Frame.Binary(true, bytes))
    }

    /** The next binary frame's bytes, or [TimeoutException] after the run's timeout. */
    suspend fun receiveBytes(): ByteArray =
        try {
            withTimeout(timeoutMs) { nextBinary() }
        } catch (e: TimeoutCancellationException) {
            throw TimeoutException("timed out after $timeoutMs ms waiting for a record")
        }

    /** The next binary frame within [waitMs], or null on timeout (for must-NOT-arrive probes). */
    suspend fun receiveBytesOrNull(waitMs: Long): ByteArray? = withTimeoutOrNull(waitMs) { nextBinary() }

    /**
     * Receives until a record whose `t` equals [type] arrives and decodes it as [T]. Server-initiated
     * `digest`/`event` records are skipped unless that is what is expected; anything else fails the
     * check with an expected-vs-got message (an unexpected `err` includes its code).
     */
    suspend inline fun <reified T> expect(type: String): T {
        while (true) {
            val bytes =
                try {
                    receiveBytes()
                } catch (e: TimeoutException) {
                    throw CheckFailure("expected '$type' record, got timeout after $timeoutMs ms")
                }
            val t = RecordCodec.peekType(bytes)
            when {
                t == type -> {
                    return RecordCodec.decode<T>(bytes)
                        ?: throw CheckFailure("expected '$type' record, got one that does not decode")
                }

                t == RecordType.DIGEST || t == RecordType.EVENT -> {
                    // Server-initiated records — skip and keep waiting.
                }

                t == RecordType.ERR -> {
                    val err = RecordCodec.decode<Err>(bytes)
                    throw CheckFailure("expected '$type' record, got err code=${err?.code} msg=${err?.msg}")
                }

                else -> {
                    throw CheckFailure("expected '$type' record, got '${t ?: "undecodable record"}'")
                }
            }
        }
    }

    /** Expects an `err` record and asserts its [code]. */
    suspend fun expectErr(code: String): Err {
        val err = expect<Err>(RecordType.ERR)
        ensure(err.code == code) { "expected err code=$code, got code=${err.code} (msg=${err.msg})" }
        return err
    }

    /** Drains frames until the server closes, then asserts the close [code]. */
    suspend fun expectClose(code: Int) {
        val reason =
            try {
                withTimeout(timeoutMs) {
                    while (ws.incoming.receiveCatching().getOrNull() != null) {
                        // Drain: tolerate any records the server sends before closing.
                    }
                    ws.closeReason.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw CheckFailure("expected close $code, got no close within $timeoutMs ms")
            }
        if (reason == null) throw CheckFailure("expected close $code, got connection end without a close frame")
        val got = reason.code.toInt()
        ensure(got == code) { "expected close $code, got close $got (${reason.message})" }
    }

    /** Receives digests until one for [scope] arrives (other server-initiated records are skipped). */
    suspend fun expectDigestFor(scope: ByteArray): Digest {
        while (true) {
            val digest = expect<Digest>(RecordType.DIGEST)
            if (digest.scope.contentEquals(scope)) return digest
        }
    }

    /** Reads the spool's unprompted hello — it arrives before the client sends anything (§7.1). */
    suspend fun readServerHello(): Hello {
        val bytes =
            try {
                receiveBytes()
            } catch (e: TimeoutException) {
                throw CheckFailure("expected server hello first, got no record within $timeoutMs ms")
            }
        val t = RecordCodec.peekType(bytes)
        ensure(t == RecordType.HELLO) { "expected server hello first, got '${t ?: "undecodable record"}'" }
        return RecordCodec.decode<Hello>(bytes)
            ?: throw CheckFailure("expected a decodable server hello, got one that does not decode")
    }

    /** Full handshake: reads the server hello, answers with a client hello at [v], returns the server's. */
    suspend fun hello(v: Int = RECORD_VERSION): Hello {
        val server = readServerHello()
        send(Hello(t = RecordType.HELLO, v = v))
        return server
    }

    private suspend fun nextBinary(): ByteArray {
        while (true) {
            val frame =
                try {
                    ws.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    val reason = withTimeoutOrNull(1_000) { ws.closeReason.await() }
                    val detail = reason?.let { "close ${it.code} ${it.message}" } ?: "no close frame"
                    throw CheckFailure("connection closed while awaiting a record ($detail)")
                }
            if (frame is Frame.Binary) return frame.readBytes()
        }
    }
}
