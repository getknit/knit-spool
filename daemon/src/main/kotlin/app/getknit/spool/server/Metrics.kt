// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * Daemon counters, rendered as Prometheus text exposition on `/metrics`. Hand-rolled on purpose:
 * the format is `name value` lines and a Micrometer dependency would be the heaviest thing in the
 * process.
 */
class Metrics {
    val connectionsCurrent = AtomicInteger()
    val connectionsTotal = LongAdder()
    val recordsTotal = LongAdder()
    val pushesTotal = LongAdder()
    val eventsTotal = LongAdder()
    val powVerifiedTotal = LongAdder()
    val rateLimitedTotal = LongAdder()
    val shedsTotal = LongAdder()
    val attachChunksStoredTotal = LongAdder()
    private val errsTotal = ConcurrentHashMap<String, LongAdder>()

    fun err(code: String) {
        errsTotal.computeIfAbsent(code) { LongAdder() }.increment()
    }

    fun errCount(code: String): Long = errsTotal[code]?.sum() ?: 0L

    fun render(
        scopesCurrent: Int,
        liveBytes: Long,
    ): String =
        buildString {
            line("knit_spool_connections_current", "gauge", connectionsCurrent.get().toLong())
            line("knit_spool_connections_total", "counter", connectionsTotal.sum())
            line("knit_spool_records_total", "counter", recordsTotal.sum())
            line("knit_spool_pushes_total", "counter", pushesTotal.sum())
            line("knit_spool_events_total", "counter", eventsTotal.sum())
            line("knit_spool_pow_verified_total", "counter", powVerifiedTotal.sum())
            line("knit_spool_rate_limited_total", "counter", rateLimitedTotal.sum())
            line("knit_spool_sheds_total", "counter", shedsTotal.sum())
            line("knit_spool_attach_chunks_stored_total", "counter", attachChunksStoredTotal.sum())
            line("knit_spool_scopes_current", "gauge", scopesCurrent.toLong())
            line("knit_spool_live_bytes", "gauge", liveBytes)
            append("# TYPE knit_spool_errs_total counter\n")
            errsTotal.entries.sortedBy { it.key }.forEach { (code, adder) ->
                append("knit_spool_errs_total{code=\"")
                    .append(code)
                    .append("\"} ")
                    .append(adder.sum())
                    .append('\n')
            }
        }

    private fun StringBuilder.line(
        name: String,
        type: String,
        value: Long,
    ) {
        append("# TYPE ")
            .append(name)
            .append(' ')
            .append(type)
            .append('\n')
        append(name).append(' ').append(value).append('\n')
    }
}
