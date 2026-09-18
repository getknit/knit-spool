// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.store.HardLimits
import io.ktor.websocket.WebSocketSession
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every verdict the store can return for an `aput` reaches the wire as the §6.5 error code it
 * maps to, with the request's `q` and scope on it — and the record passes the same gates a `push`
 * does on its way in: the per-connection push budget, and the creation gates when the scope was
 * shed meanwhile. The store side of each verdict is pinned by the contract test; this is the
 * translation, which is the part a client actually sees.
 */
class AttachmentErrTest {
    /** The contract test's budget: three 600-byte chunks overflow it, and one 1,025-byte chunk is over the cap. */
    private val attachLimits =
        HardLimits(
            maxBlob = 1_024,
            maxFramesCap = 100,
            maxTtlMs = 86_400_000L,
            maxScopes = 4,
            maxAttachBytes = 1_500,
            maxAChunk = 1_024,
            maxATotal = 8,
        )

    private fun chunk(
        seed: Int,
        size: Int = 600,
    ): Pair<ByteArray, ByteArray> {
        val data = ByteArray(size) { ((it * 11 + seed) and 0xFF).toByte() }
        data[0] = seed.toByte()
        return MessageDigest.getInstance("SHA-256").digest(data) to data
    }

    private fun aputOf(
        q: Long,
        scope: ByteArray,
        aid: ByteArray,
        idx: Int = 0,
        total: Int = 1,
        cid: ByteArray,
        data: ByteArray,
    ): Aput = Aput(t = RecordType.APUT, q = q, scope = scope, aid = aid, idx = idx, total = total, cid = cid, data = data)

    @Test
    fun duplicateConflictBadIdAndTooLargeAnswerTheirCodes() {
        withServer(testConfig(hardLimits = attachLimits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                val aid = ByteArray(32) { 1 }
                val (cid, data) = chunk(1, size = 16)

                sendRecord(aputOf(10L, scope, aid, cid = cid, data = data))
                assertEquals(10L, expectRecord<Ok>(RecordType.OK).q)

                // Byte-identical at the same position: acked again, nothing stored twice.
                sendRecord(aputOf(11L, scope, aid, cid = cid, data = data))
                assertEquals(11L, expectRecord<Ok>(RecordType.OK).q)
                assertEquals(1, store.attachmentPresence(scope, aid, now = clock.now).total)

                // A different chunk at that position: first write wins.
                val (otherCid, otherData) = chunk(2, size = 16)
                sendRecord(aputOf(12L, scope, aid, cid = otherCid, data = otherData))
                expectErr(ErrCode.CONFLICT, q = 12L, scope = scope)

                // A cid that is not the SHA-256 of the bytes it came with.
                sendRecord(aputOf(13L, scope, ByteArray(32) { 2 }, cid = cid, data = otherData))
                expectErr(ErrCode.BAD_ID, q = 13L, scope = scope)

                // One byte over the chunk cap, well inside maxRecord.
                val (bigCid, bigData) = chunk(3, size = attachLimits.maxAChunk + 1)
                sendRecord(aputOf(14L, scope, ByteArray(32) { 3 }, cid = bigCid, data = bigData))
                expectErr(ErrCode.TOO_LARGE, q = 14L, scope = scope)
            }
        }
    }

    @Test
    fun rePuttingAnEvictedAttachmentIsRefusedTombstoned() {
        withServer(testConfig(hardLimits = attachLimits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                val old = ByteArray(32) { 1 }
                val new = ByteArray(32) { 2 }
                val (cidA, dataA) = chunk(0)
                val (cidB, dataB) = chunk(1)
                val (cidC, dataC) = chunk(2)

                // 3 x 600 bytes against a 1,500-byte budget: the third evicts the first attachment whole.
                sendRecord(aputOf(10L, scope, old, cid = cidA, data = dataA))
                expectRecord<Ok>(RecordType.OK)
                sendRecord(aputOf(11L, scope, new, idx = 0, total = 2, cid = cidB, data = dataB))
                expectRecord<Ok>(RecordType.OK)
                sendRecord(aputOf(12L, scope, new, idx = 1, total = 2, cid = cidC, data = dataC))
                expectRecord<Ok>(RecordType.OK)

                // The uploader refilling what it just lost is told to stop, not silently re-evicted.
                sendRecord(aputOf(13L, scope, old, cid = cidA, data = dataA))
                expectErr(ErrCode.TOMBSTONED, q = 13L, scope = scope)
            }
        }
    }

