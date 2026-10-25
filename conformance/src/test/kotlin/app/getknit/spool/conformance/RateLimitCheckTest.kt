// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Sub
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `rate-limit` against a scripted spool. The first blast is unbounded, so a far spool with a
 * generous budget still trips; a spool may strike that blast out and reset the connection before
 * its `err rate` is read, and then the check repeats on a fresh connection with
 * [RATE_BLAST_WINDOW] pushes unanswered at most. The self-test against the real daemon met that
 * reset once in CI and lost the whole run to it.
 */
class RateLimitCheckTest {
    @Test
    fun `a blast that trips the budget is judged on its own connection`() {
        ScriptedSpool(strikeOutFirst = false).use { spool ->
            runRateLimit(spool.server)
            assertEquals(1, spool.server.connections, "no second blast after an err rate was read")
        }
    }

    @Test
    fun `a blast struck out before its err rate is read is repeated within the window`() {
        ScriptedSpool(strikeOutFirst = true).use { spool ->
            runRateLimit(spool.server)
            assertEquals(2, spool.server.connections, "the struck-out blast is repeated once")
            assertTrue(spool.windowHeld, "the second blast left more than $WINDOW pushes unanswered")
        }
    }

    private fun runRateLimit(server: WsTestServer) =
        runBlocking {
            val http = HttpClient(CIO) { install(WebSockets) }
            try {
                val client = SpoolClient(http, server.url, TIMEOUT_MS)
                val ctx =
                    Ctx(
                        client = client,
                        bareClient = client,
                        serverHello = spoolHello(),
                        timeoutMs = TIMEOUT_MS,
                        powLimit = 0,
                        hasToken = false,
                    )
                // Passing is not throwing: a CheckFailure here is the advisory the self-test rejects.
                allChecks().single { it.name == "rate-limit" }.run(ctx)
            } finally {
                http.close()
            }
        }

    /**
     * A spool whose push budget is [BUDGET]: pushes past it are answered `err rate`. With
     * [strikeOutFirst], the first connection is reset after [STRUCK_OUT_AFTER] pushes with no
     * `err rate` sent, and the second answers nothing until [WINDOW] pushes are in and the client has
     * stopped there, then answers the last of them `err rate`.
     */
    private class ScriptedSpool(
        strikeOutFirst: Boolean,
    ) : AutoCloseable {
        @Volatile
        var windowHeld = false

        val server =
            WsTestServer { number, conn ->
                conn.sendBinary(RecordCodec.encode(spoolHello()))
                conn.receiveBinary() ?: return@WsTestServer // the client's hello
                val sub = RecordCodec.decode<Sub>(conn.receiveBinary() ?: return@WsTestServer)!!.subs.single()
                conn.sendBinary(
                    RecordCodec.encode(
                        Digest(
                            t = RecordType.DIGEST,
                            scope = sub.scope,
                            digest = ByteArray(8),
                            count = 0,
                            full = false,
                            bounds = sub.bounds,
                        ),
                    ),
                )
                if (strikeOutFirst && number == 2) {
                    val held = List(WINDOW) { RecordCodec.decode<Push>(conn.receiveBinary()!!)!! }
                    windowHeld = conn.quietFor(QUIET_MS)
                    held.dropLast(1).forEach { conn.sendBinary(RecordCodec.encode(Ok(t = RecordType.OK, q = it.q))) }
                    conn.sendBinary(RecordCodec.encode(rateErr(held.last())))
                    conn.awaitEnd()
                    return@WsTestServer
                }
                var pushes = 0
                while (true) {
                    val push = RecordCodec.decode<Push>(conn.receiveBinary() ?: return@WsTestServer) ?: continue
                    pushes++
                    if (strikeOutFirst && pushes == STRUCK_OUT_AFTER) {
                        conn.reset()
                        return@WsTestServer
                    }
                    val reply =
                        if (pushes <=
                            BUDGET
                        ) {
                            RecordCodec.encode(Ok(t = RecordType.OK, q = push.q))
                        } else {
                            RecordCodec.encode(rateErr(push))
                        }
                    conn.sendBinary(reply)
                }
            }

        override fun close() = server.close()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val BUDGET = 40
        const val STRUCK_OUT_AFTER = 10
        const val QUIET_MS = 300L
        const val RETRY_MS = 250L

        /** The check's own window, `RATE_BLAST_WINDOW`, which is private to it. */
        const val WINDOW = 4

        fun spoolHello() = Hello(t = RecordType.HELLO, v = RECORD_VERSION)

        fun rateErr(push: Push) = Err(t = RecordType.ERR, code = ErrCode.RATE, q = push.q, scope = push.scope, retryMs = RETRY_MS)
    }
}
