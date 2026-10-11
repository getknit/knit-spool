// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.TurnCred
import app.getknit.spool.protocol.TurnReq
import app.getknit.spool.store.HardLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * `SPOOL_TICKET_GATES` (ADR 2026-10.h6pt): which capabilities a ticket key gates, how `hello` says
 * so, and that the default keeps what a key gated before the list existed. The files gate itself is
 * [TicketFilesTest]'s.
 */
class TicketGatesTest {
    private val now = 1_800_000_000_000L
    private val attachLimits =
        HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 8, maxAttachBytes = 1 shl 20)

    private fun keyed(gates: Long = Tickets.LEGACY_GATES) =
        testConfig(hardLimits = attachLimits, turn = testTurn(), ticket = testTicketConfig(gates = gates))

    @Test
    fun helloCarriesEntGatesBesideEntAndNeverWithoutIt() {
        withServer(testConfig(turn = testTurn())) {
            connect {
                val hello = helloHandshake()
                assertNull(hello.entGates)
                assertEquals(0L, hello.ticketGates)
            }
        }
        withServer(keyed()) { connect { assertEquals(Tickets.LEGACY_GATES, helloHandshake().entGates) } }
        withServer(keyed(Tickets.FL_FILES)) { connect { assertEquals(Tickets.FL_FILES, helloHandshake().entGates) } }
    }

    /** The default gates leave uploads open on a keyed spool, so deploying the files gate changes nothing. */
    @Test
    fun theDefaultGatesLeaveUploadsOpenAndUncharged() {
        val scope = testScope(1)
        withServer(keyed(), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                val (cid, data) = testBlob(1)
                sendRecord(aput(2L, scope, ByteArray(32) { 1 }, cid, data))
                assertEquals(2L, expectRecord<Ok>(RecordType.OK).q)
            }
            assertEquals(0, spool.uploadBudgetCount())
        }
    }

    /**
     * With `turn` left out, a `turnreq` follows the no-key rules: a subscription is enough and none is
     * refused for want of a ticket. A live TURN ticket still earns its pseudonym suffix.
     */
    @Test
    fun turnUngatedKeepsTheSubscriptionGateAndThePseudonymSuffix() {
        val ps = ByteArray(Tickets.PS_BYTES) { 0x11 }
        withServer(keyed(Tickets.FL_FILES), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 1L))
                assertEquals(ErrCode.NOT_SUBSCRIBED, expectRecord<Err>(RecordType.ERR).code)
                subscribe(testScope(1))
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 2L))
                val free = expectRecord<TurnCred>(RecordType.TURNCRED).user.substringAfter(':')
                assertNotEquals(TurnCredentials.user(0L, TurnCredentials.pseudonymSuffix(ps)).substringAfter(':'), free)
            }
            connect {
                helloHandshake()
                sendRecord(testEnt(now, q = 3L, ps = ps, fl = Tickets.FL_TURN))
                expectRecord<Ok>(RecordType.OK)
                sendRecord(TurnReq(t = RecordType.TURNREQ, q = 4L))
                val paid = expectRecord<TurnCred>(RecordType.TURNCRED).user.substringAfter(':')
                assertEquals(TurnCredentials.user(0L, TurnCredentials.pseudonymSuffix(ps)).substringAfter(':'), paid)
            }
        }
    }

    /** A connection keeps the gates of the hello it was sent; a reload reaches only connections opened after it. */
    @Test
    fun aGatesReloadNeverChangesALiveConnection() {
        val scope = testScope(2)
        withServer(keyed(), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                assertEquals(emptyList(), spool.reload(keyed(Tickets.LEGACY_GATES or Tickets.FL_FILES)))
                val (cid, data) = testBlob(1)
                sendRecord(aput(2L, scope, ByteArray(32) { 1 }, cid, data))
                assertEquals(2L, expectRecord<Ok>(RecordType.OK).q, "told uploads were open, so they stay open")
            }
            connect {
                assertEquals(Tickets.LEGACY_GATES or Tickets.FL_FILES, helloHandshake().entGates)
                subscribe(scope)
                val (cid, data) = testBlob(2)
                sendRecord(aput(3L, scope, ByteArray(32) { 2 }, cid, data))
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.TICKET, err.code)
                assertEquals(Tickets.Reason.MISSING.word, err.msg)
            }
        }
    }
}
