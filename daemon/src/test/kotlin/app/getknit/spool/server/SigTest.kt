// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Cast
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.Sig
import app.getknit.spool.protocol.Tickets
import ch.qos.logback.classic.Level
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signals (knit-next `docs/SPOOL_PROTOCOL.md` §7.8; ADR 2026-10.2q49): casts' lane with no ticket in
 * it, so a typing cue from any phone, free ones included, crosses a spool. What these pin:
 *
 * - a sig reaches every other subscriber and never its sender, and is never stored (S-7.8-5);
 * - every refusal is a silent drop, never a record and never a strike (S-7.8-2, S-7.8-7);
 * - its three budgets are its own, apart from the casts', and one address gets eight connections' worth;
 * - no ticket gates it, on a spool that gates everything else (S-7.8-4);
 * - nothing about it is logged (S-7.8-8).
 *
 * "Nothing came back" and "nothing arrived" are proved by ordering, as in [CastTest]: a connection's
 * records are handled one at a time, so anything a sig drew would arrive ahead of the answer to a
 * `list` sent after it. "Something arrived" is also checked on the fan-out counter, because a listener
 * that times out waiting is cancelled rather than failed.
 */
class SigTest {
    private val now = 1_800_000_000_000L

    private fun signalling(
        rateSigs: Int = 4,
        maxSig: Int = 2_048,
        rateSigsTotal: Int = 200,
        ticket: SpoolServer.TicketConfig? = null,
        commons: SpoolServer.CommonsConfig? = null,
        rateCasts: Int = 0,
    ) = testConfig(
        rateSigs = rateSigs,
        maxSig = maxSig,
        rateSigsTotal = rateSigsTotal,
        ticket = ticket,
        commons = commons,
        rateCasts = rateCasts,
    )

    private fun sig(
        scope: ByteArray,
        seed: Int,
        size: Int = 157,
    ): Sig = Sig(t = RecordType.SIG, scope = scope, data = ByteArray(size) { (seed + it).toByte() })

    private suspend fun DefaultClientWebSocketSession.nextIsList(
        scope: ByteArray,
        q: Long,
    ): ScopeList {
        sendRecord(ScopeList(t = RecordType.LIST, q = q, scope = scope))
        val list = expectRecord<ScopeList>(RecordType.LIST)
        assertEquals(q, list.q)
        return list
    }

    private suspend fun DefaultClientWebSocketSession.receivesSig(expected: Sig) {
        val got = expectRecord<Sig>(RecordType.SIG)
        assertContentEquals(expected.scope, got.scope)
        assertContentEquals(expected.data, got.data)
    }

