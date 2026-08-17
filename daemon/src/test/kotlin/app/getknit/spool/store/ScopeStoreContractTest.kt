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
 * and cap, TTL expiry, bounds clamping, the atomic scope quota, byte accounting, sweeping,
 * watermark shedding, and the §6.5 attachment table (presence bitmap, first-write-wins, whole-
 * attachment byte eviction, tombstones, and the deliberate absence of any digest effect).
 */
abstract class ScopeStoreContractTest {
    protected val limits =
        HardLimits(
            maxBlob = 1_024,
            maxFramesCap = 100,
            maxTtlMs = 86_400_000L,
            maxScopes = 8,
            // Attachments on, with a tiny budget so the quota path is reachable in a test.
            maxAttachBytes = 250,
            maxAChunk = 128,
        )
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

    // --- Attachments, spec §6.5 ---

    private fun chunk(seed: Int): Pair<ByteArray, ByteArray> {
        val data = ByteArray(100) { ((it * 11 + seed) and 0xFF).toByte() }
        data[0] = seed.toByte()
        return MessageDigest.getInstance("SHA-256").digest(data) to data
    }

    private val aid = ByteArray(32) { 9 }

    @Test
    fun attachmentChunksStoreAndReadBackWithAPresenceBitmap() {
        createStore().use { store ->
            store.subscribed(scope)
            val (cid0, data0) = chunk(0)
            val (cid2, data2) = chunk(2)

            assertIs<AputResult.Stored>(store.attachmentPut(scope, aid, 0, 3, cid0, data0, now = 1L))
            assertIs<AputResult.Stored>(store.attachmentPut(scope, aid, 2, 3, cid2, data2, now = 2L))

            val info = store.attachmentPresence(scope, aid, now = 3L)
            assertEquals(3, info.total)
            assertEquals(false, info.dead)
            // Chunks 0 and 2 present, 1 absent: MSB-first bit i of byte i/8 ⇒ 0b1010_0000.
            assertEquals(1, info.bits.size)
            assertEquals(0xA0, info.bits[0].toInt() and 0xFF)

            val got = store.attachmentGet(scope, aid, from = 0, n = 3, now = 3L)
            assertEquals(listOf(0, 2), got.map { it.idx })
            assertEquals(3, got.first().total)
            assertTrue(got.first().data.contentEquals(data0))
            assertTrue(got.first().cid.contentEquals(cid0))
        }
    }

    @Test
    fun attachmentsAreAbsentUntilWrittenAndNeverTouchTheDigest() {
        createStore().use { store ->
            val before = store.subscribed(scope)
            val absent = store.attachmentPresence(scope, aid, now = 1L)
            assertEquals(0, absent.total)
            assertEquals(false, absent.dead)
            assertEquals(emptyList(), store.attachmentGet(scope, aid, from = 0, n = 4, now = 1L))

            val (cid, data) = chunk(0)
            store.attachmentPut(scope, aid, 0, 1, cid, data, now = 2L)

            // The whole point of §6.5: a stored attachment moves bytes but not the frame digest.
            val after = store.digest(scope, now = 2L)!!
            assertEquals(before.digest, after.digest)
            assertEquals(0, after.count)
            assertEquals(100L, store.totalBytes())
        }
    }

    @Test
    fun firstWriteWinsAtAPositionAndTotalMustAgree() {
        createStore().use { store ->
            store.subscribed(scope)
            val (cid0, data0) = chunk(0)
            val (cid1, data1) = chunk(1)
            assertIs<AputResult.Stored>(store.attachmentPut(scope, aid, 0, 2, cid0, data0, now = 1L))

            assertIs<AputResult.Duplicate>(store.attachmentPut(scope, aid, 0, 2, cid0, data0, now = 2L))
            assertIs<AputResult.Conflict>(store.attachmentPut(scope, aid, 0, 2, cid1, data1, now = 2L))
            assertIs<AputResult.Conflict>(store.attachmentPut(scope, aid, 1, 5, cid1, data1, now = 2L))
            assertIs<AputResult.Conflict>(store.attachmentPut(scope, aid, 2, 2, cid1, data1, now = 2L))
            assertIs<AputResult.Stored>(store.attachmentPut(scope, aid, 1, 2, cid1, data1, now = 2L))
        }
    }

