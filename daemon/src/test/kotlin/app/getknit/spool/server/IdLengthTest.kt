// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.store.HardLimits
import ch.qos.logback.classic.Level
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire ids are `bstr32` (spec B-2-6, §7.2, §7.3): a `scope`, `blobId`, `aid` or `cid` of any other
 * length makes the record malformed, refused whole with `err malformed` before a push token is
 * spent or the store is touched. The codec cannot check this — CBOR decodes a byte string of any
 * length into a `ByteArray` — so the server does, first thing in every handler.
 */
class IdLengthTest {
    private val attachLimits =
        HardLimits(
            maxBlob = 1_024,
            maxFramesCap = 100,
            maxTtlMs = 86_400_000L,
            maxScopes = 4,
            maxAttachBytes = 4_096,
            maxAChunk = 512,
        )

    /** Every size here stays under the harness `maxRecord`, so `too_large` never fires first. */
    private fun badId(size: Int): ByteArray = ByteArray(size) { 0x5A }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** The refusal every test expects: `malformed`, the request's `q`, and `scope` only when given. */
    private fun assertMalformed(
        err: Err,
        q: Long,
        scope: ByteArray?,
    ) {
        assertEquals(ErrCode.MALFORMED, err.code)
        assertEquals(q, err.q)
        if (scope == null) assertNull(err.scope, "an off-length scope must not be echoed") else assertTrue(err.scope!!.contentEquals(scope))
    }

    @Test
    fun subRefusesAnOffLengthScopeAndKeepsTheConnection() {
        withServer {
            connect {
                helloHandshake()
                val sizes = listOf(0, 31, 33, 1_000)
                sizes.forEachIndexed { i, size ->
                    val q = 10L + i
                    val scope = badId(size)
                    sendRecord(Sub(t = RecordType.SUB, q = q, subs = listOf(ScopeSub(scope = scope, bounds = testBounds()))))
                    assertMalformed(expectRecord<Err>(RecordType.ERR), q, scope = null)
                    assertTrue(store.isUnknownScope(scope))
                }
                assertEquals(0, store.scopeCount())
                // Still a working connection: the refusal was in-band, not a close.
                subscribe(testScope(1))
                assertEquals(sizes.size.toLong(), spool.metrics.errCount(ErrCode.MALFORMED))
                assertEquals(0L, spool.metrics.errCount(ErrCode.INTERNAL))
            }
        }
    }

