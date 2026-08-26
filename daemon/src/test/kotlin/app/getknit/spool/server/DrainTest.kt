// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drain mode: the door closes, the room stays open. What a rolling upgrade needs between "serving"
 * and "stopped", since [SpoolServer.stop] closes every session at once.
 */
class DrainTest {
    @Test
    fun drainingRefusesTheUpgradeWith503AndRetryAfter() {
        withServer {
            spool.setDraining(true)
            val response = http.get("http://127.0.0.1:$port/spool/v1")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertNotNull(response.headers[HttpHeaders.RetryAfter])
        }
    }

    /** A planned drain and a box out of room are the same 503 to a client and different news here. */
    @Test
    fun drainRefusalsAreCountedApartFromCapacityRefusals() {
        withServer {
            spool.setDraining(true)
            http.get("http://127.0.0.1:$port/spool/v1")
            assertEquals(1L, spool.metrics.drainRefusedTotal.sum())
            assertEquals(0L, spool.metrics.connsRefusedTotal.sum())
        }
    }

    /**
     * The container HEALTHCHECK, both CI pipelines and compose's `service_healthy` gate all probe
     * `/healthz`. A drain that failed it would restart the container mid-drain.
     */
    @Test
    fun drainingLeavesHealthzAndMetricsAnswering() {
        withServer {
            spool.setDraining(true)
            http.get("http://127.0.0.1:$port/spool/v1")
            assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/healthz").status)
            val metrics = http.get("http://127.0.0.1:$port/metrics")
            assertEquals(HttpStatusCode.OK, metrics.status)
            assertTrue(metrics.bodyAsText().contains("knit_spool_drain_refused_total 1"), metrics.bodyAsText())
        }
    }

    /** The point of draining rather than stopping: live connections keep working. */
    @Test
    fun anOpenConnectionSurvivesADrainAndTheDoorReopens() {
        withServer {
            connect {
                helloHandshake()
                spool.toggleDrain()
                assertEquals(
                    HttpStatusCode.ServiceUnavailable,
                    http.get("http://127.0.0.1:$port/spool/v1").status,
                )
                // Still fully served: subscribe and push on the connection that was already open.
                subscribe(testScope(1))
                val (id, data) = testBlob(1)
                sendRecord(
                    app.getknit.spool.protocol.Push(
                        t = app.getknit.spool.protocol.RecordType.PUSH,
                        q = 1L,
                        scope = testScope(1),
                        blobId = id,
                        data = data,
                    ),
                )
                expectRecord<app.getknit.spool.protocol.Ok>(app.getknit.spool.protocol.RecordType.OK)
            }
            spool.toggleDrain()
            connect { helloHandshake() }
        }
    }

    /** A drained spool that reads as an ordinary idle one in `docker logs -f` is the trap. */
    @Test
    fun theStatusLineSaysSoWhileDrainingAndNotOtherwise() {
        withServer {
            val quiet = statusLine().render(now = 0L, scopes = 0, liveBytes = 0L, draining = false)
            assertTrue(!quiet.contains("draining"), quiet)
            val drained = statusLine().render(now = 0L, scopes = 0, liveBytes = 0L, draining = true)
            assertTrue(drained.startsWith("draining=yes "), drained)
        }
    }

    private fun statusLine() =
        StatusLine(
            metrics = Metrics(),
            maxScopes = 64,
            maxBytes = 0L,
            startedAtMs = 0L,
            heap = { HeapUse(used = 0L, max = 1L) },
        )
}