    /**
     * Two connections on one scope, in [CastTest]'s fixed order: the listener subscribes and runs
     * [setup], the sender subscribes and runs [sender], and the listener runs [listener], then [after]
     * once the sender is done, so a sig that should not have reached it cannot slip past unseen.
     */
    private suspend fun TestServer.pair(
        scope: ByteArray,
        setup: suspend DefaultClientWebSocketSession.() -> Unit = {},
        listener: suspend DefaultClientWebSocketSession.() -> Unit = {},
        after: suspend DefaultClientWebSocketSession.() -> Unit = { nextIsList(scope, q = 90L) },
        sender: suspend DefaultClientWebSocketSession.() -> Unit,
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
            sender()
        }
        done.complete(Unit)
    }

    /** S-7.8-1: on by default, and a rate of 0 leaves the key out rather than sending it as 0. */
    @Test
    fun helloCarriesSigUnlessItsRateIsZero() {
        withServer(testConfig()) {
            connect {
                val advertised = assertNotNull(helloHandshake().sig, "sigs are on by default")
                assertEquals(2_048, advertised.maxBytes)
                assertEquals(2, advertised.rate)
            }
        }
        withServer(signalling(rateSigs = 0)) {
            connect {
                val hello = helloHandshake()
                assertNull(hello.sig)
                assertFalse(RecordCodec.encode(hello).containsBytes(byteArrayOf(0x63) + "sig".toByteArray()), "no sig key while off")
            }
        }
    }

    @Test
    fun aSigReachesEveryOtherSubscriberAndNeverItsSender() {
        val scope = testScope(1)
        val sent = sig(scope, seed = 7)
        withServer(signalling()) {
            coroutineScope {
                val subscribed = List(2) { CompletableDeferred<Unit>() }
                subscribed.forEach { mine ->
                    launch {
                        connect {
                            helloHandshake()
                            subscribe(scope)
                            mine.complete(Unit)
                            receivesSig(sent)
                        }
                    }
                }
                connect {
                    helloHandshake()
                    subscribe(scope)
                    subscribed.forEach { it.await() }
                    sendRecord(sent)
                    // No echo, and no answer (S-7.8-2): the list is the next record.
                    nextIsList(scope, q = 2L)
                }
            }
            assertEquals(2L, spool.metrics.sigsFannedTotal.sum())
            assertEquals(1L, spool.metrics.sigsTotal.sum())
        }
    }

    /** A `q` a sig was never meant to carry is ignored like any unknown field: still fanned out, still unanswered. */
    @Test
    fun aSigCarryingAQIsStillNeverAnswered() {
        val scope = testScope(16)
        val data = ByteArray(32) { it.toByte() }
        // `{t: "sig", q: 5, scope, data}`, by hand: map(4), then each key and value.
        val withQ =
            byteArrayOf(0xa4.toByte(), 0x61, 0x74, 0x63) + "sig".toByteArray() + byteArrayOf(0x61, 0x71, 0x05, 0x65) +
                "scope".toByteArray() + byteArrayOf(0x58, 0x20) + scope + byteArrayOf(0x64) + "data".toByteArray() +
                byteArrayOf(0x58, 0x20) + data
        withServer(signalling()) {
            pair(scope, listener = { receivesSig(Sig(t = RecordType.SIG, scope = scope, data = data)) }) {
                sendRaw(withQ)
                nextIsList(scope, q = 6L)
            }
            assertEquals(1L, spool.metrics.sigsFannedTotal.sum())
        }
    }

    /** S-7.8-5: a sig touches nothing a `push` would: no blob, no digest fold, no tombstone, no listing. */
    @Test
    fun aSigIsNeverStored() {
        val scope = testScope(2)
        withServer(signalling()) {
            pair(scope, listener = { repeat(3) { receivesSig(sig(scope, seed = it)) } }) {
                repeat(3) { sendRecord(sig(scope, seed = it)) }
                val list = nextIsList(scope, q = 2L)
                assertTrue(list.blobIds.isNullOrEmpty(), "a sig must not be listed")
                assertTrue(list.tombstones.isNullOrEmpty(), "a sig must not leave a tombstone")
            }
            assertEquals(3L, spool.metrics.sigsFannedTotal.sum())
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
     * S-7.8-3: not subscribed, oversize, malformed and the commons are each dropped without a word.
     * The connection stays usable, and the listener's next record is the sig that was allowed.
     */
    @Test
    fun everyRefusalIsSilentAndLeavesTheConnectionUsable() {
        val scope = testScope(3)
        val allowed = sig(scope, seed = 99)
        // `allowed` is exactly maxBytes: the bound is inclusive.
        withServer(signalling(maxSig = 157, commons = testCommons())) {
            pair(scope, listener = { receivesSig(allowed) }) {
                subscribe(commonsScope(), q = 3L)
                sendRecord(sig(testScope(4), seed = 1)) // not subscribed
                sendRecord(sig(scope, seed = 2, size = 158)) // over maxBytes
                sendRecord(Sig(t = RecordType.SIG, scope = ByteArray(31), data = ByteArray(8))) // a short id
                sendRaw(byteArrayOf(0xa1.toByte(), 0x61, 0x74, 0x63) + "sig".toByteArray()) // `t` alone
                sendRecord(sig(commonsScope(), seed = 3)) // the commons
                nextIsList(scope, q = 4L)
                sendRecord(allowed)
                nextIsList(scope, q = 5L)
            }
            assertEquals(1L, spool.metrics.sigsDropped.count(SigDrop.NOT_SUBSCRIBED))
            assertEquals(1L, spool.metrics.sigsDropped.count(SigDrop.TOO_LARGE))
            assertEquals(2L, spool.metrics.sigsDropped.count(SigDrop.MALFORMED))
            assertEquals(1L, spool.metrics.sigsDropped.count(SigDrop.COMMONS))
            assertEquals(0L, spool.metrics.rateLimitedTotal.sum(), "a dropped sig is never answered rate")
            assertEquals(1L, spool.metrics.sigsFannedTotal.sum())
        }
    }

    /** S-7.8-7: past the burst, sigs are dropped and counted, and no number of them closes the connection. */
    @Test
    fun theBudgetDropsWithoutStriking() {
        val scope = testScope(5)
        // Rate 1/s, burst 4. The clock never moves, so exactly four get through.
        withServer(signalling(rateSigs = 1), clock = FakeClock(now)) {
            pair(scope, listener = { repeat(4) { receivesSig(sig(scope, seed = it)) } }) {
                repeat(30) { sendRecord(sig(scope, seed = it)) }
                nextIsList(scope, q = 2L)
            }
            assertEquals(26L, spool.metrics.sigsDropped.count(SigDrop.RATE))
            assertEquals(4L, spool.metrics.sigsFannedTotal.sum())
            assertEquals(0L, spool.metrics.rateLimitedTotal.sum())
        }
    }

    /**
     * One address gets eight connections' budgets, not casts' four, so phones behind one carrier NAT
     * keep typing: at rate 1 that is a burst of 32, so eight senders spending their own four each get
     * through and a ninth is dropped.
     */
    @Test
    fun oneAddressGetsEightConnectionsWorth() {
        val scope = testScope(6)
        withServer(signalling(rateSigs = 1), clock = FakeClock(now)) {
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val done = CompletableDeferred<Unit>()
                launch {
                    connect {
                        helloHandshake()
                        subscribe(scope)
                        ready.complete(Unit)
                        repeat(32) { expectRecord<Sig>(RecordType.SIG) }
                        done.await()
                        nextIsList(scope, q = 90L)
                    }
                }
                ready.await()
                repeat(9) { sender ->
                    connect {
                        helloHandshake()
                        subscribe(scope)
                        repeat(4) { sendRecord(sig(scope, seed = sender * 4 + it)) }
                        nextIsList(scope, q = 2L)
                    }
                }
                done.complete(Unit)
            }
            assertEquals(32L, spool.metrics.sigsFannedTotal.sum())
            assertEquals(4L, spool.metrics.sigsDropped.count(SigDrop.RATE))
        }
    }

    @Test
    fun theSpoolWideBudgetBoundsEverySenderTogether() {
        val scope = testScope(7)
        withServer(signalling(rateSigs = 100, rateSigsTotal = 1), clock = FakeClock(now)) {
            pair(scope, listener = { repeat(4) { receivesSig(sig(scope, seed = it)) } }) {
                repeat(10) { sendRecord(sig(scope, seed = it)) }
                nextIsList(scope, q = 2L)
            }
            assertEquals(6L, spool.metrics.sigsDropped.count(SigDrop.RATE))
            assertEquals(4L, spool.metrics.sigsFannedTotal.sum())
        }
    }

    /** S-7.8-3: the sig budgets are separate from the cast budgets, so a walkie that drains its own leaves typing alone. */
    @Test
    fun theSigBudgetsAreApartFromTheCastBudgets() {
        val scope = testScope(8)
        withServer(signalling(rateSigs = 1, rateCasts = 1), clock = FakeClock(now)) {
            pair(
                scope,
                listener = {
                    repeat(4) { expectRecord<Cast>(RecordType.CAST) }
                    repeat(4) { receivesSig(sig(scope, seed = it)) }
                },
            ) {
                repeat(10) { sendRecord(Cast(t = RecordType.CAST, scope = scope, data = ByteArray(64))) }
                repeat(4) { sendRecord(sig(scope, seed = it)) }
                nextIsList(scope, q = 2L)
            }
            assertEquals(6L, spool.metrics.castDroppedCount(CastDrop.RATE))
            assertEquals(0L, spool.metrics.sigsDropped.count(SigDrop.RATE))
            assertEquals(4L, spool.metrics.castsFannedTotal.sum())
            assertEquals(4L, spool.metrics.sigsFannedTotal.sum())
        }
    }

    /** `SPOOL_RATE_SIGS=0` on a reload stops sigs on connections that were told sigs exist. */
    @Test
    fun aReloadToZeroStopsSigsAtOnce() {
        val scope = testScope(9)
        withServer(signalling()) {
            pair(scope, listener = { receivesSig(sig(scope, seed = 1)) }) {
                sendRecord(sig(scope, seed = 1))
                nextIsList(scope, q = 2L)
                assertTrue(spool.reload(signalling(rateSigs = 0)).isEmpty())
                sendRecord(sig(scope, seed = 2))
                nextIsList(scope, q = 3L)
            }
            assertEquals(1L, spool.metrics.sigsDropped.count(SigDrop.OFF))
            assertEquals(1L, spool.metrics.sigsFannedTotal.sum())
        }
    }

    /** A connection whose hello carried no `sig` has its sigs dropped, even after a reload switches them on. */
    @Test
    fun aConnectionNeverToldOfSigsHasThemDropped() {
        val scope = testScope(10)
        withServer(signalling(rateSigs = 0)) {
            pair(scope) {
                assertTrue(spool.reload(signalling()).isEmpty())
                sendRecord(sig(scope, seed = 1))
                nextIsList(scope, q = 2L)
            }
            assertEquals(1L, spool.metrics.sigsDropped.count(SigDrop.OFF))
        }
    }

    /**
     * S-7.8-4: no ticket gates a sig. On a spool that gates every capability a ticket can unlock, two
     * connections with no ticket between them exchange a sig, while the cast beside it is refused.
     */
    @Test
    fun aTicketGatedSpoolCarriesASigBetweenConnectionsWithNoTicket() {
        val scope = testScope(11)
        val sent = sig(scope, seed = 1)
        val everyGate = Tickets.FL_TURN or Tickets.FL_FILES or Tickets.FL_CAST
        withServer(signalling(ticket = testTicketConfig(gates = everyGate), rateCasts = 4), clock = FakeClock(now)) {
            connect {
                val hello = helloHandshake()
                assertEquals(true, hello.ent)
                assertEquals(everyGate, hello.entGates)
                assertNotNull(hello.sig, "a ticketed spool still advertises sig")
            }
            pair(scope, listener = { receivesSig(sent) }) {
                sendRecord(Cast(t = RecordType.CAST, scope = scope, data = ByteArray(64)))
                sendRecord(sent)
                nextIsList(scope, q = 2L)
            }
            assertEquals(1L, spool.metrics.castDroppedCount(CastDrop.TICKET))
            assertEquals(1L, spool.metrics.sigsFannedTotal.sum())
        }
    }

    @Test
    fun metricsCarrySigSeriesOnlyWhileOn() {
        withServer(signalling(rateSigs = 0)) {
            assertFalse(http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("knit_spool_sigs_total"))
        }
        withServer(signalling()) {
            val body = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
            assertTrue(body.contains("knit_spool_sigs_total 0"), body)
            assertTrue(body.contains("knit_spool_sigs_fanned_total 0"), body)
            SigDrop.entries.forEach { assertTrue(body.contains("knit_spool_sigs_dropped_total{reason=\"${it.word}\"} 0"), it.word) }
            assertFalse(body.contains("knit_spool_sigs_dropped_total{reason=\"ticket\"}"), "no ticket gates a sig")
        }
    }

    /** S-7.8-8: a typing cue is an activity signal, and the daemon must not be what writes it down. */
    @Test
    fun nothingAboutASigIsLogged() {
        val scope = testScope(14)
        val logged =
            withLogCapture("app.getknit.spool.server.SpoolServer", Level.TRACE) {
                withServer(signalling(commons = testCommons())) {
                    pair(scope, listener = { receivesSig(sig(scope, seed = 1)) }) {
                        sendRecord(sig(scope, seed = 1))
                        sendRecord(sig(testScope(15), seed = 2))
                        sendRecord(sig(commonsScope(), seed = 3))
                        sendRecord(sig(scope, seed = 4, size = 4_096))
                        nextIsList(scope, q = 2L)
                    }
                }
            }.map { it.formattedMessage }
                // The boot line names the knobs (`rateSigs=4`); that is configuration, not traffic.
                .filterNot { it.startsWith("effective config:") }
                .joinToString("\n")
        assertFalse(Regex("\\bsigs?\\b", RegexOption.IGNORE_CASE).containsMatchIn(logged), logged)
        assertFalse(logged.contains(testScope(14).toHexString().take(8)), logged)
        assertFalse(logged.contains(testScope(15).toHexString().take(8)), logged)
    }
}
