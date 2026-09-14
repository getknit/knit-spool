// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.store.HardLimits
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-client table is bounded: at the cap an accept sheds every idle entry, and an entry with
 * a live connection is never shed. Addresses arrive as `X-Forwarded-For` under `trustProxy`, the
 * one way a test on loopback can be many clients.
 */
class ClientTableTest {
    private fun manyScopes() = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 64)

    @Test
    fun anAcceptAtTheCapShedsEveryIdleEntry() {
        withServer(testConfig(trustProxy = true), clientTableCap = 2) {
            connect(forwardedFor = "10.0.0.1") { helloHandshake() }
            connect(forwardedFor = "10.0.0.2") { helloHandshake() }
            assertEquals(2, spool.clientTableSize())
            // The third address finds the table full: both idle entries go, and it is the one left.
            connect(forwardedFor = "10.0.0.3") {
                helloHandshake()
                assertEquals(1, spool.clientTableSize())
            }
        }
    }

    @Test
    fun aDrainedBucketIsKeptUnderTheCapAndForgottenAtIt() {
        // burst 4 new scopes per address; the fake clock never refills
        withServer(testConfig(trustProxy = true, rateNewScopesPerMin = 1, hardLimits = manyScopes()), clientTableCap = 2) {
            connect(forwardedFor = "10.0.0.1") {
                helloHandshake()
                repeat(4) { subscribe(testScope(it + 1), q = it + 1L) }
                sendRecord(Sub(t = RecordType.SUB, q = 5L, subs = listOf(ScopeSub(scope = testScope(5), bounds = testBounds()))))
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
            }
            // Back before anything shed it: the bucket is still dry.
            connect(forwardedFor = "10.0.0.1") {
                helloHandshake()
                sendRecord(Sub(t = RecordType.SUB, q = 1L, subs = listOf(ScopeSub(scope = testScope(5), bounds = testBounds()))))
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
            }
            // Two more addresses fill the table to the cap and the third sheds it, 10.0.0.1 included.
            connect(forwardedFor = "10.0.0.2") { helloHandshake() }
            connect(forwardedFor = "10.0.0.3") { helloHandshake() }
            connect(forwardedFor = "10.0.0.1") {
                helloHandshake()
                subscribe(testScope(5), q = 1L)
            }
        }
    }

    @Test
    fun anEntryWithALiveConnectionSurvivesTheCap() {
        withServer(testConfig(trustProxy = true, maxConnsPerIp = 1), clientTableCap = 1) {
            connect(forwardedFor = "10.0.0.1") {
                helloHandshake()
                // Every one of these accepts finds the table at the cap and prunes; the live
                // entry stays, so the per-address cap still counts the connection it holds.
                connect(forwardedFor = "10.0.0.2") { helloHandshake() }
                connect(forwardedFor = "10.0.0.3") { helloHandshake() }
                connect(forwardedFor = "10.0.0.1") { awaitClose(CloseCode.ABUSE) }
            }
        }
    }
}
