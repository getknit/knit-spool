// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Ent
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.TurnCred
import app.getknit.spool.protocol.TurnReq
import ch.qos.logback.classic.Level
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entitlement tickets on `turnreq` (experimental; knit-next `docs/SPOOL_PROTOCOL.md` §7.6, ADR
 * 2026-10.bxsg) — what tells a paying caller from anyone else on a spool that sells TURN. The ticket
 * check itself is pinned in `:protocol`'s `TicketCheckTest`; these pin what the daemon does with it:
 * the hello flag, binding to a connection, the gate it puts on `turnreq`, and the username it yields.
 */
class TicketTest {
    private val now = 1_800_000_000_000L

    private fun ticketed(
        turn: SpoolServer.TurnConfig? = testTurn(),
        ticket: SpoolServer.TicketConfig? = testTicketConfig(),
    ) = testConfig(turn = turn, ticket = ticket)

    private suspend fun DefaultClientWebSocketSession.present(ent: Ent) {
        sendRecord(ent)
        assertEquals(ent.q, expectRecord<Ok>(RecordType.OK).q)
    }

    private suspend fun DefaultClientWebSocketSession.refusedWith(
        reason: Tickets.Reason,
        q: Long,
    ) {
        val err = expectRecord<Err>(RecordType.ERR)
        assertEquals(ErrCode.TICKET, err.code)
        assertEquals(q, err.q)
        assertEquals(reason.word, err.msg)
        assertNull(err.scope)
    }

    private suspend fun DefaultClientWebSocketSession.credential(q: Long): TurnCred {
        sendRecord(TurnReq(t = RecordType.TURNREQ, q = q))
        return expectRecord<TurnCred>(RecordType.TURNCRED)
    }

    @Test
    fun helloCarriesEntOnlyWithAKey() {
        withServer(testConfig(turn = testTurn())) {
            connect {
                val hello = helloHandshake()
                assertNull(hello.ent)
                assertFalse(RecordCodec.encode(hello).containsBytes("ent".toByteArray()), "no ent key without a ticket key")
            }
        }
        withServer(ticketed()) { connect { assertEquals(true, helloHandshake().ent) } }
    }

