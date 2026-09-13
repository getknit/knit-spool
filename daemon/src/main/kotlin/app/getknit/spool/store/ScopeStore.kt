// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.A_CHUNK_BYTES
import app.getknit.spool.protocol.ScopeBounds

/**
 * The spool-wide caps SUB declarations are clamped to (advertised in HELLO).
 *
 * [maxAttachBytes] and [maxAChunk] are the attachment budget of spec §6.5. Unlike the frame bounds
 * they are never declared per scope: attachments sit outside the digest, so there is nothing for
 * members to converge on and the quota stays purely the operator's. A zero [maxAttachBytes] switches
 * attachment support off, and the server then omits all three attachment limits from HELLO.
 */
class HardLimits(
    val maxBlob: Int,
    val maxFramesCap: Int,
    val maxTtlMs: Long,
    val maxScopes: Int,
    val maxAttachBytes: Int = 0,
    val maxAChunk: Int = DEFAULT_MAX_A_CHUNK,
    /**
     * The largest `total` an `aput` may declare: `⌈maxAttachBytes / A_CHUNK_BYTES⌉`, 342 at the
     * 16 MiB default. Chunking is structural (§4.5: `total = ceil(|A| / aChunkBytes)`, C-4.5-3), so
     * an attachment declaring more chunks than this is larger than the whole quota and can never
     * fit — S-6.5-3's `quota`, reached before its first chunk is stored rather than at its last.
     * Every `ahave` allocates and sends a presence bitmap of `⌈total / 8⌉` bytes, so this is also
     * what bounds that allocation: a client-chosen `total` of 80,000,000 was a 10 MB reply to an
     * 80-byte request, and `Int.MAX_VALUE` wrapped the arithmetic. Derived, not configured — there
     * is nothing to declare, document or reload — and computed in Long so a budget near
     * `Int.MAX_VALUE` cannot wrap it. Overridable only so tests that scale the budget below one
     * structural chunk keep a usable range of totals.
     */
    val maxATotal: Int = ((maxAttachBytes.toLong() + A_CHUNK_BYTES - 1) / A_CHUNK_BYTES).toInt(),
) {
    val attachments: Boolean get() = maxAttachBytes > 0

    companion object {
        /** A sealed chunk at the spec's structural 48 KiB: `1 + 12 + 40 + A_CHUNK_BYTES + 16` (§12). */
        const val DEFAULT_MAX_A_CHUNK = 1 + 12 + 40 + A_CHUNK_BYTES + 16
    }
}

/** One attachment's presence, the payload behind an `ahas` (spec §7.3). */
class AttachmentInfo(
    val total: Int,
    val bits: ByteArray,
    val dead: Boolean,
)

/** One stored chunk returned by [ScopeStore.attachmentGet]. */
class AttachmentChunk(
    val idx: Int,
    val total: Int,
    val cid: ByteArray,
    val data: ByteArray,
)

sealed class AputResult {
    /** Newly stored. [evicted] lists whole attachments dropped to stay inside the byte quota. */
    class Stored(
        val evicted: List<ByteArray>,
    ) : AputResult()

    /** Byte-identical chunk already at that position — acked, nothing changed (§6.5). */
    object Duplicate : AputResult()

    /** A *different* chunk already holds that position, or `total` disagrees. First write wins. */
    object Conflict : AputResult()

    object Tombstoned : AputResult()

    object TooLarge : AputResult()

    object BadId : AputResult()

    /**
     * The attachment cannot fit the per-scope byte budget even with every other one evicted — or
     * declares a `total` above [HardLimits.maxATotal], which no attachment inside the budget can
     * have (§4.5), and is refused before its first chunk is stored.
     */
    object QuotaExceeded : AputResult()
}

/** A scope's digest anchor: what a `digest` record carries (spec §7.2). */
class DigestInfo(
    val digest: Long,
    val count: Int,
    val full: Boolean,
    val bounds: ScopeBounds,
)

class ListInfo(
    val blobIds: List<ByteArray>,
    val tombstones: List<ByteArray>,
)

sealed class SubscribeResult {
    class Subscribed(
        val digest: DigestInfo,
    ) : SubscribeResult()

    /** The spool is at `maxScopes` and the scope is new — enforced atomically inside the store. */
    object QuotaExceeded : SubscribeResult()
}

sealed class PushResult {
    class Stored(
        val digest: DigestInfo,
        /**
         * True when the live set changed beyond the pushed blob (expiry during the op or eviction
         * pressure) — the server re-anchors all subscribers with a fresh `digest` record (§6.2).
         */
        val evictedOrExpired: Boolean,
    ) : PushResult()

    object Duplicate : PushResult()

    object Tombstoned : PushResult()

    object TooLarge : PushResult()

    object BadId : PushResult()
}

/** One scope whose live set changed during a [ScopeStore.sweep] — drives digest broadcasts. */
class SweepChange(
    val scopeId: ByteArray,
    val digest: DigestInfo,
)

/** A scope dropped by the storage watermark ([ScopeStore.shedOldestScope]). */
class ShedScope(
    val scopeId: ByteArray,
    val bounds: ScopeBounds,
    val freedBytes: Long,
)

