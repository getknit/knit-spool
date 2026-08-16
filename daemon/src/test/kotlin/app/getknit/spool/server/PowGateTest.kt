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

/** The scope-creation PoW gate per spec §6.4/§8, including the shed-scope PUSH-recreate path. */
class PowGateTest {
    private val bits = 8

    private fun stampFor(
        scope: ByteArray,
        day: Long,
    ): PowStamp = PowStamp(n = Pow.stamp(scope, day, bits)!!, d = day)

    @Test
    fun unknownScopeWithoutStampErrsPow() {
        withServer(testConfig(powBits = bits)) {
            connect {
                helloHandshake()
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 1L,
                        subs = listOf(ScopeSub(scope = testScope(1), bounds = testBounds())),
                    ),
                )
                assertEquals(ErrCode.POW, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }

    @Test
    fun validStampAccepted() {
        withServer(testConfig(powBits = bits)) {
            connect {
                helloHandshake()
                val day = Pow.utcDay(clock.now)
                subscribe(testScope(1), pow = stampFor(testScope(1), day))
            }
        }
    }

    @Test
    fun acceptedStampIsCachedPerScopeAndDay() {
        withServer(testConfig(powBits = bits)) {
            val scope = testScope(1)
            val day = Pow.utcDay(clock.now)
            val stamp = stampFor(scope, day)
            connect {
                helloHandshake()
                subscribe(scope, pow = stamp)
            }
            store.shedOldestScope()
            connect {
                helloHandshake()
                subscribe(scope, pow = stamp)
            }
            // The second acceptance came from the (scopeId, day) cache, not a fresh verification.
            assertEquals(1L, spool.metrics.powVerifiedTotal.sum())
        }
    }

    @Test
    fun dayOutsideTheWindowIsRejected() {
        withServer(testConfig(powBits = bits)) {
            connect {
                helloHandshake()
                val staleDay = Pow.utcDay(clock.now) - 2
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 1L,
                        subs =
                            listOf(
                                ScopeSub(
                                    scope = testScope(1),
                                    bounds = testBounds(),
                                    pow = stampFor(testScope(1), staleDay),
                                ),
                            ),
                    ),
                )
                assertEquals(ErrCode.POW, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }

    @Test
    fun pushToAShedScopeDemandsPowAndRecreates() {
        withServer(testConfig(powBits = bits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                val day = Pow.utcDay(clock.now)
                subscribe(scope, pow = stampFor(scope, day))
                val (id, data) = testBlob(1)
                sendRecord(Push(t = RecordType.PUSH, q = 2L, scope = scope, blobId = id, data = data))
                expectRecord<Ok>(RecordType.OK)

                store.shedOldestScope()
                // The connection still holds its sub; the store no longer knows the scope.
                sendRecord(Push(t = RecordType.PUSH, q = 3L, scope = scope, blobId = id, data = data))
                assertEquals(ErrCode.POW, expectRecord<Err>(RecordType.ERR).code)

                // With the stamp the push recreates the scope: digest re-anchor, then the ack.
                sendRecord(
                    Push(t = RecordType.PUSH, q = 4L, scope = scope, blobId = id, data = data, pow = stampFor(scope, day)),
                )
                assertEquals(0, expectRecord<Digest>(RecordType.DIGEST).count)
                assertEquals(4L, expectRecord<Ok>(RecordType.OK).q)
            }
        }
    }
}
