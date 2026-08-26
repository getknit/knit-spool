// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.BuildInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * Daemon counters, rendered as Prometheus text exposition on `/metrics`. Hand-rolled on purpose:
 * the format is `name value` lines and a Micrometer dependency would be the heaviest thing in the
 * process.
 */
class Metrics(
    private val version: String = BuildInfo.version,
    private val commit: String = BuildInfo.commit,
) {
    val connectionsCurrent = AtomicInteger()
    val connectionsTotal = LongAdder()
    val recordsTotal = LongAdder()
    val pushesTotal = LongAdder()
    val eventsTotal = LongAdder()
    val powVerifiedTotal = LongAdder()
    val rateLimitedTotal = LongAdder()

    /**
     * Upgrades refused because the spool was already holding `SPOOL_MAX_CONNS` connections. Rising
     * here means clients are being turned away while the box still looks healthy in every other
     * counter — the cap doing its job, and the cue that this spool wants a sibling rather than a
     * bigger number.
     */
    val connsRefusedTotal = LongAdder()

    /**
     * Upgrades refused because the spool is draining. Apart from [connsRefusedTotal] on purpose: a
     * planned drain and a box out of room are the same `503` to a client and completely different
     * news to an operator, and only one of them is a reason to buy hardware.
     */
    val drainRefusedTotal = LongAdder()

    val shedsTotal = LongAdder()
    val attachChunksStoredTotal = LongAdder()

    /**
     * Commons traffic, split out from the spool-wide counters because a shared room and a private
     * conversation fail differently: `commons_rate_limited_total` rising is the room at its
     * spool-wide budget, which costs every member at once and is the cue to raise
     * `SPOOL_COMMONS_RATE_PUSHES` — nothing in [rateLimitedTotal] distinguishes that from one noisy
     * client being throttled on its own connection.
     */
    val commonsPushesTotal = LongAdder()
    val commonsRateLimitedTotal = LongAdder()

    /**
     * Record bytes handed to clients — the input to a metered link's monthly bill, which no other
     * counter here exposes. Fan-out means one push leaves as (subscribers - 1) copies, so egress is
     * a multiple of ingest that [eventsTotal] alone cannot tell you the size of.
     *
     * Counts CBOR record payload at frame construction: it excludes WebSocket and TLS framing
     * (which add a few percent), and includes the rare frame dropped for a slow consumer rather
     * than written. Treat it as an estimate of the same magnitude as the real figure, not a
     * byte-exact accounting of what left the NIC.
     */
    val egressBytesTotal = LongAdder()
    private val errsTotal = ConcurrentHashMap<String, LongAdder>()

    fun err(code: String) {
        errsTotal.computeIfAbsent(code) { LongAdder() }.increment()
    }

    fun errCount(code: String): Long = errsTotal[code]?.sum() ?: 0L

    /** Every error code seen so far with its total — what the status line diffs between lines. */
    fun errsByCode(): Map<String, Long> = errsTotal.entries.associate { it.key to it.value.sum() }

    fun render(
        scopesCurrent: Int,
        liveBytes: Long,
        maxConns: Int = 0,
        commonsSubscribers: Int? = null,
    ): String =
        buildString {
            // A gauge that is always 1, carrying its payload in the labels — the exposition idiom
            // for build metadata. It exists so a fleet dashboard can join a spool's series against
            // what it is running: `count by (version) (knit_spool_build_info)` answers "what is
            // actually deployed", which no counter here can. Emitted first, and always, because it
            // is the one series that is true before any traffic arrives.
            append("# TYPE knit_spool_build_info gauge\n")
            append("knit_spool_build_info{version=\"")
                .append(labelValue(version))
                .append("\",commit=\"")
                .append(labelValue(commit))
                .append("\"} 1\n")
            line("knit_spool_connections_current", "gauge", connectionsCurrent.get().toLong())
            line("knit_spool_connections_total", "counter", connectionsTotal.sum())
            line("knit_spool_records_total", "counter", recordsTotal.sum())
            line("knit_spool_pushes_total", "counter", pushesTotal.sum())
            line("knit_spool_events_total", "counter", eventsTotal.sum())
            line("knit_spool_pow_verified_total", "counter", powVerifiedTotal.sum())
            line("knit_spool_rate_limited_total", "counter", rateLimitedTotal.sum())
            line("knit_spool_conns_refused_total", "counter", connsRefusedTotal.sum())
            // Emitted even when unset (0 = unlimited) so an alert expression can divide by it
            // without the series appearing and disappearing with the configuration.
            line("knit_spool_max_conns", "gauge", maxConns.toLong())
            line("knit_spool_drain_refused_total", "counter", drainRefusedTotal.sum())
            line("knit_spool_sheds_total", "counter", shedsTotal.sum())
            line("knit_spool_attach_chunks_stored_total", "counter", attachChunksStoredTotal.sum())
            line("knit_spool_egress_bytes_total", "counter", egressBytesTotal.sum())
            line("knit_spool_scopes_current", "gauge", scopesCurrent.toLong())
            line("knit_spool_live_bytes", "gauge", liveBytes)
            // Absent entirely on a spool with no commons, rather than three flat zeroes that read
            // as a room nobody is using.
            if (commonsSubscribers != null) {
                line("knit_spool_commons_subscribers", "gauge", commonsSubscribers.toLong())
                line("knit_spool_commons_pushes_total", "counter", commonsPushesTotal.sum())
                line("knit_spool_commons_rate_limited_total", "counter", commonsRateLimitedTotal.sum())
            }
            append("# TYPE knit_spool_errs_total counter\n")
            errsTotal.entries.sortedBy { it.key }.forEach { (code, adder) ->
                append("knit_spool_errs_total{code=\"")
                    .append(code)
                    .append("\"} ")
                    .append(adder.sum())
                    .append('\n')
            }
        }

    /**
     * Prometheus escapes exactly three characters in a label value — backslash, double quote,
     * newline — and takes the rest as literal UTF-8. [errsTotal]'s codes are a closed set from the
     * protocol and need none of this; the build stamp is whatever `-PspoolVersion` was handed, and
     * one stray quote there would corrupt not just its own line but the scrape's parse from that
     * point on.
     */
    private fun labelValue(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

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