/**
 * Per-scope blob storage, SPOOL_PROTOCOL.md §6.1–§6.2: the live `blobId → (blob, arrivedAt)` set
 * bounded by the applied bounds, a tombstone set of evicted/expired ids on the same `ttlMs` clock,
 * and an incrementally maintained digest. Eviction is oldest-by-`arrivedAt`; a push matching a
 * tombstone is refused and never re-enters the digest; `blobId = SHA-256(data)` is verified on
 * every push (spec: a third party must not be able to poison an honest spool's digest).
 *
 * Every `scopeId`, `blobId`, `aid` and `cid` handed in is exactly `ID_BYTES` (32) long: the server
 * refuses anything else as `malformed` at the top of each handler, so implementations may key on an
 * id without re-checking its length.
 *
 * Every stored attachment's `total` is at most [HardLimits.maxATotal]: [attachmentPut] refuses a
 * larger one as [AputResult.QuotaExceeded], and a persistent store drops any header above it on
 * boot, so the presence bitmap [attachmentPresence] builds is at most `⌈maxATotal / 8⌉` bytes and
 * its `Int` arithmetic cannot wrap.
 *
 * Implementations are safe for use from multiple threads; the server serializes calls through a
 * single-parallelism dispatcher regardless, so implementations may simply lock.
 */
interface ScopeStore : AutoCloseable {
    /** True when [scopeId] is new to this store (the PoW gate keys on this, spec §6.4). */
    fun isUnknownScope(scopeId: ByteArray): Boolean

    fun scopeCount(): Int

    /**
     * Total charged bytes across all scopes (the watermark input; not file size): frames at their
     * payload size, attachment chunks at [chunkCharge] — never below [ATTACH_CHUNK_FLOOR] each.
     */
    fun totalBytes(): Long

    /** Subscribe-time bounds declaration: clamps to the hard caps and (re)applies them. */
    fun subscribe(
        scopeId: ByteArray,
        declared: ScopeBounds,
        now: Long,
    ): SubscribeResult

    fun digest(
        scopeId: ByteArray,
        now: Long,
    ): DigestInfo?

    fun list(
        scopeId: ByteArray,
        now: Long,
    ): ListInfo?

    /** Returns the requested blobs that are still live; ids the scope no longer holds are omitted. */
    fun pull(
        scopeId: ByteArray,
        blobIds: List<ByteArray>,
        now: Long,
    ): List<Pair<ByteArray, ByteArray>>

    fun push(
        scopeId: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        now: Long,
    ): PushResult

    /**
     * Expires live blobs and tombstones across all scopes; returns one [SweepChange] per scope
     * whose live set changed. Call periodically.
     */
    fun sweep(now: Long): List<SweepChange>

    /**
     * Drops the least-recently-active scope entirely (watermark policy), or null when there is
     * nothing left to drop. [pinned] is never chosen: a commons is operator-declared and shared by
     * everyone on the spool, so shedding it to make room for one client's conversation would be the
     * watermark defeating the point of the feature.
     */
    fun shedOldestScope(pinned: ByteArray? = null): ShedScope?

    // --- Attachments (spec §6.5/§7.3). Only reachable when [HardLimits.attachments] is on. ---

    /** One attachment's presence bitmap; `total = 0` when unknown, `dead` when tombstoned. */
    fun attachmentPresence(
        scopeId: ByteArray,
        aid: ByteArray,
        now: Long,
    ): AttachmentInfo

    /** The stored chunks in `[from, from + n)`; indices the spool lacks are simply absent. */
    fun attachmentGet(
        scopeId: ByteArray,
        aid: ByteArray,
        from: Int,
        n: Int,
        now: Long,
    ): List<AttachmentChunk>

    /**
     * Stores one sealed chunk; see [AputResult] for the outcomes §6.5 requires. A `total` above
     * [HardLimits.maxATotal] is [AputResult.QuotaExceeded] before anything is looked up or hashed.
     */
    fun attachmentPut(
        scopeId: ByteArray,
        aid: ByteArray,
        idx: Int,
        total: Int,
        cid: ByteArray,
        data: ByteArray,
        now: Long,
    ): AputResult

    override fun close()

    companion object {
        /**
         * Per-scope tombstone count bound: `max(2 × maxFrames, TOMBSTONE_FLOOR)`, oldest-first
         * drop. The spec (§6.1) only says "bounded"; unbounded would let an attacker cycling
         * unique blobs through eviction grow the set at push rate for a whole `ttlMs`. Twice the
         * live cap keeps the full anti-churn window for the current live set plus an equal
         * recently-evicted generation; the floor protects small-`maxFrames` scopes.
         */
        const val TOMBSTONE_FLOOR = 1024

        fun tombstoneCap(bounds: ScopeBounds): Int = maxOf(2 * bounds.maxFrames, TOMBSTONE_FLOOR)

        /**
         * Per-chunk charge floor: an `aput` is charged `max(data.size, ATTACH_CHUNK_FLOOR)` against
         * the scope's `maxAttachBytes` and the spool's watermark, at every site that counts
         * attachment bytes — the put, the drop and the boot recompute — in both stores.
         *
         * The quota is in bytes (§6.5) but what a chunk costs is a row: a one-byte chunk is still a
         * header row, a chunk row and an index entry, ~390 B of SQLite file (measured: 3,000
         * one-byte chunks were 3,000 B counted and 1,144 KiB of file) and the same order on the
         * heap. Counted at size, the 16 MiB default admitted ~16 million rows per scope while the
         * watermark saw 16 MiB. Frames are count-capped by `maxFrames`; attachments have no count
         * cap, and this floor stands in for one: a scope holds at most `maxAttachBytes / 512` chunk
         * rows and the spool `maxBytes / 512`. Honest traffic never notices — only an attachment's
         * last chunk can be shorter than the structural 48 KiB, so the charge moves by at most 511
         * bytes per attachment and never for a full chunk.
         */
        const val ATTACH_CHUNK_FLOOR = 512

        /** What one stored chunk of [size] bytes costs against the attachment quota and the watermark. */
        fun chunkCharge(size: Int): Long = maxOf(size, ATTACH_CHUNK_FLOOR).toLong()
    }
}
