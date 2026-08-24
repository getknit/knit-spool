// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.store.HardLimits
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Per-connection/per-IP rate limits and the global storage watermark (spec §6.4). */
class RateAndWatermarkTest {
    @Test
    fun recordFloodErrsRateWithRetryMs() {
        withServer(testConfig(rateRecords = 2)) {
            // burst 8; the fake clock never refills
            connect {
                helloHandshake()
                repeat(9) { sendRecord(Ok(t = "x-future", q = it.toLong())) }
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.RATE, err.code)
                assertTrue(err.retryMs!! > 0)
            }
        }
    }

    @Test
    fun sustainedFloodCloses4003() {
        withServer(testConfig(rateRecords = 2)) {
            connect {
                helloHandshake()
                repeat(16) { sendRecord(Ok(t = "x-future", q = it.toLong())) }
                awaitClose(CloseCode.ABUSE)
            }
        }
    }

    @Test
    fun perIpConnectionCapCloses4003() {
        withServer(testConfig(maxConnsPerIp = 1)) {
            connect {
                helloHandshake()
                connect {
                    awaitClose(CloseCode.ABUSE)
                }
            }
        }
    }

    @Test
    fun globalConnectionCapRefusesTheUpgradeWith503() {
        withServer(testConfig(maxConns = 1)) {
            connect {
                helloHandshake()
                val response = http.get("http://127.0.0.1:$port/spool/v1")
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                // Retry-After is the whole point: a client told "later" goes to its other spools
                // instead of re-dialing this one immediately.
                assertNotNull(response.headers[HttpHeaders.RetryAfter])
                assertEquals(1L, spool.metrics.connsRefusedTotal.sum())
            }
            // The slot is released when the holder leaves, so the next client is served.
            connect { helloHandshake() }
        }
    }

    @Test
    fun capacityRefusalLeavesHealthzAndMetricsAnswering() {
        withServer(testConfig(maxConns = 1)) {
            connect {
                helloHandshake()
                // The ops surface must survive a full spool: /healthz is what the image's
                // HEALTHCHECK polls, and 503 there would restart the container at its busiest
                // moment — turning a spool that is merely full into a spool that is gone.
                assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/healthz").status)
                val metrics = http.get("http://127.0.0.1:$port/metrics")
                assertEquals(HttpStatusCode.OK, metrics.status)
                assertTrue(metrics.bodyAsText().contains("knit_spool_max_conns 1"))
            }
        }
    }

    @Test
    fun connectionCapOfZeroIsUnlimited() {
        withServer(testConfig(maxConns = 0)) {
            connect {
                helloHandshake()
                connect {
                    helloHandshake()
                    assertEquals(0L, spool.metrics.connsRefusedTotal.sum())
                }
            }
        }
    }

    @Test
    fun newScopeCreationBucketErrsRate() {
        val limits = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 100)
        withServer(testConfig(hardLimits = limits, rateNewScopesPerMin = 1)) {
            // burst 4
            connect {
                helloHandshake()
                (1..4).forEach { subscribe(testScope(it), q = it.toLong()) }
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 5L,
                        subs = listOf(ScopeSub(scope = testScope(5), bounds = testBounds())),
                    ),
                )
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.RATE, err.code)
                assertTrue(err.retryMs!! > 0)
            }
        }
    }

    @Test
    fun watermarkShedsTheOldestScopeAndBroadcastsAnEmptyDigest() {
        withServer(testConfig(maxBytes = 100L)) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                (1..2).forEach { seed ->
                    val (id, data) = testBlob(seed) // 40 bytes each
                    sendRecord(Push(t = RecordType.PUSH, q = seed.toLong(), scope = testScope(1), blobId = id, data = data))
                    expectRecord<Ok>(RecordType.OK)
                }
                clock.advance(1_000)
                val anchored = this
                connect {
                    helloHandshake()
                    subscribe(testScope(2))
                    val (id, data) = testBlob(3)
                    sendRecord(Push(t = RecordType.PUSH, q = 3L, scope = testScope(2), blobId = id, data = data))
                    expectRecord<Ok>(RecordType.OK) // 120 bytes total > 100: scope 1 is shed
                }
                val digest = anchored.expectRecord<app.getknit.spool.protocol.Digest>(RecordType.DIGEST)
                assertTrue(digest.scope.contentEquals(testScope(1)))
                assertEquals(0, digest.count)
                assertTrue(digest.digest.contentEquals(ByteArray(8)))
                assertTrue(store.isUnknownScope(testScope(1)))
                assertEquals(40L, store.totalBytes())
            }
        }
    }
}
