// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
