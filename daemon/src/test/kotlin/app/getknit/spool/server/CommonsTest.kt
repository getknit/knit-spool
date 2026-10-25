// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.SubscribeResult
import ch.qos.logback.classic.Level
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.WebSocketSession
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The commons per spec §7.4: operator-declared rooms, each shared by everyone on the spool who holds
 * its invite. On the data path a room is an ordinary scope — `sub`/`push`/`event` are unchanged — so
 * what is tested here is the policy that makes it survivable as a *shared* room: bounds its members
 * cannot move, a watermark that cannot take it away, push budgets that throttle without accusing
 * anyone, and a `hello` that says each room exists without saying where — and, with several rooms,
 * that none of this leaks from one room into another.
 */
class CommonsTest {
    @Test
    fun helloAdvertisesTheCommonsBoundsButNeverItsId() {
        withServer(testConfig(token = "t", commons = testCommons(name = "lax commons", maxFrames = 7, ttlMs = 60_000L))) {
            connect(token = "t") {
                val hello = helloHandshake()
                val commons = assertNotNull(hello.commons, "hello omitted the commons")
                assertEquals("lax commons", commons.name)
                assertEquals(7, commons.maxFrames)
                assertEquals(60_000L, commons.ttlMs)
                assertFalse(commons.attach)
                // One room, so `rooms` is that room again, led by its tag (S-7.4-1, S-7.4-15).
                val room = assertNotNull(hello.rooms).single()
                assertContentEquals(Commons.tag(commonsScope()), room.tag)
                assertEquals("lax commons", room.name)
                assertEquals(7, room.maxFrames)
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
    fun aSpoolWithNoTokenLeavesRoomNamesOutOfHello() {
        // S-7.4-19: without a token anyone who connects reads `hello`, so a room's name there would
        // be public. The name still reaches members, in the room's invite link.
        withServer(
            testConfig(rooms = listOf(testCommons(name = "Home"), testCommons(name = "Den", scopeId = commonsScope2(), number = 2))),
        ) {
            connect {
                val hello = helloHandshake()
                assertNull(assertNotNull(hello.commons).name)
                assertTrue(assertNotNull(hello.rooms).all { it.name == null })
                assertFalse(RecordCodec.encode(hello).containsBytes("Home".toByteArray()), "a name reached the hello")
            }
        }
    }

    @Test
    fun helloListsEveryRoomByTagAndRepeatsTheFirstAsCommons() {
        val first = testCommons(name = "Home", maxFrames = 5)
        val second = testCommons(name = null, maxFrames = 3, attach = true, scopeId = commonsScope2(), number = 2)
        withServer(testConfig(token = "t", rooms = listOf(first, second))) {
            connect(token = "t") {
                val hello = helloHandshake()
                val rooms = assertNotNull(hello.rooms)
                assertEquals(2, rooms.size)
                assertContentEquals(Commons.tag(commonsScope()), rooms[0].tag)
                assertContentEquals(Commons.tag(commonsScope2()), rooms[1].tag)
                assertEquals(listOf("Home", null), rooms.map { it.name })
                assertEquals(listOf(5, 3), rooms.map { it.maxFrames })
                assertEquals(listOf(false, true), rooms.map { it.attach })
                // `commons` is the first room without its tag, for clients that predate `rooms`.
                val commons = assertNotNull(hello.commons)
                assertEquals("Home", commons.name)
                assertEquals(5, commons.maxFrames)
                assertFalse(commons.attach)
                val encoded = RecordCodec.encode(hello)
                assertFalse(encoded.containsBytes(commonsScope()) || encoded.containsBytes(commonsScope2()), "hello carried a room's id")
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
    fun noRoomIsEverShedByTheWatermark() {
        // Two 40-byte blobs over a 60-byte watermark: something has to go, and it must not be a
        // room everyone in it shares — the second room as much as the first.
        withServer(testConfig(maxBytes = 60L, rooms = listOf(testCommons(), testCommons(scopeId = commonsScope2(), number = 2)))) {
            val ordinary = testScope(9)
            connect {
                helloHandshake()
                subscribe(commonsScope2())
                subscribe(ordinary, q = 2L)
                val (commonsId, commonsData) = testBlob(1)
                pushBlob(commonsScope2(), commonsId, commonsData, q = 3L)
                expectRecord<Ok>(RecordType.OK)
                val (otherId, otherData) = testBlob(2)
                pushBlob(ordinary, otherId, otherData, q = 4L)
                expectRecord<Ok>(RecordType.OK)
            }
            spool.sweepTick()
            assertTrue(store.isUnknownScope(ordinary), "the ordinary scope should have been shed")
            assertFalse(store.isUnknownScope(commonsScope2()), "the second room was shed")
            assertFalse(store.isUnknownScope(commonsScope()), "the first room was shed")
        }
    }

    @Test
    fun eachRoomPinsItsOwnBounds() {
        val rooms = listOf(testCommons(maxFrames = 5), testCommons(maxFrames = 3, ttlMs = 30_000L, scopeId = commonsScope2(), number = 2))
        withServer(testConfig(rooms = rooms)) {
            connect {
                helloHandshake()
                val hostile = ScopeBounds(maxFrames = 1, ttlMs = 1L, maxBlob = 1)
                assertEquals(5, subscribe(commonsScope(), bounds = hostile).bounds.maxFrames)
                val second = subscribe(commonsScope2(), bounds = hostile, q = 2L).bounds
                assertEquals(3, second.maxFrames, "the second room took the first room's bounds, or the client's")
                assertEquals(30_000L, second.ttlMs)
            }
        }
    }

    @Test
    fun aRoomBudgetThrottlesOnlyItsRoom() {
        // Room one at one push a second, burst four, on a clock that never advances; room two
        // unthrottled. Room one's member hitting its budget must not cost room two a push.
        val rooms = listOf(testCommons(ratePushes = 1), testCommons(scopeId = commonsScope2(), number = 2))
        withServer(testConfig(rooms = rooms)) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                subscribe(commonsScope2(), q = 2L)
                repeat(4) { seed ->
                    pushBlob(commonsScope(), testBlob(seed).first, testBlob(seed).second, q = 10L + seed)
                    expectRecord<Ok>(RecordType.OK)
                }
                pushBlob(commonsScope(), testBlob(5).first, testBlob(5).second, q = 20L)
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
                pushBlob(commonsScope2(), testBlob(6).first, testBlob(6).second, q = 21L)
                assertEquals(21L, expectRecord<Ok>(RecordType.OK).q, "room two paid for room one's budget")
            }
            assertEquals(1L, spool.metrics.commonsRateLimitedCount(1, CommonsBudget.ROOM))
            assertEquals(0L, spool.metrics.commonsRateLimitedCount(2, CommonsBudget.ROOM))
            assertEquals(4L, spool.metrics.commonsPushCount(1))
            assertEquals(1L, spool.metrics.commonsPushCount(2))
        }
    }

    @Test
    fun theSpoolWideBudgetRefusesWithoutChargingTheRoom() {
        // Room one: one push a second, burst four. The spool: ten a second, burst forty, which room
        // two spends first. Room one's pushes are then refused by the spool-wide budget — and must
        // not also come out of room one's own, or half a second later room one would have room for
        // one push instead of four (S-7.4-4).
        val rooms = listOf(testCommons(ratePushes = 1, maxFrames = 50), testCommons(maxFrames = 50, scopeId = commonsScope2(), number = 2))
        withServer(testConfig(rooms = rooms, commonsRateTotal = 10)) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                subscribe(commonsScope2(), q = 2L)
                repeat(40) { seed ->
                    pushBlob(commonsScope2(), testBlob(seed).first, testBlob(seed).second, q = 100L + seed)
                    expectRecord<Ok>(RecordType.OK)
                }
                repeat(3) { seed ->
                    pushBlob(commonsScope(), testBlob(60 + seed).first, testBlob(60 + seed).second, q = 200L + seed)
                    val err = expectRecord<Err>(RecordType.ERR)
                    assertEquals(ErrCode.RATE, err.code)
                    assertTrue(assertNotNull(err.retryMs) > 0L)
                }
                clock.advance(500L)
                repeat(4) { seed ->
                    pushBlob(commonsScope(), testBlob(70 + seed).first, testBlob(70 + seed).second, q = 300L + seed)
                    assertEquals(300L + seed, expectRecord<Ok>(RecordType.OK).q, "room one was charged for pushes it was refused")
                }
            }
            assertEquals(3L, spool.metrics.commonsRateLimitedCount(1, CommonsBudget.SPOOL))
            assertEquals(0L, spool.metrics.commonsRateLimitedCount(1, CommonsBudget.ROOM))
        }
    }

    @Test
    fun eachRoomGatesItsOwnAttachments() {
        val hardLimits =
            HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 4, maxAttachBytes = 4_096, maxAChunk = 512)
        val rooms = listOf(testCommons(attach = false), testCommons(attach = true, scopeId = commonsScope2(), number = 2))
        withServer(testConfig(hardLimits = hardLimits, rooms = rooms)) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                subscribe(commonsScope2(), q = 2L)
                val data = ByteArray(16) { it.toByte() }
                val cid = MessageDigest.getInstance("SHA-256").digest(data)
                val aid = ByteArray(32) { 7 }
                sendRecord(aput(q = 3L, scope = commonsScope(), aid = aid, cid = cid, data = data))
                assertEquals(ErrCode.MALFORMED, expectRecord<Err>(RecordType.ERR).code)
                sendRecord(aput(q = 4L, scope = commonsScope2(), aid = aid, cid = cid, data = data))
                assertEquals(4L, expectRecord<Ok>(RecordType.OK).q, "the second room's switch was not its own")
            }
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

    /**
     * A persistent store can already hold `maxScopes` scopes from before the commons was configured.
     * The commons then cannot be created, and the daemon refuses to start rather than come up
     * advertising a room it does not have.
     */
    @Test
    fun bootRefusesWhenTheStoreIsFullAndTheCommonsCannotBeCreated() {
        val limits = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 1)
        val full = InMemoryScopeStore(limits)
        assertIs<SubscribeResult.Subscribed>(full.subscribe(testScope(9), testBounds(), now = 0L, held = { false }))
        // Holding a frame, so not a bare scope the commons could reclaim (ADR 2026-10.d2zb).
        val (id, blob) = testBlob(9)
        full.push(testScope(9), id, blob, now = 0L)

        val refused =
            assertFailsWith<IllegalStateException> {
                withServer(testConfig(hardLimits = limits, commons = testCommons()), store = full) {}
            }
        assertTrue(refused.message!!.contains("SPOOL_MAX_SCOPES"), refused.message)
        assertTrue(full.isUnknownScope(commonsScope()))
    }

