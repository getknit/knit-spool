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
import java.sql.ResultSet
import java.sql.Statement
import java.util.concurrent.atomic.AtomicLong

/**
 * SQLite-backed [ScopeStore] — one WAL-mode database file, one JDBC connection for the store's
 * lifetime, every interface method a single transaction under the instance lock (the server
 * serializes calls anyway; SQLite has one writer). `synchronous=NORMAL` is deliberate: a lost WAL
 * tail after power loss is acceptable — spools are cattle, any member refills via anti-entropy
 * (spec §9.1) — while transactions keep blobs, tombstones, and the digest column mutually
 * consistent. On boot the per-scope digest/count/bytes columns are recomputed from the blob rows
 * and self-healed, so the incrementally maintained columns can never drift permanently; the
 * attachment bytes are re-summed the same way at their charged size ([ScopeStore.chunkCharge]), so
 * a store written under an older counting rule is re-counted on its first boot.
 *
 * The file runs `auto_vacuum=INCREMENTAL` and [reclaimSpace] hands freed pages back to the disk
 * (ADR 2026-10.us34). Without it SQLite reuses freed pages but never truncates, so the file stays
 * the size of the most the spool ever held. A file written before that is converted with one
 * `VACUUM` on its first open.
 */
class SqliteScopeStore private constructor(
    private val connection: Connection,
    private val hardLimits: HardLimits,
    private val dbFile: Path,
) : ScopeStore {
    companion object {
        private val log = LoggerFactory.getLogger(SqliteScopeStore::class.java)

        private const val BITS_PER_BYTE = 8

        /** The chunk bitmap is MSB-first (spec §6.5): index 0 is the top bit of byte 0. */
        private const val HIGH_BIT = 0x80

        private val ABSENT = AttachmentInfo(total = 0, bits = ByteArray(0), dead = false)

        /** `PRAGMA auto_vacuum`'s value for INCREMENTAL. */
        private const val AUTO_VACUUM_INCREMENTAL = 2

        /**
         * Free space [reclaimSpace] leaves alone. Ordinary push-and-expire churn frees and reuses a
         * few pages every tick; handing those back only to grow the file again is write I/O for
         * nothing, so the file is allowed this much slack above live data.
         */
        const val RECLAIM_FLOOR_BYTES = 4L * 1024 * 1024

        /** Caps the `-wal` file between checkpoints, which otherwise keeps its own high-water size. */
        private const val JOURNAL_SIZE_LIMIT = 64L * 1024 * 1024

        /**
         * What a one-off conversion `VACUUM` needs free in the data directory: the rebuilt copy, which
         * [inDataDirectoryTemp] keeps there, plus the WAL that carries it back into the file.
         */
        private const val VACUUM_SPACE_FACTOR = 2

        /** One chunk row's charge in SQL — the same rule as [ScopeStore.chunkCharge]. */
        private const val CHUNK_CHARGE_SQL = "max(length(data), ${ScopeStore.ATTACH_CHUNK_FLOOR})"

        /** A bare scope ([ScopeStore]) in SQL, over `scopes` aliased `s`: nothing but its row. */
        private const val BARE_SQL =
            "s.live_count = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM tombstones t WHERE t.scope_id = s.scope_id) " +
                "AND NOT EXISTS (SELECT 1 FROM attachments a WHERE a.scope_id = s.scope_id) " +
                "AND NOT EXISTS (SELECT 1 FROM attachment_tombstones x WHERE x.scope_id = s.scope_id)"

        /**
         * A full [subscribe]'s eviction candidates, least recently active first. [BARE_SQL] leads with
         * `live_count = 0`, so the scan runs on `idx_scopes_empty` and never visits a scope holding a
         * frame. Internal for SqliteScopeStoreTest, which checks that plan.
         */
        internal const val BARE_BY_ACTIVITY_SQL = "SELECT s.scope_id FROM scopes s WHERE $BARE_SQL ORDER BY s.last_activity ASC"

        /** The `scopes` columns a [ScopeRow] is built from, in [scopeRow]'s order. */
        private const val ROW_COLUMNS = "max_frames, ttl_ms, max_blob, digest, live_count, live_bytes, attach_bytes"

        /**
         * One [sweep] batch: the next scopes after a cursor in key order, each with its row and whether
         * [sweepScope] would find anything to expire there. The four `EXISTS` mirror [sweepScope] and
         * [sweepAttachments] clause for clause, so a scope with nothing due costs no statement of its
         * own and no write (ADR 2026-10.d9ez). Each cutoff keeps the bare indexed column on the left
         * and the outer row's `ttl_ms` on the right, so it still range-seeks the per-scope indexes.
         */
        private const val SWEEP_WINDOW_SQL =
            "SELECT s.scope_id, $ROW_COLUMNS, " +
                "EXISTS (SELECT 1 FROM blobs b WHERE b.scope_id = s.scope_id AND b.arrived_at < ? - s.ttl_ms) " +
                "OR EXISTS (SELECT 1 FROM tombstones t WHERE t.scope_id = s.scope_id AND t.expires_at < ?) " +
                "OR EXISTS (SELECT 1 FROM attachments a WHERE a.scope_id = s.scope_id AND a.arrived_at < ? - s.ttl_ms) " +
                "OR EXISTS (SELECT 1 FROM attachment_tombstones x WHERE x.scope_id = s.scope_id AND x.expires_at < ?) " +
                "FROM scopes s WHERE s.scope_id > ? ORDER BY s.scope_id LIMIT ?"

        /** One [reclaimIdle] batch: the next scopes after a cursor in key order, each with whether it is bare and idle past its TTL. */
        private const val RECLAIM_WINDOW_SQL =
            "SELECT s.scope_id, s.last_activity + s.ttl_ms < ? AND $BARE_SQL " +
                "FROM scopes s WHERE s.scope_id > ? ORDER BY s.scope_id LIMIT ?"

        fun open(
            dataDir: Path,
            hardLimits: HardLimits,
        ): SqliteScopeStore {
            Files.createDirectories(dataDir)
            val dbFile = dataDir.resolve("spool.db")
            val connection = DriverManager.getConnection("jdbc:sqlite:$dbFile")
            val store = SqliteScopeStore(connection, hardLimits, dbFile)
            store.initialize()
            return store
        }
    }

    private val statements = HashMap<String, PreparedStatement>()
    private val bytesTotal = AtomicLong(0L)

    /**
     * Rows in `scopes` as of the last commit, so a new scope's quota check is not a `COUNT(*)` over
     * every row (ADR 2026-10.ywkw). [scopeDelta] is the open transaction's inserts minus deletes,
     * folded in by [tx] only once it commits, so a rolled-back transaction leaves the count alone.
     * [scopeCount] still asks the database: it doubles as the health check's probe.
     */
    private var scopeRows = 0
    private var scopeDelta = 0

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
        val convert = configure()
        connection.createStatement().use { statement ->
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
            // Only scopes holding no frame, so a full subscribe looks for a bare one to evict without
            // walking every scope that holds frames (ADR 2026-10.ywkw). Partial, so a downgraded
            // binary simply maintains it; no schema_version step.
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_scopes_empty ON scopes(last_activity) WHERE live_count = 0")
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
        if (convert) convertToIncremental()
        connection.autoCommit = false
        recomputeOnBoot()
    }

    /**
     * Sets the connection's pragmas, asking for `auto_vacuum=INCREMENTAL` first: it must precede
     * the first `CREATE TABLE`. True when the file needs [convertToIncremental] for the mode to
     * take. A file with no tables yet takes it as it is, and one already INCREMENTAL needs nothing.
     */
    private fun configure(): Boolean =
        connection.createStatement().use { statement ->
            val mode = pragmaLong(statement, "auto_vacuum")
            val hasTables =
                statement.executeQuery("SELECT COUNT(*) FROM sqlite_schema").use { it.next() && it.getInt(1) > 0 }
            statement.execute("PRAGMA auto_vacuum=INCREMENTAL")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA journal_size_limit=$JOURNAL_SIZE_LIMIT")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA foreign_keys=ON")
            hasTables && mode != AUTO_VACUUM_INCREMENTAL.toLong()
        }

    /**
     * Rebuilds a file written before `auto_vacuum=INCREMENTAL` so the mode takes effect: SQLite
     * applies it to an existing file only through a `VACUUM`. Runs once per file, before the store
     * serves anything. Never fatal: a file that cannot be rebuilt (too little free disk, an I/O
     * error) is left exactly as it was, the spool boots as before, and the next boot tries again.
     * A `VACUUM` can also throw after the rebuilt file is already in place, so a failure is judged
     * by the file, not by the exception.
     */
    @Suppress("TooGenericExceptionCaught") // a failed rebuild leaves the file as it was; boot carries on
    private fun convertToIncremental() {
        val before = storeFileBytes()
        val live =
            connection.createStatement().use {
                (pragmaLong(it, "page_count") - pragmaLong(it, "freelist_count")) * pragmaLong(it, "page_size")
            }
        val usable = runCatching { Files.getFileStore(dbFile).usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (usable < VACUUM_SPACE_FACTOR * live) {
            log.warn(
                "store: not converting {} to incremental auto-vacuum — {} bytes free, needs ~{}; " +
                    "freed space stays in the file until a later boot",
                dbFile.fileName,
                usable,
                VACUUM_SPACE_FACTOR * live,
            )
            return
        }
        val started = System.nanoTime()
        try {
            connection.createStatement().use { statement ->
                inDataDirectoryTemp(statement) { statement.execute("VACUUM") }
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        } catch (e: Exception) {
            if (!convertedAnyway()) {
                log.warn("store: incremental auto-vacuum conversion failed, file left as it was: {}", e.message)
                return
            }
            log.warn("store: the conversion VACUUM threw after the file had converted: {}", e.message)
        }
        log.info(
            "store: converted {} to incremental auto-vacuum, {} -> {} bytes in {} ms",
            dbFile.fileName,
            before,
            storeFileBytes(),
            (System.nanoTime() - started) / 1_000_000,
        )
    }

    /**
     * Runs [block] with SQLite's temp files in the data directory. The conversion `VACUUM` builds
     * its copy in a temp database that spills to a file once it outgrows the page cache. SQLite
     * picks that file's directory itself and skips any it cannot write, so under a read-only root
     * the copy lands in `/tmp`, often a small tmpfs, where the free-space check never looked.
     * `temp_store_directory` is deprecated and process-wide. The conversion runs while the store
     * opens, before anything else uses SQLite, and the setting goes back to SQLite's default after.
     */
    private fun inDataDirectoryTemp(
        statement: Statement,
        block: () -> Unit,
    ) {
        val dir =
            dbFile
                .toAbsolutePath()
                .parent
                .toString()
                .replace("'", "''")
        statement.execute("PRAGMA temp_store_directory = '$dir'")
        try {
            block()
        } finally {
            statement.execute("PRAGMA temp_store_directory = ''")
        }
    }

    /**
     * True when a `VACUUM` that threw had already put the rebuilt file in place: a disk that fills
     * as SQLite cleans up raises `SQLITE_FULL` after the conversion has landed.
     */
    private fun convertedAnyway(): Boolean =
        runCatching {
            connection.createStatement().use { statement ->
                pragmaLong(statement, "auto_vacuum") == AUTO_VACUUM_INCREMENTAL.toLong() &&
                    statement.executeQuery("PRAGMA integrity_check").use { it.next() && it.getString(1) == "ok" }
            }
        }.getOrDefault(false)

    private fun pragmaLong(
        statement: Statement,
        name: String,
    ): Long = statement.executeQuery("PRAGMA $name").use { if (it.next()) it.getLong(1) else 0L }

    /**
     * Recomputes digest/count/bytes per scope from the blob rows; heals and warns on drift.
     *
     * First drops every attachment whose header declares more chunks than [HardLimits.maxATotal],
     * so the `attach_bytes` re-sum below never counts one. Such a header is either older than the
     * bound — written by a client that chose a `total` no attachment could have, when nothing
     * refused it — or it was legitimate under a `SPOOL_MAX_ATTACH_BYTES` the operator has since
     * lowered below its chunk count; either way the attachment can never complete, and the
     * presence bitmap `ahave` would build for it is sized by that `total`. No tombstone: nothing
     * conforming wrote it, and `ahave` should answer absent, not dead.
     */
    private fun recomputeOnBoot() {
        tx {
            val dropChunks =
                prep(
                    "DELETE FROM attachment_chunks WHERE (scope_id, aid) IN " +
                        "(SELECT scope_id, aid FROM attachments WHERE total > ?)",
                )
            dropChunks.setInt(1, hardLimits.maxATotal)
            dropChunks.executeUpdate()
            val dropHeaders = prep("DELETE FROM attachments WHERE total > ?")
            dropHeaders.setInt(1, hardLimits.maxATotal)
            val dropped = dropHeaders.executeUpdate()
            if (dropped > 0) {
                log.warn(
                    "dropped {} attachment(s) declaring more than {} chunks — none could ever fit the quota",
                    dropped,
                    hardLimits.maxATotal,
                )
            }
            var total = 0L
            prep("SELECT scope_id, digest, live_count, live_bytes FROM scopes").executeQuery().use { rows ->
                val scopes = ArrayList<Triple<ByteArray, Long, Pair<Int, Long>>>()
                while (rows.next()) {
                    scopes.add(Triple(rows.getBytes(1), rows.getLong(2), rows.getInt(3) to rows.getLong(4)))
                }
                scopeRows = scopes.size
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
                    val attachSelect = prep("SELECT COALESCE(SUM($CHUNK_CHARGE_SQL), 0) FROM attachment_chunks WHERE scope_id = ?")
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

    override fun storeFileBytes(): Long = sizeOrZero(dbFile) + sizeOrZero(dbFile.resolveSibling("${dbFile.fileName}-wal"))

    private fun sizeOrZero(file: Path): Long = runCatching { Files.size(file) }.getOrDefault(0L)

    /**
     * Frees up to [maxPages] pages from the end of the file once more than [RECLAIM_FLOOR_BYTES] are
     * free, then checkpoints with TRUNCATE so the shorter file and an empty WAL reach the disk now
     * rather than at the next auto-checkpoint. Runs in autocommit, outside [tx]: the checkpoint
     * cannot run inside this connection's own transaction.
     */
    @Synchronized
    override fun reclaimSpace(maxPages: Int): Boolean {
        connection.autoCommit = true
        try {
            connection.createStatement().use { statement ->
                val pageSize = pragmaLong(statement, "page_size")
                if (pragmaLong(statement, "freelist_count") * pageSize <= RECLAIM_FLOOR_BYTES) return false
                // executeUpdate, not execute: SQLite frees one page per step of this pragma, and
                // sqlite-jdbc's execute() steps once, freeing one page however many were asked for.
                statement.executeUpdate("PRAGMA incremental_vacuum($maxPages)")
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                return pragmaLong(statement, "freelist_count") * pageSize > RECLAIM_FLOOR_BYTES
            }
        } finally {
            connection.autoCommit = false
        }
    }

    @Synchronized
    override fun subscribe(
        scopeId: ByteArray,
        declared: ScopeBounds,
        now: Long,
        held: (ByteArray) -> Boolean,
    ): SubscribeResult =
        tx {
            val clamped =
                ScopeBounds(
                    maxFrames = declared.maxFrames.coerceIn(1, hardLimits.maxFramesCap),
                    ttlMs = declared.ttlMs.coerceIn(1L, hardLimits.maxTtlMs),
                    maxBlob = declared.maxBlob.coerceIn(1, hardLimits.maxBlob),
                )
            var row = readRow(scopeId)
            var reclaimed: ByteArray? = null
            if (row == null) {
                if (scopeRows + scopeDelta >= hardLimits.maxScopes) {
                    reclaimed =
                        prep(BARE_BY_ACTIVITY_SQL)
                            .executeQuery()
                            .use { rows ->
                                var found: ByteArray? = null
                                while (found == null && rows.next()) found = rows.getBytes(1).takeUnless(held)
                                found
                            } ?: return@tx SubscribeResult.QuotaExceeded
                    deleteScope(reclaimed)
                }
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
                scopeDelta++
                row = ScopeRow(clamped, digest = 0L, liveCount = 0, liveBytes = 0L, attachBytes = 0L)
            } else {
                row.bounds = clamped
            }
            sweepScope(scopeId, row, now)
            enforceTombstoneCap(scopeId, row.bounds)
            writeRow(scopeId, row, now)
            SubscribeResult.Subscribed(digestInfo(row), reclaimed)
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
    ): List<ByteArray> =
        tx {
            val row = readRow(scopeId) ?: return@tx emptyList()
            sweepScope(scopeId, row, now)
            writeRow(scopeId, row, now)
            // Ids only, not bytes: the data streams through `blob`, one blob at a time, so nothing
            // here holds more than the id list.
            blobIds.filter { blobId ->
                val select = prep("SELECT 1 FROM blobs WHERE scope_id = ? AND blob_id = ?")
                select.setBytes(1, scopeId)
                select.setBytes(2, blobId)
                select.executeQuery().use { it.next() }
            }
        }

    @Synchronized
    override fun blob(
        scopeId: ByteArray,
        blobId: ByteArray,
        now: Long,
    ): ByteArray? =
        tx {
            val select = prep("SELECT data FROM blobs WHERE scope_id = ? AND blob_id = ?")
            select.setBytes(1, scopeId)
            select.setBytes(2, blobId)
            select.executeQuery().use { if (it.next()) it.getBytes(1) else null }
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

    /**
     * One batch of the sweep (ADR 2026-10.d9ez). Only scopes [SWEEP_WINDOW_SQL] marks due are swept,
     * and a row is written back only when the sweep moved it: a tombstone aging out does not. Every
     * scope used to be read and rewritten in one transaction per pass, a stall that grew with the
     * scope count with every record queued behind it (knit-spool#4).
     */
    @Synchronized
    override fun sweep(
        now: Long,
        after: ByteArray?,
        limit: Int,
    ): ScopeBatch<SweepChange> =
        tx {
            val select = prep(SWEEP_WINDOW_SQL)
            for (param in 1..4) select.setLong(param, now)
            val due = ArrayList<Pair<ByteArray, ScopeRow>>()
            val next =
                window(select, cursorParam = 5, after, limit) { rows ->
                    if (rows.getBoolean(9)) due.add(rows.getBytes(1) to scopeRow(rows, from = 2))
                }
            val changes =
                due.mapNotNull { (scopeId, row) ->
                    val attachBefore = row.attachBytes
                    val changed = sweepScope(scopeId, row, now)
                    if (changed || row.attachBytes != attachBefore) writeRow(scopeId, row, lastActivity = null)
                    if (changed) SweepChange(scopeId, digestInfo(row)) else null
                }
            ScopeBatch(changes, next)
        }

    /**
     * Steps one key-ordered window ([SWEEP_WINDOW_SQL], [RECLAIM_WINDOW_SQL]) whose last two
     * parameters, from [cursorParam], are the cursor and the limit. Hands [each] every row, then
     * returns where the next window starts: null once a short window has ended the pass. The rows
     * are read out before the caller writes anything, so no cursor is open across its deletes.
     */
    private fun window(
        select: PreparedStatement,
        cursorParam: Int,
        after: ByteArray?,
        limit: Int,
        each: (ResultSet) -> Unit,
    ): ByteArray? {
        require(limit > 0) { "limit must be positive, was $limit" }
        // An empty blob sorts before every scope id (SQLite compares blobs by memcmp, shorter first).
        select.setBytes(cursorParam, after ?: ByteArray(0))
        select.setInt(cursorParam + 1, limit)
        var seen = 0
        var last: ByteArray? = null
        select.executeQuery().use { rows ->
            while (rows.next()) {
                seen++
                last = rows.getBytes(1)
                each(rows)
            }
        }
        return if (seen == limit) last else null
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
            deleteScope(shed.scopeId)
            bytesTotal.addAndGet(-shed.freedBytes)
            shed
        }

    /** One batch of the idle reclaim, windowed like [sweep] (ADR 2026-10.d9ez). */
    @Synchronized
    override fun reclaimIdle(
        now: Long,
        held: (ByteArray) -> Boolean,
        after: ByteArray?,
        limit: Int,
    ): ScopeBatch<ByteArray> =
        tx {
            val select = prep(RECLAIM_WINDOW_SQL)
            select.setLong(1, now)
            val idle = ArrayList<ByteArray>()
            val next =
                window(select, cursorParam = 2, after, limit) { rows ->
                    if (rows.getBoolean(2)) rows.getBytes(1).takeUnless(held)?.let(idle::add)
                }
            idle.forEach(::deleteScope)
            ScopeBatch(idle, next)
        }

    /** Deletes one scope and everything under it; the caller settles [bytesTotal]. */
    private fun deleteScope(scopeId: ByteArray) {
        // attachment_chunks has no foreign key of its own (see dropAttachment), so the scope
        // cascade would leave its rows behind — a dropped scope must take its bytes with it.
        val deleteChunks = prep("DELETE FROM attachment_chunks WHERE scope_id = ?")
        deleteChunks.setBytes(1, scopeId)
        deleteChunks.executeUpdate()
        val delete = prep("DELETE FROM scopes WHERE scope_id = ?")
        delete.setBytes(1, scopeId)
        scopeDelta -= delete.executeUpdate()
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
            // total ≤ hardLimits.maxATotal: attachmentPut refuses more and the boot heal drops any
            // header above it, so this is at most ⌈maxATotal / 8⌉ bytes and the Int sum cannot wrap.
            val bits = ByteArray((total + BITS_PER_BYTE - 1) / BITS_PER_BYTE)
            val chunkSelect = prep("SELECT idx FROM attachment_chunks WHERE scope_id = ? AND aid = ?")
            chunkSelect.setBytes(1, scopeId)
            chunkSelect.setBytes(2, aid)
            chunkSelect.executeQuery().use { rows ->
                while (rows.next()) {
                    val index = rows.getInt(1)
                    if (index in 0 until total) {
                        bits[index / BITS_PER_BYTE] =
                            (bits[index / BITS_PER_BYTE].toInt() or (HIGH_BIT ushr (index % BITS_PER_BYTE))).toByte()
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
            // Headers only, not bytes: the data streams through `attachmentChunk`, one chunk at a
            // time, so nothing here holds a chunk payload.
            val select =
                prep(
                    "SELECT idx, cid FROM attachment_chunks WHERE scope_id = ? AND aid = ? " +
                        "AND idx >= ? AND idx < ? ORDER BY idx ASC",
                )
            select.setBytes(1, scopeId)
            select.setBytes(2, aid)
            select.setInt(3, from)
            select.setInt(4, minOf(from.toLong() + n, total.toLong()).toInt())
            val out = ArrayList<AttachmentChunk>()
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    out.add(AttachmentChunk(idx = rows.getInt(1), total = total, cid = rows.getBytes(2)))
                }
            }
            out
        }
    }

    @Synchronized
    override fun attachmentChunk(
        scopeId: ByteArray,
        aid: ByteArray,
        idx: Int,
        now: Long,
    ): ByteArray? =
        tx {
            val select = prep("SELECT data FROM attachment_chunks WHERE scope_id = ? AND aid = ? AND idx = ?")
            select.setBytes(1, scopeId)
            select.setBytes(2, aid)
            select.setInt(3, idx)
            select.executeQuery().use { if (it.next()) it.getBytes(1) else null }
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
        // the scope row back. The total bound leads: a record that will be refused pays for no hash.
        if (total > hardLimits.maxATotal) return AputResult.QuotaExceeded
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
            val charge = ScopeStore.chunkCharge(data.size)
            row.attachBytes += charge
            bytesTotal.addAndGet(charge)
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
        val sum = prep("SELECT COALESCE(SUM($CHUNK_CHARGE_SQL), 0) FROM attachment_chunks WHERE scope_id = ? AND aid = ?")
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
        val select = prep("SELECT $ROW_COLUMNS FROM scopes WHERE scope_id = ?")
        select.setBytes(1, scopeId)
        return select.executeQuery().use { rows -> if (rows.next()) scopeRow(rows, from = 1) else null }
    }

    /** The [ROW_COLUMNS] of the current result row, the first of them at column [from]. */
    private fun scopeRow(
        rows: ResultSet,
        from: Int,
    ): ScopeRow =
        ScopeRow(
            bounds =
                ScopeBounds(
                    maxFrames = rows.getInt(from),
                    ttlMs = rows.getLong(from + 1),
                    maxBlob = rows.getInt(from + 2),
                ),
            digest = rows.getLong(from + 3),
            liveCount = rows.getInt(from + 4),
            liveBytes = rows.getLong(from + 5),
            attachBytes = rows.getLong(from + 6),
        )

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

    @Suppress("TooGenericExceptionCaught") // rolls back and rethrows; the type is not its business
    private fun <T> tx(block: () -> T): T =
        try {
            val result = block()
            connection.commit()
            scopeRows += scopeDelta
            result
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            scopeDelta = 0
        }

    private fun prep(sql: String): PreparedStatement = statements.getOrPut(sql) { connection.prepareStatement(sql) }
}
