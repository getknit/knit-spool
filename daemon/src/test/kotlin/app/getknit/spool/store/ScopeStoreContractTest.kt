// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [ScopeStore] contract per SPOOL_PROTOCOL.md §6.1–§6.2, run against every backend: digest
 * folding, blobId verification, oldest-by-arrivedAt eviction into tombstones, tombstone refusal
 * and cap, TTL expiry, bounds clamping, the atomic scope quota, byte accounting, sweeping, and
 * watermark shedding.
 */
abstract class ScopeStoreContractTest {
    protected val limits =
        HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 8)
    private val bounds = ScopeBounds(maxFrames = 3, ttlMs = 10_000L, maxBlob = 1_024)
    private val scope = ByteArray(32) { 1 }

    protected abstract fun createStore(): ScopeStore

    private fun blob(seed: Int): Pair<ByteArray, ByteArray> {
        val data = ByteArray(40) { ((it * 7 + seed) and 0xFF).toByte() }
        // Stamp the full seed so blobs stay unique past 256 (the tombstone-cap test needs >1024).
        data[0] = (seed ushr 24).toByte()
        data[1] = (seed ushr 16).toByte()
        data[2] = (seed ushr 8).toByte()
        data[3] = seed.toByte()
        return MessageDigest.getInstance("SHA-256").digest(data) to data
    }

    private fun ScopeStore.subscribed(
        scopeId: ByteArray,
        declared: ScopeBounds = bounds,
        now: Long = 0L,
    ): DigestInfo = assertIs<SubscribeResult.Subscribed>(subscribe(scopeId, declared, now)).digest

    @Test
    fun pushFoldsTheDigestAndDuplicatesAreIdempotent() {
        createStore().use { store ->
            store.subscribed(scope)
            val (id, data) = blob(1)

            val stored = store.push(scope, id, data, now = 1L)

            assertIs<PushResult.Stored>(stored)
            assertEquals(ScopeDigest.fnv64(id), stored.digest.digest)
            assertEquals(false, stored.evictedOrExpired)
            assertIs<PushResult.Duplicate>(store.push(scope, id, data, now = 2L))
            assertEquals(1, store.digest(scope, now = 2L)?.count)
        }
    }

    @Test
    fun badBlobIdIsRefused() {
        createStore().use { store ->
            store.subscribed(scope)
            val (_, data) = blob(1)

            assertIs<PushResult.BadId>(store.push(scope, ByteArray(32), data, now = 1L))
            assertEquals(0, store.digest(scope, now = 1L)?.count)
        }
    }

    @Test
    fun overflowEvictsOldestIntoTombstonesAndRefusesRePush() {
        createStore().use { store ->
            store.subscribed(scope)
            val blobs = (1..4).map { blob(it) }
            val results = blobs.mapIndexed { i, (id, data) -> store.push(scope, id, data, now = i.toLong()) }

            assertTrue(assertIs<PushResult.Stored>(results.last()).evictedOrExpired)
            val list = store.list(scope, now = 5L)!!
            assertEquals(3, list.blobIds.size)
            assertTrue(list.tombstones.single().contentEquals(blobs.first().first))
            assertIs<PushResult.Tombstoned>(
                store.push(scope, blobs.first().first, blobs.first().second, now = 6L),
            )
            assertTrue(store.digest(scope, now = 6L)!!.full)
        }
    }

    @Test
    fun ttlExpiryTombstonesAndTombstonesThemselvesExpire() {
        createStore().use { store ->
            store.subscribed(scope)
            val (id, data) = blob(1)
            store.push(scope, id, data, now = 0L)

            assertEquals(0, store.digest(scope, now = 20_000L)?.count)
            assertEquals(1, store.list(scope, now = 20_000L)?.tombstones?.size)
            assertEquals(0, store.list(scope, now = 40_000L)?.tombstones?.size)
            assertEquals(0L, store.digest(scope, now = 40_000L)?.digest)
        }
    }

    @Test
    fun subscribeClampsDeclaredBoundsToTheHardCaps() {
        createStore().use { store ->
            val info =
                store.subscribed(
                    scope,
                    ScopeBounds(maxFrames = 10_000, ttlMs = Long.MAX_VALUE, maxBlob = 1 shl 30),
                )

            assertEquals(limits.maxFramesCap, info.bounds.maxFrames)
            assertEquals(limits.maxTtlMs, info.bounds.ttlMs)
            assertEquals(limits.maxBlob, info.bounds.maxBlob)
        }
    }

    @Test
    fun subscribeEnforcesTheScopeQuotaAtomically() {
        createStore().use { store ->
            repeat(limits.maxScopes) { i ->
                store.subscribed(ByteArray(32) { (i + 10).toByte() })
            }

            assertIs<SubscribeResult.QuotaExceeded>(store.subscribe(ByteArray(32) { 99 }, bounds, now = 0L))
            // Re-subscribing a known scope is never quota-gated.
            store.subscribed(ByteArray(32) { 10 })
            assertEquals(limits.maxScopes, store.scopeCount())
        }
    }

    @Test
    fun sweepExpiresAndReportsChangedScopes() {
        createStore().use { store ->
            val quiet = ByteArray(32) { 7 }
            store.subscribed(scope)
            store.subscribed(quiet)
            val (id, data) = blob(1)
            store.push(scope, id, data, now = 0L)

            assertTrue(store.sweep(now = 1L).isEmpty())
            val changes = store.sweep(now = 20_000L)
            assertEquals(1, changes.size)
            assertTrue(changes.single().scopeId.contentEquals(scope))
            assertEquals(0, changes.single().digest.count)
            assertTrue(store.sweep(now = 20_001L).isEmpty())
        }
    }

    @Test
    fun byteAccountingTracksPushEvictionAndExpiry() {
        createStore().use { store ->
            store.subscribed(scope)
            assertEquals(0L, store.totalBytes())
            val blobs = (1..4).map { blob(it) }
            blobs.forEachIndexed { i, (id, data) -> store.push(scope, id, data, now = i.toLong()) }

            // Four 40-byte pushes, one evicted by maxFrames=3.
            assertEquals(120L, store.totalBytes())
            store.sweep(now = 20_000L)
            assertEquals(0L, store.totalBytes())
        }
    }

    @Test
    fun shedOldestScopeDropsTheLeastRecentlyActive() {
        createStore().use { store ->
            val old = ByteArray(32) { 2 }
            val fresh = ByteArray(32) { 3 }
            store.subscribed(old, now = 0L)
            store.subscribed(fresh, now = 0L)
            val (id, data) = blob(1)
            store.push(old, id, data, now = 0L)
            store.push(fresh, id, data, now = 100L) // fresh is more recently active

            val shed = store.shedOldestScope()!!
            assertTrue(shed.scopeId.contentEquals(old))
            assertEquals(40L, shed.freedBytes)
            assertTrue(store.isUnknownScope(old))
            assertEquals(40L, store.totalBytes())
            store.shedOldestScope()
            assertNull(store.shedOldestScope())
            assertEquals(0L, store.totalBytes())
        }
    }

    @Test
    fun tombstonesAreCountBounded() {
        createStore().use { store ->
            val tight = ScopeBounds(maxFrames = 1, ttlMs = limits.maxTtlMs, maxBlob = 1_024)
            store.subscribed(scope, tight)
            val cap = ScopeStore.tombstoneCap(tight)
            // Enough evictions to overflow the cap by five.
            repeat(cap + 6) { i ->
                val (id, data) = blob(i)
                assertIs<PushResult.Stored>(store.push(scope, id, data, now = i.toLong()))
            }

            assertEquals(cap, store.list(scope, now = (cap + 10).toLong())!!.tombstones.size)
        }
    }
}
