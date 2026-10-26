// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * A spool that resets the connection under a client still writing (`rate-limit`'s 4003 mid-blast
 * is the everyday case) must reach a check as a [TransportFailure]. ktor reports the failed write
 * as a `CancellationException` ("Failed to send frame"), and every catch in the suite rethrows
 * cancellation, so before this the reset ended the whole run instead of the one check. The other
 * direction holds too: a cancellation that is the caller's own still reaches the caller as one.
 * The resets run over `wss://` as well, since that is the transport a remote spool is reached on.
 */
class SessionTransportTest {
    @Test
    fun `a reset under a send is a transport failure, not a cancellation`() = resetUnderSend(tls = null)

    @Test
    fun `a reset under a send over wss is a transport failure`() = resetUnderSend(tls = TestTls.server)

    @Test
    fun `a reset under a receive is a transport failure, not a cancellation`() = resetUnderReceive(tls = null)

    @Test
    fun `a reset under a receive over wss is a transport failure`() = resetUnderReceive(tls = TestTls.server)

    @Test
    fun `a close the spool sends is the close expectClose reads`() {
        WsTestServer { _, conn ->
            conn.sendClose(CLOSE_CODE)
            conn.awaitEnd()
        }.use { server ->
            connect(server) { expectClose(CLOSE_CODE) }
        }
    }

    /**
     * A dead send must not read as a close the spool sent. After a reset under a send, ktor's
     * session completes `closeReason` with 1000 or 1006 depending on which side of it notices
     * first, and before this `expectClose` charged the spool with whichever it was.
     */
    @Test
    fun `a send that died is not read as the spool's close`() {
        resetting().use { server ->
            connect(server) {
                assertFailsWith<TransportFailure> { blast() }
                assertFailsWith<TransportFailure> { expectClose(CLOSE_CODE) }
            }
        }
    }

    @Test
    fun `the run's own timeout is still a TimeoutException`() {
        silent().use { server ->
            connect(server, timeoutMs = SHORT_TIMEOUT_MS) { assertFailsWith<TimeoutException> { receiveBytes() } }
        }
    }

    @Test
    fun `an outer timeout still reaches the caller as one`() {
        silent().use { server ->
            connect(server) {
                assertFailsWith<TimeoutCancellationException> { withTimeout(SHORT_TIMEOUT_MS) { receiveBytes() } }
            }
        }
    }

    private fun resetUnderSend(tls: SSLContext?) {
        resetting(tls).use { server ->
            val failure = connect(server) { assertFailsWith<TransportFailure> { blast() } }
            assertContains(failure.message.orEmpty(), "connection died while sending")
        }
    }

    private fun resetUnderReceive(tls: SSLContext?) {
        resetting(tls).use { server ->
            connect(server) { assertFailsWith<TransportFailure> { receiveBytes() } }
        }
    }

    /** Sends until a send fails: the first land in the socket buffer, and one after the RST fails. */
    private suspend fun Session.blast() {
        repeat(SENDS) {
            sendRaw(ByteArray(FRAME_BYTES))
            delay(SEND_GAP_MS)
        }
    }

    private fun <T> connect(
        server: WsTestServer,
        timeoutMs: Long = TIMEOUT_MS,
        block: suspend Session.() -> T,
    ): T =
        runBlocking {
            val http = conformanceHttpClient(TestTls.client.takeIf { server.url.startsWith("wss:") })
            try {
                SpoolClient(http, server.url, timeoutMs).connect(block)
            } finally {
                http.close()
            }
        }

    /** Accepts the upgrade, then resets the connection after [RESET_AFTER_MS]. */
    private fun resetting(tls: SSLContext? = null) =
        WsTestServer(tls) { _, conn ->
            Thread.sleep(RESET_AFTER_MS)
            conn.reset()
        }

    /** Accepts the upgrade and then says nothing. */
    private fun silent() = WsTestServer { _, conn -> conn.awaitEnd() }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 300L
        const val RESET_AFTER_MS = 200L
        const val SENDS = 200
        const val SEND_GAP_MS = 10L
        const val FRAME_BYTES = 4_096
        const val CLOSE_CODE = 4003
    }
}
