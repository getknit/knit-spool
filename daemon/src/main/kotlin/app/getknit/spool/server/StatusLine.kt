// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import java.util.Locale

/** Heap in use and the ceiling the JVM was started with, both in bytes. */
class HeapUse(
    val used: Long,
    val max: Long,
)

/**
 * The periodic one-line status the daemon logs every `SPOOL_STATUS_MS` — what you get by running
 * `docker logs -f` and glancing at it, on a box with no Prometheus in front of `/metrics`.
 *
 * Gauges are absolute (`conns`, `scopes`, `live`, `heap`) and counters are printed as the delta
 * since the previous line (`records=+142`), because on a scrolling log the interesting question is
 * "what has this spool been doing for the last five minutes", not "what has it done since boot" —
 * `/metrics` already answers the latter, monotonically and exactly. Keys are `key=value` so the
 * line greps and parses; it stays one line at any load, with the per-code error breakdown bounded
 * to the [ERR_CODES_SHOWN] busiest codes.
 *
 * Holds the previous counter snapshot, so a single instance must serve the whole process — every
 * [render] call consumes the deltas it prints.
 */
class StatusLine(
    private val metrics: Metrics,
    private val maxScopes: Int,
    private val maxBytes: Long,
    private val maxConns: Int = 0,
    private val startedAtMs: Long,
    private val heap: () -> HeapUse = ::runtimeHeap,
) {
    private var prev = snapshot()

    fun render(
        now: Long,
        scopes: Int,
        liveBytes: Long,
        commonsSubscribers: Int? = null,
        commonsFrames: Int = 0,
    ): String {
        val current = snapshot()
        val delta = current - prev
        prev = current
        val heapUse = heap()
        return buildString {
            append("up=").append(uptime(now - startedAtMs))
            append(" conns=").append(metrics.connectionsCurrent.get())
            // Same idiom as scopes: the ceiling is only printed when there is one to hit.
            if (maxConns > 0) append('/').append(maxConns)
            append(" accepted=+").append(delta.connections)
            append(" scopes=").append(scopes).append('/').append(maxScopes)
            // Members in the room and frames it holds. Omitted when there is no commons; the frame
            // ceiling is static and already in the startup line, so it is not repeated here.
            if (commonsSubscribers != null) {
                append(" commons=")
                    .append(commonsSubscribers)
                    .append("sub/")
                    .append(commonsFrames)
                    .append('f')
            }
            append(" live=").append(bytes(liveBytes))
            // 0 is "unlimited" for the watermark, and "4.2MiB/0B" would read as a spool at its cap.
            if (maxBytes > 0) append('/').append(bytes(maxBytes))
            append(" heap=").append(bytes(heapUse.used)).append('/').append(bytes(heapUse.max))
            append(" records=+").append(delta.records)
            append(" pushes=+").append(delta.pushes)
            append(" events=+").append(delta.events)
            append(" egress=+").append(bytes(delta.egressBytes))
            append(" limited=+").append(delta.rateLimited)
            append(" refused=+").append(delta.connsRefused)
            append(" sheds=+").append(delta.sheds)
            append(" errs=+").append(delta.errs.values.sum())
            appendErrCodes(delta.errs)
        }
    }

    private fun StringBuilder.appendErrCodes(errs: Map<String, Long>) {
        if (errs.isEmpty()) return
        val busiest =
            errs.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(ERR_CODES_SHOWN)
        append('{')
        busiest.forEachIndexed { i, (code, count) ->
            if (i > 0) append(',')
            append(code).append('=').append(count)
        }
        if (errs.size > busiest.size) append(",+").append(errs.size - busiest.size).append("more")
        append('}')
    }

    private fun snapshot(): Counters =
        Counters(
            connections = metrics.connectionsTotal.sum(),
            records = metrics.recordsTotal.sum(),
            pushes = metrics.pushesTotal.sum(),
            events = metrics.eventsTotal.sum(),
            egressBytes = metrics.egressBytesTotal.sum(),
            rateLimited = metrics.rateLimitedTotal.sum(),
            connsRefused = metrics.connsRefusedTotal.sum(),
            sheds = metrics.shedsTotal.sum(),
            errs = metrics.errsByCode(),
        )

    private class Counters(
        val connections: Long,
        val records: Long,
        val pushes: Long,
        val events: Long,
        val egressBytes: Long,
        val rateLimited: Long,
        val connsRefused: Long,
        val sheds: Long,
        val errs: Map<String, Long>,
    ) {
        operator fun minus(older: Counters): Counters =
            Counters(
                connections = connections - older.connections,
                records = records - older.records,
                pushes = pushes - older.pushes,
                events = events - older.events,
                egressBytes = egressBytes - older.egressBytes,
                rateLimited = rateLimited - older.rateLimited,
                connsRefused = connsRefused - older.connsRefused,
                sheds = sheds - older.sheds,
                // A code absent from the older snapshot is entirely new, so its whole count is the
                // delta; a code whose count did not move is dropped rather than printed as zero.
                errs =
                    errs
                        .mapValues { (code, count) -> count - (older.errs[code] ?: 0L) }
                        .filterValues { it > 0L },
            )
    }

    companion object {
        /** Error codes named in one line before the tail is summarized as `+Nmore`. */
        const val ERR_CODES_SHOWN = 3

        private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

        fun runtimeHeap(): HeapUse {
            val runtime = Runtime.getRuntime()
            return HeapUse(used = runtime.totalMemory() - runtime.freeMemory(), max = runtime.maxMemory())
        }

        /** Two significant units, so the field stays short: `3d04h`, `2h14m`, `14m32s`, `32s`. */
        internal fun uptime(ms: Long): String {
            val seconds = (ms / 1_000).coerceAtLeast(0)
            val d = seconds / 86_400
            val h = seconds % 86_400 / 3_600
            val m = seconds % 3_600 / 60
            val s = seconds % 60
            return when {
                d > 0 -> "%dd%02dh".format(Locale.ROOT, d, h)
                h > 0 -> "%dh%02dm".format(Locale.ROOT, h, m)
                m > 0 -> "%dm%02ds".format(Locale.ROOT, m, s)
                else -> "${s}s"
            }
        }

        /** Binary units, one decimal above a kibibyte — `0B`, `912B`, `4.2MiB`. */
        internal fun bytes(n: Long): String {
            if (n < 1024) return "${n}B"
            var value = n.toDouble()
            var unit = 0
            while (value >= 1024 && unit < UNITS.lastIndex) {
                value /= 1024
                unit++
            }
            return "%.1f%s".format(Locale.ROOT, value, UNITS[unit])
        }
    }
}
