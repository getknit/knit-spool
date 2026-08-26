// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.shortHex
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicLong

/**
 * SQLite-backed [ScopeStore] — one WAL-mode database file, one JDBC connection for the store's
 * lifetime, every interface method a single transaction under the instance lock (the server
 * serializes calls anyway; SQLite has one writer). `synchronous=NORMAL` is deliberate: a lost WAL
 * tail after power loss is acceptable — spools are cattle, any member refills via anti-entropy
 * (spec §9.1) — while transactions keep blobs, tombstones, and the digest column mutually
 * consistent. On boot the per-scope digest/count/bytes columns are recomputed from the blob rows
 * and self-healed, so the incrementally maintained columns can never drift permanently.
 */
class SqliteScopeStore private constructor(
    private val connection: Connection,
    private val hardLimits: HardLimits,
) : ScopeStore {
    companion object {
        private val log = LoggerFactory.getLogger(SqliteScopeStore::class.java)

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        private const val BITS_PER_BYTE = 8

        private val ABSENT = AttachmentInfo(total = 0, bits = ByteArray(0), dead = false)

        fun open(
            dataDir: Path,
            hardLimits: HardLimits,
        ): SqliteScopeStore {
            Files.createDirectories(dataDir)
            val connection = DriverManager.getConnection("jdbc:sqlite:${dataDir.resolve("spool.db")}")
            val store = SqliteScopeStore(connection, hardLimits)
            store.initialize()
            return store
        }
    }

    private val statements = HashMap<String, PreparedStatement>()
    private val bytesTotal = AtomicLong(0L)

    /**
     * Reused across pushes — `getInstance` costs ~1.2 us of provider lookup per call, and every
     * store method is `@Synchronized` (the server serializes them anyway). `digest()` self-resets.
     */
    private val sha256 = MessageDigest.getInstance("SHA-256")

    /** Per-scope columns bundled for read-modify-write inside one transaction. */
    private class ScopeRow(
        var bounds: ScopeBounds,
        var digest: Long,
        var liveCount: Int,
        var liveBytes: Long,
        var attachBytes: Long,
    )

    private fun initialize() {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS meta (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS scopes (
                    scope_id      BLOB PRIMARY KEY,
                    max_frames    INTEGER NOT NULL,
                    ttl_ms        INTEGER NOT NULL,
                    max_blob      INTEGER NOT NULL,
                    digest        INTEGER NOT NULL DEFAULT 0,
                    live_count    INTEGER NOT NULL DEFAULT 0,
                    live_bytes    INTEGER NOT NULL DEFAULT 0,
                    attach_bytes  INTEGER NOT NULL DEFAULT 0,
                    last_activity INTEGER NOT NULL
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_scopes_activity ON scopes(last_activity)")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS blobs (
                    scope_id   BLOB NOT NULL REFERENCES scopes(scope_id) ON DELETE CASCADE,
                    blob_id    BLOB NOT NULL,
                    data       BLOB NOT NULL,
                    arrived_at INTEGER NOT NULL,
                    PRIMARY KEY (scope_id, blob_id)
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_blobs_eviction ON blobs(scope_id, arrived_at)")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS tombstones (
                    scope_id   BLOB NOT NULL REFERENCES scopes(scope_id) ON DELETE CASCADE,
                    blob_id    BLOB NOT NULL,
                    expires_at INTEGER NOT NULL,
                    PRIMARY KEY (scope_id, blob_id)
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tombstones_expiry ON tombstones(scope_id, expires_at)")
            // Attachments (spec §6.5). A rowid table for the chunks: WITHOUT ROWID suits small key
            // rows, not the ~48 KiB payload each of these carries — same reasoning as `blobs`.
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS attachments (
                    scope_id   BLOB NOT NULL REFERENCES scopes(scope_id) ON DELETE CASCADE,
                    aid        BLOB NOT NULL,
                    total      INTEGER NOT NULL,
                    arrived_at INTEGER NOT NULL,
                    PRIMARY KEY (scope_id, aid)
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_attachments_eviction ON attachments(scope_id, arrived_at)")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS attachment_chunks (
                    scope_id BLOB NOT NULL,
                    aid      BLOB NOT NULL,
                    idx      INTEGER NOT NULL,
                    cid      BLOB NOT NULL,
                    data     BLOB NOT NULL,
                    PRIMARY KEY (scope_id, aid, idx)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS attachment_tombstones (
                    scope_id   BLOB NOT NULL REFERENCES scopes(scope_id) ON DELETE CASCADE,
                    aid        BLOB NOT NULL,
                    expires_at INTEGER NOT NULL,
                    PRIMARY KEY (scope_id, aid)
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            // schema 1 → 2 adds the attach_bytes column. A fresh database already has it from the
            // CREATE above and reports no stored version, so the ALTER runs only for an existing v1.
            val existingVersion =
                statement.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'").use {
                    if (it.next()) it.getString(1) else null
                }
            if (existingVersion == "1") {
                statement.executeUpdate("ALTER TABLE scopes ADD COLUMN attach_bytes INTEGER NOT NULL DEFAULT 0")
            }
            statement.executeUpdate("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '2')")
        }
        connection.autoCommit = false
        recomputeOnBoot()
    }

    /** Recomputes digest/count/bytes per scope from the blob rows; heals and warns on drift. */
    private fun recomputeOnBoot() {
        tx {
            var total = 0L
            prep("SELECT scope_id, digest, live_count, live_bytes FROM scopes").executeQuery().use { rows ->
                val scopes = ArrayList<Triple<ByteArray, Long, Pair<Int, Long>>>()
                while (rows.next()) {
                    scopes.add(Triple(rows.getBytes(1), rows.getLong(2), rows.getInt(3) to rows.getLong(4)))
                }
                for ((scopeId, storedDigest, storedCounts) in scopes) {
                    var digest = 0L
                    var count = 0
                    var bytes = 0L
                    val select = prep("SELECT blob_id, length(data) FROM blobs WHERE scope_id = ?")
                    select.setBytes(1, scopeId)
                    select.executeQuery().use { blobRows ->
                        while (blobRows.next()) {
                            digest = digest xor ScopeDigest.fnv64(blobRows.getBytes(1))
                            count++
                            bytes += blobRows.getLong(2)
                        }
                    }
                    if (digest != storedDigest || count != storedCounts.first || bytes != storedCounts.second) {
                        log.warn("scope columns drifted from blob rows — healed (scope {})", shortHex(scopeId))
                        val update = prep("UPDATE scopes SET digest = ?, live_count = ?, live_bytes = ? WHERE scope_id = ?")
                        update.setLong(1, digest)
                        update.setInt(2, count)
                        update.setLong(3, bytes)
                        update.setBytes(4, scopeId)
                        update.executeUpdate()
                    }
                    var attachBytes = 0L
                    val attachSelect = prep("SELECT COALESCE(SUM(length(data)), 0) FROM attachment_chunks WHERE scope_id = ?")
                    attachSelect.setBytes(1, scopeId)
                    attachSelect.executeQuery().use { attachRows ->
                        if (attachRows.next()) attachBytes = attachRows.getLong(1)
                    }
                    val healAttach = prep("UPDATE scopes SET attach_bytes = ? WHERE scope_id = ?")
                    healAttach.setLong(1, attachBytes)
                    healAttach.setBytes(2, scopeId)
                    healAttach.executeUpdate()
                    total += bytes + attachBytes
                }
            }
            bytesTotal.set(total)
        }
    }

    @Synchronized
    override fun isUnknownScope(scopeId: ByteArray): Boolean = tx { readRow(scopeId) == null }

    @Synchronized
    override fun scopeCount(): Int =
        tx {
            prep("SELECT COUNT(*) FROM scopes").executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    override fun totalBytes(): Long = bytesTotal.get()

    @Synchronized
    override fun subscribe(
        scopeId: ByteArray,
        declared: ScopeBounds,
        now: Long,
    ): SubscribeResult =
        tx {
            val clamped =
                ScopeBounds(
                    maxFrames = declared.maxFrames.coerceIn(1, hardLimits.maxFramesCap),
                    ttlMs = declared.ttlMs.coerceIn(1L, hardLimits.maxTtlMs),
                    maxBlob = declared.maxBlob.coerceIn(1, hardLimits.maxBlob),
                )
            var row = readRow(scopeId)
            if (row == null) {
                val count =
                    prep("SELECT COUNT(*) FROM scopes").executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                if (count >= hardLimits.maxScopes) return@tx SubscribeResult.QuotaExceeded
                val insert =
                    prep(
                        "INSERT INTO scopes (scope_id, max_frames, ttl_ms, max_blob, digest, live_count, live_bytes, last_activity) " +
                            "VALUES (?, ?, ?, ?, 0, 0, 0, ?)",
                    )
                insert.setBytes(1, scopeId)
                insert.setInt(2, clamped.maxFrames)
                insert.setLong(3, clamped.ttlMs)
                insert.setInt(4, clamped.maxBlob)
                insert.setLong(5, now)
                insert.executeUpdate()
                row = ScopeRow(clamped, digest = 0L, liveCount = 0, liveBytes = 0L, attachBytes = 0L)
            } else {
                row.bounds = clamped
            }
            sweepScope(scopeId, row, now)
            enforceTombstoneCap(scopeId, row.bounds)
            writeRow(scopeId, row, now)
            SubscribeResult.Subscribed(digestInfo(row))
        }

    @Synchronized
    override fun digest(
        scopeId: ByteArray,
        now: Long,
    ): DigestInfo? =
        tx {
            val row = readRow(scopeId) ?: return@tx null
            sweepScope(scopeId, row, now)
            writeRow(scopeId, row, lastActivity = null)
            digestInfo(row)
        }

    @Synchronized
    override fun list(
        scopeId: ByteArray,
        now: Long,
    ): ListInfo? =
        tx {
            val row = readRow(scopeId) ?: return@tx null
            sweepScope(scopeId, row, now)
            writeRow(scopeId, row, now)
            val blobIds = ArrayList<ByteArray>()
            val select = prep("SELECT blob_id FROM blobs WHERE scope_id = ? ORDER BY arrived_at ASC, rowid ASC")
            select.setBytes(1, scopeId)
            select.executeQuery().use { rows ->
                while (rows.next()) blobIds.add(rows.getBytes(1))
            }
            val tombstones = ArrayList<ByteArray>()
            val selectTombstones = prep("SELECT blob_id FROM tombstones WHERE scope_id = ?")
            selectTombstones.setBytes(1, scopeId)
            selectTombstones.executeQuery().use { rows ->
                while (rows.next()) tombstones.add(rows.getBytes(1))
            }
            ListInfo(blobIds = blobIds, tombstones = tombstones)
        }

    @Synchronized
    override fun pull(
        scopeId: ByteArray,
        blobIds: List<ByteArray>,
        now: Long,
    ): List<Pair<ByteArray, ByteArray>> =
        tx {
            val row = readRow(scopeId) ?: return@tx emptyList()
            sweepScope(scopeId, row, now)
            writeRow(scopeId, row, now)
            blobIds.mapNotNull { blobId ->
                val select = prep("SELECT data FROM blobs WHERE scope_id = ? AND blob_id = ?")
                select.setBytes(1, scopeId)
                select.setBytes(2, blobId)
                select.executeQuery().use { rows ->
                    if (rows.next()) blobId to rows.getBytes(1) else null
                }
            }
        }

    @Synchronized
    override fun push(
        scopeId: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        now: Long,
    ): PushResult =
        tx {
            val row = readRow(scopeId) ?: return@tx PushResult.BadId
            val expired = sweepScope(scopeId, row, now)
            if (data.size > row.bounds.maxBlob) {
                writeRow(scopeId, row, now)
                return@tx PushResult.TooLarge
            }
            if (!sha256.digest(data).contentEquals(blobId)) {
                writeRow(scopeId, row, now)
                return@tx PushResult.BadId
            }
            if (exists("SELECT 1 FROM tombstones WHERE scope_id = ? AND blob_id = ?", scopeId, blobId)) {
                writeRow(scopeId, row, now)
                return@tx PushResult.Tombstoned
            }
            if (exists("SELECT 1 FROM blobs WHERE scope_id = ? AND blob_id = ?", scopeId, blobId)) {
                writeRow(scopeId, row, now)
                return@tx PushResult.Duplicate
            }
            val insert = prep("INSERT INTO blobs (scope_id, blob_id, data, arrived_at) VALUES (?, ?, ?, ?)")
            insert.setBytes(1, scopeId)
            insert.setBytes(2, blobId)
            insert.setBytes(3, data)
            insert.setLong(4, now)
            insert.executeUpdate()
            row.digest = row.digest xor ScopeDigest.fnv64(blobId)
            row.liveCount++
            row.liveBytes += data.size
            bytesTotal.addAndGet(data.size.toLong())
            var evicted = false
            while (row.liveCount > row.bounds.maxFrames) {
                evictOldest(scopeId, row, now)
                evicted = true
            }
            // Only expiry and eviction add tombstones, and bounds cannot change inside a push — so
            // with neither, the cap still holds and its COUNT + DELETE would be pure overhead.
            // `subscribe` keeps calling this unconditionally: re-subscribing *can* lower the cap.
            if (expired || evicted) enforceTombstoneCap(scopeId, row.bounds)
            writeRow(scopeId, row, now)
            PushResult.Stored(digestInfo(row), evictedOrExpired = expired || evicted)
        }

    @Synchronized
    override fun sweep(now: Long): List<SweepChange> =
        tx {
            val scopeIds = ArrayList<ByteArray>()
            prep("SELECT scope_id FROM scopes").executeQuery().use { rows ->
                while (rows.next()) scopeIds.add(rows.getBytes(1))
            }
            scopeIds.mapNotNull { scopeId ->
                val row = readRow(scopeId) ?: return@mapNotNull null
                val changed = sweepScope(scopeId, row, now)
                writeRow(scopeId, row, lastActivity = null)
                if (changed) SweepChange(scopeId, digestInfo(row)) else null
            }
        }

    @Synchronized
    override fun shedOldestScope(pinned: ByteArray?): ShedScope? =
        tx {
            // Two statements rather than one with a nullable bind: `scope_id != NULL` is NULL, not
            // true, in SQL, so the pinned form would silently shed nothing when no scope is pinned.
            val select =
                if (pinned == null) {
                    prep(
                        "SELECT scope_id, max_frames, ttl_ms, max_blob, live_bytes + attach_bytes FROM scopes " +
                            "ORDER BY last_activity ASC LIMIT 1",
                    )
                } else {
                    prep(
                        "SELECT scope_id, max_frames, ttl_ms, max_blob, live_bytes + attach_bytes FROM scopes " +
                            "WHERE scope_id != ? ORDER BY last_activity ASC LIMIT 1",
                    ).also { it.setBytes(1, pinned) }
                }
            val shed =
                select.executeQuery().use { rows ->
                    if (!rows.next()) return@tx null
                    ShedScope(
                        scopeId = rows.getBytes(1),
                        bounds =
                            ScopeBounds(
                                maxFrames = rows.getInt(2),
                                ttlMs = rows.getLong(3),
                                maxBlob = rows.getInt(4),
                            ),
                        freedBytes = rows.getLong(5),
                    )
                }
            // attachment_chunks has no foreign key of its own (see dropAttachment), so the scope
            // cascade would leave its rows behind — a shed scope must take its bytes with it.
            val deleteChunks = prep("DELETE FROM attachment_chunks WHERE scope_id = ?")
            deleteChunks.setBytes(1, shed.scopeId)
            deleteChunks.executeUpdate()
            val delete = prep("DELETE FROM scopes WHERE scope_id = ?")
            delete.setBytes(1, shed.scopeId)
            delete.executeUpdate()
            bytesTotal.addAndGet(-shed.freedBytes)
            shed
        }

    @Synchronized
    override fun attachmentPresence(
        scopeId: ByteArray,
        aid: ByteArray,
        now: Long,
    ): AttachmentInfo =
        tx {
            val row = readRow(scopeId) ?: return@tx ABSENT
            sweepScope(scopeId, row, now)
            writeRow(scopeId, row, now)
            if (exists("SELECT 1 FROM attachment_tombstones WHERE scope_id = ? AND aid = ?", scopeId, aid)) {
                return@tx AttachmentInfo(total = 0, bits = ByteArray(0), dead = true)
            }
            val totalSelect = prep("SELECT total FROM attachments WHERE scope_id = ? AND aid = ?")
            totalSelect.setBytes(1, scopeId)
            totalSelect.setBytes(2, aid)
            val total = totalSelect.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
            if (total <= 0) return@tx ABSENT
            val bits = ByteArray((total + BITS_PER_BYTE - 1) / BITS_PER_BYTE)
            val chunkSelect = prep("SELECT idx FROM attachment_chunks WHERE scope_id = ? AND aid = ?")
            chunkSelect.setBytes(1, scopeId)
            chunkSelect.setBytes(2, aid)
            chunkSelect.executeQuery().use { rows ->
                while (rows.next()) {
                    val index = rows.getInt(1)
                    if (index in 0 until total) {
                        bits[index / BITS_PER_BYTE] =
                            (bits[index / BITS_PER_BYTE].toInt() or (0x80 ushr (index % BITS_PER_BYTE))).toByte()
                    }
                }
            }
            AttachmentInfo(total = total, bits = bits, dead = false)
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
        return tx {
            val row = readRow(scopeId) ?: return@tx emptyList()
            sweepScope(scopeId, row, now)
            writeRow(scopeId, row, now)
            val totalSelect = prep("SELECT total FROM attachments WHERE scope_id = ? AND aid = ?")
            totalSelect.setBytes(1, scopeId)
            totalSelect.setBytes(2, aid)
            val total = totalSelect.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
            if (total <= 0) return@tx emptyList()
            val select =
                prep(
                    "SELECT idx, cid, data FROM attachment_chunks WHERE scope_id = ? AND aid = ? " +
                        "AND idx >= ? AND idx < ? ORDER BY idx ASC",
                )
            select.setBytes(1, scopeId)
            select.setBytes(2, aid)
            select.setInt(3, from)
            select.setInt(4, minOf(from.toLong() + n, total.toLong()).toInt())
            val out = ArrayList<AttachmentChunk>()
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    out.add(AttachmentChunk(idx = rows.getInt(1), total = total, cid = rows.getBytes(2), data = rows.getBytes(3)))
                }
            }
            out
        }
    }

    @Synchronized
    @Suppress("ReturnCount") // one guard per §6.5 rejection reason
    override fun attachmentPut(
        scopeId: ByteArray,
        aid: ByteArray,
        idx: Int,
        total: Int,
        cid: ByteArray,
        data: ByteArray,
        now: Long,
    ): AputResult {
        // The cheap, DB-free rejections run first so no path returns after a sweep without writing
        // the scope row back.
        if (data.size > hardLimits.maxAChunk) return AputResult.TooLarge
        if (!sha256.digest(data).contentEquals(cid)) return AputResult.BadId
        if (total < 1 || idx !in 0 until total) return AputResult.Conflict
        return tx {
            val row = readRow(scopeId) ?: return@tx AputResult.BadId
            sweepScope(scopeId, row, now)
            if (exists("SELECT 1 FROM attachment_tombstones WHERE scope_id = ? AND aid = ?", scopeId, aid)) {
                writeRow(scopeId, row, now)
                return@tx AputResult.Tombstoned
            }
            val header = prep("SELECT total FROM attachments WHERE scope_id = ? AND aid = ?")
            header.setBytes(1, scopeId)
            header.setBytes(2, aid)
            val heldTotal = header.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
            // A disagreeing `total` is the same class of fault as a disagreeing chunk: first write wins.
            if (heldTotal != 0 && heldTotal != total) {
                writeRow(scopeId, row, now)
                return@tx AputResult.Conflict
            }
            val heldCid = prep("SELECT cid FROM attachment_chunks WHERE scope_id = ? AND aid = ? AND idx = ?")
            heldCid.setBytes(1, scopeId)
            heldCid.setBytes(2, aid)
            heldCid.setInt(3, idx)
            val existingCid = heldCid.executeQuery().use { if (it.next()) it.getBytes(1) else null }
            if (existingCid != null) {
                writeRow(scopeId, row, now)
                return@tx if (existingCid.contentEquals(cid)) AputResult.Duplicate else AputResult.Conflict
            }
            if (heldTotal == 0) {
                val insertHeader = prep("INSERT INTO attachments (scope_id, aid, total, arrived_at) VALUES (?, ?, ?, ?)")
                insertHeader.setBytes(1, scopeId)
                insertHeader.setBytes(2, aid)
                insertHeader.setInt(3, total)
                insertHeader.setLong(4, now)
                insertHeader.executeUpdate()
            }
            val insert = prep("INSERT INTO attachment_chunks (scope_id, aid, idx, cid, data) VALUES (?, ?, ?, ?, ?)")
            insert.setBytes(1, scopeId)
            insert.setBytes(2, aid)
            insert.setInt(3, idx)
            insert.setBytes(4, cid)
            insert.setBytes(5, data)
            insert.executeUpdate()
            row.attachBytes += data.size
            bytesTotal.addAndGet(data.size.toLong())
            val result = enforceAttachmentQuota(scopeId, row, aid, now)
            writeRow(scopeId, row, now)
            result
        }
    }

    /**
     * Brings the scope back inside the byte budget by dropping whole attachments oldest-first, never
     * the one just written. When nothing else remains to drop, the new attachment cannot fit at all:
     * it is removed WITHOUT a tombstone, so a retry after a sweep (or against a roomier spool) still
     * works.
     */
    private fun enforceAttachmentQuota(
        scopeId: ByteArray,
        row: ScopeRow,
        aid: ByteArray,
        now: Long,
    ): AputResult {
        val evicted = ArrayList<ByteArray>()
        while (row.attachBytes > hardLimits.maxAttachBytes) {
            val select =
                prep(
                    "SELECT aid FROM attachments WHERE scope_id = ? AND aid != ? " +
                        "ORDER BY arrived_at ASC, aid ASC LIMIT 1",
                )
            select.setBytes(1, scopeId)
            select.setBytes(2, aid)
            val victim = select.executeQuery().use { if (it.next()) it.getBytes(1) else null }
            if (victim == null) {
                dropAttachment(scopeId, row, aid, tombstone = false, now = now)
                return AputResult.QuotaExceeded
            }
            dropAttachment(scopeId, row, victim, tombstone = true, now = now)
            evicted.add(victim)
        }
        return AputResult.Stored(evicted)
    }

    @Synchronized
    override fun close() {
        runCatching {
            connection.autoCommit = true
            connection.createStatement().use { it.execute("PRAGMA optimize") }
        }
        statements.values.forEach { runCatching { it.close() } }
        connection.close()
    }

    /** Expires live blobs and tombstones for one scope; returns true when the live set changed. */
    private fun sweepScope(
        scopeId: ByteArray,
        row: ScopeRow,
        now: Long,
    ): Boolean {
        val dead = ArrayList<Pair<ByteArray, Long>>()
        // The cutoff is computed here, not as `arrived_at + ttl < now` in SQL: an expression over the
        // indexed column is not sargable, so that form degrades to `SCAN blobs` — a walk of the
        // scope's whole live set on every push, list, pull, and subscribe. This form range-seeks
        // idx_blobs_eviction instead. Integer-exact rearrangement; ttlMs <= maxTtlMs so no overflow.
        val select = prep("SELECT blob_id, length(data) FROM blobs WHERE scope_id = ? AND arrived_at < ?")
        select.setBytes(1, scopeId)
        select.setLong(2, now - row.bounds.ttlMs)
        select.executeQuery().use { rows ->
            while (rows.next()) dead.add(rows.getBytes(1) to rows.getLong(2))
        }
        dead.forEach { (blobId, size) -> removeToTombstone(scopeId, row, blobId, size, now) }
        val expire = prep("DELETE FROM tombstones WHERE scope_id = ? AND expires_at < ?")
        expire.setBytes(1, scopeId)
        expire.setLong(2, now)
        expire.executeUpdate()
        sweepAttachments(scopeId, row, now)
        return dead.isNotEmpty()
    }

    /**
     * Expires attachments and their tombstones. Deliberately contributes nothing to [sweepScope]'s
     * return value: attachments sit outside the digest (§6.5), so an expiring one must not provoke a
     * digest broadcast announcing a frame set that did not move.
     */
    private fun sweepAttachments(
        scopeId: ByteArray,
        row: ScopeRow,
        now: Long,
    ) {
        val dead = ArrayList<ByteArray>()
        // Same sargable form as the blob sweep: a bare column compared to a precomputed cutoff, so
        // idx_attachments_eviction range-seeks instead of the scope's attachments being scanned.
        val select = prep("SELECT aid FROM attachments WHERE scope_id = ? AND arrived_at < ?")
        select.setBytes(1, scopeId)
        select.setLong(2, now - row.bounds.ttlMs)
        select.executeQuery().use { rows ->
            while (rows.next()) dead.add(rows.getBytes(1))
        }
        dead.forEach { dropAttachment(scopeId, row, it, tombstone = true, now = now) }
        val expire = prep("DELETE FROM attachment_tombstones WHERE scope_id = ? AND expires_at < ?")
        expire.setBytes(1, scopeId)
        expire.setLong(2, now)
        expire.executeUpdate()
    }

    /** Drops one whole attachment — never a partial chunk set, per §6.5. */
    private fun dropAttachment(
        scopeId: ByteArray,
        row: ScopeRow,
        aid: ByteArray,
        tombstone: Boolean,
        now: Long,
    ) {
        var freed = 0L
        val sum = prep("SELECT COALESCE(SUM(length(data)), 0) FROM attachment_chunks WHERE scope_id = ? AND aid = ?")
        sum.setBytes(1, scopeId)
        sum.setBytes(2, aid)
        sum.executeQuery().use { if (it.next()) freed = it.getLong(1) }
        // Chunks are deleted explicitly rather than through the parent's cascade: the pair is not
        // declared as a composite foreign key, so nothing else would collect them.
        val deleteChunks = prep("DELETE FROM attachment_chunks WHERE scope_id = ? AND aid = ?")
        deleteChunks.setBytes(1, scopeId)
        deleteChunks.setBytes(2, aid)
        deleteChunks.executeUpdate()
        val delete = prep("DELETE FROM attachments WHERE scope_id = ? AND aid = ?")
        delete.setBytes(1, scopeId)
        delete.setBytes(2, aid)
        if (delete.executeUpdate() == 0 && freed == 0L) return
        row.attachBytes -= freed
        bytesTotal.addAndGet(-freed)
        if (!tombstone) return
        val insert = prep("INSERT OR REPLACE INTO attachment_tombstones (scope_id, aid, expires_at) VALUES (?, ?, ?)")
        insert.setBytes(1, scopeId)
        insert.setBytes(2, aid)
        insert.setLong(3, now + row.bounds.ttlMs)
        insert.executeUpdate()
        val cap = ScopeStore.tombstoneCap(row.bounds)
        val trim =
            prep(
                "DELETE FROM attachment_tombstones WHERE scope_id = ? AND aid IN (" +
                    "SELECT aid FROM attachment_tombstones WHERE scope_id = ? ORDER BY expires_at ASC, aid ASC " +
                    "LIMIT max(0, (SELECT COUNT(*) FROM attachment_tombstones WHERE scope_id = ?) - ?))",
            )
        trim.setBytes(1, scopeId)
        trim.setBytes(2, scopeId)
        trim.setBytes(3, scopeId)
        trim.setInt(4, cap)
        trim.executeUpdate()
    }

    private fun evictOldest(
        scopeId: ByteArray,
        row: ScopeRow,
        now: Long,
    ) {
        val select =
            prep(
                "SELECT blob_id, length(data) FROM blobs WHERE scope_id = ? " +
                    "ORDER BY arrived_at ASC, rowid ASC LIMIT 1",
            )
        select.setBytes(1, scopeId)
        val oldest =
            select.executeQuery().use { rows ->
                if (rows.next()) rows.getBytes(1) to rows.getLong(2) else null
            } ?: return
        removeToTombstone(scopeId, row, oldest.first, oldest.second, now)
    }

    private fun removeToTombstone(
        scopeId: ByteArray,
        row: ScopeRow,
        blobId: ByteArray,
        size: Long,
        now: Long,
    ) {
        val delete = prep("DELETE FROM blobs WHERE scope_id = ? AND blob_id = ?")
        delete.setBytes(1, scopeId)
        delete.setBytes(2, blobId)
        if (delete.executeUpdate() == 0) return
        row.digest = row.digest xor ScopeDigest.fnv64(blobId)
        row.liveCount--
        row.liveBytes -= size
        bytesTotal.addAndGet(-size)
        val insert = prep("INSERT OR REPLACE INTO tombstones (scope_id, blob_id, expires_at) VALUES (?, ?, ?)")
        insert.setBytes(1, scopeId)
        insert.setBytes(2, blobId)
        insert.setLong(3, now + row.bounds.ttlMs)
        insert.executeUpdate()
    }

    private fun enforceTombstoneCap(
        scopeId: ByteArray,
        bounds: ScopeBounds,
    ) {
        val cap = ScopeStore.tombstoneCap(bounds)
        val delete =
            prep(
                // Oldest-first by expiry (== insertion order on a monotone clock); the table is
                // WITHOUT ROWID, so the id pair is the only handle.
                "DELETE FROM tombstones WHERE scope_id = ? AND blob_id IN (" +
                    "SELECT blob_id FROM tombstones WHERE scope_id = ? ORDER BY expires_at ASC, blob_id ASC " +
                    "LIMIT max(0, (SELECT COUNT(*) FROM tombstones WHERE scope_id = ?) - ?))",
            )
        delete.setBytes(1, scopeId)
        delete.setBytes(2, scopeId)
        delete.setBytes(3, scopeId)
        delete.setInt(4, cap)
        delete.executeUpdate()
    }

    private fun readRow(scopeId: ByteArray): ScopeRow? {
        val select =
            prep("SELECT max_frames, ttl_ms, max_blob, digest, live_count, live_bytes, attach_bytes FROM scopes WHERE scope_id = ?")
        select.setBytes(1, scopeId)
        return select.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            ScopeRow(
                bounds =
                    ScopeBounds(
                        maxFrames = rows.getInt(1),
                        ttlMs = rows.getLong(2),
                        maxBlob = rows.getInt(3),
                    ),
                digest = rows.getLong(4),
                liveCount = rows.getInt(5),
                liveBytes = rows.getLong(6),
                attachBytes = rows.getLong(7),
            )
        }
    }

    private fun writeRow(
        scopeId: ByteArray,
        row: ScopeRow,
        lastActivity: Long?,
    ) {
        val update =
            prep(
                "UPDATE scopes SET max_frames = ?, ttl_ms = ?, max_blob = ?, digest = ?, live_count = ?, live_bytes = ?, " +
                    "attach_bytes = ?, last_activity = COALESCE(?, last_activity) WHERE scope_id = ?",
            )
        update.setInt(1, row.bounds.maxFrames)
        update.setLong(2, row.bounds.ttlMs)
        update.setInt(3, row.bounds.maxBlob)
        update.setLong(4, row.digest)
        update.setInt(5, row.liveCount)
        update.setLong(6, row.liveBytes)
        update.setLong(7, row.attachBytes)
        if (lastActivity == null) update.setNull(8, java.sql.Types.INTEGER) else update.setLong(8, lastActivity)
        update.setBytes(9, scopeId)
        update.executeUpdate()
    }

    private fun digestInfo(row: ScopeRow): DigestInfo =
        DigestInfo(
            digest = row.digest,
            count = row.liveCount,
            full = row.liveCount >= row.bounds.maxFrames,
            bounds = row.bounds,
        )

    private fun exists(
        sql: String,
        scopeId: ByteArray,
        blobId: ByteArray,
    ): Boolean {
        val select = prep(sql)
        select.setBytes(1, scopeId)
        select.setBytes(2, blobId)
        return select.executeQuery().use { it.next() }
    }

    private fun <T> tx(block: () -> T): T =
        try {
            val result = block()
            connection.commit()
            result
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        }

    private fun prep(sql: String): PreparedStatement = statements.getOrPut(sql) { connection.prepareStatement(sql) }

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
        }
        return String(out)
    }
}
