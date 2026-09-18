// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.conformance.SuiteOptions
import app.getknit.spool.conformance.runSuite
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.ScopeStore
import app.getknit.spool.store.SqliteScopeStore
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reference daemon passes its own conformance suite, run in-process.
 *
 * CI runs the same checks against the packaged binary (`conformance-selftest`), which proves the
 * shipped artefacts; this proves the server on every `./gradlew check`, and it is what the merged
 * coverage report counts. It also reaches what the CI job cannot: that job passes no token and no
 * invite, so `auth-required`, the `commons-*` checks and `moderation-advertisement` skip there and
 * run here — and the SQLite store, which every other server test leaves to the contract test, is
 * put under real wire traffic.
 *
 * Every run asserts the exact set of skipped checks and that nothing came back advisory. Without
 * that a green run proves little: `rate-limit` is advisory, so a bucket too generous to overflow
 * would exit 0 and cover nothing, and a limit set one byte wrong would turn a check into a skip.
 */
class ConformanceSelfTest {
    @TempDir
    lateinit var tempDir: Path

    /** Nothing is skipped: every optional feature is on and every limit is inside the probes' reach. */
    @Test
    fun everyCheckPassesWithTokenPowCommonsAndModeration() {
        val run =
            runSuiteAgainst(
                testConfig(
                    token = TOKEN,
                    powBits = POW_BITS,
                    requireModeration = true,
                    commons = testCommons(attach = true),
                    hardLimits = suiteLimits(),
                    ratePushes = RATE_PUSHES,
                ),
                token = TOKEN,
                commonsSecret = TEST_COMMONS_SECRET,
            )
        run.assertClean(expectedSkips = emptySet())
    }

    /** The CI job's shape — no token, no commons, no moderation — against the persistent store. */
    @Test
    fun theBareSuitePassesAgainstSqlite() {
        val limits = suiteLimits()
        val run =
            SqliteScopeStore.open(tempDir, limits).use { store ->
                runSuiteAgainst(
                    testConfig(powBits = POW_BITS, hardLimits = limits, ratePushes = RATE_PUSHES),
                    store = store,
                )
            }
        run.assertClean(expectedSkips = BARE_SKIPS)
    }

    /**
     * Without `--destructive` the quota and rate probes skip, and with PoW off and `maxRecord` too
     * small for the over-cap probes the checks that guard their own preconditions skip too — the
     * runner's other branch, and the reasons a run against someone else's spool reports.
     */
    @Test
    fun aNonDestructiveRunSkipsWhatItCannotProbe() {
        val run =
            runSuiteAgainst(
                testConfig(
                    powBits = 0,
                    maxRecord = 1_100,
                    hardLimits =
                        HardLimits(
                            maxBlob = 1_024,
                            maxFramesCap = 100,
                            maxTtlMs = 86_400_000L,
                            maxScopes = MAX_SCOPES,
                            maxAttachBytes = 1_500,
                            maxATotal = 8,
                        ),
                ),
                destructive = false,
            )
        run.assertClean(
            expectedSkips =
                BARE_SKIPS +
                    setOf(
                        "push-too-large",
                        "pow-gate",
                        "sub-over-max-scopes",
                        "attachment-get-truncated",
                        "rate-limit",
                        "quota-scopes",
                    ),
        )
    }

    private class Run(
        val exitCode: Int,
        val tap: String,
        val summary: String,
    ) {
        private val lines = tap.lineSequence().filter { it.startsWith("ok ") || it.startsWith("not ok ") }.toList()

        private fun names(predicate: (String) -> Boolean): Set<String> =
            lines
                .filter(predicate)
                .map { it.substringAfter(" - ").substringBefore(" #") }
                .toSet()

        fun assertClean(expectedSkips: Set<String>) {
            assertEquals(emptySet(), names { it.startsWith("not ok ") }, "failed or errored checks:\n$tap")
            assertEquals(emptySet(), names { "# advisory:" in it }, "advisory shortfalls:\n$tap")
            assertEquals(expectedSkips, names { "# SKIP" in it }, "skipped checks:\n$tap")
            assertEquals(0, exitCode, "exit code; summary: $summary")
            assertTrue(lines.size > expectedSkips.size, "the run must have executed something:\n$tap")
        }
    }

    private fun runSuiteAgainst(
        config: SpoolServer.Config,
        store: ScopeStore = InMemoryScopeStore(config.hardLimits),
        token: String? = null,
        commonsSecret: ByteArray? = null,
        destructive: Boolean = true,
    ): Run {
        val tap = ByteArrayOutputStream()
        val summary = ByteArrayOutputStream()
        var exitCode = -1
        // A real clock, not the harness default: the runner mines PoW stamps for today's UTC day and
        // the server checks them against its own clock, so a server frozen in 1970 refuses every one.
        withServer(config, clock = FakeClock(now = System.currentTimeMillis()), store = store) {
            val url = "ws://127.0.0.1:$port/spool/v1"
            val options =
                SuiteOptions(
                    url = url,
                    connectUrl = token?.let { "$url?k=$it" } ?: url,
                    hasToken = token != null,
                    timeoutMs = 10_000L,
                    powLimit = 24,
                    destructive = destructive,
                    commonsSecret = commonsSecret,
                )
            exitCode = runSuite(http, options, out = PrintStream(tap, true), err = PrintStream(summary, true))
        }
        return Run(exitCode, tap.toString(), summary.toString())
    }

    private companion object {
        const val TOKEN = "conformance"
        const val POW_BITS = 8

        /**
         * The suite creates a fresh scope per check — about 27 before `quota-scopes` fills the rest —
         * so this must be at least that, and at most ~98 at `maxRecord` 8192 or `sub-over-max-scopes`
         * cannot fit its over-cap `sub` into one record and skips itself. 64 is also the daemon's
         * default.
         */
        const val MAX_SCOPES = 64

        /**
         * A push budget the `rate-limit` probe can overflow: at 10/s the bucket bursts to 40 and the
         * 41st push draws `err rate`. At the harness default of 1000 the probe blasts its whole 1000,
         * waits two seconds, and reports an advisory — an exit 0 that proves nothing.
         */
        const val RATE_PUSHES = 10

        /**
         * Attachments on, and generously: `attachment-get-truncated` sizes its probe from the
         * advertised budget and skips under 33 chunks (≈1.6 MB), and a commons with `attach = true`
         * on a spool that advertises no attachment limits fails `commons-advertisement` outright.
         */
        const val MAX_ATTACH_BYTES = 16_777_216

        fun suiteLimits(): HardLimits =
            HardLimits(
                maxBlob = 1_024,
                maxFramesCap = 100,
                maxTtlMs = 86_400_000L,
                maxScopes = MAX_SCOPES,
                maxAttachBytes = MAX_ATTACH_BYTES,
            )

        /** What skips without a token, a commons or a moderation flag — the CI job's exact SKIP set. */
        val BARE_SKIPS =
            setOf(
                "auth-required",
                "commons-advertisement",
                "commons-bounds-pinned",
                "commons-fanout",
                "moderation-advertisement",
            )
    }
}
