// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The per-pseudonym upload ledger behind the files gate (ADR 2026-10.h6pt), off the wire. */
class UploadBudgetsTest {
    private val nowS = 1_800_000_000L

    private fun ticket(
        seed: Int,
        expS: Long = nowS + 86_400,
        ab: Long? = null,
    ) = Ticket(
        v = Tickets.VERSION,
        kid = ByteArray(Tickets.KID_BYTES),
        aud = "spool.test",
        nbf = nowS - 60,
        exp = expS,
        ps = ByteArray(Tickets.PS_BYTES) { seed.toByte() },
        fl = Tickets.FL_FILES,
        ab = ab,
    )

    @Test
    fun aChunkIsAdmittedOnlyWhileItFitsTheSmallerOfTheClaimAndTheCap() {
        val budgets = UploadBudgets()
        assertTrue(budgets.admits(ticket(1), 1_000, spoolCap = 1_000))
        assertFalse(budgets.admits(ticket(1), 1_001, spoolCap = 1_000))
        assertFalse(budgets.admits(ticket(1, ab = 500), 600, spoolCap = 1_000))
        budgets.charge(ticket(1), 600, nowS)
        assertTrue(budgets.admits(ticket(1), 400, spoolCap = 1_000))
        assertFalse(budgets.admits(ticket(1), 401, spoolCap = 1_000))
        assertTrue(budgets.admits(ticket(2), 1_000, spoolCap = 1_000), "another pseudonym has its own budget")
        assertEquals(0, UploadBudgets().size, "checking alone makes no entry")
    }

    @Test
    fun anEntryLivesUntilTheLatestTicketOnItsPseudonymHasExpired() {
        val budgets = UploadBudgets()
        budgets.charge(ticket(1, expS = nowS + 100), 1, nowS)
        budgets.charge(ticket(1, expS = nowS + 1_000), 1, nowS)
        budgets.charge(ticket(1, expS = nowS + 50), 1, nowS)
        budgets.prune(nowS + 1_000 + Tickets.SKEW_S)
        assertEquals(1, budgets.size)
        budgets.prune(nowS + 1_000 + Tickets.SKEW_S + 1)
        assertEquals(0, budgets.size)
    }

    /** At the cap the dead go first, then whichever live entry is nearest its end; never a refusal to meter. */
    @Test
    fun aFullLedgerPrunesThenEvictsTheEntryNearestItsExpiry() {
        val budgets = UploadBudgets(cap = 2)
        budgets.charge(ticket(1, expS = nowS - Tickets.SKEW_S - 1), 10, nowS)
        budgets.charge(ticket(2, expS = nowS + 500), 10, nowS)
        budgets.charge(ticket(3, expS = nowS + 100), 10, nowS)
        assertEquals(2, budgets.size)
        assertTrue(budgets.admits(ticket(1), 1_000, spoolCap = 1_000), "the expired entry was pruned")
        budgets.charge(ticket(4, expS = nowS + 900), 10, nowS)
        assertEquals(2, budgets.size)
        assertTrue(budgets.admits(ticket(3), 1_000, spoolCap = 1_000), "the entry nearest expiry was evicted")
        assertFalse(budgets.admits(ticket(2), 991, spoolCap = 1_000), "the later one kept what it spent")
    }
}
