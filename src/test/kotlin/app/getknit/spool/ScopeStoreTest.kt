// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.store.InMemoryScopeStore
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Store behavior per SPOOL_PROTOCOL.md §6.1–§6.2: digest folding, blobId verification,
 * oldest-by-arrivedAt eviction into tombstones, tombstone refusal, TTL expiry, bounds clamping.
 */
class ScopeStoreTest {
    private val limits =
        InMemoryScopeStore.HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 8)
    private val bounds = ScopeBounds(maxFrames = 3, ttlMs = 10_000L, maxBlob = 1_024)
    private val scope = ByteArray(32) { 1 }

    private fun blob(seed: Int): Pair<ByteArray, ByteArray> {
        val data = ByteArray(40) { ((it * 7 + seed) and 0xFF).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(data) to data
    }

    @Test
    fun pushFoldsTheDigestAndDuplicatesAreIdempotent() {
        val store = InMemoryScopeStore(limits)
        store.subscribe(scope, bounds, now = 0L)
        val (id, data) = blob(1)

        val stored = store.push(scope, id, data, now = 1L)

        assertIs<InMemoryScopeStore.PushResult.Stored>(stored)
        assertEquals(ScopeDigest.fnv64(id), stored.digest.digest)
        assertIs<InMemoryScopeStore.PushResult.Duplicate>(store.push(scope, id, data, now = 2L))
        assertEquals(1, store.digest(scope, now = 2L)?.count)
    }

    @Test
    fun badBlobIdIsRefused() {
        val store = InMemoryScopeStore(limits)
        store.subscribe(scope, bounds, now = 0L)
        val (_, data) = blob(1)

        assertIs<InMemoryScopeStore.PushResult.BadId>(store.push(scope, ByteArray(32), data, now = 1L))
        assertEquals(0, store.digest(scope, now = 1L)?.count)
    }

    @Test
    fun overflowEvictsOldestIntoTombstonesAndRefusesRePush() {
        val store = InMemoryScopeStore(limits)
        store.subscribe(scope, bounds, now = 0L)
        val blobs = (1..4).map { blob(it) }
        blobs.forEachIndexed { i, (id, data) -> store.push(scope, id, data, now = i.toLong()) }

        val list = store.list(scope, now = 5L)!!
        assertEquals(3, list.blobIds.size)
        assertTrue(list.tombstones.single().contentEquals(blobs.first().first))
        assertIs<InMemoryScopeStore.PushResult.Tombstoned>(
            store.push(scope, blobs.first().first, blobs.first().second, now = 6L),
        )
        assertTrue(store.digest(scope, now = 6L)!!.full)
    }

    @Test
    fun ttlExpiryTombstonesAndTombstonesThemselvesExpire() {
        val store = InMemoryScopeStore(limits)
        store.subscribe(scope, bounds, now = 0L)
        val (id, data) = blob(1)
        store.push(scope, id, data, now = 0L)

        assertEquals(0, store.digest(scope, now = 20_000L)?.count)
        assertEquals(1, store.list(scope, now = 20_000L)?.tombstones?.size)
        assertEquals(0, store.list(scope, now = 40_000L)?.tombstones?.size)
        assertEquals(0L, store.digest(scope, now = 40_000L)?.digest)
    }

    @Test
    fun subscribeClampsDeclaredBoundsToTheHardCaps() {
        val store = InMemoryScopeStore(limits)

        val info = store.subscribe(scope, ScopeBounds(maxFrames = 10_000, ttlMs = Long.MAX_VALUE, maxBlob = 1 shl 30), now = 0L)

        assertEquals(limits.maxFramesCap, info.bounds.maxFrames)
        assertEquals(limits.maxTtlMs, info.bounds.ttlMs)
        assertEquals(limits.maxBlob, info.bounds.maxBlob)
    }
}
