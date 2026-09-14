// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Achunk
import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ahas
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.ScopeStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F6: `pull` and `aget` fetch each payload one at a time instead of materializing the whole result,
 * so a reader that stops reading pins one blob or chunk, not `maxPull × maxBlob` (up to 4 MiB) or
 * `maxAget × maxAChunk`. The heap bound is a property the review measured against a live server;
 * what a unit test can pin is the mechanism that gives it — the store hands out payloads per id,
 * once each, and only for what is served, because the fetch sits inside the send loop rather than
 * ahead of it.
 */
class StreamingReadTest {
    /** Delegates to a real store and counts the per-payload fetches the streaming handlers make. */
    private class CountingStore(
        private val inner: ScopeStore,
    ) : ScopeStore by inner {
        val blobFetches = AtomicInteger(0)
        val chunkFetches = AtomicInteger(0)

        override fun blob(
            scopeId: ByteArray,
            blobId: ByteArray,
            now: Long,
        ): ByteArray? {
            blobFetches.incrementAndGet()
            return inner.blob(scopeId, blobId, now)
        }

        override fun attachmentChunk(
            scopeId: ByteArray,
            aid: ByteArray,
            idx: Int,
            now: Long,
        ): ByteArray? {
            chunkFetches.incrementAndGet()
            return inner.attachmentChunk(scopeId, aid, idx, now)
        }
    }

    @Test
    fun pullFetchesEachBlobOnceAndNotTheMissingOnes() {
        val config = testConfig(maxPull = 8)
        val store = CountingStore(InMemoryScopeStore(config.hardLimits))
        withServer(config, store = store) {
            connect {
                helloHandshake()
                // maxFrames well above the four pushes: an eviction would broadcast a digest to the
                // uploader and this test asserts on the exact record sequence.
                subscribe(testScope(1), ScopeBounds(maxFrames = 100, ttlMs = 10_000L, maxBlob = 1_024))
                val held = (1..4).map { testBlob(it) }
                held.forEach { (id, data) ->
                    sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                    expectRecord<Ok>(RecordType.OK)
                }
                val absent = testBlob(9).first
                sendRecord(
                    Pull(
                        t = RecordType.PULL,
                        q = 2L,
                        scope = testScope(1),
                        blobIds = held.map { it.first } + absent,
                    ),
                )
                repeat(held.size) { expectRecord<Blob>(RecordType.BLOB) }
                expectRecord<Ok>(RecordType.OK)
            }
        }
        // One fetch per held blob, and none for the id the scope did not hold: `pull` returned the
        // live ids and the loop fetched their bytes one at a time, never a batch of all of them.
        assertEquals(4, store.blobFetches.get())
    }

    @Test
    fun agetFetchesEachChunkOnceOverTheRange() {
        val total = 4
        val config =
            testConfig(
                hardLimits =
                    HardLimits(
                        maxBlob = 1_024,
                        maxFramesCap = 100,
                        maxTtlMs = 86_400_000L,
                        maxScopes = 4,
                        maxAttachBytes = 1 shl 20,
                    ),
            )
        val store = CountingStore(InMemoryScopeStore(config.hardLimits))
        withServer(config, store = store) {
            connect {
                helloHandshake()
                subscribe(testScope(1), ScopeBounds(maxFrames = 3, ttlMs = 10_000L, maxBlob = 1_024))
                val aid = ByteArray(32) { 7 }
                repeat(total) { idx ->
                    val data = ByteArray(48) { (idx * 3 + it).toByte() }
                    val cid = MessageDigest.getInstance("SHA-256").digest(data)
                    sendRecord(
                        Aput(
                            t = RecordType.APUT,
                            q = 10L + idx,
                            scope = testScope(1),
                            aid = aid,
                            idx = idx,
                            total = total,
                            cid = cid,
                            data = data,
                        ),
                    )
                    expectRecord<Ok>(RecordType.OK)
                }
                sendRecord(Ahave(t = RecordType.AHAVE, q = 20L, scope = testScope(1), aid = aid))
                assertEquals(total, expectRecord<Ahas>(RecordType.AHAS).total)
                sendRecord(Aget(t = RecordType.AGET, q = 21L, scope = testScope(1), aid = aid, from = 0, n = total))
                repeat(total) { expectRecord<Achunk>(RecordType.ACHUNK) }
                expectRecord<Ok>(RecordType.OK)
            }
        }
        // One fetch per chunk in the range — streamed, not materialized as a list of payloads.
        assertEquals(total, store.chunkFetches.get())
    }
}
