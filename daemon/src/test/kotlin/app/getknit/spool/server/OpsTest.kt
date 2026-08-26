// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The operator surface: /healthz, /metrics, graceful shutdown. */
class OpsTest {
    @Test
    fun healthzReturns200() {
        withServer {
            val response = http.get("http://127.0.0.1:$port/healthz")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("ok"))
        }
    }

    @Test
    fun metricsExposeCounters() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val (id, data) = testBlob(1)
                sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                expectRecord<Ok>(RecordType.OK)
            }
            val body = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
            assertTrue(body.contains("knit_spool_pushes_total 1"))
            assertTrue(body.contains("knit_spool_scopes_current 1"))
            assertTrue(body.contains("knit_spool_connections_total 1"))
        }
    }

    /**
     * The point of the egress counter is that it measures what *leaves*, which fan-out makes a
     * multiple of what arrives — so a single 1 KiB push to three subscribers must move the counter
     * by ~3 KiB, not ~1 KiB. An implementation that counted at the ingest side, or once per push
     * rather than once per recipient, passes every other metrics assertion and fails this one.
     */
    @Test
    fun egressCountsFanOutAmplificationNotIngest() {
        withServer {
            val data = ByteArray(1_000) { it.toByte() }
            val id = MessageDigest.getInstance("SHA-256").digest(data)
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val uploader = this
                connect {
                    helloHandshake()
                    subscribe(testScope(1))
                    val subA = this
                    connect {
                        helloHandshake()
                        subscribe(testScope(1))
                        val subB = this
                        connect {
                            helloHandshake()
                            subscribe(testScope(1))
                            val subC = this
                            val before = egressBytes()
                            with(uploader) {
                                sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                                expectRecord<Ok>(RecordType.OK)
                            }
                            // Drain all three copies so every event is certainly counted.
                            for (subscriber in listOf(subA, subB, subC)) {
                                with(subscriber) { expectRecord<Event>(RecordType.EVENT) }
                            }
                            val delta = egressBytes() - before
                            assertTrue(
                                delta >= 3 * data.size,
                                "egress $delta should cover three ${data.size}-byte event copies",
                            )
                            assertTrue(
                                delta < 3 * data.size + 600,
                                "egress $delta is far above three copies plus envelopes — overcounting?",
                            )
                        }
                    }
                }
            }
        }
    }

    /** Reads the counter back out of the rendered exposition, so the render path is covered too. */
    private suspend fun TestServer.egressBytes(): Long =
        http
            .get("http://127.0.0.1:$port/metrics")
            .bodyAsText()
            .lineSequence()
            .first { it.startsWith("knit_spool_egress_bytes_total ") }
            .substringAfterLast(' ')
            .toLong()

    @Test
    fun metricsAreTokenGatedOnPrivateSpools() {
        withServer(testConfig(token = "s3cret")) {
            assertEquals(HttpStatusCode.Forbidden, http.get("http://127.0.0.1:$port/metrics").status)
            assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/metrics?k=s3cret").status)
        }
    }

    /**
     * The scrape credential replaces the connect credential rather than joining it — the whole point
     * of the split is that a client holding SPOOL_TOKEN cannot read the spool's traffic shape.
     */
    @Test
    fun aMetricsTokenReplacesTheSpoolTokenOnMetrics() {
        withServer(testConfig(token = "s3cret", metricsToken = "scrape")) {
            assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/metrics?k=scrape").status)
            assertEquals(HttpStatusCode.Forbidden, http.get("http://127.0.0.1:$port/metrics?k=s3cret").status)
            assertEquals(HttpStatusCode.Forbidden, http.get("http://127.0.0.1:$port/metrics").status)
        }
    }

    /** A public spool can gate its metrics without becoming private: the two are independent. */
    @Test
    fun aMetricsTokenGatesAnOtherwisePublicSpool() {
        withServer(testConfig(metricsToken = "scrape")) {
            assertEquals(HttpStatusCode.Forbidden, http.get("http://127.0.0.1:$port/metrics").status)
            assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/metrics?k=scrape").status)
            // Still public on the wire — the metrics token is not a bearer token for /spool/v1.
            connect { helloHandshake() }
        }
    }

    @Test
    fun gracefulShutdownCloses1001() {
        withServer {
            connect {
                helloHandshake()
                launch(Dispatchers.IO) { spool.stop() }
                awaitClose(
                    CloseReason.Codes.GOING_AWAY.code
                        .toInt(),
                )
            }
        }
    }
}
