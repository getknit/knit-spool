// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.store.HardLimits
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.WebSocketSession
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The commons per spec §7.4: one operator-declared scope shared by everyone on the spool who holds
 * the invite. On the data path it is an ordinary scope — `sub`/`push`/`event` are unchanged — so
 * what is tested here is the policy that makes it survivable as a *shared* room: bounds its members
 * cannot move, a watermark that cannot take it away, a spool-wide push budget that throttles
 * without accusing anyone, and a `hello` that says the room exists without saying where.
 */
class CommonsTest {
    @Test
    fun helloAdvertisesTheCommonsBoundsButNeverItsId() {
        withServer(testConfig(commons = testCommons(name = "lax commons", maxFrames = 7, ttlMs = 60_000L))) {
            connect {
                val hello = helloHandshake()
                val commons = assertNotNull(hello.commons, "hello omitted the commons")
                assertEquals("lax commons", commons.name)
                assertEquals(7, commons.maxFrames)
                assertEquals(60_000L, commons.ttlMs)
                assertFalse(commons.attach)
                // The id is what the invite buys. A spool that published it would turn a room only
                // invite holders can find into one anybody who connects can subscribe to and flood.
                assertFalse(
                    RecordCodec.encode(hello).containsBytes(commonsScope()),
                    "the server hello carried the commons scope id",
                )
            }
        }
    }

    @Test
    fun helloOmitsTheCommonsWhenDisabled() {
        withServer(testConfig()) {
            connect {
                assertNull(helloHandshake().commons)
            }
        }
    }

    @Test
    fun joiningTheCommonsNeedsNoProofOfWork() {
        // 20 bits costs a client real seconds to mine. The commons exists from boot, so it is never
        // an unknown scope and the §6.4 creation gates never fire for it.
        withServer(testConfig(powBits = 20, commons = testCommons())) {
            connect {
                helloHandshake()
                assertEquals(0, subscribe(commonsScope()).count)
            }
        }
    }

    @Test
    fun commonsBoundsAreOperatorPinnedNotClientDeclared() {
        // The regression guard for the eviction attack. The store applies whatever the most recent
        // subscriber declared, so unpinned, one member asking for maxFrames = 1 would evict
        // everyone else's history on its way into the room.
        withServer(testConfig(commons = testCommons(maxFrames = 5, ttlMs = 60_000L, maxBlob = 1_024))) {
            connect {
                helloHandshake()
                val hostile = ScopeBounds(maxFrames = 1, ttlMs = 1L, maxBlob = 1)
                val digest = subscribe(commonsScope(), bounds = hostile)
                assertEquals(5, digest.bounds.maxFrames, "a member moved the commons frame cap")
                assertEquals(60_000L, digest.bounds.ttlMs)
                assertEquals(1_024, digest.bounds.maxBlob)

                repeat(4) { seed ->
                    val (id, data) = testBlob(seed)
                    pushBlob(commonsScope(), id, data, q = 10L + seed)
                    expectRecord<Ok>(RecordType.OK)
                }
                // All four still live: none would have survived the bounds the client asked for.
                assertEquals(4, subscribe(commonsScope(), bounds = hostile, q = 99L).count)
            }
        }
    }

    @Test
    fun commonsIsNeverShedByTheWatermark() {
        // Two 40-byte blobs over a 60-byte watermark: something has to go, and it must not be the
        // room everyone shares.
        withServer(testConfig(maxBytes = 60L, commons = testCommons())) {
            val ordinary = testScope(9)
            connect {
                helloHandshake()
                subscribe(commonsScope())
                subscribe(ordinary, q = 2L)
                val (commonsId, commonsData) = testBlob(1)
                pushBlob(commonsScope(), commonsId, commonsData, q = 3L)
                expectRecord<Ok>(RecordType.OK)
                val (otherId, otherData) = testBlob(2)
                pushBlob(ordinary, otherId, otherData, q = 4L)
                expectRecord<Ok>(RecordType.OK)
            }
            spool.sweepTick()
            assertTrue(store.isUnknownScope(ordinary), "the ordinary scope should have been shed")
            assertFalse(store.isUnknownScope(commonsScope()), "the commons was shed")
        }
    }

