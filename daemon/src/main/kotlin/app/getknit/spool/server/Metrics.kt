// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.BuildInfo
import app.getknit.spool.protocol.Tickets
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

    /**
     * Bare scopes given back (ADR 2026-10.d2zb): idle past their TTL, or evicted to admit a new
     * scope at `SPOOL_MAX_SCOPES`. Apart from [shedsTotal] because nothing was lost: a shed drops
     * held frames under byte pressure, a reclaim drops an empty row.
     */
    val reclaimsTotal = LongAdder()

    /**
     * New scopes refused `quota` at `SPOOL_MAX_SCOPES`, on a `sub` or on the push that recreates a
     * shed scope (ADR 2026-10.x9gq). Apart from the `quota` errs, which also count attachments over
     * their caps: this one is a conversation that could not start here, and on a spool that sells
     * TURN, a call that gets none from it. Rising means the spool is sized too small, not attacked.
     */
    val newScopesQuotaRefusedTotal = LongAdder()
    val attachChunksStoredTotal = LongAdder()

    /**
     * Commons traffic per room, keyed by the room's number (1 for `SPOOL_COMMONS_*`, n for
     * `SPOOL_COMMONS_<n>_*`) and never by its id or name. Split out from the spool-wide counters
     * because a shared room and a private conversation fail differently: `commons_rate_limited_total`
     * rising is a room at its budget, which costs every member at once and is the cue to raise
     * `SPOOL_COMMONS_RATE_PUSHES` (`budget="room"`) or `SPOOL_COMMONS_RATE_TOTAL` (`budget="spool"`) —
     * nothing in [rateLimitedTotal] distinguishes that from one noisy client being throttled on its
     * own connection.
     */
    private val commonsPushesTotal = ConcurrentHashMap<Int, LongAdder>()
    private val commonsRateLimitedTotal = ConcurrentHashMap<Pair<Int, CommonsBudget>, LongAdder>()

    fun commonsPushed(room: Int) = commonsPushesTotal.computeIfAbsent(room) { LongAdder() }.increment()

    fun commonsRateLimited(
        room: Int,
        budget: CommonsBudget,
    ) = commonsRateLimitedTotal.computeIfAbsent(room to budget) { LongAdder() }.increment()

    fun commonsPushCount(room: Int): Long = commonsPushesTotal[room]?.sum() ?: 0L

    fun commonsRateLimitedCount(
        room: Int,
        budget: CommonsBudget,
    ): Long = commonsRateLimitedTotal[room to budget]?.sum() ?: 0L

    /**
     * TURN credentials issued (experimental), by server: which box phones chose, as the spool saw
     * it. Refusals land in the `rate`/`not_subscribed` errs.
     */
    val turnIssuedTotal = ServerCounter()

    /** Of [turnIssuedTotal], those issued under an entitlement ticket: the paying callers. */
    val turnIssuedTicketedTotal = ServerCounter()

    /** `ent` records accepted (experimental). */
    val ticketAcceptedTotal = LongAdder()

    /** Attachment bytes charged to ticket holders' daily upload budgets, at their charged size (ADR 2026-10.h6pt). */
    val ticketAttachBytesChargedTotal = LongAdder()

    /**
     * Of [ticketAttachBytesChargedTotal], those charged under a ticket that carries `am` (ADR
     * 2026-10.h4ps): what the size-capped holders store, beside everyone else. A second counter
     * rather than a label, so a query on the first keeps meaning the whole.
     */
    val ticketAttachBytesChargedAmTotal = LongAdder()

    /**
     * Ticket refusals by [Tickets.Reason] — on `ent`, and on a `turnreq` or `aput` that a gate
     * refused (`missing`, `expired`, `flags`, `size`, `budget`). A closed
     * set, every member rendered from zero, so a field test can tell an expired ticket from a
     * clock-skewed or mis-addressed one without a series appearing mid-test.
     */
    private val ticketRefusedTotal = Tickets.Reason.entries.associateWith { LongAdder() }

    fun ticketRefused(reason: Tickets.Reason) {
        ticketRefusedTotal.getValue(reason).increment()
    }

    /** Cast records received (experimental, SPOOL_PROTOCOL.md §7.7), fanned out or not. */
    val castsTotal = LongAdder()

    /** Copies of casts handed to subscribers: one cast in leaves as up to (subscribers - 1) of these. */
    val castsFannedTotal = LongAdder()

    /**
     * Casts dropped, by [CastDrop]. A cast is never answered, so this is the only place a refusal
     * shows. Every reason is per cast except [CastDrop.FULL], which counts one subscriber's lost copy.
     */
    private val castDroppedTotal = CastDrop.entries.associateWith { LongAdder() }

    fun castDropped(reason: CastDrop) {
        castDroppedTotal.getValue(reason).increment()
    }

    fun castDroppedCount(reason: CastDrop): Long = castDroppedTotal.getValue(reason).sum()

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
        commonsSubscribers: Map<Int, Int>? = null,
        storeFileBytes: Long? = null,
        turnServers: Int = 0,
        tickets: Boolean = false,
        casts: Boolean = false,
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
            line("knit_spool_reclaims_total", "counter", reclaimsTotal.sum())
            line("knit_spool_new_scopes_quota_refused_total", "counter", newScopesQuotaRefusedTotal.sum())
            line("knit_spool_attach_chunks_stored_total", "counter", attachChunksStoredTotal.sum())
            line("knit_spool_egress_bytes_total", "counter", egressBytesTotal.sum())
            line("knit_spool_scopes_current", "gauge", scopesCurrent.toLong())
            line("knit_spool_live_bytes", "gauge", liveBytes)
            // Absent on an in-memory spool: a 0 there would read as a file that costs nothing.
            if (storeFileBytes != null) line("knit_spool_store_file_bytes", "gauge", storeFileBytes)
            // Absent entirely on a spool with no commons, rather than flat zeroes that read as a
            // room nobody is using. One series per configured room, zeroes included, so a room's
            // series never appears and disappears with its traffic.
            if (!commonsSubscribers.isNullOrEmpty()) {
                append("# TYPE knit_spool_commons_subscribers gauge\n")
                commonsSubscribers.forEach { (room, count) -> roomLine("knit_spool_commons_subscribers", room, count.toLong()) }
                append("# TYPE knit_spool_commons_pushes_total counter\n")
                commonsSubscribers.keys.forEach { roomLine("knit_spool_commons_pushes_total", it, commonsPushCount(it)) }
                append("# TYPE knit_spool_commons_rate_limited_total counter\n")
                commonsSubscribers.keys.forEach { room ->
                    CommonsBudget.entries.forEach { budget ->
                        roomLine("knit_spool_commons_rate_limited_total", room, commonsRateLimitedCount(room, budget), budget)
                    }
                }
            }
            // Absent on a spool that issues no credentials, for the commons' reason, and one series
            // per configured server, zeroes included, so a server's series never comes and goes.
            if (turnServers > 0) serverLines("knit_spool_turn_issued_total", turnIssuedTotal, turnServers)
            // Absent on a spool with no ticket key, for the commons' reason.
            if (tickets) {
                line("knit_spool_ticket_accepted_total", "counter", ticketAcceptedTotal.sum())
                if (turnServers > 0) serverLines("knit_spool_turn_issued_ticketed_total", turnIssuedTicketedTotal, turnServers)
                line("knit_spool_ticket_attach_bytes_charged_total", "counter", ticketAttachBytesChargedTotal.sum())
                line("knit_spool_ticket_attach_bytes_charged_am_total", "counter", ticketAttachBytesChargedAmTotal.sum())
                append("# TYPE knit_spool_ticket_refused_total counter\n")
                ticketRefusedTotal.forEach { (reason, adder) ->
                    append("knit_spool_ticket_refused_total{reason=\"")
                        .append(reason.word)
                        .append("\"} ")
                        .append(adder.sum())
                        .append('\n')
                }
            }
            // Absent while casts are off, for the commons' reason.
            if (casts) {
                line("knit_spool_casts_total", "counter", castsTotal.sum())
                line("knit_spool_casts_fanned_total", "counter", castsFannedTotal.sum())
                append("# TYPE knit_spool_casts_dropped_total counter\n")
                castDroppedTotal.forEach { (reason, adder) ->
                    append("knit_spool_casts_dropped_total{reason=\"")
                        .append(reason.word)
                        .append("\"} ")
                        .append(adder.sum())
                        .append('\n')
                }
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

    /** One commons series sample, `name{room="n"[,budget="…"]} value`; the `# TYPE` line is the caller's. */
    private fun StringBuilder.roomLine(
        name: String,
        room: Int,
        value: Long,
        budget: CommonsBudget? = null,
    ) {
        append(name).append("{room=\"").append(room)
        if (budget != null) append("\",budget=\"").append(budget.word)
        append("\"} ").append(value).append('\n')
    }

    /** A counter's `# TYPE` line, then `name{server="n"} value` for servers 1 to [servers], zeroes included. */
    private fun StringBuilder.serverLines(
        name: String,
        counter: ServerCounter,
        servers: Int,
    ) {
        append("# TYPE ").append(name).append(" counter\n")
        for (server in 1..servers) {
            append(name)
                .append("{server=\"")
                .append(server)
                .append("\"} ")
                .append(counter.count(server))
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

/**
 * A counter split by TURN server, keyed by the server's number (1 for `SPOOL_TURN_*`, n for
 * `SPOOL_TURN_<n>_*`) and never by its host, as the commons counters key a room by its number.
 */
class ServerCounter {
    private val counts = ConcurrentHashMap<Int, LongAdder>()

    fun increment(server: Int) = counts.computeIfAbsent(server) { LongAdder() }.increment()

    fun count(server: Int): Long = counts[server]?.sum() ?: 0L
}

/**
 * Which commons budget refused a push (spec S-7.4-4): the room's own, or the spool-wide one every
 * room shares. A metrics label and nothing else: a refused member sees the same `err rate` either way.
 */
enum class CommonsBudget(
    val word: String,
) {
    ROOM("room"),
    SPOOL("spool"),
}

/**
 * Why a cast was dropped (experimental, SPOOL_PROTOCOL.md §7.7). The word is a metrics label and
 * nothing else: a cast is never answered, so no client ever sees one.
 */
enum class CastDrop(
    val word: String,
) {
    /** Undecodable, or a scope id that is not 32 bytes. */
    MALFORMED("malformed"),

    /** Casts are off, or were off when this connection was sent its hello. */
    OFF("off"),
    NOT_SUBSCRIBED("not_subscribed"),
    COMMONS("commons"),
    TOO_LARGE("too_large"),

    /** On a ticketed spool, no subscriber of the scope holds a live cast ticket. */
    TICKET("ticket"),
    RATE("rate"),

    /** One subscriber's outbound channel was full; that copy was lost. */
    FULL("full"),
}