    /** S-7.6-16: no ticket, no credential — and holding a subscription does not substitute for one. */
    @Test
    fun anUnticketedTurnreqIsRefusedEvenWhenSubscribed() {
        withServer(ticketed(), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 5L))
                refusedWith(Tickets.Reason.MISSING, q = 5L)
            }
            assertEquals(1L, spool.metrics.errCount(ErrCode.TICKET))
        }
    }

    /** With a ticket, the subscription gate is waived: a credential can be fetched on a scope-free connection. */
    @Test
    fun aTicketedConnectionNeedsNoSubscription() {
        val clock = FakeClock(now)
        withServer(ticketed(turn = testTurn(ttlS = 300L)), clock = clock) {
            connect {
                helloHandshake()
                present(testEnt(clock.now))
                val cred = credential(q = 6L)
                assertEquals(6L, cred.q)
                assertEquals(now / 1_000 + 300L, cred.exp)
                assertEquals(TurnCredentials.pass(TEST_TURN_SECRET, cred.user), cred.pass)
            }
            assertEquals(1L, spool.metrics.ticketAcceptedTotal.sum())
            assertEquals(1L, spool.metrics.turnIssuedTicketedTotal.sum())
        }
    }

    /**
     * S-7.6-17: every credential one pseudonym fetches shares its suffix — across connections too —
     * so coturn's `user-quota` counts the holder. Another pseudonym gets another suffix.
     */
    @Test
    fun aPseudonymYieldsOneStableSuffix() {
        val clock = FakeClock(now)
        val alice = ByteArray(Tickets.PS_BYTES) { 0x0a }
        val bob = ByteArray(Tickets.PS_BYTES) { 0x0b }
        withServer(ticketed(), clock = clock) {
            val suffixes = mutableListOf<String>()
            repeat(2) {
                connect {
                    helloHandshake()
                    present(testEnt(clock.now, ps = alice))
                    suffixes += credential(q = 1L).user.substringAfter(':')
                    suffixes += credential(q = 2L).user.substringAfter(':')
                }
            }
            assertEquals(1, suffixes.toSet().size, "one pseudonym, one suffix: $suffixes")
            val expected = TurnCredentials.user(0L, TurnCredentials.pseudonymSuffix(alice)).substringAfter(':')
            assertEquals(expected, suffixes.first())
            connect {
                helloHandshake()
                present(testEnt(clock.now, ps = bob))
                assertNotEquals(suffixes.first(), credential(q = 3L).user.substringAfter(':'))
            }
        }
    }

    /** S-7.6-14: each refusal names its reason, echoes `q`, and is not a strike. */
    @Test
    fun aBadTicketIsRefusedWithItsReasonAndNoStrike() {
        val clock = FakeClock(now)
        withServer(ticketed(), clock = clock) {
            connect {
                helloHandshake()
                val nowS = clock.now / 1_000
                val cases =
                    listOf(
                        Tickets.Reason.AUDIENCE to testEnt(clock.now, q = 20L, aud = "elsewhere.test"),
                        Tickets.Reason.EARLY to testEnt(clock.now, q = 21L, nbfS = nowS + 3_600, expS = nowS + 7_200),
                        Tickets.Reason.EXPIRED to testEnt(clock.now, q = 22L, nbfS = nowS - 7_200, expS = nowS - 3_600),
                        Tickets.Reason.KEY to testEnt(clock.now, q = 23L, signingKey = Tickets.generateSigningKey()),
                    )
                // More refusals than the strike window allows, on one connection that stays open.
                repeat(3) {
                    for ((reason, ent) in cases) {
                        sendRecord(ent)
                        refusedWith(reason, ent.q)
                    }
                }
                present(testEnt(clock.now, q = 30L))
            }
            val scraped = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
            assertTrue(scraped.contains("knit_spool_ticket_refused_total{reason=\"audience\"} 3\n"), scraped)
            assertTrue(scraped.contains("knit_spool_ticket_refused_total{reason=\"signature\"} 0\n"), "every reason renders from zero")
            assertTrue(scraped.contains("knit_spool_ticket_accepted_total 1\n"), scraped)
        }
    }

    /** S-7.6-15: a refused renewal leaves the accepted ticket in place; an accepted one replaces it. */
    @Test
    fun aRefusedRenewalKeepsTheOldTicketAndAnAcceptedOneReplacesIt() {
        val clock = FakeClock(now)
        withServer(ticketed(), clock = clock) {
            connect {
                helloHandshake()
                present(testEnt(clock.now, ps = ByteArray(Tickets.PS_BYTES) { 1 }))
                val first = credential(q = 1L).user.substringAfter(':')
                sendRecord(testEnt(clock.now, q = 2L, aud = "elsewhere.test"))
                refusedWith(Tickets.Reason.AUDIENCE, q = 2L)
                assertEquals(first, credential(q = 3L).user.substringAfter(':'), "the refused ticket must not displace the good one")
                // The connection's burst is two credentials; refill it rather than test the rate here.
                clock.advance(60_000L)
                present(testEnt(clock.now, q = 4L, ps = ByteArray(Tickets.PS_BYTES) { 2 }))
                assertNotEquals(first, credential(q = 5L).user.substringAfter(':'), "an accepted ticket replaces the old one")
            }
        }
    }

    @Test
    fun aTicketThatExpiresMidConnectionStopsIssuing() {
        val clock = FakeClock(now)
        withServer(ticketed(), clock = clock) {
            connect {
                helloHandshake()
                present(testEnt(clock.now, expS = clock.now / 1_000 + 600))
                credential(q = 1L)
                clock.advance((600 + Tickets.SKEW_S + 1) * 1_000)
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 2L))
                refusedWith(Tickets.Reason.EXPIRED, q = 2L)
            }
        }
    }

    @Test
    fun aTicketWithoutTheTurnFlagIsRefusedTurn() {
        val clock = FakeClock(now)
        withServer(ticketed(), clock = clock) {
            connect {
                helloHandshake()
                present(testEnt(clock.now, fl = 0L))
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 3L))
                refusedWith(Tickets.Reason.FLAGS, q = 3L)
            }
        }
    }

    /** A spool with no key never advertised `ent`, and treats one like any record it was not offered. */
    @Test
    fun withoutAKeyEntIsMalformedAndTurnreqIsUnchanged() {
        val clock = FakeClock(now)
        withServer(ticketed(ticket = null), clock = clock) {
            connect {
                helloHandshake()
                sendRecord(testEnt(clock.now, q = 7L))
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.MALFORMED, err.code)
                assertEquals(7L, err.q)
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 8L))
                assertEquals(ErrCode.NOT_SUBSCRIBED, expectRecord<Err>(RecordType.ERR).code)
                subscribe(testScope(1))
                val a = credential(q = 9L)
                val b = credential(q = 10L)
                assertNotEquals(a.user, b.user, "unticketed issues keep their fresh random suffix")
            }
            assertFalse(http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("ticket"))
        }
    }

    /**
     * A connection follows the hello it was sent. A reload that adds the key must not start refusing
     * `turnreq` on a client that was never told tickets exist; one that removes it must not waive the
     * subscription gate for a client that was — it holds its ticket, and new `ent`s are refused `key`.
     */
    @Test
    fun aReloadNeverChangesALiveConnectionsGate() {
        val clock = FakeClock(now)
        withServer(ticketed(ticket = null), clock = clock) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                spool.reload(ticketed())
                credential(q = 1L)
            }
            connect { assertEquals(true, helloHandshake().ent, "a new connection sees the reloaded key") }
        }
        withServer(ticketed(), clock = clock) {
            connect {
                helloHandshake()
                present(testEnt(clock.now))
                spool.reload(ticketed(ticket = null))
                credential(q = 2L)
                sendRecord(testEnt(clock.now, q = 3L))
                refusedWith(Tickets.Reason.KEY, q = 3L)
            }
        }
    }

    @Test
    fun theAddressBudgetStillAppliesToTicketHolders() {
        val clock = FakeClock(now)
        withServer(ticketed(turn = testTurn(ratePer10Min = 1)), clock = clock) {
            repeat(4) {
                connect {
                    helloHandshake()
                    present(testEnt(clock.now))
                    repeat(2) { credential(q = it.toLong()) }
                }
            }
            connect {
                helloHandshake()
                present(testEnt(clock.now))
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 1L))
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }

    /** S-7.6-18: the ticket and its pseudonym are as unloggable as the credential they buy. */
    @Test
    fun neitherTheTicketNorItsPseudonymIsEverLogged() {
        val clock = FakeClock(now)
        val ps = ByteArray(Tickets.PS_BYTES) { 0x5c }
        val ent = testEnt(clock.now, ps = ps)
        lateinit var cred: TurnCred
        val logged =
            withLogCapture("app.getknit.spool.server.SpoolServer", Level.TRACE) {
                withServer(ticketed(), clock = clock) {
                    connect {
                        helloHandshake()
                        present(ent)
                        cred = credential(q = 1L)
                    }
                }
            }.joinToString("\n") { it.formattedMessage }
        assertFalse(logged.contains(ps.toHexString()), logged)
        assertFalse(logged.contains(ent.sig.toHexString()), logged)
        assertFalse(logged.contains(Tickets.encodeText(ent.tk, ent.sig)), logged)
        assertFalse(logged.contains(cred.user.substringAfter(':')), logged)
        assertTrue(cred.pass.isNotEmpty())
    }
}
