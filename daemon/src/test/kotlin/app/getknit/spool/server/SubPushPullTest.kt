// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import kotlinx.coroutines.TimeoutCancellationException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scope operations per spec §7.2: sub/digest/list/pull/push/event and their error paths. */
class SubPushPullTest {
    private suspend fun io.ktor.websocket.WebSocketSession.pushBlob(
        scope: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        q: Long = 5L,
    ) {
        sendRecord(Push(t = RecordType.PUSH, q = q, scope = scope, blobId = blobId, data = data))
    }

    @Test
    fun opBeforeSubErrsNotSubscribed() {
        withServer {
            connect {
                helloHandshake()
                val (id, data) = testBlob(1)
                pushBlob(testScope(1), id, data, q = 42L)
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.NOT_SUBSCRIBED, err.code)
                assertEquals(42L, err.q)
            }
        }
    }

    @Test
    fun subReturnsOneDigestPerScope() {
        withServer {
            connect {
                helloHandshake()
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 1L,
                        subs =
                            listOf(
                                ScopeSub(scope = testScope(1), bounds = testBounds()),
                                ScopeSub(scope = testScope(2), bounds = testBounds()),
                            ),
                    ),
                )
                val first = expectRecord<Digest>(RecordType.DIGEST)
                val second = expectRecord<Digest>(RecordType.DIGEST)
                assertTrue(first.scope.contentEquals(testScope(1)))
                assertTrue(second.scope.contentEquals(testScope(2)))
                assertEquals(0, first.count)
                assertTrue(first.digest.contentEquals(ByteArray(8)))
            }
        }
    }

    @Test
    fun subClampsDeclaredBounds() {
        val config = testConfig()
        withServer(config) {
            connect {
                helloHandshake()
                val digest =
                    subscribe(
                        testScope(1),
                        ScopeBounds(maxFrames = Int.MAX_VALUE, ttlMs = Long.MAX_VALUE, maxBlob = Int.MAX_VALUE),
                    )
                assertEquals(config.hardLimits.maxFramesCap, digest.bounds.maxFrames)
                assertEquals(config.hardLimits.maxTtlMs, digest.bounds.ttlMs)
                assertEquals(config.hardLimits.maxBlob, digest.bounds.maxBlob)
            }
        }
    }

    @Test
    fun pushOkThenListShowsTheId() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val (id, data) = testBlob(1)
                pushBlob(testScope(1), id, data)
                assertEquals(5L, expectRecord<Ok>(RecordType.OK).q)
                sendRecord(ScopeList(t = RecordType.LIST, q = 6L, scope = testScope(1)))
                val list = expectRecord<ScopeList>(RecordType.LIST)
                assertEquals(6L, list.q)
                assertTrue(list.blobIds!!.single().contentEquals(id))
                assertNull(list.tombstones)
            }
        }
    }

    @Test
    fun pushBadIdErrs() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val (_, data) = testBlob(1)
                pushBlob(testScope(1), ByteArray(32), data)
                assertEquals(ErrCode.BAD_ID, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }

    @Test
    fun pushOverDeclaredMaxBlobErrsTooLarge() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1), ScopeBounds(maxFrames = 3, ttlMs = 10_000L, maxBlob = 64))
                val data = ByteArray(100) { it.toByte() }
                val id = MessageDigest.getInstance("SHA-256").digest(data)
                pushBlob(testScope(1), id, data)
                assertEquals(ErrCode.TOO_LARGE, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }

    @Test
    fun pullStreamsBlobsThenOkWithMissing() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val (id, data) = testBlob(1)
                pushBlob(testScope(1), id, data)
                expectRecord<Ok>(RecordType.OK)
                val unknown = testBlob(9).first
                sendRecord(Pull(t = RecordType.PULL, q = 7L, scope = testScope(1), blobIds = listOf(id, unknown)))
                val blob = expectRecord<Blob>(RecordType.BLOB)
                assertTrue(blob.blobId.contentEquals(id))
                assertTrue(blob.data.contentEquals(data))
                val ok = expectRecord<Ok>(RecordType.OK)
                assertEquals(7L, ok.q)
                assertTrue(ok.missing!!.single().contentEquals(unknown))
            }
        }
    }

    @Test
    fun pullTruncatesToMaxPull() {
        withServer(testConfig(maxPull = 4)) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val (id, data) = testBlob(1)
                pushBlob(testScope(1), id, data)
                expectRecord<Ok>(RecordType.OK)
                // Six unknown ids first, the held one last: truncation to 4 must drop it.
                val wanted = (10..15).map { testBlob(it).first } + listOf(id)
                sendRecord(Pull(t = RecordType.PULL, q = 8L, scope = testScope(1), blobIds = wanted))
                val ok = expectRecord<Ok>(RecordType.OK)
                assertEquals(4, ok.missing!!.size)
            }
        }
    }

    @Test
    fun eventFanOutExcludesTheUploader() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val uploader = this
                connect {
                    helloHandshake()
                    subscribe(testScope(1))
                    val (id, data) = testBlob(1)
                    with(uploader) { pushBlob(testScope(1), id, data) }
                    with(uploader) { expectRecord<Ok>(RecordType.OK) }
                    val event = expectRecord<Event>(RecordType.EVENT)
                    assertTrue(event.blobId.contentEquals(id))
                    assertFailsWith<TimeoutCancellationException> { uploader.nextBinary(timeoutMs = 300L) }
                }
            }
        }
    }

    @Test
    fun duplicatePushAcksWithoutASecondEvent() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val uploader = this
                connect {
                    helloHandshake()
                    subscribe(testScope(1))
                    val (id, data) = testBlob(1)
                    with(uploader) { pushBlob(testScope(1), id, data) }
                    with(uploader) { expectRecord<Ok>(RecordType.OK) }
                    expectRecord<Event>(RecordType.EVENT)
                    with(uploader) { pushBlob(testScope(1), id, data, q = 6L) }
                    assertEquals(6L, with(uploader) { expectRecord<Ok>(RecordType.OK) }.q)
                    assertFailsWith<TimeoutCancellationException> { nextBinary(timeoutMs = 300L) }
                }
            }
        }
    }

    @Test
    fun tombstonedRePushErrsEndToEnd() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1)) // maxFrames = 3
                val blobs = (1..4).map { testBlob(it) }
                blobs.forEach { (id, data) ->
                    pushBlob(testScope(1), id, data)
                }
                // Four oks, interleaved with the digest re-anchor for the eviction on the fourth.
                var oks = 0
                while (oks < 4) {
                    val bytes = nextBinary()
                    when (RecordCodec.peekType(bytes)) {
                        RecordType.OK -> oks++
                        RecordType.DIGEST -> Unit
                        else -> error("unexpected record")
                    }
                }
                pushBlob(testScope(1), blobs.first().first, blobs.first().second)
                var err: Err? = null
                while (err == null) {
                    val bytes = nextBinary()
                    when (RecordCodec.peekType(bytes)) {
                        RecordType.ERR -> err = RecordCodec.decode<Err>(bytes)

                        RecordType.DIGEST -> Unit

                        // the eviction re-anchor may still be in flight
                        else -> error("unexpected record")
                    }
                }
                assertEquals(ErrCode.TOMBSTONED, err.code)
            }
        }
    }

    @Test
    fun evictionBroadcastsAnUnsolicitedDigest() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val uploader = this
                connect {
                    helloHandshake()
                    subscribe(testScope(1))
                    (1..4).forEach { seed ->
                        val (id, data) = testBlob(seed)
                        with(uploader) { pushBlob(testScope(1), id, data) }
                        with(uploader) { expectRecord<Ok>(RecordType.OK) }
                    }
                    // This subscriber sees the four events, then the eviction re-anchor.
                    var digest: Digest? = null
                    while (digest == null) {
                        val bytes = nextBinary()
                        if (RecordCodec.peekType(bytes) == RecordType.DIGEST) {
                            digest = RecordCodec.decode<Digest>(bytes)
                        }
                    }
                    assertEquals(3, digest.count)
                    assertEquals(true, digest.full)
                }
            }
        }
    }

    @Test
    fun maxScopesQuotaErrsPerScope() {
        withServer {
            // hardLimits.maxScopes = 4
            connect {
                helloHandshake()
                (1..4).forEach { subscribe(testScope(it)) }
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 9L,
                        subs = listOf(ScopeSub(scope = testScope(5), bounds = testBounds())),
                    ),
                )
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.QUOTA, err.code)
                assertEquals(9L, err.q)
                assertTrue(err.scope!!.contentEquals(testScope(5)))
            }
        }
    }
}
