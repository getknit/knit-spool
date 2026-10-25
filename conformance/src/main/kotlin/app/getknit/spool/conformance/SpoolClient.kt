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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw TransportFailure("could not open a WebSocket within $timeoutMs ms")
            } catch (e: Exception) {
                // The endpoint went away mid-run. That is not the spool failing the spec.
                currentCoroutineContext().ensureActive()
                throw TransportFailure("could not open a WebSocket: ${describeFailure(e)}")
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

/**
 * The [TransportFailure] for a connection that died under a send or a receive, unless [e] is this
 * coroutine's own cancellation, which it rethrows.
 *
 * A `CancellationException` is not always one. ktor cancels a session's channels when its socket
 * dies: a failed write cancels `outgoing` with "Failed to send frame", so the next send throws a
 * cancellation whose cause is the I/O error. Every catch in the suite rethrows cancellation, so
 * one let through ends the whole run instead of the one check. A spool that resets a client still
 * writing, as a 4003 mid-blast does, is the everyday case.
 */
private suspend fun transportDied(
    doing: String,
    e: CancellationException,
): TransportFailure {
    currentCoroutineContext().ensureActive()
    return TransportFailure("connection died while $doing: ${describeFailure(e)}")
}

/**
 * Close codes RFC 6455 §7.4.1 reserves and forbids on the wire: 1005 "No Status Rcvd" and 1006
 * "Abnormal Closure". A client that reports one of these is telling you it never received a close
 * frame at all, so they mark a dead transport rather than anything the spool did.
 */
private val SYNTHETIC_CLOSE_CODES = setOf<Short>(1005, 1006)

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
        sendFrame(Frame.Binary(true, bytes))
    }

    /** Sends [frame] as it is, for the checks that send what is not a record. */
    suspend fun sendFrame(frame: Frame) {
        try {
            ws.send(frame)
        } catch (e: CancellationException) {
            throw transportDied("sending a record", e)
        } catch (_: ClosedSendChannelException) {
            throw TransportFailure("connection closed while sending a record")
        }
    }

    /** The next binary frame's bytes, or [TimeoutException] after the run's timeout. */
    suspend fun receiveBytes(): ByteArray =
        try {
            withTimeout(timeoutMs) { nextBinary() }
        } catch (_: TimeoutCancellationException) {
            // An outer timeout is the caller's, not this receive's: it must reach the caller as one.
            currentCoroutineContext().ensureActive()
            throw TimeoutException("timed out after $timeoutMs ms waiting for a record")
        }

    /** The next binary frame within [waitMs], or null on timeout (for must-NOT-arrive probes). */
    suspend fun receiveBytesOrNull(waitMs: Long): ByteArray? = withTimeoutOrNull(waitMs) { nextBinary() }

    /**
     * Receives until a record whose `t` equals [type] arrives and decodes it as [T]. Server-initiated
     * `digest`/`event`/`cast` records are skipped unless that is what is expected; anything else fails the
     * check with an expected-vs-got message (an unexpected `err` includes its code).
     */
    suspend inline fun <reified T> expect(type: String): T {
        while (true) {
            val bytes =
                try {
                    receiveBytes()
                } catch (_: TimeoutException) {
                    throw CheckFailure("expected '$type' record, got timeout after $timeoutMs ms")
                }
            val t = RecordCodec.peekType(bytes)
            when {
                t == type -> {
                    return RecordCodec.decode<T>(bytes)
                        ?: throw CheckFailure("expected '$type' record, got one that does not decode")
                }

                t == RecordType.DIGEST || t == RecordType.EVENT || t == RecordType.CAST -> {
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
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
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
            } catch (_: TimeoutException) {
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
                } catch (_: ClosedReceiveChannelException) {
                    val reason =
                        withTimeoutOrNull(1_000) { ws.closeReason.await() }
                            ?: throw TransportFailure("connection died while awaiting a record (no close frame)")
                    // 1005/1006 are reserved by RFC 6455 §7.4.1 and MUST NOT be sent in a close
                    // frame, so the client synthesised them: the connection dropped without the
                    // spool ever saying why. Only a code the spool actually sent judges the spool.
                    if (reason.code in SYNTHETIC_CLOSE_CODES) {
                        throw TransportFailure(
                            "connection died while awaiting a record (close ${reason.code}, no close frame sent)",
                        )
                    }
                    throw CheckFailure(
                        "connection closed while awaiting a record (close ${reason.code} ${reason.message})",
                    )
                } catch (e: CancellationException) {
                    // receiveBytes' own timeout lands here too; transportDied rethrows it as the
                    // cancellation it is.
                    throw transportDied("awaiting a record", e)
                }
            if (frame is Frame.Binary) return frame.readBytes()
        }
    }
}
