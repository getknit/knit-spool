// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Cast
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.Tickets
import ch.qos.logback.classic.Level
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Casts (experimental; knit-next `docs/VOICE_CALLS.md` §2.7, proposed spec §7.7): a sealed frame
 * fanned out to a scope's other live subscribers and never stored, so a walkie's chunks can cross a
 * spool. What these pin is the shape of a lane that keeps nothing and answers nothing:
 *
 * - a cast reaches every other subscriber and never its caster;
 * - it leaves no trace in the store, the digest or a listing;
 * - every refusal is a silent drop, never a record and never a strike;
 * - beside a ticket key, one subscriber's cast ticket unlocks the scope both ways.
 *
 * "Nothing came back" is proved by ordering rather than by waiting: a connection's records are
 * handled one at a time, so an answer to a cast would arrive before the answer to a `list` sent
 * after it. "Nothing arrived" is proved the same way: the listener probes only once the caster's own
 * probe has come back, by which time any cast fanned out to it is already queued ahead of the answer.
 */
class CastTest {
    private val now = 1_800_000_000_000L

    private fun casting(
        rateCasts: Int = 4,
        maxCast: Int = 2_048,
        ticket: SpoolServer.TicketConfig? = null,
        commons: SpoolServer.CommonsConfig? = null,
        rateCastsTotal: Int = 200,
    ) = testConfig(rateCasts = rateCasts, maxCast = maxCast, ticket = ticket, commons = commons, rateCastsTotal = rateCastsTotal)

    private fun cast(
        scope: ByteArray,
        seed: Int,
        size: Int = 64,
    ): Cast = Cast(t = RecordType.CAST, scope = scope, data = ByteArray(size) { (seed + it).toByte() })

    /** Answers a `list` for [scope] as the very next record, which is what proves nothing was sent ahead of it. */
    private suspend fun DefaultClientWebSocketSession.nextIsList(
        scope: ByteArray,
        q: Long,
    ): ScopeList {
        sendRecord(ScopeList(t = RecordType.LIST, q = q, scope = scope))
        val list = expectRecord<ScopeList>(RecordType.LIST)
        assertEquals(q, list.q)
        return list
    }

    private suspend fun DefaultClientWebSocketSession.receivesCast(expected: Cast) {
        val got = expectRecord<Cast>(RecordType.CAST)
        assertContentEquals(expected.scope, got.scope)
        assertContentEquals(expected.data, got.data)
    }

    private suspend fun DefaultClientWebSocketSession.present(fl: Long) {
        sendRecord(testEnt(now, q = 30L, fl = fl))
        assertEquals(30L, expectRecord<Ok>(RecordType.OK).q)
    }

