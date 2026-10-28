// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exposition-format details the server tests read past. */
class MetricsTest {
    @Test
    fun buildInfoIsALabelledGaugeOfOne() {
        val rendered = Metrics(version = "1.2.3", commit = "cd08e5f").render(scopesCurrent = 0, liveBytes = 0L)
        assertTrue(rendered.contains("# TYPE knit_spool_build_info gauge\n"), rendered)
        assertTrue(rendered.contains("""knit_spool_build_info{version="1.2.3",commit="cd08e5f"} 1"""), rendered)
    }

    /**
     * Absent on a spool that issues no TURN credentials, rather than a flat zero, and otherwise one
     * series per configured server from zero, labelled by its number and never its host.
     */
    @Test
    fun turnIssuedIsEmittedOnlyWhenTurnIsOnOnePerServer() {
        val metrics = Metrics()
        metrics.turnIssuedTotal.increment(2)
        val rendered = metrics.render(scopesCurrent = 0, liveBytes = 0L, turnServers = 3)
        assertTrue(rendered.contains("# TYPE knit_spool_turn_issued_total counter\n"), rendered)
        assertTrue(rendered.contains("knit_spool_turn_issued_total{server=\"1\"} 0\n"), rendered)
        assertTrue(rendered.contains("knit_spool_turn_issued_total{server=\"2\"} 1\n"), rendered)
        assertTrue(rendered.contains("knit_spool_turn_issued_total{server=\"3\"} 0\n"), rendered)
        assertTrue(!rendered.contains("server=\"4\""), rendered)
        assertTrue(!rendered.contains("ticketed"), "the ticketed twin needs a ticket key: $rendered")
        assertTrue(!metrics.render(scopesCurrent = 0, liveBytes = 0L).contains("turn"))
    }

    @Test
    fun ticketSeriesAreEmittedOnlyWithAKeyAndEveryReasonFromZero() {
        val metrics = Metrics(version = "1.2.3", commit = "cd08e5f")
        assertTrue(!metrics.render(scopesCurrent = 0, liveBytes = 0L).contains("ticket"))
        metrics.ticketRefused(app.getknit.spool.protocol.Tickets.Reason.EXPIRED)
        val rendered = metrics.render(scopesCurrent = 0, liveBytes = 0L, tickets = true)
        assertTrue(rendered.contains("knit_spool_ticket_refused_total{reason=\"expired\"} 1\n"), rendered)
        app.getknit.spool.protocol.Tickets.Reason.entries.forEach { reason ->
            assertTrue(rendered.contains("knit_spool_ticket_refused_total{reason=\"${reason.word}\"}"), reason.word)
        }
        assertTrue(rendered.contains("knit_spool_ticket_accepted_total 0\n"), rendered)
        // A key without TURN has no ticketed credentials to count; with it, one series per server.
        assertTrue(!rendered.contains("knit_spool_turn_issued_ticketed_total"), rendered)
        metrics.turnIssuedTotal.increment(1)
        metrics.turnIssuedTicketedTotal.increment(1)
        val withTurn = metrics.render(scopesCurrent = 0, liveBytes = 0L, turnServers = 2, tickets = true)
        assertTrue(withTurn.contains("knit_spool_turn_issued_ticketed_total{server=\"1\"} 1\n"), withTurn)
        assertTrue(withTurn.contains("knit_spool_turn_issued_ticketed_total{server=\"2\"} 0\n"), withTurn)
        assertTrue(withTurn.contains("knit_spool_turn_issued_total{server=\"1\"} 1\n"), withTurn)
    }

    /** Absent while sigs are off; on, every drop reason from zero, and none of them `ticket`. */
    @Test
    fun sigSeriesAreEmittedOnlyWhileOnAndEveryReasonFromZero() {
        val metrics = Metrics()
        assertTrue(!metrics.render(scopesCurrent = 0, liveBytes = 0L).contains("sigs"))
        metrics.sigsTotal.increment()
        metrics.sigsDropped.increment(SigDrop.RATE)
        val rendered = metrics.render(scopesCurrent = 0, liveBytes = 0L, sigs = true)
        assertTrue(rendered.contains("# TYPE knit_spool_sigs_total counter\nknit_spool_sigs_total 1\n"), rendered)
        assertTrue(rendered.contains("knit_spool_sigs_fanned_total 0\n"), rendered)
        assertTrue(rendered.contains("knit_spool_sigs_dropped_total{reason=\"rate\"} 1\n"), rendered)
        SigDrop.entries.forEach { assertTrue(rendered.contains("knit_spool_sigs_dropped_total{reason=\"${it.word}\"}"), it.word) }
        assertTrue(!rendered.contains("reason=\"ticket\""), rendered)
        assertTrue(!rendered.contains("knit_spool_casts_total"), "the cast series follow casts, not sigs: $rendered")
    }

    /**
     * A stray quote in a label value corrupts not just its own line but the scrape's parse from that
     * point on, and the stamp is whatever `-PspoolVersion` was handed.
     */
    @Test
    fun labelValuesEscapeWhatPrometheusRequires() {
        val rendered = Metrics(version = """a"b\c""", commit = "d\ne").render(scopesCurrent = 0, liveBytes = 0L)
        assertTrue(rendered.contains("""version="a\"b\\c""""), rendered)
        assertTrue(rendered.contains("""commit="d\ne""""), rendered)
        assertTrue(rendered.lineSequence().count { it.startsWith("knit_spool_build_info") } == 1, rendered)
    }

    /**
     * A new scope refused `quota` at `SPOOL_MAX_SCOPES` is counted on its own (ADR 2026-10.x9gq):
     * it is the sizing failure a shared spool has to see before its members report it. Always
     * emitted, from zero, so a dashboard can alert on the first one.
     */
    @Test
    fun aNewScopeRefusedQuotaIsCounted() {
        withServer {
            assertTrue(http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("knit_spool_new_scopes_quota_refused_total 0\n"))
            connect {
                helloHandshake()
                // hardLimits.maxScopes = 4, all held by this connection, so none can be reclaimed.
                (1..4).forEach { subscribe(testScope(it)) }
                sendRecord(Sub(t = RecordType.SUB, q = 9L, subs = listOf(ScopeSub(scope = testScope(5), bounds = testBounds()))))
                assertEquals(ErrCode.QUOTA, expectRecord<Err>(RecordType.ERR).code)
            }
            assertEquals(1L, spool.metrics.newScopesQuotaRefusedTotal.sum())
            assertTrue(http.get("http://127.0.0.1:$port/metrics").bodyAsText().contains("knit_spool_new_scopes_quota_refused_total 1\n"))
        }
    }
}
