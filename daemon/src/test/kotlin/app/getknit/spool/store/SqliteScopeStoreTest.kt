// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteScopeStoreTest : ScopeStoreContractTest() {
    @TempDir
    lateinit var tempDir: Path

    override fun createStore(): ScopeStore = SqliteScopeStore.open(tempDir, limits)

    private val scope = ByteArray(32) { 5 }
    private val bounds = ScopeBounds(maxFrames = 3, ttlMs = 10_000L, maxBlob = 1_024)

    private fun blob(seed: Int): Pair<ByteArray, ByteArray> {
        val data = ByteArray(40) { ((it * 7 + seed) and 0xFF).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(data) to data
    }

    @Test
    fun restartPreservesLiveSetTombstonesDigestAndBounds() {
        val blobs = (1..4).map { blob(it) }
        var expectedDigest = 0L
        createStore().use { store ->
            store.subscribe(scope, bounds, now = 0L, held = { false })
            blobs.forEachIndexed { i, (id, data) -> store.push(scope, id, data, now = i.toLong()) }
            expectedDigest = assertIs<DigestInfo>(store.digest(scope, now = 4L)).digest
        }

        createStore().use { store ->
            assertEquals(false, store.isUnknownScope(scope))
            val info = assertIs<SubscribeResult.Subscribed>(store.subscribe(scope, bounds, now = 5L, held = { false })).digest
            assertEquals(expectedDigest, info.digest)
            assertEquals(3, info.count)
            assertEquals(bounds.maxFrames, info.bounds.maxFrames)
            val list = store.list(scope, now = 5L)!!
            assertEquals(3, list.blobIds.size)
            assertTrue(list.tombstones.single().contentEquals(blobs.first().first))
            assertEquals(120L, store.totalBytes())
        }
    }

    @Test
    fun bootRecomputeHealsATamperedDigestColumn() {
        val (id, data) = blob(1)
        createStore().use { store ->
            store.subscribe(scope, bounds, now = 0L, held = { false })
            store.push(scope, id, data, now = 1L)
        }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("spool.db")}").use { raw ->
            raw.createStatement().use { it.executeUpdate("UPDATE scopes SET digest = 12345, live_bytes = 7") }
        }

        createStore().use { store ->
            val info = store.digest(scope, now = 2L)!!
            assertEquals(ScopeDigest.fnv64(id), info.digest)
            assertEquals(40L, store.totalBytes())
        }
    }

    /**
     * A schema-1 file — written before attachments existed — gains the `attach_bytes` column on
     * open, once, and is stamped schema 2 so the next open does not try again. Built by taking a
     * fresh file back to that shape rather than by checking in a fixture: the only difference
     * between the two versions is the one column and the meta row.
     */
    @Test
    fun aSchema1FileIsMigratedOnOpen() {
        createStore().use { store -> store.subscribe(scope, bounds, now = 0L, held = { false }) }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("spool.db")}").use { raw ->
            raw.createStatement().use {
                it.executeUpdate("ALTER TABLE scopes DROP COLUMN attach_bytes")
                it.executeUpdate("UPDATE meta SET value = '1' WHERE key = 'schema_version'")
            }
        }

        val data = byteArrayOf(1)
        val cid = MessageDigest.getInstance("SHA-256").digest(data)
        createStore().use { store ->
            assertEquals(false, store.isUnknownScope(scope))
            assertIs<AputResult.Stored>(store.attachmentPut(scope, ByteArray(32) { 9 }, 0, 1, cid, data, now = 1L))
            assertEquals(ScopeStore.ATTACH_CHUNK_FLOOR.toLong(), store.totalBytes())
        }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("spool.db")}").use { raw ->
            raw.createStatement().use { statement ->
                val version =
                    statement.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'").use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                assertEquals("2", version)
            }
        }
    }

    /** A closed store refuses rather than answering from a dead connection. */
    @Test
    fun aClosedStoreRefusesInsteadOfAnsweringStale() {
        val store = createStore()
        store.subscribe(scope, bounds, now = 0L, held = { false })
        store.close()

        assertFailsWith<SQLException> { store.scopeCount() }
    }

    @Test
    fun bootRecomputeHealsAPreFloorAttachBytesColumn() {
        val data = byteArrayOf(1)
        val cid = MessageDigest.getInstance("SHA-256").digest(data)
        val aid = ByteArray(32) { 9 }
        createStore().use { store ->
            store.subscribe(scope, bounds, now = 0L, held = { false })
            assertIs<AputResult.Stored>(store.attachmentPut(scope, aid, 0, 1, cid, data, now = 1L))
        }

        // A store written before the floor summed raw payload: one byte for this row.
        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("spool.db")}").use { raw ->
            raw.createStatement().use { it.executeUpdate("UPDATE scopes SET attach_bytes = 1") }
        }

        createStore().use { store ->
            val floor = ScopeStore.ATTACH_CHUNK_FLOOR.toLong()
            assertEquals(floor, store.totalBytes())
            // The shed reads the column itself, so this proves the row was healed, not just the gauge.
            assertEquals(floor, store.shedOldestScope()!!.freedBytes)
            assertEquals(0L, store.totalBytes())
        }
    }

    @Test
    fun bootDropsAnAttachmentDeclaringMoreChunksThanTheBoundOnReopen() {
        val data = byteArrayOf(1)
        val cid = MessageDigest.getInstance("SHA-256").digest(data)
        val aid = ByteArray(32) { 9 }
        createStore().use { store ->
            store.subscribe(scope, bounds, now = 0L, held = { false })
            assertIs<AputResult.Stored>(store.attachmentPut(scope, aid, 0, 1, cid, data, now = 1L))
        }

        // A store written before the bound holds whatever `total` the client chose.
        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("spool.db")}").use { raw ->
            raw.createStatement().use { it.executeUpdate("UPDATE attachments SET total = ${Int.MAX_VALUE}") }
        }

        createStore().use { store ->
            // Gone whole — header and chunk — and released from the charge, not merely hidden.
            val info = store.attachmentPresence(scope, aid, now = 2L)
            assertEquals(0, info.total)
            assertEquals(0, info.bits.size)
            assertTrue(!info.dead, "the drop must not tombstone: nothing conforming wrote it")
            assertEquals(0L, store.totalBytes())
            assertEquals(0, store.attachmentGet(scope, aid, from = 0, n = 1, now = 2L).size)
            assertIs<AputResult.Stored>(store.attachmentPut(scope, aid, 0, 1, cid, data, now = 3L))
        }
    }

    private val dbFile get() = tempDir.resolve("spool.db")

    private fun pragma(name: String): Long =
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { raw ->
            raw.createStatement().use { it.executeQuery("PRAGMA $name").use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
        }

    /** Room for [bigBlobs]: 64 KiB frames, a few hundred to a scope. */
    private val roomy = HardLimits(maxBlob = 65_536, maxFramesCap = 1_000, maxTtlMs = 86_400_000L, maxScopes = 8)
    private val roomyBounds = ScopeBounds(maxFrames = 1_000, ttlMs = 10_000L, maxBlob = 65_536)

    /** Pushes [count] distinct 64 KiB frames into [scope] at time 0: `count / 16` MiB of payload. */
    private fun ScopeStore.bigBlobs(count: Int) {
        subscribe(scope, roomyBounds, now = 0L, held = { false })
        val random = Random(42)
        repeat(count) {
            val data = random.nextBytes(65_536)
            assertIs<PushResult.Stored>(push(scope, MessageDigest.getInstance("SHA-256").digest(data), data, now = 0L))
        }
    }

    @Test
    fun aFreshDatabaseIsIncrementalAutoVacuum() {
        createStore().close()
        assertEquals(2L, pragma("auto_vacuum"))
    }

    /**
     * ADR 2026-10.us34: the TTL sweep empties the tables, and the reclaim, not the sweep, gives the
     * space back. Calling it once and checking the freelist fell by a whole batch is what pins the
     * `executeUpdate`: `execute()` steps `incremental_vacuum` once and frees one page per call.
     */
    @Test
    fun reclaimSpaceShrinksTheFileAfterFramesExpire() {
        SqliteScopeStore.open(tempDir, roomy).use { store ->
            store.bigBlobs(400) // 25 MiB
            store.sweep(now = 20_000L)
            assertEquals(0L, store.totalBytes())
            val bloated = assertNotNull(store.storeFileBytes())
            assertTrue(bloated > 20L * 1024 * 1024, "the expired frames should still be in the file: $bloated")

            val freeBefore = pragma("freelist_count")
            assertTrue(store.reclaimSpace(maxPages = 2_048))
            assertEquals(freeBefore - 2_048, pragma("freelist_count"))

            while (store.reclaimSpace(maxPages = 2_048)) continue
            val after = assertNotNull(store.storeFileBytes())
            assertTrue(after <= SqliteScopeStore.RECLAIM_FLOOR_BYTES + 1024 * 1024, "file still $after bytes")
            assertTrue(pragma("freelist_count") * pragma("page_size") <= SqliteScopeStore.RECLAIM_FLOOR_BYTES)
        }
    }

    /** Below the floor, churn's freed pages stay in the file for the next push to reuse. */
    @Test
    fun reclaimSpaceLeavesASmallFreelistAlone() {
        SqliteScopeStore.open(tempDir, roomy).use { store ->
            store.bigBlobs(32) // 2 MiB
            store.sweep(now = 20_000L)
            val before = store.storeFileBytes()
            val freeBefore = pragma("freelist_count")
            assertTrue(freeBefore > 0)
            assertTrue(!store.reclaimSpace(maxPages = 2_048))
            assertEquals(before, store.storeFileBytes())
            assertEquals(freeBefore, pragma("freelist_count"))
        }
    }

    /**
     * A file from before ADR 2026-10.us34 has auto_vacuum NONE and its high-water size. The first
     * open converts it with a VACUUM, which also drops what it was holding free, and keeps every
     * live frame. Built by taking a fresh file back to NONE rather than from a fixture.
     */
    @Test
    fun aNoneModeFileIsConvertedOnOpen() {
        var digest = 0L
        SqliteScopeStore.open(tempDir, roomy).use { store ->
            store.bigBlobs(400)
            store.sweep(now = 20_000L)
            val (id, data) = blob(1)
            assertIs<PushResult.Stored>(store.push(scope, id, data, now = 20_000L))
            digest = assertIs<DigestInfo>(store.digest(scope, now = 20_001L)).digest
        }
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { raw ->
            raw.createStatement().use {
                it.execute("PRAGMA auto_vacuum=NONE")
                // Rebuilds as NONE, so refill the free space the rebuild just dropped.
                it.execute("VACUUM")
                it.executeUpdate("CREATE TABLE filler (data BLOB)")
                it.executeUpdate(
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 320) " +
                        "INSERT INTO filler SELECT randomblob(65536) FROM n",
                )
                it.executeUpdate("DROP TABLE filler")
                it.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
        assertEquals(0L, pragma("auto_vacuum"))
        val bloated = Files.size(dbFile)
        assertTrue(bloated > 15L * 1024 * 1024, "the fixture should hold its freed pages: $bloated")

        SqliteScopeStore.open(tempDir, roomy).use { store ->
            assertEquals(2L, pragma("auto_vacuum"))
            assertTrue(assertNotNull(store.storeFileBytes()) < 1024L * 1024, "converted file ${store.storeFileBytes()} bytes")
            val info = assertIs<DigestInfo>(store.digest(scope, now = 20_002L))
            assertEquals(digest, info.digest)
            assertEquals(1, info.count)
            assertEquals(40L, store.totalBytes())
        }
    }

    /**
     * The conversion's rebuilt copy is a temp database the size of the live data. Left to SQLite it
     * spills to the temp directory once it outgrows the page cache, and under a read-only root that
     * is a small `/tmp` tmpfs, while the space check reads the data directory. With SQLite's temp
     * directory pointed elsewhere, the copy must still land in the data directory. SQLite unlinks a
     * temp file as soon as it opens it, so an inotify watch is what sees where it went. 10 MiB live
     * is well past the default 2 MiB page cache.
     */
    @Test
    fun theConversionKeepsItsTempCopyInTheDataDirectory() {
        var digest = 0L
        SqliteScopeStore.open(tempDir, roomy).use { store ->
            store.bigBlobs(160)
            digest = assertIs<DigestInfo>(store.digest(scope, now = 1L)).digest
        }
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { raw ->
            raw.createStatement().use {
                it.execute("PRAGMA auto_vacuum=NONE")
                it.execute("VACUUM")
                it.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
        assertEquals(0L, pragma("auto_vacuum"))

        val elsewhere = Files.createDirectory(tempDir.resolve("elsewhere"))
        setTempDirectory(elsewhere.toString())
        val created = mutableMapOf<Path, MutableList<String>>()
        try {
            FileSystems.getDefault().newWatchService().use { watcher ->
                val keys = listOf(tempDir, elsewhere).associateBy { it.register(watcher, ENTRY_CREATE) }
                SqliteScopeStore.open(tempDir, roomy).use { store ->
                    assertEquals(2L, pragma("auto_vacuum"))
                    assertEquals(digest, assertIs<DigestInfo>(store.digest(scope, now = 2L)).digest)
                }
                while (true) {
                    val key = watcher.poll(1, TimeUnit.SECONDS) ?: break
                    val dir = keys.getValue(key)
                    key.pollEvents().forEach { created.getOrPut(dir) { mutableListOf() } += it.context().toString() }
                    key.reset()
                }
            }
        } finally {
            setTempDirectory("")
        }
        assertEquals(emptyList(), created[elsewhere].orEmpty(), "temp files outside the data directory")
        assertTrue(created[tempDir].orEmpty().any { it.startsWith("etilqs_") }, "no temp copy in the data directory: $created")
    }

    /** SQLite's temp directory is process-wide, so setting it on any connection sets it for the store's. */
    private fun setTempDirectory(dir: String) =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { raw ->
            raw.createStatement().use { it.execute("PRAGMA temp_store_directory = '$dir'") }
        }
}
