// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cap on one scope's live subscribers (knit-next `docs/SPOOL_PROTOCOL.md` S-6.4-6; ADR 2026-10.fxcs).
 * What these pin:
 *
 * - a `sub` past the cap is refused `quota` with its `q` and scope, counted, and never a strike;
 * - a refresh by a member, and every commons room, are exempt;
 * - a place frees when a member leaves, and a reload that lowers the cap closes nobody;
 * - concurrent subs never overshoot it.
 */
class ScopeSubscribersTest {
    /** Holds [n] connections subscribed to [scope] open while [block] runs, nested so each stays live. */
    private suspend fun TestServer.withMembers(
        n: Int,
        scope: ByteArray,
        held: List<DefaultClientWebSocketSession> = emptyList(),
        block: suspend (List<DefaultClientWebSocketSession>) -> Unit,
    ) {
        if (held.size == n) {
            block(held)
            return
        }
        connect {
            helloHandshake()
            subscribe(scope)
            withMembers(n, scope, held + this, block)
        }
    }

    private suspend fun WebSocketSession.sendSub(
        q: Long,
        vararg scopes: ByteArray,
    ) {
        sendRecord(Sub(t = RecordType.SUB, q = q, subs = scopes.map { ScopeSub(scope = it, bounds = testBounds()) }))
    }

    private suspend fun WebSocketSession.expectCrowded(
        scope: ByteArray,
        q: Long,
    ) {
        val err = expectRecord<Err>(RecordType.ERR)
        assertEquals(ErrCode.QUOTA, err.code)
        assertEquals(q, err.q)
        assertContentEquals(scope, err.scope)
    }

    @Test
    fun aSubPastTheCapIsRefusedQuotaAndTheRestOfItsBatchStands() {
        val full = testScope(1)
        val other = testScope(2)
        withServer(testConfig(maxScopeSubscribers = 3)) {
            withMembers(3, full) {
                connect {
                    helloHandshake()
                    sendSub(q = 7L, full, other)
                    expectCrowded(full, q = 7L)
                    // The refusal is that entry's alone: the next scope in the same record subscribes.
                    assertEquals(0, expectRecord<Digest>(RecordType.DIGEST).count)
                }
            }
            assertEquals(1L, spool.metrics.subsCrowdedTotal.sum())
            assertEquals(0L, spool.metrics.rateLimitedTotal.sum(), "a full scope is never a strike")
            assertContains(http.get("http://127.0.0.1:$port/metrics").bodyAsText(), "knit_spool_subs_crowded_total 1")
        }
    }

    /** A member re-subscribing to refresh its bounds takes no new place, so a full scope answers it a digest. */
    @Test
    fun aMemberRefreshingAtTheCapIsAnsweredADigest() {
        val scope = testScope(1)
        withServer(testConfig(maxScopeSubscribers = 2)) {
            withMembers(2, scope) { members ->
                assertNotNull(members.first().subscribe(scope, q = 5L))
            }
            assertEquals(0L, spool.metrics.subsCrowdedTotal.sum())
        }
    }

    @Test
    fun aPlaceFreesWhenAMemberLeaves() {
        val scope = testScope(1)
        withServer(testConfig(maxScopeSubscribers = 2)) {
            connect {
                helloHandshake()
                subscribe(scope)
                val memberIn = CompletableDeferred<Unit>()
                val refused = CompletableDeferred<Unit>()
                val left = CompletableDeferred<Unit>()
                coroutineScope {
                    launch {
                        connect {
                            helloHandshake()
                            subscribe(scope)
                            memberIn.complete(Unit)
                            refused.await()
                        }
                        left.complete(Unit)
                    }
                    connect {
                        helloHandshake()
                        memberIn.await()
                        sendSub(q = 3L, scope)
                        expectCrowded(scope, q = 3L)
                        refused.complete(Unit)
                        left.await()
                        // The departed member's teardown may trail its close by a moment, so retry briefly.
                        val answered =
                            withTimeout(5_000L) {
                                var q = 4L
                                while (true) {
                                    sendSub(q, scope)
                                    val bytes = nextBinary()
                                    if (RecordCodec.peekType(bytes) == RecordType.DIGEST) break
                                    assertEquals(ErrCode.QUOTA, RecordCodec.decode<Err>(bytes)?.code)
                                    q++
                                    delay(RETRY_MS)
                                }
                                q
                            }
                        assertTrue(answered >= 4L)
                    }
                }
            }
        }
    }

    /** A commons room holds many members by design and pays its own budgets: the cap never reaches it. */
    @Test
    fun aCommonsRoomIsExempt() {
        withServer(testConfig(maxScopeSubscribers = 2, commons = testCommons())) {
            withMembers(4, commonsScope()) { members ->
                assertEquals(4, members.size)
            }
            assertEquals(0L, spool.metrics.subsCrowdedTotal.sum())
        }
    }

    /** The cap is read by every sub: a reload that lowers it refuses the next newcomer and closes nobody. */
    @Test
    fun aReloadThatLowersTheCapClosesNobody() {
        val scope = testScope(1)
        withServer(testConfig(maxScopeSubscribers = 3)) {
            withMembers(3, scope) { members ->
                assertTrue(spool.reload(testConfig(maxScopeSubscribers = 1)).isEmpty())
                // All three are still subscribed: a push from one reaches the other two.
                val (id, data) = testBlob(1)
                members[0].sendRecord(Push(t = RecordType.PUSH, q = 9L, scope = scope, blobId = id, data = data))
                members[0].expectRecord<Ok>(RecordType.OK)
                members.drop(1).forEach { assertContentEquals(id, it.expectRecord<Event>(RecordType.EVENT).blobId) }
                connect {
                    helloHandshake()
                    sendSub(q = 2L, scope)
                    expectCrowded(scope, q = 2L)
                }
            }
        }
    }

    /**
     * Concurrent subs check and take a place in one step, and a subscriber is moved from pending to
     * live in another, so eight newcomers racing for three places get exactly three.
     */
    @Test
    fun concurrentSubsNeverOvershootTheCap() {
        val scope = testScope(1)
        val racers = 8
        withServer(testConfig(maxScopeSubscribers = 3)) {
            val answers = List(racers) { CompletableDeferred<String>() }
            val done = CompletableDeferred<Unit>()
            coroutineScope {
                answers.forEach { answer ->
                    launch {
                        connect {
                            helloHandshake()
                            sendSub(q = 1L, scope)
                            answer.complete(assertNotNull(RecordCodec.peekType(nextBinary())))
                            done.await()
                        }
                    }
                }
                val types = answers.awaitAll()
                assertEquals(3, types.count { it == RecordType.DIGEST }, "answers: $types")
                assertEquals(racers - 3, types.count { it == RecordType.ERR }, "answers: $types")
                done.complete(Unit)
            }
            assertEquals((racers - 3).toLong(), spool.metrics.subsCrowdedTotal.sum())
        }
    }

    private companion object {
        const val RETRY_MS = 20L
    }
}
