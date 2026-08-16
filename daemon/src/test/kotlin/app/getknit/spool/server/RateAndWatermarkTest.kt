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
import kotlin.test.Test
import kotlin.test.assertEquals
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
