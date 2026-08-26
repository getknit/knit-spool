// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The periodic operator status line: deltas, gauges, formatting, and the wired-up server tick. */
class StatusLineTest {
    private fun statusLine(
        metrics: Metrics,
        maxBytes: Long = 268_435_456L,
        heapUsed: Long = 100L * 1024 * 1024,
    ) = StatusLine(
        metrics = metrics,
        maxScopes = 64,
        maxBytes = maxBytes,
        startedAtMs = 0L,
        heap = { HeapUse(used = heapUsed, max = 256L * 1024 * 1024) },
    )

    private fun fields(line: String): Map<String, String> =
        line.split(' ').associate { field ->
            field.substringBefore('=') to field.substringAfter('=')
        }

    /**
     * The reason counters are printed as deltas: a line that repeated the since-boot totals would
     * be identical every five minutes on an idle spool and unreadable on a busy one. The second
     * line must describe only the second interval.
     */
    @Test
    fun countersArePerIntervalDeltasNotRunningTotals() {
        val metrics = Metrics()
        val status = statusLine(metrics)
        repeat(10) { metrics.recordsTotal.increment() }
        repeat(4) { metrics.pushesTotal.increment() }
        val first = fields(status.render(now = 60_000L, scopes = 1, liveBytes = 0L))
        assertEquals("+10", first["records"])
        assertEquals("+4", first["pushes"])

        repeat(3) { metrics.recordsTotal.increment() }
        val second = fields(status.render(now = 120_000L, scopes = 1, liveBytes = 0L))
        assertEquals("+3", second["records"], "the second line must cover only the second interval")
        assertEquals("+0", second["pushes"], "an idle counter reads +0, not its total")
    }

    @Test
    fun gaugesAreAbsoluteAndSizedAgainstTheirCaps() {
        val metrics = Metrics()
        metrics.connectionsCurrent.set(3)
        val line = fields(statusLine(metrics).render(now = 7_384_000L, scopes = 12, liveBytes = 4_404_019L))
        assertEquals("2h03m", line["up"])
        assertEquals("3", line["conns"])
        assertEquals("12/64", line["scopes"])
        assertEquals("4.2MiB/256.0MiB", line["live"])
        assertEquals("100.0MiB/256.0MiB", line["heap"])
    }

    /** `maxBytes = 0` is unlimited; printing "4.2MiB/0B" would read as a spool pinned at its cap. */
    @Test
    fun unlimitedWatermarkPrintsNoDenominator() {
        val line = fields(statusLine(Metrics(), maxBytes = 0L).render(now = 0L, scopes = 0, liveBytes = 4_404_019L))
        assertEquals("4.2MiB", line["live"])
    }

    @Test
    fun errorsCarryABoundedPerCodeBreakdown() {
        val metrics = Metrics()
        val status = statusLine(metrics)
        repeat(5) { metrics.err(ErrCode.RATE) }
        repeat(2) { metrics.err(ErrCode.QUOTA) }
        metrics.err(ErrCode.TOO_LARGE)
        val line = status.render(now = 0L, scopes = 0, liveBytes = 0L)
        assertTrue(line.contains("errs=+8{rate=5,quota=2,too_large=1}"), line)
    }

    /** A spool under a code-cycling client must not grow the line without bound. */
    @Test
    fun errorBreakdownSummarizesTheTail() {
        val metrics = Metrics()
        val status = statusLine(metrics)
        listOf(ErrCode.RATE, ErrCode.QUOTA, ErrCode.POW, ErrCode.CONFLICT, ErrCode.BAD_ID)
            .forEach { metrics.err(it) }
        val line = status.render(now = 0L, scopes = 0, liveBytes = 0L)
        assertTrue(line.contains("errs=+5{"), line)
        assertTrue(line.contains(",+2more}"), line)
        assertEquals(
            StatusLine.ERR_CODES_SHOWN,
            line
                .substringAfter('{')
                .substringBefore('}')
                .split(',')
                .count { !it.startsWith("+") },
            line,
        )
    }

    /** A quiet interval prints no breakdown at all rather than a stale one. */
    @Test
    fun quietIntervalDropsTheBreakdown() {
        val metrics = Metrics()
        val status = statusLine(metrics)
        metrics.err(ErrCode.RATE)
        assertTrue(status.render(now = 0L, scopes = 0, liveBytes = 0L).contains("errs=+1{rate=1}"))
        val second = status.render(now = 60_000L, scopes = 0, liveBytes = 0L)
        assertTrue(second.endsWith("errs=+0"), second)
    }

    @Test
    fun uptimeKeepsTwoUnits() {
        assertEquals("0s", StatusLine.uptime(0L))
        assertEquals("32s", StatusLine.uptime(32_000L))
        assertEquals("14m32s", StatusLine.uptime(872_000L))
        assertEquals("2h14m", StatusLine.uptime(8_073_000L))
        assertEquals("3d04h", StatusLine.uptime(273_600_000L))
    }

    @Test
    fun bytesUseBinaryUnits() {
        assertEquals("0B", StatusLine.bytes(0L))
        assertEquals("912B", StatusLine.bytes(912L))
        assertEquals("1.0KiB", StatusLine.bytes(1_024L))
        assertEquals("4.2MiB", StatusLine.bytes(4_404_019L))
        assertEquals("1.5GiB", StatusLine.bytes(1_610_612_736L))
    }

    /** End to end: the server's tick reads the live store and logs one line under its own logger. */
    @Test
    fun serverTickLogsOneLineWithLiveStoreState() {
        val logged =
            withLogCapture("app.getknit.spool.Status") {
                withServer {
                    connect {
                        helloHandshake()
                        subscribe(testScope(1))
                        val (id, data) = testBlob(1)
                        sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                        expectRecord<Ok>(RecordType.OK)
                    }
                    spool.statusTick()
                }
            }
        assertEquals(1, logged.size, "one tick, one line")
        val line = logged.single().formattedMessage
        assertFalse(line.contains('\n'), "the status line must stay a single line: $line")
        val fields = fields(line)
        assertEquals("1/4", fields["scopes"], line)
        assertEquals("40B", fields["live"], line)
        assertEquals("+1", fields["pushes"], line)
        assertEquals("+1", fields["accepted"], line)
    }
}
