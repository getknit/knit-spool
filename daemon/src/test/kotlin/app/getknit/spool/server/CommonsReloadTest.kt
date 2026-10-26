// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.shortHex
import app.getknit.spool.store.HardLimits
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Commons rooms changed by `SIGHUP` (spec S-7.4-21, ADR 2026-10.hs75). A rotation is how an operator
 * removes a member, and it used to be a restart that reconnected every room's members and every
 * private conversation. Now only the connections in a room the reload retires or reshapes are closed,
 * `1001`, so each reads the room again from a new `hello`; everyone else keeps their connection.
 *
 * As in [ReloadTest], the signal is not raised: these drive [SpoolServer.reload] with the
 * configuration the handler would have parsed.
 */
class CommonsReloadTest {
    @Test
    fun rotatingARoomClosesOnlyItsMembers() {
        val second = testCommons(scopeId = commonsScope2(), number = 2)
        withServer(testConfig(rooms = listOf(testCommons(), second))) {
            connect {
                val firstMember = this
                helloHandshake()
                subscribe(commonsScope())
                connect {
                    val secondMember = this
                    helloHandshake()
                    subscribe(commonsScope2())
                    connect {
                        helloHandshake()
                        subscribe(testScope(9))

                        val ignored = spool.reload(testConfig(rooms = listOf(testCommons(scopeId = rotatedScope()), second)))
                        assertTrue(ignored.isEmpty(), "rooms reload now, so nothing about them is ignored: $ignored")

                        with(firstMember) { awaitClose(GOING_AWAY) }
                        // A private conversation and the other room carry on over the same connection.
                        pushBlob(testScope(9), 1, q = 10L)
                        assertEquals(10L, expectRecord<Ok>(RecordType.OK).q)
                        with(secondMember) {
                            pushBlob(commonsScope2(), 2, q = 11L)
                            assertEquals(11L, expectRecord<Ok>(RecordType.OK).q, "the other room's member was disturbed")
                        }
                    }
                }
            }
            // The rotated room is in every hello from now on, and the old one in none.
            connect {
                val tags = assertNotNull(helloHandshake().rooms).map { it.tag.toList() }
                assertEquals(listOf(Commons.tag(rotatedScope()).toList(), Commons.tag(commonsScope2()).toList()), tags)
            }
            assertEquals("${shortHex(rotatedScope())},${shortHex(commonsScope2())}", spool.reloadable["commons"])
        }
    }

    /**
     * A connection opened before the rotation was told the old room exists. One that subscribes to it
     * only after the reload would otherwise sit in the retired scope until it reconnected on its own,
     * since the reload's scan found it in no room.
     */
    @Test
    fun aSubAfterTheRotationFromAnOlderHelloIsClosed() {
        withServer(testConfig(commons = testCommons())) {
            connect {
                helloHandshake()
                spool.reload(testConfig(commons = testCommons(scopeId = rotatedScope())))
                sendSub(commonsScope())
                awaitClose(GOING_AWAY)
            }
            // A connection whose hello never listed the old room is not closed for subscribing to
            // its id: there it is an ordinary scope, like any other it might name.
            connect {
                helloHandshake()
                assertEquals(3, subscribe(commonsScope()).bounds.maxFrames, "the old id is an ordinary scope now")
            }
        }
    }

