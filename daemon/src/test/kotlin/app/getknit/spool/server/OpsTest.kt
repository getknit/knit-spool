// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    @Test
    fun metricsAreTokenGatedOnPrivateSpools() {
        withServer(testConfig(token = "s3cret")) {
            assertEquals(HttpStatusCode.Forbidden, http.get("http://127.0.0.1:$port/metrics").status)
            assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/metrics?k=s3cret").status)
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