    @Test
    fun commonsBudgetThrottlesWithoutStrikingTheConnection() {
        // One push per second, burst four, on a clock that never advances — so everything past the
        // burst is refused. A member throttled by a budget it shares with everyone else has not
        // misbehaved: eight strikes would close it 4003 and tell a well-behaved client it abused
        // the spool.
        withServer(testConfig(commons = testCommons(ratePushes = 1))) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                var refusals = 0
                repeat(16) { seed ->
                    val (id, data) = testBlob(seed)
                    pushBlob(commonsScope(), id, data, q = 20L + seed)
                    val bytes = nextBinary()
                    if (RecordCodec.peekType(bytes) == RecordType.ERR) {
                        val err = assertNotNull(RecordCodec.decode<Err>(bytes))
                        assertEquals(ErrCode.RATE, err.code)
                        assertNotNull(err.retryMs, "err rate must carry retryMs")
                        refusals++
                    }
                }
                assertTrue(refusals > RATE_STRIKE_LIMIT, "expected more refusals ($refusals) than the strike limit")
                // Still open and still serving — the refusals never became strikes.
                subscribe(commonsScope(), q = 90L)
            }
        }
    }

    @Test
    fun commonsRefusesAttachmentsWhenTheyAreOff() {
        val hardLimits =
            HardLimits(
                maxBlob = 1_024,
                maxFramesCap = 100,
                maxTtlMs = 86_400_000L,
                maxScopes = 4,
                maxAttachBytes = 4_096,
                maxAChunk = 512,
            )
        withServer(testConfig(hardLimits = hardLimits, commons = testCommons(attach = false))) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                val ordinary = testScope(4)
                subscribe(ordinary, q = 2L)

                val data = ByteArray(16) { it.toByte() }
                val cid = MessageDigest.getInstance("SHA-256").digest(data)
                val aid = ByteArray(32) { 7 }
                sendRecord(aput(q = 3L, scope = commonsScope(), aid = aid, cid = cid, data = data))
                assertEquals(ErrCode.MALFORMED, expectRecord<Err>(RecordType.ERR).code)

                // The same chunk into an ordinary scope still stores: the switch is the commons',
                // not the spool's.
                sendRecord(aput(q = 4L, scope = ordinary, aid = aid, cid = cid, data = data))
                assertEquals(4L, expectRecord<Ok>(RecordType.OK).q)
            }
        }
    }

    @Test
    fun commonsFansOutToTheOtherMembers() {
        withServer(testConfig(commons = testCommons())) {
            connect {
                val sender = this
                helloHandshake()
                subscribe(commonsScope())
                connect {
                    helloHandshake()
                    subscribe(commonsScope())
                    val (id, data) = testBlob(1)
                    with(sender) {
                        pushBlob(commonsScope(), id, data)
                        expectRecord<Ok>(RecordType.OK)
                    }
                    val event = expectRecord<Event>(RecordType.EVENT)
                    assertTrue(event.scope.contentEquals(commonsScope()))
                    assertTrue(event.data.contentEquals(data))
                }
            }
        }
    }

    @Test
    fun commonsSeriesAppearOnlyWhenTheCommonsIsEnabled() {
        withServer(testConfig(commons = testCommons())) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                val body = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
                assertTrue(body.contains("knit_spool_commons_subscribers 1"), body)
                assertTrue(body.contains("knit_spool_commons_pushes_total"), body)
                assertTrue(body.contains("knit_spool_commons_rate_limited_total"), body)
            }
        }
        withServer(testConfig()) {
            // Not three flat zeroes: a spool with no commons has no commons series at all.
            assertFalse(http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("knit_spool_commons"))
        }
    }

    private companion object {
        /** Mirrors [SpoolServer]'s private RATE_STRIKE_LIMIT, so the assertion states its intent. */
        const val RATE_STRIKE_LIMIT = 8

        suspend fun WebSocketSession.pushBlob(
            scope: ByteArray,
            blobId: ByteArray,
            data: ByteArray,
            q: Long = 2L,
        ) {
            sendRecord(Push(t = RecordType.PUSH, q = q, scope = scope, blobId = blobId, data = data))
        }

        fun aput(
            q: Long,
            scope: ByteArray,
            aid: ByteArray,
            cid: ByteArray,
            data: ByteArray,
        ): Aput =
            Aput(
                t = RecordType.APUT,
                q = q,
                scope = scope,
                aid = aid,
                idx = 0,
                total = 1,
                cid = cid,
                data = data,
            )

        fun ByteArray.containsBytes(needle: ByteArray): Boolean =
            (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
    }
}
