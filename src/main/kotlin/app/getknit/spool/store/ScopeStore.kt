// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import java.security.MessageDigest

/**
 * Per-scope blob storage, SPOOL_PROTOCOL.md §6.1–§6.2: the live `blobId → (blob, arrivedAt)` set
 * bounded by the applied bounds, a tombstone set of evicted/expired ids on the same `ttlMs` clock,
 * and an incrementally maintained digest. Eviction is oldest-by-`arrivedAt`; a push matching a
 * tombstone is refused and never re-enters the digest; `blobId = SHA-256(data)` is verified on
 * every push (spec: a third party must not be able to poison an honest spool's digest).
 *
 * In-memory reference implementation — disk persistence is deliberately later work (spools are
 * cattle; any member refills a wiped spool via anti-entropy, spec §9.1).
 */
class InMemoryScopeStore(
    private val hardLimits: HardLimits,
) {
    /** The spool-wide caps SUB declarations are clamped to (advertised in HELLO). */
    class HardLimits(
        val maxBlob: Int,
        val maxFramesCap: Int,
        val maxTtlMs: Long,
        val maxScopes: Int,
    )

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

    sealed class PushResult {
        class Stored(
            val digest: DigestInfo,
        ) : PushResult()

        object Duplicate : PushResult()

        object Tombstoned : PushResult()

        object TooLarge : PushResult()

        object BadId : PushResult()
    }

    private class StoredBlob(
        val blob: ByteArray,
        val arrivedAt: Long,
    )

    private class Scope(
        var bounds: ScopeBounds,
    ) {
        val live = LinkedHashMap<String, StoredBlob>() // insertion order == arrival order
        val tombstones = LinkedHashMap<String, Long>() // hex id → tombstone expiry
        var digest = 0L
    }

    private val scopes = LinkedHashMap<String, Scope>()

    /** True when [scopeId] is new to this store (the PoW gate keys on this, spec §6.4). */
    @Synchronized
    fun isUnknownScope(scopeId: ByteArray): Boolean = hex(scopeId) !in scopes

    @Synchronized
    fun scopeCount(): Int = scopes.size

    /** Subscribe-time bounds declaration: clamps to the hard caps and (re)applies them. */
    @Synchronized
    fun subscribe(
        scopeId: ByteArray,
        declared: ScopeBounds,
        now: Long,
    ): DigestInfo {
        val clamped =
            ScopeBounds(
                maxFrames = declared.maxFrames.coerceIn(1, hardLimits.maxFramesCap),
                ttlMs = declared.ttlMs.coerceIn(1L, hardLimits.maxTtlMs),
                maxBlob = declared.maxBlob.coerceIn(1, hardLimits.maxBlob),
            )
        val scope = scopes.getOrPut(hex(scopeId)) { Scope(clamped) }
        scope.bounds = clamped
        sweepScope(scope, now)
        return digestInfo(scope)
    }

    @Synchronized
    fun digest(
        scopeId: ByteArray,
        now: Long,
    ): DigestInfo? =
        scopes[hex(scopeId)]?.let {
            sweepScope(it, now)
            digestInfo(it)
        }

    @Synchronized
    fun list(
        scopeId: ByteArray,
        now: Long,
    ): ListInfo? =
        scopes[hex(scopeId)]?.let { scope ->
            sweepScope(scope, now)
            ListInfo(
                blobIds = scope.live.keys.map(::unhex),
                tombstones = scope.tombstones.keys.map(::unhex),
            )
        }

    /** Returns the requested blobs that are still live; ids the scope no longer holds are omitted. */
    @Synchronized
    fun pull(
        scopeId: ByteArray,
        blobIds: List<ByteArray>,
        now: Long,
    ): List<Pair<ByteArray, ByteArray>> {
        val scope = scopes[hex(scopeId)] ?: return emptyList()
        sweepScope(scope, now)
        return blobIds.mapNotNull { id -> scope.live[hex(id)]?.let { id to it.blob } }
    }

    @Synchronized
    fun push(
        scopeId: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        now: Long,
    ): PushResult {
        val scope = scopes[hex(scopeId)] ?: return PushResult.BadId
        sweepScope(scope, now)
        if (data.size > scope.bounds.maxBlob) return PushResult.TooLarge
        if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(blobId)) return PushResult.BadId
        val key = hex(blobId)
        if (key in scope.tombstones) return PushResult.Tombstoned
        if (key in scope.live) return PushResult.Duplicate
        scope.live[key] = StoredBlob(blob = data, arrivedAt = now)
        scope.digest = scope.digest xor ScopeDigest.fnv64(blobId)
        while (scope.live.size > scope.bounds.maxFrames) {
            evictOldest(scope, now)
        }
        return PushResult.Stored(digestInfo(scope))
    }

    /** Expires live blobs and tombstones across all scopes; call periodically. */
    @Synchronized
    fun sweep(now: Long) {
        scopes.values.forEach { sweepScope(it, now) }
    }

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

    private fun sweepScope(
        scope: Scope,
        now: Long,
    ) {
        scope.live.entries
            .filter { it.value.arrivedAt + scope.bounds.ttlMs < now }
            .forEach { removeToTombstone(scope, it.key, now) }
        scope.tombstones.entries.removeIf { it.value < now }
    }

    private fun removeToTombstone(
        scope: Scope,
        key: String,
        now: Long,
    ) {
        scope.live.remove(key) ?: return
        scope.digest = scope.digest xor ScopeDigest.fnv64(unhex(key))
        scope.tombstones[key] = now + scope.bounds.ttlMs
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
