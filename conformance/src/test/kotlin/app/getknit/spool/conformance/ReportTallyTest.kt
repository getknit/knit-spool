// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tally is the artefact an operator reads, so what it counts is a contract. The distinction
 * these pin down is that an errored check reached no verdict about the spool and therefore must
 * not move the conformance ratio in either direction.
 */
class ReportTallyTest {
    @Test
    fun `errored check is absent from the ratio rather than counted against the spool`() {
        val run =
            capture { report ->
                report.pass(1, "a", must = true)
                report.pass(2, "b", must = true)
                report.error(3, "c", "IllegalArgumentException: Failed requirement. at Session.receive:12")
            }
        // Two checks judged, both passed. The third is reported, not charged to the spool.
        assertContains(run.summary, "MUST: 2/2 passed")
        assertContains(run.summary, "errored 1")
        assertEquals(3, run.exitCode, "an inconclusive run is not a clean one")
    }

    @Test
    fun `a spec violation still fails the run and counts in the ratio`() {
        val run =
            capture { report ->
                report.pass(1, "a", must = true)
                report.fail(2, "b", "expected 'digest' record, got err code=rate")
            }
        assertContains(run.summary, "MUST: 1/2 passed")
        assertFalse(run.summary.contains("errored"), "no errors to report: ${run.summary}")
        assertEquals(1, run.exitCode)
    }

    @Test
    fun `a failure outranks an error in the exit code`() {
        val run =
            capture { report ->
                report.error(1, "a", "transport died")
                report.fail(2, "b", "expected ok, got err")
            }
        assertEquals(1, run.exitCode, "a real spec violation is the more important signal")
    }

    @Test
    fun `a clean run still exits zero and says nothing about errors`() {
        val run =
            capture { report ->
                report.pass(1, "a", must = true)
                report.skip(2, "b", "destructive (pass --destructive)")
                report.advisory(3, "c", must = true, reason = "retryMs absent")
            }
        assertEquals(0, run.exitCode)
        assertContains(run.summary, "MUST: 2/2 passed, skipped 1, advisory-noted 1")
        assertFalse(run.summary.contains("errored"), "clean runs stay quiet: ${run.summary}")
    }

    @Test
    fun `an errored check prints not ok with its reason, since TAP has no third state`() {
        val run = capture { report -> report.error(7, "q-correlation", "NullPointerException (no message)") }
        assertContains(run.tap, "not ok 7 - q-correlation")
        assertTrue("error" in run.tap, "the line must be distinguishable from a spec failure: ${run.tap}")
        assertContains(run.tap, "  # NullPointerException (no message)")
    }

    private class Captured(
        val tap: String,
        val summary: String,
        val exitCode: Int,
    )

    private fun capture(block: (Report) -> Unit): Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val stdout = System.out
        val stderr = System.err
        val exitCode: Int
        try {
            System.setOut(PrintStream(out, true))
            System.setErr(PrintStream(err, true))
            val report = Report(total = 3)
            report.begin()
            block(report)
            exitCode = report.summary()
        } finally {
            System.setOut(stdout)
            System.setErr(stderr)
        }
        return Captured(tap = out.toString(), summary = err.toString(), exitCode = exitCode)
    }
}