    /**
     * Over the watermark with nothing left to shed but the pinned commons, the sweeper says so
     * once and stops — rather than logging on every pass or spinning on a shed that returns null.
     * `configFromEnv` refuses a commons that alone exceeds `maxBytes`, so reaching this at all
     * means the two have drifted apart, which is exactly what the operator needs to hear.
     */
    @Test
    fun aWatermarkThatOnlyTheCommonsCanSatisfyWarnsOnce() {
        val logged =
            withLogCapture("app.getknit.spool.server.SpoolServer") {
                withServer(testConfig(maxBytes = 100L, commons = testCommons())) {
                    connect {
                        helloHandshake()
                        subscribe(commonsScope())
                        (1..3).forEach { seed ->
                            val (id, data) = testBlob(seed)
                            pushBlob(commonsScope(), id, data, q = 10L + seed)
                            expectRecord<Ok>(RecordType.OK)
                        }
                        // 120 bytes over a 100-byte watermark; the third push already tried to shed.
                        spool.sweepTick()
                        spool.sweepTick()
                    }
                    assertFalse(store.isUnknownScope(commonsScope()), "the commons must never be shed")
                }
            }
        val stuck = logged.filter { it.level == Level.WARN && it.formattedMessage.contains("only the pinned commons") }
        assertEquals(1, stuck.size, "one warning for the condition, not one per pass:\n${logged.map { it.formattedMessage }}")
    }

    @Test
    fun commonsSeriesAppearOnlyWhenTheCommonsIsEnabled() {
        withServer(testConfig(rooms = listOf(testCommons(), testCommons(scopeId = commonsScope2(), number = 2)))) {
            connect {
                helloHandshake()
                subscribe(commonsScope2())
                val body = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
                // One series per room, labelled by its number and never by its id or name.
                assertTrue(body.contains("knit_spool_commons_subscribers{room=\"1\"} 0"), body)
                assertTrue(body.contains("knit_spool_commons_subscribers{room=\"2\"} 1"), body)
                assertTrue(body.contains("knit_spool_commons_pushes_total{room=\"2\"} 0"), body)
                assertTrue(body.contains("knit_spool_commons_rate_limited_total{room=\"1\",budget=\"spool\"} 0"), body)
                assertFalse(body.contains(commonsScope2().toHexString().take(8)), "a room's id reached /metrics")
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
    }
}
