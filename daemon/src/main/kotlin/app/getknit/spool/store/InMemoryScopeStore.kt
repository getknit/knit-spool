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

    private class Scope(
        var bounds: ScopeBounds,
        var lastActivity: Long,
    ) {
        val live = LinkedHashMap<String, StoredBlob>() // insertion order == arrival order
        val tombstones = LinkedHashMap<String, Long>() // hex id → tombstone expiry
        var digest = 0L
        var liveBytes = 0L
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
    override fun shedOldestScope(): ShedScope? {
        val oldest = scopes.entries.minByOrNull { it.value.lastActivity } ?: return null
        scopes.remove(oldest.key)
        bytesTotal -= oldest.value.liveBytes
        return ShedScope(
            scopeId = unhex(oldest.key),
            bounds = oldest.value.bounds,
            freedBytes = oldest.value.liveBytes,
        )
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

    /** Expires live blobs and tombstones; returns true when the live set changed. */
    private fun sweepScope(
        scope: Scope,
        now: Long,
    ): Boolean {
        val dead = scope.live.entries.filter { it.value.arrivedAt + scope.bounds.ttlMs < now }
        dead.forEach { removeToTombstone(scope, it.key, now) }
        scope.tombstones.entries.removeIf { it.value < now }
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

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
