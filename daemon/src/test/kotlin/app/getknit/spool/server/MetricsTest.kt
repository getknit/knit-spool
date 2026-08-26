// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import kotlin.test.Test
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
}