    /**
     * Two connections on one scope, in a fixed order:
     *
     * 1. the listener subscribes and runs [setup];
     * 2. the caster subscribes and runs [caster], which ends with its own probe;
     * 3. meanwhile the listener runs [listener], then waits for the caster to finish and runs [after].
     *
     * The listener stays connected until the caster is done, so a cast that should not have
     * reached it cannot slip past by finding no one subscribed.
     */
    private suspend fun TestServer.pair(
        scope: ByteArray,
        setup: suspend DefaultClientWebSocketSession.() -> Unit = {},
        listener: suspend DefaultClientWebSocketSession.() -> Unit = {},
        after: suspend DefaultClientWebSocketSession.() -> Unit = { nextIsList(scope, q = 90L) },
        caster: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = coroutineScope {
        val ready = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        launch {
            connect {
                helloHandshake()
                subscribe(scope)
                setup()
                ready.complete(Unit)
                listener()
                done.await()
                after()
            }
        }
        connect {
            helloHandshake()
            subscribe(scope)
            ready.await()
            caster()
        }
        done.complete(Unit)
    }

    @Test
    fun helloCarriesCastOnlyWhileOn() {
        withServer(testConfig()) {
            connect {
                val hello = helloHandshake()
                assertNull(hello.cast)
                assertFalse(RecordCodec.encode(hello).containsBytes("cast".toByteArray()), "no cast key while casts are off")
            }
        }
        withServer(casting(rateCasts = 4, maxCast = 1_500)) {
            connect {
                val advertised = helloHandshake().cast
                assertEquals(1_500, advertised?.maxBytes)
                assertEquals(4, advertised?.rate)
            }
        }
    }

    @Test
    fun aCastReachesEveryOtherSubscriberAndNeverItsCaster() {
        val scope = testScope(1)
        val sent = cast(scope, seed = 7)
        withServer(casting()) {
            coroutineScope {
                val subscribed = List(2) { CompletableDeferred<Unit>() }
                subscribed.forEach { mine ->
                    launch {
                        connect {
                            helloHandshake()
                            subscribe(scope)
                            mine.complete(Unit)
                            receivesCast(sent)
                        }
                    }
                }
                connect {
                    helloHandshake()
                    subscribe(scope)
                    subscribed.forEach { it.await() }
                    sendRecord(sent)
                    // No echo, and no answer: the list is the next record.
                    nextIsList(scope, q = 2L)
                }
            }
            assertEquals(2L, spool.metrics.castsFannedTotal.sum())
        }
    }

    /** A cast touches nothing a `push` would: no blob, no digest fold, no tombstone, no listing. */
    @Test
    fun aCastIsNeverStored() {
        val scope = testScope(2)
        withServer(casting()) {
            pair(scope, listener = { repeat(3) { receivesCast(cast(scope, seed = it)) } }) {
                repeat(3) { sendRecord(cast(scope, seed = it)) }
                val list = nextIsList(scope, q = 2L)
                assertTrue(list.blobIds.isNullOrEmpty(), "a cast must not be listed")
                assertTrue(list.tombstones.isNullOrEmpty(), "a cast must not leave a tombstone")
            }
            connect {
                helloHandshake()
                val digest = subscribe(scope)
                assertEquals(0, digest.count)
                assertContentEquals(ByteArray(8), digest.digest)
            }
            assertEquals(0L, store.totalBytes())
        }
    }

    /**
     * Every refusal drops the cast without a word: not subscribed, oversize, malformed, the
     * commons. The connection stays usable, and the listener's next record is the cast that was
     * allowed.
     */
    @Test
    fun everyRefusalIsSilentAndLeavesTheConnectionUsable() {
        val scope = testScope(3)
        val allowed = cast(scope, seed = 99)
        withServer(casting(maxCast = 128, commons = testCommons())) {
            pair(scope, listener = { receivesCast(allowed) }) {
                subscribe(commonsScope(), q = 3L)
                sendRecord(cast(testScope(4), seed = 1)) // not subscribed
                sendRecord(cast(scope, seed = 2, size = 129)) // over maxBytes
                sendRecord(Cast(t = RecordType.CAST, scope = ByteArray(31), data = ByteArray(8))) // a short id
                sendRaw(byteArrayOf(0xa1.toByte(), 0x61, 0x74, 0x64) + "cast".toByteArray()) // `t` alone
                sendRecord(cast(commonsScope(), seed = 3)) // the commons
                nextIsList(scope, q = 4L)
                sendRecord(allowed)
                nextIsList(scope, q = 5L)
            }
            assertEquals(1L, spool.metrics.castDroppedCount(CastDrop.NOT_SUBSCRIBED))
            assertEquals(1L, spool.metrics.castDroppedCount(CastDrop.TOO_LARGE))
            assertEquals(2L, spool.metrics.castDroppedCount(CastDrop.MALFORMED))
            assertEquals(1L, spool.metrics.castDroppedCount(CastDrop.COMMONS))
            assertEquals(0L, spool.metrics.rateLimitedTotal.sum(), "a dropped cast is never answered rate")
        }
    }

    /** Past the burst, casts are dropped and counted, and no number of them closes the connection. */
    @Test
    fun theBudgetDropsWithoutStriking() {
        val scope = testScope(5)
        // Rate 1/s, burst 4. The clock never moves, so exactly four get through.
        withServer(casting(rateCasts = 1), clock = FakeClock(now)) {
            pair(scope, listener = { repeat(4) { receivesCast(cast(scope, seed = it)) } }) {
                repeat(30) { sendRecord(cast(scope, seed = it)) }
                nextIsList(scope, q = 2L)
            }
            assertEquals(26L, spool.metrics.castDroppedCount(CastDrop.RATE))
            assertEquals(4L, spool.metrics.castsFannedTotal.sum())
        }
    }

    /** The spool-wide budget bounds every connection together. */
    @Test
    fun theSpoolWideBudgetBoundsEveryCasterTogether() {
        val scope = testScope(6)
        withServer(casting(rateCasts = 100, rateCastsTotal = 1), clock = FakeClock(now)) {
            pair(scope, listener = { repeat(4) { receivesCast(cast(scope, seed = it)) } }) {
                repeat(10) { sendRecord(cast(scope, seed = it)) }
                nextIsList(scope, q = 2L)
            }
            assertEquals(6L, spool.metrics.castDroppedCount(CastDrop.RATE))
        }
    }

    /** `SPOOL_RATE_CASTS=0` on a reload stops casts on connections that were told casts exist. */
    @Test
    fun aReloadToZeroStopsCastsAtOnce() {
        val scope = testScope(7)
        withServer(casting()) {
            pair(scope, listener = { receivesCast(cast(scope, seed = 1)) }) {
                sendRecord(cast(scope, seed = 1))
                nextIsList(scope, q = 2L)
                assertTrue(spool.reload(casting(rateCasts = 0)).isEmpty())
                sendRecord(cast(scope, seed = 2))
                nextIsList(scope, q = 3L)
            }
            assertEquals(1L, spool.metrics.castDroppedCount(CastDrop.OFF))
        }
    }

    /** A ticketed spool drops a cast on a scope no subscriber holds a cast ticket for, TURN-only tickets included. */
    @Test
    fun aTicketedSpoolDropsCastsNobodyUnlocked() {
        val scope = testScope(8)
        withServer(casting(ticket = testTicketConfig()), clock = FakeClock(now)) {
            pair(scope) {
                sendRecord(cast(scope, seed = 1))
                nextIsList(scope, q = 2L)
                present(Tickets.FL_TURN)
                sendRecord(cast(scope, seed = 2))
                nextIsList(scope, q = 4L)
            }
            assertEquals(2L, spool.metrics.castDroppedCount(CastDrop.TICKET))
            assertEquals(0L, spool.metrics.castsFannedTotal.sum())
        }
    }

    /** A keyed spool that leaves `cast` out of `SPOOL_TICKET_GATES` admits any subscriber (ADR 2026-10.h6pt). */
    @Test
    fun aKeyedSpoolWithCastsUngatedHearsAFreeCaster() {
        val scope = testScope(40)
        val sent = cast(scope, seed = 1)
        withServer(casting(ticket = testTicketConfig(gates = Tickets.FL_TURN)), clock = FakeClock(now)) {
            pair(scope, listener = { receivesCast(sent) }) {
                sendRecord(sent)
                nextIsList(scope, q = 4L)
            }
            assertEquals(0L, spool.metrics.castDroppedCount(CastDrop.TICKET))
        }
    }

    /** A cast ticket on the caster's own connection unlocks its casts. */
    @Test
    fun aTicketedCasterIsHeard() {
        val scope = testScope(9)
        val sent = cast(scope, seed = 1)
        withServer(casting(ticket = testTicketConfig()), clock = FakeClock(now)) {
            pair(scope, listener = { receivesCast(sent) }) {
                present(Tickets.FL_TURN or Tickets.FL_CAST)
                sendRecord(sent)
                nextIsList(scope, q = 4L)
            }
        }
    }

    /**
     * One payer unlocks the scope both ways: with the listener holding the ticket, the free caster
     * is heard, and the listener's own cast reaches the free phone.
     */
    @Test
    fun oneTicketedSubscriberUnlocksTheScopeForEveryone() {
        val scope = testScope(10)
        val free = cast(scope, seed = 1)
        val paid = cast(scope, seed = 2)
        withServer(casting(ticket = testTicketConfig()), clock = FakeClock(now)) {
            pair(
                scope,
                setup = { present(Tickets.FL_CAST) },
                listener = {
                    receivesCast(free)
                    sendRecord(paid)
                },
            ) {
                sendRecord(free)
                receivesCast(paid)
            }
            assertEquals(0L, spool.metrics.castDroppedCount(CastDrop.TICKET))
        }
    }

    /** A cast ticket stops unlocking at its expiry plus the skew, like any ticket. */
    @Test
    fun anExpiredCastTicketUnlocksNothing() {
        val scope = testScope(11)
        val clock = FakeClock(now)
        withServer(casting(ticket = testTicketConfig()), clock = clock) {
            pair(scope) {
                sendRecord(testEnt(now, q = 3L, fl = Tickets.FL_CAST, expS = now / 1_000 + 60))
                expectRecord<Ok>(RecordType.OK)
                clock.advance((60 + Tickets.SKEW_S + 1) * 1_000)
                sendRecord(cast(scope, seed = 1))
                nextIsList(scope, q = 4L)
            }
            assertEquals(1L, spool.metrics.castDroppedCount(CastDrop.TICKET))
        }
    }

    /** The unlock lives on a live subscription: once the ticketed subscriber leaves, the scope locks again. */
    @Test
    fun theScopeLocksAgainWhenItsPayerLeaves() {
        val scope = testScope(12)
        val heard = cast(scope, seed = 1)
        withServer(casting(ticket = testTicketConfig()), clock = FakeClock(now)) {
            coroutineScope {
                val payerReady = CompletableDeferred<Unit>()
                val listenerReady = CompletableDeferred<Unit>()
                val casterDone = CompletableDeferred<Unit>()
                launch {
                    connect {
                        helloHandshake()
                        subscribe(scope)
                        present(Tickets.FL_CAST)
                        payerReady.complete(Unit)
                        receivesCast(heard)
                    }
                }
                launch {
                    connect {
                        helloHandshake()
                        subscribe(scope)
                        listenerReady.complete(Unit)
                        receivesCast(heard)
                        casterDone.await()
                        // The second cast, sent after the payer left, must not have arrived.
                        nextIsList(scope, q = 9L)
                    }
                }
                connect {
                    helloHandshake()
                    subscribe(scope)
                    payerReady.await()
                    listenerReady.await()
                    sendRecord(heard)
                    awaitSubscribers(scope, count = 2)
                    sendRecord(cast(scope, seed = 2))
                    nextIsList(scope, q = 4L)
                }
                casterDone.complete(Unit)
            }
            assertEquals(1L, spool.metrics.castDroppedCount(CastDrop.TICKET))
        }
    }

    /** Waits for the fan-out table to drop to [count]: a closed connection leaves it in the server's own time. */
    private suspend fun TestServer.awaitSubscribers(
        scope: ByteArray,
        count: Int,
    ) {
        repeat(250) {
            if (spool.subscriberCount(scope) == count) return
            delay(20)
        }
        fail("scope still has ${spool.subscriberCount(scope)} subscribers, wanted $count")
    }

    /** A spool with no ticket key gates nothing: any subscriber's cast is heard. */
    @Test
    fun aSpoolWithoutTicketsLetsAnySubscriberCast() {
        val scope = testScope(13)
        val sent = cast(scope, seed = 1)
        withServer(casting()) {
            pair(scope, listener = { receivesCast(sent) }) {
                sendRecord(sent)
                nextIsList(scope, q = 2L)
            }
        }
    }

    @Test
    fun metricsCarryCastSeriesOnlyWhileOn() {
        withServer(testConfig()) {
            assertFalse(http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("knit_spool_casts_total"))
        }
        withServer(casting()) {
            val body = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
            assertTrue(body.contains("knit_spool_casts_total 0"), body)
            assertTrue(body.contains("knit_spool_casts_dropped_total{reason=\"ticket\"} 0"), body)
        }
    }

    /** The lane exists to carry a live activity signal, and the daemon must not be what writes it down. */
    @Test
    fun nothingAboutACastIsLogged() {
        val scope = testScope(14)
        val logged =
            withLogCapture("app.getknit.spool.server.SpoolServer", Level.TRACE) {
                withServer(casting()) {
                    pair(scope, listener = { receivesCast(cast(scope, seed = 1)) }) {
                        sendRecord(cast(scope, seed = 1))
                        sendRecord(cast(testScope(15), seed = 2))
                        nextIsList(scope, q = 2L)
                    }
                }
            }.map { it.formattedMessage }
                // The boot line names the knobs (`rateCasts=4`); that is configuration, not traffic.
                .filterNot { it.startsWith("effective config:") }
                .joinToString("\n")
        assertFalse(logged.contains("cast", ignoreCase = true), logged)
        assertFalse(logged.contains(testScope(14).toHexString().take(8)), logged)
    }
}
