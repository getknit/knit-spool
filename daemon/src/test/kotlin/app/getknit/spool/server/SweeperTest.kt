// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pow
import app.getknit.spool.protocol.PowStamp
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The periodic sweeper: TTL expiry re-anchors, PoW-cache pruning. Driven via sweepTick(). */
class SweeperTest {
    @Test
    fun expiryBroadcastsAFreshDigest() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1)) // ttlMs = 10_000
                val (id, data) = testBlob(1)
                sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                expectRecord<Ok>(RecordType.OK)

                clock.advance(20_000)
                spool.sweepTick()

                val digest = expectRecord<Digest>(RecordType.DIGEST)
                assertEquals(0, digest.count)
                assertTrue(digest.digest.contentEquals(ByteArray(8)))
            }
        }
    }

    @Test
    fun powCacheIsPrunedAfterDayRollover() {
        val bits = 8
        withServer(testConfig(powBits = bits)) {
            val scope = testScope(1)
            val day = Pow.utcDay(clock.now)
            val staleStamp = PowStamp(n = Pow.stamp(scope, day, bits)!!, d = day)
            connect {
                helloHandshake()
                subscribe(scope, pow = staleStamp)
            }
            store.shedOldestScope()
            clock.advance(3 * Pow.DAY_MS)
            spool.sweepTick()
            connect {
                helloHandshake()
                // Without pruning, the cached (scope, day) pair would bypass the day window.
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 1L,
                        subs = listOf(ScopeSub(scope = scope, bounds = testBounds(), pow = staleStamp)),
                    ),
                )
                assertEquals(ErrCode.POW, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }
}
