// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The runner's TLS client survives the suite's own pattern: one `wss://` connection per check,
 * opened and closed back to back. ktor's CIO TLS client did not (KTOR-9974, #6). It writes
 * `close_notify` concurrently with a connection's last record, which corrupts kotlinx-io's
 * process-wide segment pool, and the next connection to open fails inside its handshake. On
 * loopback this test reproduces that: when it was written, CIO faulted 8 of its 800 connections,
 * with the same exceptions the lab spool's runs reported, and the Java engine none of 4,800.
 */
class TlsCycleTest {
    @Test
    fun `wss connections open and close back to back without a fault`() {
        WsTestServer(TestTls.server) { _, conn ->
            conn.sendBinary(RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION)))
            conn.receiveBinary() ?: return@WsTestServer // the client's hello
            conn.receiveBinary() // null at the client's close
            conn.sendClose(NORMAL_CLOSE)
        }.use { server ->
            val faults = ConcurrentLinkedQueue<String>()
            runBlocking {
                val http = conformanceHttpClient(TestTls.client)
                try {
                    val client = SpoolClient(http, server.url, TIMEOUT_MS)
                    coroutineScope {
                        repeat(PARALLEL) {
                            launch(Dispatchers.Default) {
                                repeat(CONNECTIONS / PARALLEL) {
                                    try {
                                        client.connect { hello() }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        faults += describeFailure(e)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    http.close()
                }
            }
            assertTrue(faults.isEmpty(), "${faults.size} of $CONNECTIONS connections faulted:\n${faults.take(5).joinToString("\n")}")
        }
    }

    private companion object {
        const val CONNECTIONS = 800
        const val PARALLEL = 8
        const val TIMEOUT_MS = 5_000L
        const val NORMAL_CLOSE = 1000
    }
}