    @Test
    fun reshapingARoomClosesItsMembersAndPinsTheNewBounds() {
        withServer(testConfig(commons = testCommons(maxFrames = 5))) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                spool.reload(testConfig(commons = testCommons(maxFrames = 7)))
                awaitClose(GOING_AWAY)
            }
            assertEquals(7, store.digest(commonsScope(), clock.now)?.bounds?.maxFrames, "the store kept the old bounds")
            connect {
                assertEquals(7, assertNotNull(helloHandshake().rooms).single().maxFrames)
                assertEquals(7, subscribe(commonsScope(), bounds = ScopeBounds(maxFrames = 1, ttlMs = 1L, maxBlob = 1)).bounds.maxFrames)
            }
        }
    }

    /** A name, a budget or a room's number are not what a member keeps to, so changing them closes nobody. */
    @Test
    fun renamingRetuningOrRenumberingARoomClosesNobody() {
        val first = testCommons(name = "Home")
        val second = testCommons(name = "Den", scopeId = commonsScope2(), number = 2)
        withServer(testConfig(token = "t", rooms = listOf(first, second))) {
            connect(token = "t") {
                helloHandshake()
                subscribe(commonsScope())
                subscribe(commonsScope2(), q = 2L)
                val renamed = testCommons(name = "Kitchen", ratePushes = 500, scopeId = commonsScope2(), number = 1)
                assertTrue(spool.reload(testConfig(token = "t", rooms = listOf(renamed, testCommons(name = "Home", number = 2)))).isEmpty())
                pushBlob(commonsScope(), 1, q = 10L)
                assertEquals(10L, expectRecord<Ok>(RecordType.OK).q, "a member was closed for a change it does not keep to")
                pushBlob(commonsScope2(), 2, q = 11L)
                assertEquals(11L, expectRecord<Ok>(RecordType.OK).q)
            }
            // Metrics follow the number: the room that is now first counts as room 1.
            assertEquals(1L, spool.metrics.commonsPushCount(1))
            connect(token = "t") {
                assertEquals("Kitchen", assertNotNull(helloHandshake().commons).name)
            }
        }
    }

    /** A reload with nothing new for the rooms must not hand a member back the budget it already spent. */
    @Test
    fun anIdenticalReloadKeepsADrainedBudgetDrained() {
        withServer(testConfig(commons = testCommons(ratePushes = 1))) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                repeat(4) { seed ->
                    pushBlob(commonsScope(), seed, q = 10L + seed)
                    expectRecord<Ok>(RecordType.OK)
                }
                assertTrue(spool.reload(testConfig(commons = testCommons(ratePushes = 1), requireModeration = true)).isEmpty())
                pushBlob(commonsScope(), 9, q = 20L)
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code, "the reload refilled the room's budget")
            }
        }
    }

    /** S-7.4-3: a room exists before any hello lists it, so its members join without mining. */
    @Test
    fun aRoomAddedByAReloadNeedsNoProofOfWork() {
        val first = testCommons()
        withServer(testConfig(powBits = 20, commons = first)) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                spool.reload(testConfig(powBits = 20, rooms = listOf(first, testCommons(scopeId = commonsScope2(), number = 2))))
                // Adding a room closes nobody: the first room's member is still connected.
                pushBlob(commonsScope(), 1, q = 10L)
                assertEquals(10L, expectRecord<Ok>(RecordType.OK).q)
            }
            connect {
                assertEquals(2, assertNotNull(helloHandshake().rooms).size)
                assertEquals(0, subscribe(commonsScope2()).count)
            }
        }
    }

    /**
     * A room the store cannot create keeps the running rooms, and their total budget, exactly as they
     * were. The rest of the reload still applies: a full store must not block a token or a quota change.
     */
    @Test
    fun aRoomTheStoreCannotCreateLeavesTheRunningRoomsAndAppliesTheRest() {
        val limits = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 2)
        withServer(testConfig(hardLimits = limits, commons = testCommons())) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                // The second slot, holding a frame and so not a bare scope the new room could reclaim.
                subscribe(testScope(9), q = 2L)
                pushBlob(testScope(9), 1, q = 3L)
                expectRecord<Ok>(RecordType.OK)

                val wanted = listOf(testCommons(), testCommons(scopeId = commonsScope2(), number = 2))
                val ignored = spool.reload(testConfig(hardLimits = limits, rooms = wanted, requireModeration = true))
                assertTrue(ignored.single().startsWith("commons room 2 cannot be created"), ignored.toString())

                // Still a member of the room that was running.
                pushBlob(commonsScope(), 2, q = 4L)
                assertEquals(4L, expectRecord<Ok>(RecordType.OK).q)
            }
            connect {
                val hello = helloHandshake()
                assertEquals(1, assertNotNull(hello.rooms).size)
                assertEquals(true, hello.moderation, "the rest of the reload was dropped with the rooms")
            }
            assertEquals(shortHex(commonsScope()), spool.reloadable["commons"])
        }
    }

    /** The old scope is an ordinary one once its room is rotated away; the new room is pinned from its creation. */
    @Test
    fun aRetiredRoomCanBeShedAndItsReplacementCannot() {
        withServer(testConfig(maxBytes = 60L, commons = testCommons())) {
            connect {
                helloHandshake()
                subscribe(commonsScope())
                pushBlob(commonsScope(), 1, q = 10L)
                expectRecord<Ok>(RecordType.OK)
            }
            spool.reload(testConfig(maxBytes = 60L, commons = testCommons(scopeId = rotatedScope())))
            connect {
                helloHandshake()
                subscribe(rotatedScope())
                pushBlob(rotatedScope(), 2, q = 10L)
                expectRecord<Ok>(RecordType.OK)
            }
            spool.sweepTick()
            assertTrue(store.isUnknownScope(commonsScope()), "the retired room's scope should have been shed")
            assertFalse(store.isUnknownScope(rotatedScope()), "the new room was shed")
        }
    }

    @Test
    fun metricsAndTheAttachmentSwitchFollowTheReloadedRooms() {
        val limits =
            HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 4, maxAttachBytes = 4_096, maxAChunk = 512)
        withServer(testConfig(hardLimits = limits, rooms = listOf(testCommons(), testCommons(scopeId = commonsScope2(), number = 2)))) {
            spool.reload(testConfig(hardLimits = limits, commons = testCommons(attach = true)))
            val body = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
            assertTrue(body.contains("knit_spool_commons_subscribers{room=\"1\"} 0"), body)
            assertFalse(body.contains("room=\"2\""), "a removed room's series is still scraped")
            connect {
                assertTrue(assertNotNull(helloHandshake().rooms).single().attach)
                subscribe(commonsScope())
                val data = ByteArray(16) { it.toByte() }
                sendRecord(
                    aput(
                        q = 5L,
                        scope = commonsScope(),
                        aid = testScope(1),
                        cid = MessageDigest.getInstance("SHA-256").digest(data),
                        data = data,
                    ),
                )
                assertEquals(5L, expectRecord<Ok>(RecordType.OK).q, "the room's new attach setting was not applied")
            }
        }
    }

    private companion object {
        val GOING_AWAY =
            CloseReason.Codes.GOING_AWAY.code
                .toInt()

        /** A third invite's room: what an operator rotates the first room to. */
        fun rotatedScope(): ByteArray = Commons.scopeId(ByteArray(Commons.SECRET_BYTES) { (it + 201).toByte() })

        suspend fun WebSocketSession.pushBlob(
            scope: ByteArray,
            seed: Int,
            q: Long,
        ) {
            val (blobId, data) = testBlob(seed)
            sendRecord(Push(t = RecordType.PUSH, q = q, scope = scope, blobId = blobId, data = data))
        }

        /** A `sub` whose answer the caller reads itself, for the one that is answered with a close. */
        suspend fun DefaultClientWebSocketSession.sendSub(scope: ByteArray) {
            sendRecord(Sub(t = RecordType.SUB, q = 1L, subs = listOf(ScopeSub(scope = scope, bounds = testBounds()))))
        }
    }
}