    @Test
    fun subIsRefusedWholeWhenAnyEntryIsOffLength() {
        withServer {
            connect {
                helloHandshake()
                val good = testScope(1)
                // The good entry comes FIRST, so a spool that processed entries in order until the
                // bad one would have subscribed it — which is exactly what must not happen.
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 7L,
                        subs =
                            listOf(
                                ScopeSub(scope = good, bounds = testBounds()),
                                ScopeSub(scope = badId(31), bounds = testBounds()),
                            ),
                    ),
                )
                assertMalformed(expectRecord<Err>(RecordType.ERR), 7L, scope = null)

                sendRecord(ScopeList(t = RecordType.LIST, q = 8L, scope = good))
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.NOT_SUBSCRIBED, err.code)
                assertTrue(store.isUnknownScope(good))
                assertEquals(0, store.scopeCount())
            }
        }
    }

    @Test
    fun everyScopedRecordRefusesAnOffLengthScopeBeforeAnyOtherCheck() {
        withServer(testConfig(hardLimits = attachLimits)) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                // Not subscribed, so without the length check these would answer `not_subscribed`.
                val bad = badId(33)
                val aid = ByteArray(32) { 7 }
                val (blobId, data) = testBlob(2)
                val records =
                    listOf<Pair<Long, ByteArray>>(
                        20L to RecordCodec.encode(ScopeList(t = RecordType.LIST, q = 20L, scope = bad)),
                        21L to RecordCodec.encode(Pull(t = RecordType.PULL, q = 21L, scope = bad, blobIds = listOf(blobId))),
                        22L to RecordCodec.encode(Ahave(t = RecordType.AHAVE, q = 22L, scope = bad, aid = aid)),
                        23L to RecordCodec.encode(Aget(t = RecordType.AGET, q = 23L, scope = bad, aid = aid, from = 0, n = 1)),
                        24L to RecordCodec.encode(aput(q = 24L, scope = bad, aid = aid, cid = sha256(data), data = data)),
                        25L to RecordCodec.encode(Push(t = RecordType.PUSH, q = 25L, scope = bad, blobId = blobId, data = data)),
                    )
                for ((q, bytes) in records) {
                    sendRaw(bytes)
                    assertMalformed(expectRecord<Err>(RecordType.ERR), q, scope = null)
                }
                assertEquals(0L, store.totalBytes())
                assertTrue(store.isUnknownScope(bad))

                sendRecord(Push(t = RecordType.PUSH, q = 26L, scope = testScope(1), blobId = blobId, data = data))
                assertEquals(26L, expectRecord<Ok>(RecordType.OK).q)
                assertEquals(0L, spool.metrics.errCount(ErrCode.INTERNAL))
            }
        }
    }

    @Test
    fun pullRefusesAnOffLengthBlobIdAnywhereInTheList() {
        withServer(testConfig(maxPull = 4)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                val good = testBlob(1).first

                sendRecord(Pull(t = RecordType.PULL, q = 30L, scope = scope, blobIds = listOf(good, badId(31))))
                assertMalformed(expectRecord<Err>(RecordType.ERR), 30L, scope = scope)

                // Past `maxPull`, where a well-formed id would be silently truncated away (S-7.2-5):
                // the record is still malformed, because the bytes are, wherever they sit.
                val beyondCap = List(4) { testBlob(it).first } + badId(33)
                sendRecord(Pull(t = RecordType.PULL, q = 31L, scope = scope, blobIds = beyondCap))
                assertMalformed(expectRecord<Err>(RecordType.ERR), 31L, scope = scope)

                sendRecord(Pull(t = RecordType.PULL, q = 32L, scope = scope, blobIds = listOf(good)))
                val ok = expectRecord<Ok>(RecordType.OK)
                assertEquals(32L, ok.q)
                assertTrue(ok.missing!!.single().contentEquals(good))
            }
        }
    }

    @Test
    fun attachmentRecordsRefuseAnOffLengthAid() {
        withServer(testConfig(hardLimits = attachLimits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                val data = ByteArray(16) { it.toByte() }
                val cid = sha256(data)
                var q = 40L
                for (aid in listOf(badId(31), badId(2_000))) {
                    sendRecord(Ahave(t = RecordType.AHAVE, q = q, scope = scope, aid = aid))
                    assertMalformed(expectRecord<Err>(RecordType.ERR), q++, scope = scope)
                    sendRecord(Aget(t = RecordType.AGET, q = q, scope = scope, aid = aid, from = 0, n = 1))
                    assertMalformed(expectRecord<Err>(RecordType.ERR), q++, scope = scope)
                    sendRecord(aput(q = q, scope = scope, aid = aid, cid = cid, data = data))
                    assertMalformed(expectRecord<Err>(RecordType.ERR), q++, scope = scope)
                }
                assertEquals(0L, store.totalBytes())

                // The store was reachable all along — just never for the bad ids.
                sendRecord(aput(q = q, scope = scope, aid = ByteArray(32) { 7 }, cid = cid, data = data))
                assertEquals(q, expectRecord<Ok>(RecordType.OK).q)
                assertTrue(store.totalBytes() > 0L)
            }
        }
    }

    @Test
    fun offLengthBlobIdAndCidAreMalformedNotBadId() {
        withServer(testConfig(hardLimits = attachLimits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                val (_, data) = testBlob(1)

                // A 32-byte id that is not SHA-256(data) is still `bad_id` (SubPushPullTest); a
                // wrong-LENGTH one never gets as far as the hash and is malformed instead.
                sendRecord(Push(t = RecordType.PUSH, q = 50L, scope = scope, blobId = badId(31), data = data))
                assertMalformed(expectRecord<Err>(RecordType.ERR), 50L, scope = scope)
                sendRecord(aput(q = 51L, scope = scope, aid = ByteArray(32) { 7 }, cid = badId(33), data = data))
                assertMalformed(expectRecord<Err>(RecordType.ERR), 51L, scope = scope)

                assertEquals(0L, store.totalBytes())
                // The push counter sits after the guard, so a malformed push never reaches it.
                assertEquals(0L, spool.metrics.pushesTotal.sum())
            }
        }
    }

    @Test
    fun anOffLengthIdSpendsNoPushToken() {
        // One push per second bursts to four, and the fake clock never refills: the connection has
        // exactly four push tokens for the life of this test.
        withServer(testConfig(ratePushes = 1)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                val (blobId, data) = testBlob(1)

                (60L..63L).forEach { q ->
                    sendRecord(Push(t = RecordType.PUSH, q = q, scope = scope, blobId = badId(31), data = data))
                    assertMalformed(expectRecord<Err>(RecordType.ERR), q, scope = scope)
                }
                // Four valid pushes of the same blob (duplicates ack `ok` with no fan-out) all fit...
                (64L..67L).forEach { q ->
                    sendRecord(Push(t = RecordType.PUSH, q = q, scope = scope, blobId = blobId, data = data))
                    assertEquals(q, expectRecord<Ok>(RecordType.OK).q)
                }
                // ...and the fifth is the one that finds the bucket empty. Had the malformed pushes
                // spent tokens, the first valid one would already have been refused.
                sendRecord(Push(t = RecordType.PUSH, q = 68L, scope = scope, blobId = blobId, data = data))
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }

    @Test
    fun attachmentIdLengthIsCheckedBeforeTheAttachmentSwitch() {
        // Attachments off: `requireAttachments` answers `malformed` too, but it used to echo the
        // scope as sent — a 1000-byte one included. The length check now wins and echoes nothing.
        val data = ByteArray(16)
        val events =
            withLogCapture("app.getknit.spool.server.SpoolServer") {
                withServer {
                    connect {
                        helloHandshake()
                        sendRecord(aput(q = 70L, scope = badId(1_000), aid = ByteArray(32) { 7 }, cid = sha256(data), data = data))
                        assertMalformed(expectRecord<Err>(RecordType.ERR), 70L, scope = null)
                    }
                }
            }
        assertTrue(events.none { it.level == Level.ERROR }, "a malformed id must not reach the guarded catch-all")
    }
}