    @Test
    fun attachmentPutVerifiesTheContentAddressAndTheChunkCap() {
        createStore().use { store ->
            store.subscribed(scope)
            val (cid, data) = chunk(0)

            assertIs<AputResult.BadId>(store.attachmentPut(scope, aid, 0, 1, ByteArray(32), data, now = 1L))
            assertIs<AputResult.TooLarge>(
                store.attachmentPut(scope, aid, 0, 1, cid, ByteArray(limits.maxAChunk + 1), now = 1L),
            )
            assertEquals(0, store.attachmentPresence(scope, aid, now = 1L).total)
        }
    }

    @Test
    fun theByteQuotaEvictsWholeAttachmentsOldestFirstAndTombstonesThem() {
        createStore().use { store ->
            store.subscribed(scope)
            val old = ByteArray(32) { 1 }
            val new = ByteArray(32) { 2 }
            val (cidA, dataA) = chunk(0)
            val (cidB, dataB) = chunk(1)
            val (cidC, dataC) = chunk(2)
            // 3 x 100 bytes against a 250-byte budget: the third push must evict a whole attachment.
            store.attachmentPut(scope, old, 0, 1, cidA, dataA, now = 1L)
            store.attachmentPut(scope, new, 0, 2, cidB, dataB, now = 2L)

            val stored = assertIs<AputResult.Stored>(store.attachmentPut(scope, new, 1, 2, cidC, dataC, now = 3L))

            assertEquals(1, stored.evicted.size)
            assertTrue(stored.evicted.single().contentEquals(old))
            // Evicted whole, and tombstoned so the uploader stops refilling what it would only lose.
            val gone = store.attachmentPresence(scope, old, now = 4L)
            assertEquals(0, gone.total)
            assertEquals(true, gone.dead)
            assertIs<AputResult.Tombstoned>(store.attachmentPut(scope, old, 0, 1, cidA, dataA, now = 4L))
            assertEquals(2, store.attachmentPresence(scope, new, now = 4L).total)
            assertEquals(200L, store.totalBytes())
        }
    }

    @Test
    fun anAttachmentTooBigForTheBudgetIsRefusedWithoutATombstone() {
        createStore().use { store ->
            store.subscribed(scope)
            val (cid0, data0) = chunk(0)
            val (cid1, data1) = chunk(1)
            val (cid2, data2) = chunk(2)
            store.attachmentPut(scope, aid, 0, 3, cid0, data0, now = 1L)
            store.attachmentPut(scope, aid, 1, 3, cid1, data1, now = 1L)

            // The third chunk takes this attachment past the budget with nothing else to evict.
            assertIs<AputResult.QuotaExceeded>(store.attachmentPut(scope, aid, 2, 3, cid2, data2, now = 2L))

            // Dropped entirely, but NOT tombstoned — a later retry must still be possible.
            val info = store.attachmentPresence(scope, aid, now = 3L)
            assertEquals(0, info.total)
            assertEquals(false, info.dead)
            assertEquals(0L, store.totalBytes())
        }
    }

    @Test
    fun attachmentsExpireOnTheScopeTtlAndAreShedWithTheScope() {
        createStore().use { store ->
            store.subscribed(scope)
            val (cid, data) = chunk(0)
            store.attachmentPut(scope, aid, 0, 1, cid, data, now = 1L)

            // Expiry stamps from the FIRST chunk and is never extended by a later one.
            store.sweep(now = 1L + bounds.ttlMs + 1)
            val expired = store.attachmentPresence(scope, aid, now = 1L + bounds.ttlMs + 1)
            assertEquals(0, expired.total)
            assertEquals(true, expired.dead)
            assertEquals(0L, store.totalBytes())
        }

        // A persistent backend reopens the SAME database here, so the expired attachment above is
        // still tombstoned — the shed half needs its own attachment id to have anything to free.
        val shedAid = ByteArray(32) { 8 }
        createStore().use { store ->
            store.subscribed(scope)
            val (cid, data) = chunk(0)
            store.attachmentPut(scope, shedAid, 0, 1, cid, data, now = 1L)

            val shed = store.shedOldestScope()!!
            assertTrue(shed.scopeId.contentEquals(scope))
            assertEquals(100L, shed.freedBytes)
            assertEquals(0L, store.totalBytes())
            assertEquals(true, store.isUnknownScope(scope))
        }
    }
}
