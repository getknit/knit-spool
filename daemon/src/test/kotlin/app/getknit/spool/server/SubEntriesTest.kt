// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.store.HardLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `sub` is bounded and metered by its entries. More entries than `maxScopes`, or the same scope
 * twice, is a malformed record refused whole before any token or store hop; every entry past the
 * first spends a record token, so a record's cost on the store thread is its entry count and not
 * one; and a record refused for rate — by either bucket — strikes the abuse window once, not once
 * per entry. Finding F3 of the 2026-09-13 security review.
 */
class SubEntriesTest {
    private val roomy = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 32)

    private fun subOf(
        q: Long,
        scopes: List<ByteArray>,
    ): Sub = Sub(t = RecordType.SUB, q = q, subs = scopes.map { ScopeSub(scope = it, bounds = testBounds()) })

    private fun assertMalformed(
        err: Err,
        q: Long,
    ) {
        assertEquals(ErrCode.MALFORMED, err.code)
        assertEquals(q, err.q)
        assertNull(err.scope, "a whole-record refusal names no scope")
    }

    private fun assertRate(
        err: Err,
        q: Long,
        scope: ByteArray,
    ) {
        assertEquals(ErrCode.RATE, err.code)
        assertEquals(q, err.q)
        assertTrue(err.scope!!.contentEquals(scope), "err rate must name the refused scope")
        assertTrue(err.retryMs!! > 0)
    }

    @Test
    fun subNamingMoreScopesThanMaxScopesIsRefusedWhole() {
        withServer {
            // hardLimits.maxScopes = 4
            connect {
                helloHandshake()
                sendRecord(subOf(q = 1L, scopes = (1..5).map(::testScope)))
                assertMalformed(expectRecord<Err>(RecordType.ERR), q = 1L)
                assertEquals(0, store.scopeCount(), "a refused record subscribes nothing")

                // Not even the first entry: the record was refused whole.
                sendRecord(ScopeList(t = RecordType.LIST, q = 2L, scope = testScope(1)))
                assertEquals(ErrCode.NOT_SUBSCRIBED, expectRecord<Err>(RecordType.ERR).code)

                // Exactly maxScopes is the bound, not one under it, and the connection still works.
                sendRecord(subOf(q = 3L, scopes = (1..4).map(::testScope)))
                repeat(4) { expectRecord<Digest>(RecordType.DIGEST) }
                assertEquals(4, store.scopeCount())
            }
        }
    }

    @Test
    fun subNamingAScopeTwiceIsRefusedWhole() {
        withServer {
            connect {
                helloHandshake()
                sendRecord(subOf(q = 1L, scopes = listOf(testScope(1), testScope(2), testScope(1))))
                assertMalformed(expectRecord<Err>(RecordType.ERR), q = 1L)
                assertEquals(0, store.scopeCount())
                assertTrue(store.isUnknownScope(testScope(2)), "the entry between the duplicates must not slip through")

                subscribe(testScope(1), q = 2L)
            }
        }
    }

    @Test
    fun everySubEntryPastTheFirstSpendsARecordToken() {
        withServer(testConfig(hardLimits = roomy, rateRecords = 1)) {
            // Burst 4, and the fake clock never refills: the record's own token pays for the first
            // entry, three more pay for the next three, and the remaining eight find the bucket dry.
            connect {
                helloHandshake()
                sendRecord(subOf(q = 1L, scopes = (1..12).map(::testScope)))
                (1..4).forEach { assertTrue(expectRecord<Digest>(RecordType.DIGEST).scope.contentEquals(testScope(it))) }
                (5..12).forEach { assertRate(expectRecord<Err>(RecordType.ERR), q = 1L, scope = testScope(it)) }
                assertEquals(8L, spool.metrics.rateLimitedTotal.sum())
                assertEquals(4, store.scopeCount())
                (5..12).forEach { assertTrue(store.isUnknownScope(testScope(it)), "a refused entry must not reach the store") }

                // Eight refusals were one strike, not eight: the connection is open, and four
                // seconds refill the bucket to its burst, which is exactly a four-entry sub.
                clock.advance(4_000L)
                sendRecord(subOf(q = 2L, scopes = (5..8).map(::testScope)))
                (5..8).forEach { assertTrue(expectRecord<Digest>(RecordType.DIGEST).scope.contentEquals(testScope(it))) }
                assertEquals(8, store.scopeCount())
            }
        }
    }

    @Test
    fun aSubRefusedByTheNewScopeBucketStrikesOnce() {
        withServer(testConfig(hardLimits = roomy, rateNewScopesPerMin = 1)) {
            // Burst 4 new scopes per address. Twelve in one record: four created, eight refused
            // `rate` — which under a strike per entry would have closed the connection 4003 at the
            // eighth, one record into a session that had never been told to slow down.
            connect {
                helloHandshake()
                sendRecord(subOf(q = 1L, scopes = (1..12).map(::testScope)))
                (1..4).forEach { assertTrue(expectRecord<Digest>(RecordType.DIGEST).scope.contentEquals(testScope(it))) }
                (5..12).forEach { assertRate(expectRecord<Err>(RecordType.ERR), q = 1L, scope = testScope(it)) }
                assertEquals(4, store.scopeCount())

                clock.advance(60_000L)
                assertEquals(0, subscribe(testScope(5), q = 2L).count)
                assertEquals(5, store.scopeCount())
            }
        }
    }
}
