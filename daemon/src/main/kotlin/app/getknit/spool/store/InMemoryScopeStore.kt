// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import java.security.MessageDigest

/**
 * In-memory [ScopeStore] — the default when no data dir is configured, and the test workhorse.
 * Spools are cattle: a wiped (or restarted, with this store) spool is refilled by any member via
 * anti-entropy (spec §9.1). All methods hold the instance lock.
 */
class InMemoryScopeStore(
    private val hardLimits: HardLimits,
) : ScopeStore {
    private class StoredBlob(
        val blob: ByteArray,
        val arrivedAt: Long,
    )

    private class StoredChunk(
        val cid: ByteArray,
        val data: ByteArray,
    )

    /** One attachment (spec §6.5). [arrivedAt] is stamped by the FIRST chunk and never extended. */
    private class StoredAttachment(
        val total: Int,
        val arrivedAt: Long,
    ) {
        val chunks = HashMap<Int, StoredChunk>()
        var bytes = 0L
    }

    private class Scope(
        var bounds: ScopeBounds,
        var lastActivity: Long,
    ) {
        val live = LinkedHashMap<String, StoredBlob>() // insertion order == arrival order
        val tombstones = LinkedHashMap<String, Long>() // hex id → tombstone expiry
        val attachments = LinkedHashMap<String, StoredAttachment>() // hex aid → attachment
        val attachTombstones = LinkedHashMap<String, Long>() // hex aid → tombstone expiry
        var digest = 0L
        var liveBytes = 0L
        var attachBytes = 0L
    }

    private val scopes = LinkedHashMap<String, Scope>()
    private var bytesTotal = 0L

    @Synchronized
    override fun isUnknownScope(scopeId: ByteArray): Boolean = hex(scopeId) !in scopes

    @Synchronized
    override fun scopeCount(): Int = scopes.size

    @Synchronized
    override fun totalBytes(): Long = bytesTotal

    @Synchronized
    override fun subscribe(
        scopeId: ByteArray,
        declared: ScopeBounds,
        now: Long,
    ): SubscribeResult {
        val key = hex(scopeId)
        if (key !in scopes && scopes.size >= hardLimits.maxScopes) return SubscribeResult.QuotaExceeded
        val clamped =
            ScopeBounds(
                maxFrames = declared.maxFrames.coerceIn(1, hardLimits.maxFramesCap),
                ttlMs = declared.ttlMs.coerceIn(1L, hardLimits.maxTtlMs),
                maxBlob = declared.maxBlob.coerceIn(1, hardLimits.maxBlob),
            )
        val scope = scopes.getOrPut(key) { Scope(clamped, now) }
        scope.bounds = clamped
        scope.lastActivity = now
        sweepScope(scope, now)
        enforceTombstoneCap(scope)
        return SubscribeResult.Subscribed(digestInfo(scope))
    }

    @Synchronized
    override fun digest(
        scopeId: ByteArray,
        now: Long,
    ): DigestInfo? =
        scopes[hex(scopeId)]?.let {
            sweepScope(it, now)
            digestInfo(it)
        }

    @Synchronized
    override fun list(
        scopeId: ByteArray,
        now: Long,
    ): ListInfo? =
        scopes[hex(scopeId)]?.let { scope ->
            scope.lastActivity = now
            sweepScope(scope, now)
            ListInfo(
                blobIds = scope.live.keys.map(::unhex),
                tombstones = scope.tombstones.keys.map(::unhex),
            )
        }

    @Synchronized
    override fun pull(
        scopeId: ByteArray,
        blobIds: List<ByteArray>,
        now: Long,
    ): List<Pair<ByteArray, ByteArray>> {
        val scope = scopes[hex(scopeId)] ?: return emptyList()
        scope.lastActivity = now
        sweepScope(scope, now)
        return blobIds.mapNotNull { id -> scope.live[hex(id)]?.let { id to it.blob } }
    }

    @Synchronized
    override fun push(
        scopeId: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        now: Long,
    ): PushResult {
        val scope = scopes[hex(scopeId)] ?: return PushResult.BadId
        scope.lastActivity = now
        val expired = sweepScope(scope, now)
        if (data.size > scope.bounds.maxBlob) return PushResult.TooLarge
        if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(blobId)) return PushResult.BadId
        val key = hex(blobId)
        if (key in scope.tombstones) return PushResult.Tombstoned
        if (key in scope.live) return PushResult.Duplicate
        scope.live[key] = StoredBlob(blob = data, arrivedAt = now)
        scope.liveBytes += data.size
        bytesTotal += data.size
        scope.digest = scope.digest xor ScopeDigest.fnv64(blobId)
        var evicted = false
        while (scope.live.size > scope.bounds.maxFrames) {
            evictOldest(scope, now)
            evicted = true
        }
        return PushResult.Stored(digestInfo(scope), evictedOrExpired = expired || evicted)
    }

    @Synchronized
    override fun sweep(now: Long): List<SweepChange> =
        scopes.entries.mapNotNull { (key, scope) ->
            if (sweepScope(scope, now)) SweepChange(unhex(key), digestInfo(scope)) else null
        }

    @Synchronized
    override fun shedOldestScope(pinned: ByteArray?): ShedScope? {
        val pinnedHex = pinned?.let(::hex)
        val oldest =
            scopes.entries
                .filter { it.key != pinnedHex }
                .minByOrNull { it.value.lastActivity } ?: return null
        scopes.remove(oldest.key)
        val freed = oldest.value.liveBytes + oldest.value.attachBytes
        bytesTotal -= freed
        return ShedScope(
            scopeId = unhex(oldest.key),
            bounds = oldest.value.bounds,
            freedBytes = freed,
        )
    }

    @Synchronized
    override fun attachmentPresence(
        scopeId: ByteArray,
        aid: ByteArray,
        now: Long,
    ): AttachmentInfo {
        val scope = scopes[hex(scopeId)] ?: return ABSENT
        scope.lastActivity = now
        sweepScope(scope, now)
        val key = hex(aid)
        if (key in scope.attachTombstones) return AttachmentInfo(total = 0, bits = ByteArray(0), dead = true)
        val held = scope.attachments[key] ?: return ABSENT
        return AttachmentInfo(total = held.total, bits = bitmapOf(held), dead = false)
    }

    @Synchronized
    override fun attachmentGet(
        scopeId: ByteArray,
        aid: ByteArray,
        from: Int,
        n: Int,
        now: Long,
    ): List<AttachmentChunk> {
        if (from < 0 || n <= 0) return emptyList()
        val scope = scopes[hex(scopeId)] ?: return emptyList()
        scope.lastActivity = now
        sweepScope(scope, now)
        val held = scope.attachments[hex(aid)] ?: return emptyList()
        return (from until minOf(from + n, held.total)).mapNotNull { index ->
            held.chunks[index]?.let { AttachmentChunk(idx = index, total = held.total, cid = it.cid, data = it.data) }
        }
    }

    @Synchronized
    @Suppress("ReturnCount") // one guard per §6.5 rejection reason; a nested pyramid would read worse
    override fun attachmentPut(
        scopeId: ByteArray,
        aid: ByteArray,
        idx: Int,
        total: Int,
        cid: ByteArray,
        data: ByteArray,
        now: Long,
    ): AputResult {
        val scope = scopes[hex(scopeId)] ?: return AputResult.BadId
        scope.lastActivity = now
        sweepScope(scope, now)
        if (data.size > hardLimits.maxAChunk) return AputResult.TooLarge
        if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(cid)) return AputResult.BadId
        if (total < 1 || idx !in 0 until total) return AputResult.Conflict
        val key = hex(aid)
        if (key in scope.attachTombstones) return AputResult.Tombstoned
        val existing = scope.attachments[key]
        // A disagreeing `total` is the same class of fault as a disagreeing chunk: first write wins.
        if (existing != null && existing.total != total) return AputResult.Conflict
        existing?.chunks?.get(idx)?.let {
            return if (it.cid.contentEquals(cid)) AputResult.Duplicate else AputResult.Conflict
        }
        val attachment = existing ?: StoredAttachment(total, now).also { scope.attachments[key] = it }
        attachment.chunks[idx] = StoredChunk(cid = cid, data = data)
        attachment.bytes += data.size
        scope.attachBytes += data.size
        bytesTotal += data.size
        return enforceAttachmentQuota(scope, key, now)
    }

    /**
     * Brings [scope] back inside the byte budget by dropping whole attachments oldest-first, never
     * the one just written and never a partial chunk set. If nothing else is left to drop, the new
     * attachment simply cannot fit: it is removed WITHOUT a tombstone, so a later retry against a
     * roomier spool (or the same one after a sweep) is still possible.
     */
    private fun enforceAttachmentQuota(
        scope: Scope,
        key: String,
        now: Long,
    ): AputResult {
        val evicted = mutableListOf<ByteArray>()
        while (scope.attachBytes > hardLimits.maxAttachBytes) {
            val victim =
                scope.attachments.entries
                    .filter { it.key != key }
                    .minByOrNull { it.value.arrivedAt }
            if (victim == null) {
                dropAttachment(scope, key, tombstone = false, now = now)
                return AputResult.QuotaExceeded
            }
            dropAttachment(scope, victim.key, tombstone = true, now = now)
            evicted.add(unhex(victim.key))
        }
        return AputResult.Stored(evicted)
    }

    private fun dropAttachment(
        scope: Scope,
        key: String,
        tombstone: Boolean,
        now: Long,
    ) {
        val removed = scope.attachments.remove(key) ?: return
        scope.attachBytes -= removed.bytes
        bytesTotal -= removed.bytes
        if (!tombstone) return
        scope.attachTombstones[key] = now + scope.bounds.ttlMs
        val cap = ScopeStore.tombstoneCap(scope.bounds)
        while (scope.attachTombstones.size > cap) {
            scope.attachTombstones.remove(scope.attachTombstones.keys.firstOrNull() ?: return)
        }
    }

    private fun bitmapOf(attachment: StoredAttachment): ByteArray {
        val out = ByteArray((attachment.total + BITS_PER_BYTE - 1) / BITS_PER_BYTE)
        for (index in attachment.chunks.keys) {
            if (index in 0 until attachment.total) {
                out[index / BITS_PER_BYTE] = (out[index / BITS_PER_BYTE].toInt() or (0x80 ushr (index % BITS_PER_BYTE))).toByte()
            }
        }
        return out
    }

    override fun close() = Unit

    private fun digestInfo(scope: Scope): DigestInfo =
        DigestInfo(
            digest = scope.digest,
            count = scope.live.size,
            full = scope.live.size >= scope.bounds.maxFrames,
            bounds = scope.bounds,
        )

    private fun evictOldest(
        scope: Scope,
        now: Long,
    ) {
        val oldest = scope.live.entries.minByOrNull { it.value.arrivedAt } ?: return
        removeToTombstone(scope, oldest.key, now)
    }

    /**
     * Expires live blobs, attachments, and both tombstone sets. The returned flag is about the
     * **frame** set only: attachments are outside the digest (§6.5), so an expiring attachment must
     * not provoke a digest broadcast that says nothing changed.
     */
    private fun sweepScope(
        scope: Scope,
        now: Long,
    ): Boolean {
        val dead = scope.live.entries.filter { it.value.arrivedAt + scope.bounds.ttlMs < now }
        dead.forEach { removeToTombstone(scope, it.key, now) }
        scope.tombstones.entries.removeIf { it.value < now }
        scope.attachments.entries
            .filter { it.value.arrivedAt + scope.bounds.ttlMs < now }
            .map { it.key }
            .forEach { dropAttachment(scope, it, tombstone = true, now = now) }
        scope.attachTombstones.entries.removeIf { it.value < now }
        return dead.isNotEmpty()
    }

    private fun removeToTombstone(
        scope: Scope,
        key: String,
        now: Long,
    ) {
        val removed = scope.live.remove(key) ?: return
        scope.liveBytes -= removed.blob.size
        bytesTotal -= removed.blob.size
        scope.digest = scope.digest xor ScopeDigest.fnv64(unhex(key))
        scope.tombstones[key] = now + scope.bounds.ttlMs
        enforceTombstoneCap(scope)
    }

    private fun enforceTombstoneCap(scope: Scope) {
        val cap = ScopeStore.tombstoneCap(scope.bounds)
        while (scope.tombstones.size > cap) {
            val eldest = scope.tombstones.keys.firstOrNull() ?: return
            scope.tombstones.remove(eldest)
        }
    }

    // Hex is this store's map key, so it sits on every lookup: `String.format` per byte measured
    // ~100x a nibble table (≈6 us vs ≈0.05 us for a 32-byte id), and `list` unhexes every blob id.
    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
        }
        return String(out)
    }

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) {
            ((hexNibble(value[it * 2]) shl 4) or hexNibble(value[it * 2 + 1])).toByte()
        }

    private fun hexNibble(c: Char): Int = if (c <= '9') c - '0' else c - 'a' + 10

    private companion object {
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
        private const val BITS_PER_BYTE = 8
        private val ABSENT = AttachmentInfo(total = 0, bits = ByteArray(0), dead = false)
    }
}
