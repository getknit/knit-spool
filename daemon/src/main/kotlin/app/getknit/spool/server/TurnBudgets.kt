// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import java.util.concurrent.ConcurrentHashMap

/** As many holders as [UPLOAD_BUDGETS_CAP], for the same reason. */
internal const val TURN_BUDGETS_CAP = UPLOAD_BUDGETS_CAP

private const val TURN_WINDOW_S = 600.0
private const val TURN_PS_MIN_BURST = 2.0

/**
 * Each ticket holder's TURN issues, `SPOOL_TICKET_TURN_RATE` per ten minutes (ADR 2026-10.x9gq),
 * keyed by the ticket's daily pseudonym. The per-connection and per-address buckets key on things a
 * holder can multiply: a ticket shared across a hundred addresses was limited by nothing the spool
 * could see. The pseudonym is the one key every copy of the ticket carries.
 *
 * Built like [UploadBudgets], whose reasoning holds here unchanged: memory only, never logged, an
 * entry made by the first issue and dead once its ticket's window closes. At [cap] a new pseudonym
 * first prunes the dead entries, then evicts the one nearest its expiry, rather than going unmetered.
 *
 * The burst is half the ten-minute rate, at least two: at the default 16 that is the 8 one address
 * gets at the default per-connection rate, so a redial storm on one phone does not use up the
 * holder's other devices' share at once.
 */
internal class TurnBudgets(
    private val clock: () -> Long,
    private val cap: Int = TURN_BUDGETS_CAP,
) {
    private class Entry(
        @Volatile var expS: Long,
        val rate: Int,
        val bucket: TokenBucket,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    val size: Int get() = entries.size

    /**
     * Takes one issue from [ticket]'s pseudonym at [ratePer10Min]: 0 on success, else the
     * milliseconds until the next. A rate a reload moved rebuilds the bucket full, as
     * [IpState.castBucket] does, so a raised rate is never held back by the old one's debt.
     */
    fun take(
        ticket: Ticket,
        ratePer10Min: Int,
        nowS: Long,
    ): Long {
        val key = ticket.ps.toHexString()
        if (!entries.containsKey(key) && entries.size >= cap) makeRoom(nowS)
        // Atomic, so two connections of one holder arriving at once share the bucket rather than
        // each taking from one of their own.
        val entry =
            checkNotNull(
                entries.compute(key) { _, current ->
                    current?.takeIf { it.rate == ratePer10Min } ?: Entry(ticket.exp, ratePer10Min, bucket(ratePer10Min))
                },
            )
        // A renewed ticket on the same day shares the pseudonym, and so the bucket; keep the later end.
        if (ticket.exp > entry.expS) entry.expS = ticket.exp
        return entry.bucket.take()
    }

    /** Drops every entry whose ticket window, skew included, closed before [nowS]. */
    fun prune(nowS: Long) {
        entries.values.removeIf { it.expS + Tickets.SKEW_S < nowS }
    }

    private fun bucket(ratePer10Min: Int): TokenBucket =
        TokenBucket(ratePer10Min / TURN_WINDOW_S, maxOf(TURN_PS_MIN_BURST, ratePer10Min / 2.0), clock)

    private fun makeRoom(nowS: Long) {
        prune(nowS)
        if (entries.size < cap) return
        entries.entries.minByOrNull { it.value.expS }?.let { entries.remove(it.key, it.value) }
    }
}
