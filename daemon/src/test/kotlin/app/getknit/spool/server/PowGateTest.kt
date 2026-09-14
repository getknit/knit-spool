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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The scope-creation PoW gate per spec §6.4/§8, including the shed-scope PUSH- and SUB-recreate paths. */
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
    fun aFullStampCacheVerifiesAgainInsteadOfGrowing() {
        withServer(testConfig(powBits = bits), powCacheCap = 1) {
            val day = Pow.utcDay(clock.now)
            val a = testScope(1)
            val b = testScope(2)
            connect {
                helloHandshake()
                subscribe(a, pow = stampFor(a, day), q = 1L)
                subscribe(b, pow = stampFor(b, day), q = 2L)
            }
            assertEquals(2L, spool.metrics.powVerifiedTotal.sum())
            // A took the one slot; B's stamp was verified and then not cached.
            store.shedOldestScope()
            connect {
                helloHandshake()
                subscribe(a, pow = stampFor(a, day))
            }
            assertEquals(2L, spool.metrics.powVerifiedTotal.sum(), "a cached stamp passes without a hash")
            store.shedOldestScope()
            connect {
                helloHandshake()
                subscribe(b, pow = stampFor(b, day))
            }
            assertEquals(3L, spool.metrics.powVerifiedTotal.sum(), "an uncached stamp is hashed again, and passes")
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

    @Test
    fun subToAShedScopeDemandsPowAndRecreates() {
        withServer(testConfig(powBits = bits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                val day = Pow.utcDay(clock.now)
                subscribe(scope, pow = stampFor(scope, day))
                store.shedOldestScope()
                assertTrue(store.isUnknownScope(scope))

                // The connection still holds its sub; the store no longer knows the scope. A re-sub
                // is the "first SUB for an unknown scope id" of S-6.4-2, whatever this connection
                // remembers — and without a stamp it is refused, not quietly recreated.
                sendRecord(Sub(t = RecordType.SUB, q = 2L, subs = listOf(ScopeSub(scope = scope, bounds = testBounds()))))
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.POW, err.code)
                assertEquals(2L, err.q)
                assertTrue(err.scope!!.contentEquals(scope))
                assertTrue(store.isUnknownScope(scope), "a refused re-sub must not recreate the scope")

                // With the stamp the sub recreates it, through the (scope, day) cache: no second verify.
                assertEquals(0, subscribe(scope, q = 3L, pow = stampFor(scope, day)).count)
                assertFalse(store.isUnknownScope(scope))
                assertEquals(1L, spool.metrics.powVerifiedTotal.sum())
            }
        }
    }

    @Test
    fun reSubscribingAKnownScopeSkipsTheGates() {
        withServer(testConfig(powBits = bits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope, pow = stampFor(scope, Pow.utcDay(clock.now)))
                // A bounds refresh on a scope the store still holds is not a creation: no stamp needed.
                assertEquals(0, subscribe(scope, q = 2L).count)
                assertEquals(1L, spool.metrics.powVerifiedTotal.sum())
            }
        }
    }
}
