// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Ample for every ticket holder a spool sees in a day; see [UploadBudgets] for what happens past it. */
internal const val UPLOAD_BUDGETS_CAP = 65_536

/**
 * What each ticket holder has uploaded on its day where `files` is gated (S-7.6-22, ADR
 * 2026-10.h6pt), keyed by the ticket's daily pseudonym. The pseudonym changes each day, so the
 * budget does too, and an entry is dead once its ticket's window closes.
 *
 * Only the uploader's own ticket is ever charged: nothing here is keyed by scope, so one holder's
 * budget cannot pay for anyone else's upload. Memory only, so a restart resets every budget, and
 * neither a pseudonym nor what it spent is ever logged or persisted (S-7.6-18). An entry is made by
 * the first charge, so a ticket that never uploads costs nothing here.
 *
 * At [cap] a new pseudonym first prunes the dead entries, then evicts the one nearest its expiry.
 * Refusing to grow instead, as the PoW cache does, would leave a new holder unmetered; entries come
 * only from tickets the issuer signed, so reaching the cap means a busy spool, not an attack.
 */
internal class UploadBudgets(
    private val cap: Int = UPLOAD_BUDGETS_CAP,
) {
    private class Entry(
        @Volatile var expS: Long,
    ) {
        val used = AtomicLong()
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    val size: Int get() = entries.size

    /** Whether [ticket]'s pseudonym may store [bytes] more under `min(ab, spoolCap)`. */
    fun admits(
        ticket: Ticket,
        bytes: Long,
        spoolCap: Long,
    ): Boolean {
        val limit = minOf(ticket.ab ?: spoolCap, spoolCap)
        val used = entries[key(ticket)]?.used?.get() ?: 0L
        return used + bytes <= limit
    }

    /** Adds [bytes] to [ticket]'s pseudonym; called only once a chunk is actually stored. */
    fun charge(
        ticket: Ticket,
        bytes: Long,
        nowS: Long,
    ) {
        val key = key(ticket)
        if (!entries.containsKey(key) && entries.size >= cap) makeRoom(nowS)
        val entry = entries.computeIfAbsent(key) { Entry(ticket.exp) }
        // A renewed ticket on the same day shares the pseudonym, and so the budget; keep the later end.
        if (ticket.exp > entry.expS) entry.expS = ticket.exp
        entry.used.addAndGet(bytes)
    }

    /** Drops every entry whose ticket window, skew included, closed before [nowS]. */
    fun prune(nowS: Long) {
        entries.values.removeIf { it.expS + Tickets.SKEW_S < nowS }
    }

    private fun makeRoom(nowS: Long) {
        prune(nowS)
        if (entries.size < cap) return
        entries.entries.minByOrNull { it.value.expS }?.let { entries.remove(it.key, it.value) }
    }

    private fun key(ticket: Ticket): String = ticket.ps.toHexString()
}
