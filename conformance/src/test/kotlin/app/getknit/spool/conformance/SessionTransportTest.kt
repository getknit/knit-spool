// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * A spool that resets the connection under a client still writing (`rate-limit`'s 4003 mid-blast
 * is the everyday case) must reach a check as a [TransportFailure]. ktor reports the failed write
 * as a `CancellationException` ("Failed to send frame"), and every catch in the suite rethrows
 * cancellation, so before this the reset ended the whole run instead of the one check. The other
 * direction holds too: a cancellation that is the caller's own still reaches the caller as one.
 */
class SessionTransportTest {
    @Test
    fun `a reset under a send is a transport failure, not a cancellation`() {
        resetting().use { server ->
            val failure =
                connect(server) {
                    assertFailsWith<TransportFailure> {
                        // The first sends land in the socket buffer; one after the RST fails.
                        repeat(SENDS) {
                            sendRaw(ByteArray(FRAME_BYTES))
                            delay(SEND_GAP_MS)
                        }
                    }
                }
            assertContains(failure.message.orEmpty(), "connection died while sending")
        }
    }

    @Test
    fun `a reset under a receive is a transport failure, not a cancellation`() {
        resetting().use { server ->
            connect(server) { assertFailsWith<TransportFailure> { receiveBytes() } }
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

    private fun <T> connect(
        server: WsTestServer,
        timeoutMs: Long = TIMEOUT_MS,
        block: suspend Session.() -> T,
    ): T =
        runBlocking {
            val http = HttpClient(CIO) { install(WebSockets) }
            try {
                SpoolClient(http, server.url, timeoutMs).connect(block)
            } finally {
                http.close()
            }
        }

    /** Accepts the upgrade, then resets the connection after [RESET_AFTER_MS]. */
    private fun resetting() =
        WsTestServer { _, conn ->
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
    }
}