    @Test
    fun aputDrawsOnThePushBudget() {
        // One push per second bursts to four; the fifth record in the same instant is over.
        withServer(testConfig(hardLimits = attachLimits, ratePushes = 1)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                (1..4).forEach { i ->
                    val (cid, data) = chunk(i, size = 16)
                    sendRecord(aputOf(i.toLong(), scope, ByteArray(32) { i.toByte() }, cid = cid, data = data))
                    assertEquals(i.toLong(), expectRecord<Ok>(RecordType.OK).q)
                }
                val (cid, data) = chunk(5, size = 16)
                sendRecord(aputOf(5L, scope, ByteArray(32) { 5 }, cid = cid, data = data))
                val err = expectErr(ErrCode.RATE, q = 5L, scope = scope)
                assertTrue(err.retryMs!! > 0, "err rate carries a positive retryMs")
            }
        }
    }

    /** An `aput` recreates a shed scope exactly as a `push` does (§6.2/§6.4): digest re-anchor, then the ack. */
    @Test
    fun aputToAShedScopeRecreatesItThenStores() {
        withServer(testConfig(hardLimits = attachLimits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                store.shedOldestScope()
                assertTrue(store.isUnknownScope(scope))

                val (cid, data) = chunk(1, size = 16)
                sendRecord(aputOf(10L, scope, ByteArray(32) { 1 }, cid = cid, data = data))
                assertEquals(0, expectRecord<Digest>(RecordType.DIGEST).count)
                assertEquals(10L, expectRecord<Ok>(RecordType.OK).q)
                assertTrue(!store.isUnknownScope(scope))
            }
        }
    }

    /**
     * Recreating a shed scope competes for the same quota as any new one. When other connections
     * have filled the table meanwhile, the `push` or `aput` that would have recreated it is refused
     * `quota` — not stored into a scope that does not exist, and not silently dropped.
     */
    @Test
    fun recreatingAShedScopeAtQuotaIsRefusedQuota() {
        val limits =
            HardLimits(
                maxBlob = 1_024,
                maxFramesCap = 100,
                maxTtlMs = 86_400_000L,
                maxScopes = 2,
                maxAttachBytes = 1_500,
                maxAChunk = 1_024,
                maxATotal = 8,
            )
        withServer(testConfig(hardLimits = limits)) {
            connect {
                helloHandshake()
                val shed = testScope(1)
                subscribe(shed)
                store.shedOldestScope()

                connect {
                    helloHandshake()
                    subscribe(testScope(2), q = 1L)
                    subscribe(testScope(3), q = 2L)
                }
                assertEquals(limits.maxScopes, store.scopeCount())

                val (id, blob) = testBlob(1)
                sendRecord(Push(t = RecordType.PUSH, q = 10L, scope = shed, blobId = id, data = blob))
                expectErr(ErrCode.QUOTA, q = 10L, scope = shed)

                val (cid, data) = chunk(1, size = 16)
                sendRecord(aputOf(11L, scope = shed, aid = ByteArray(32) { 1 }, cid = cid, data = data))
                expectErr(ErrCode.QUOTA, q = 11L, scope = shed)
                assertTrue(store.isUnknownScope(shed), "a refused recreate must not create the scope")
            }
        }
    }

    private suspend fun WebSocketSession.expectErr(
        code: String,
        q: Long,
        scope: ByteArray,
    ): Err {
        val err = expectRecord<Err>(RecordType.ERR)
        assertEquals(code, err.code)
        assertEquals(q, err.q)
        assertTrue(err.scope!!.contentEquals(scope), "err carries the request's scope")
        return err
    }
}
