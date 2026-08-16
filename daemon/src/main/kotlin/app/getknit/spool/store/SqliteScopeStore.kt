// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
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

    /** Per-scope columns bundled for read-modify-write inside one transaction. */
    private class ScopeRow(
        var bounds: ScopeBounds,
        var digest: Long,
        var liveCount: Int,
        var liveBytes: Long,
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
            statement.executeUpdate("INSERT OR IGNORE INTO meta (key, value) VALUES ('schema_version', '1')")
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
                        log.warn("scope columns drifted from blob rows — healed (scope {}…)", hex(scopeId).take(12))
                        val update = prep("UPDATE scopes SET digest = ?, live_count = ?, live_bytes = ? WHERE scope_id = ?")
                        update.setLong(1, digest)
                        update.setInt(2, count)
                        update.setLong(3, bytes)
                        update.setBytes(4, scopeId)
                        update.executeUpdate()
                    }
                    total += bytes
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
                row = ScopeRow(clamped, digest = 0L, liveCount = 0, liveBytes = 0L)
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
            if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(blobId)) {
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
            enforceTombstoneCap(scopeId, row.bounds)
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
    override fun shedOldestScope(): ShedScope? =
        tx {
            val select =
                prep(
                    "SELECT scope_id, max_frames, ttl_ms, max_blob, live_bytes FROM scopes " +
                        "ORDER BY last_activity ASC LIMIT 1",
                )
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
            val delete = prep("DELETE FROM scopes WHERE scope_id = ?")
            delete.setBytes(1, shed.scopeId)
            delete.executeUpdate()
            bytesTotal.addAndGet(-shed.freedBytes)
            shed
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
        val select = prep("SELECT blob_id, length(data) FROM blobs WHERE scope_id = ? AND arrived_at + ? < ?")
        select.setBytes(1, scopeId)
        select.setLong(2, row.bounds.ttlMs)
        select.setLong(3, now)
        select.executeQuery().use { rows ->
            while (rows.next()) dead.add(rows.getBytes(1) to rows.getLong(2))
        }
        dead.forEach { (blobId, size) -> removeToTombstone(scopeId, row, blobId, size, now) }
        val expire = prep("DELETE FROM tombstones WHERE scope_id = ? AND expires_at < ?")
        expire.setBytes(1, scopeId)
        expire.setLong(2, now)
        expire.executeUpdate()
        return dead.isNotEmpty()
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
        val select = prep("SELECT max_frames, ttl_ms, max_blob, digest, live_count, live_bytes FROM scopes WHERE scope_id = ?")
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
                    "last_activity = COALESCE(?, last_activity) WHERE scope_id = ?",
            )
        update.setInt(1, row.bounds.maxFrames)
        update.setLong(2, row.bounds.ttlMs)
        update.setInt(3, row.bounds.maxBlob)
        update.setLong(4, row.digest)
        update.setInt(5, row.liveCount)
        update.setLong(6, row.liveBytes)
        if (lastActivity == null) update.setNull(7, java.sql.Types.INTEGER) else update.setLong(7, lastActivity)
        update.setBytes(8, scopeId)
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

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
