// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
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
 * TURN credential issuing (experimental; knit-next `docs/SPOOL_PROTOCOL.md` §7.6).
 * The spool hands out coturn REST credentials and never relays media; these pin the gate, the
 * credential's shape and the budget.
 */
class TurnTest {
    @Test
    fun helloOmitsTurnUnlessConfigured() {
        withServer(testConfig()) {
            connect {
                val hello = helloHandshake()
                assertNull(hello.turn)
                assertFalse(
                    RecordCodec.encode(hello).containsBytes("turn".toByteArray()),
                    "the server hello carried a turn key on a spool that issues no credentials",
                )
            }
        }
        withServer(testConfig(turn = testTurn())) {
            connect { assertEquals(true, helloHandshake().turn) }
        }
    }

    /** The request names no scope, so holding *some* subscription is the whole gate. */
    @Test
    fun aConnectionWithNoSubscriptionIsRefused() {
        withServer(testConfig(turn = testTurn())) {
            connect {
                helloHandshake()
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 3L))
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.NOT_SUBSCRIBED, err.code)
                assertEquals(3L, err.q)
                assertNull(err.scope, "a turnreq names no scope, so the refusal must not invent one")
            }
        }
    }

    @Test
    fun aSubscriberGetsACredentialCoturnWouldAccept() {
        val clock = FakeClock(now = 1_700_000_000_123L)
        withServer(testConfig(turn = testTurn(ttlS = 3_600L)), clock = clock) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 9L))
                val cred = expectRecord<TurnCred>(RecordType.TURNCRED)
                assertEquals(9L, cred.q)
                assertEquals(TEST_TURN_URLS, cred.urls)
                // Seconds, not milliseconds: coturn compares the user's prefix against its own clock.
                assertEquals(1_700_000_000L + 3_600L, cred.exp)
                assertTrue(USER.matches(cred.user), cred.user)
                assertEquals(cred.exp.toString(), cred.user.substringBefore(':'))
                assertEquals(TurnCredentials.pass(TEST_TURN_SECRET, cred.user), cred.pass)

                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 10L))
                val second = expectRecord<TurnCred>(RecordType.TURNCRED)
                assertNotEquals(cred.user, second.user, "two issues must never share a suffix")
            }
            assertEquals(2L, spool.metrics.turnIssuedTotal.count(1))
            assertTrue(http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("knit_spool_turn_issued_total{server=\"1\"} 2\n"))
        }
    }

    /**
     * Past the budget the answer is `rate` with a `retryMs`, and no strike: a client that redials a
     * dropped call is not abusing the scope plane, and closing it 4003 would end the call it is
     * trying to save. The connection stays usable.
     */
    @Test
    fun theConnectionBudgetRefusesWithRateAndNoStrike() {
        withServer(testConfig(turn = testTurn(ratePer10Min = 1))) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                repeat(2) { sendRecord(TurnReq(t = RecordType.TURNREQ, q = it.toLong())) }
                repeat(2) { expectRecord<TurnCred>(RecordType.TURNCRED) }
                repeat(RATE_STRIKE_LIMIT_FOR_TEST) { attempt ->
                    sendRecord(TurnReq(t = RecordType.TURNREQ, q = 100L + attempt))
                    val err = expectRecord<Err>(RecordType.ERR)
                    assertEquals(ErrCode.RATE, err.code)
                    assertEquals(100L + attempt, err.q)
                    assertTrue((err.retryMs ?: 0L) > 0L, "a rate refusal must say when to come back")
                }
                // Still open after more refusals than the strike window allows.
                sendRecord(Sub(t = RecordType.SUB, q = 200L, subs = listOf(ScopeSub(scope = testScope(2), bounds = testBounds()))))
                expectRecord<Digest>(RecordType.DIGEST)
            }
        }
    }

    /** One address gets four connections' worth, so reconnecting is not a way around the budget. */
    @Test
    fun theAddressBudgetSpansConnections() {
        withServer(testConfig(turn = testTurn(ratePer10Min = 1))) {
            // The per-address burst is 4 × 2 = 8: four connections spend it, the fifth is refused.
            repeat(4) {
                connect {
                    helloHandshake()
                    subscribe(testScope(1))
                    repeat(2) { sendRecord(TurnReq(t = RecordType.TURNREQ, q = it.toLong())) }
                    repeat(2) { expectRecord<TurnCred>(RecordType.TURNCRED) }
                }
            }
            connect {
                helloHandshake()
                subscribe(testScope(1))
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 1L))
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }

    /** C-7.6-3 says a client never sends this to a spool without `turn`; its `q` still gets an answer. */
    @Test
    fun aSpoolWithoutTurnAnswersMalformed() {
        withServer(testConfig()) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 4L))
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.MALFORMED, err.code)
                assertEquals(4L, err.q)
            }
            assertTrue(!http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("knit_spool_turn_issued_total"))
        }
    }

    /**
     * S-7.6-48: `turnHosts` names each server's UDP entry, in server order, beside `turn`. One server
     * sends its one entry; one with no UDP URL sends no `turnHosts` at all, never an empty list.
     */
    @Test
    fun helloNamesEachServersEntryInServerOrder() {
        withServer(testConfig(turn = testTurn(more = listOf(testTurnServer2())))) {
            connect { assertEquals(listOf("spool.example:3478", "[2001:db8::7]:3478"), helloHandshake().turnHosts) }
        }
        withServer(testConfig(turn = testTurn())) {
            connect { assertEquals(listOf("spool.example:3478"), helloHandshake().turnHosts) }
        }
        withServer(testConfig(turn = testTurn(urls = listOf("turns:spool.example:5349?transport=tcp")))) {
            connect {
                val hello = helloHandshake()
                assertEquals(true, hello.turn)
                assertNull(hello.turnHosts)
                assertFalse(RecordCodec.encode(hello).containsBytes("turnHosts".toByteArray()), "an omitted turnHosts is no key at all")
            }
        }
        withServer(testConfig()) { connect { assertNull(helloHandshake().turnHosts) } }
    }

    /**
     * S-7.6-49/50: a `host` equal to an entry selects that server, whose URLs alone come back, signed
     * with its secret and not another's. An absent, unknown or not-quite-equal one gets server 1.
     */
    @Test
    fun aHostSelectsItsServerAndAnythingElseGetsServerOne() {
        val clock = FakeClock(now = 1_700_000_000_000L)
        withServer(testConfig(turn = testTurn(more = listOf(testTurnServer2()))), clock = clock) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val two = credential(q = 1L, host = "[2001:db8::7]:3478")
                assertEquals(TEST_TURN_URLS_2, two.urls)
                assertEquals(TurnCredentials.pass(TEST_TURN_SECRET_2, two.user), two.pass)
                assertNotEquals(TurnCredentials.pass(TEST_TURN_SECRET, two.user), two.pass)
                assertEquals(TEST_TURN_URLS, credential(q = 2L, host = "spool.example:3478").urls)
                // Byte for byte: the entry is the spool's to spell, and a client copies it.
                for ((q, host) in listOf(3L to null, 4L to "unknown.example:3478", 5L to "[2001:DB8::7]:3478", 6L to "[2001:db8::7]")) {
                    clock.advance(TURN_TOKEN_MS)
                    val one = credential(q = q, host = host)
                    assertEquals(TEST_TURN_URLS, one.urls, "host=$host")
                    assertEquals(TurnCredentials.pass(TEST_TURN_SECRET, one.user), one.pass, "host=$host")
                }
            }
            assertEquals(5L, spool.metrics.turnIssuedTotal.count(1))
            assertEquals(1L, spool.metrics.turnIssuedTotal.count(2))
            val scraped = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
            assertTrue(scraped.contains("knit_spool_turn_issued_total{server=\"1\"} 5\n"), scraped)
            assertTrue(scraped.contains("knit_spool_turn_issued_total{server=\"2\"} 1\n"), scraped)
            assertEquals(0L, spool.metrics.errCount(ErrCode.MALFORMED), "an unknown host is never an error")
        }
    }

    /** A spool with no `turnHosts` (one server, `turns:` only) treats every `host` as absent. */
    @Test
    fun aSpoolWithoutTurnHostsIgnoresHost() {
        withServer(testConfig(turn = testTurn(urls = listOf("turns:spool.example:5349?transport=tcp")))) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                assertEquals(listOf("turns:spool.example:5349?transport=tcp"), credential(q = 1L, host = "spool.example:5349").urls)
            }
        }
    }

    /** S-7.6-50: one per-connection budget, whatever host is asked; naming another is no way around it. */
    @Test
    fun theBudgetIsSharedAcrossHosts() {
        withServer(testConfig(turn = testTurn(ratePer10Min = 1, more = listOf(testTurnServer2())))) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                credential(q = 1L, host = "spool.example:3478")
                credential(q = 2L, host = "[2001:db8::7]:3478")
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 3L, host = "[2001:db8::7]:3478"))
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
            }
            assertEquals(1L, spool.metrics.turnIssuedTotal.count(1))
            assertEquals(1L, spool.metrics.turnIssuedTotal.count(2))
        }
    }

    /** A `host` that is not a text string is a malformed record, as any mistyped field is. */
    @Test
    fun aHostThatIsNotTextIsMalformed() {
        withServer(testConfig(turn = testTurn(more = listOf(testTurnServer2())))) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                // {t: "turnreq", q: 7, host: 7}
                sendRaw("a36174677475726e72657161710764686f737407".hexToByteArray())
                assertEquals(ErrCode.MALFORMED, expectRecord<Err>(RecordType.ERR).code)
            }
            assertEquals(0L, spool.metrics.turnIssuedTotal.count(1))
        }
    }

    /** SECURITY.md: a secret in this daemon's log is a vulnerability, and a live credential is one. */
    @Test
    fun neitherHalfOfACredentialIsEverLogged() {
        lateinit var cred: TurnCred
        val logged =
            withLogCapture("app.getknit.spool.server.SpoolServer", Level.TRACE) {
                withServer(testConfig(turn = testTurn())) {
                    connect {
                        helloHandshake()
                        subscribe(testScope(1))
                        sendRecord(TurnReq(t = RecordType.TURNREQ, q = 1L))
                        cred = expectRecord<TurnCred>(RecordType.TURNCRED)
                    }
                }
            }.joinToString("\n") { it.formattedMessage }
        assertFalse(logged.contains(cred.pass), logged)
        assertFalse(logged.contains(cred.user.substringAfter(':')), logged)
        assertFalse(logged.contains(TEST_TURN_SECRET), logged)
    }

    private suspend fun DefaultClientWebSocketSession.credential(
        q: Long,
        host: String?,
    ): TurnCred {
        sendRecord(TurnReq(t = RecordType.TURNREQ, q = q, host = host))
        return expectRecord<TurnCred>(RecordType.TURNCRED).also { assertEquals(q, it.q) }
    }

    private companion object {
        val USER = Regex("^\\d+:[a-z2-7]{16}$")

        /** One per-connection token at [testTurn]'s 60 per ten minutes. */
        const val TURN_TOKEN_MS = 10_000L

        /** More refusals than `RATE_STRIKE_LIMIT`, so a strike would have closed the connection. */
        const val RATE_STRIKE_LIMIT_FOR_TEST = 10
    }
}
