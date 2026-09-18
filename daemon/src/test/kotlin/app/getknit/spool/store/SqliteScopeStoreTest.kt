// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
            store.subscribe(scope, bounds, now = 0L)
            blobs.forEachIndexed { i, (id, data) -> store.push(scope, id, data, now = i.toLong()) }
            expectedDigest = assertIs<DigestInfo>(store.digest(scope, now = 4L)).digest
        }

        createStore().use { store ->
            assertEquals(false, store.isUnknownScope(scope))
            val info = assertIs<SubscribeResult.Subscribed>(store.subscribe(scope, bounds, now = 5L)).digest
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
            store.subscribe(scope, bounds, now = 0L)
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
        createStore().use { store -> store.subscribe(scope, bounds, now = 0L) }

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
        store.subscribe(scope, bounds, now = 0L)
        store.close()

        assertFailsWith<SQLException> { store.scopeCount() }
    }

    @Test
    fun bootRecomputeHealsAPreFloorAttachBytesColumn() {
        val data = byteArrayOf(1)
        val cid = MessageDigest.getInstance("SHA-256").digest(data)
        val aid = ByteArray(32) { 9 }
        createStore().use { store ->
            store.subscribe(scope, bounds, now = 0L)
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
            store.subscribe(scope, bounds, now = 0L)
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
}
