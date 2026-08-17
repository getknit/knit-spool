// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

/** Thrown by a check that cannot run in this environment; rendered as a TAP `# SKIP` directive. */
class SkipCheck(
    val reason: String,
) : Exception(reason)

/** A hard check assertion; the message states expected vs got. */
class CheckFailure(
    message: String,
) : Exception(message)

/**
 * The run could not reach the spool well enough to judge it: the socket never opened, or it died
 * mid-record without the spool ever saying why. Distinct from [CheckFailure] because it is not a
 * finding about the implementation under test — a spool is not non-conformant because the network
 * between here and it broke.
 *
 * A close frame is the dividing line. If the spool closed the connection it *spoke*, and what it
 * said is its behaviour, so that stays a [CheckFailure]. Silence is the transport's fault.
 */
class TransportFailure(
    message: String,
) : Exception(message)

/**
 * A spec-MAY shortfall worth surfacing without failing the run — rendered as an `# advisory:` note
 * on an `ok` line, including when a MUST check's mandatory portion passed and only the advisory
 * portion fell short.
 */
class Advisory(
    val reason: String,
) : Exception(reason)

/** Throws [CheckFailure] with [message] when [condition] does not hold. */
inline fun ensure(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw CheckFailure(message())
}

/**
 * TAP version 13 output on stdout, one line per check. Skips render as `# SKIP <reason>`, advisory
 * shortfalls as `# advisory: <reason>` (never failing the run), failures as `not ok` with indented
 * diagnostic lines, and checks that reached no verdict as `not ok ... # error:`. [summary] prints
 * the MUST tally to stderr and yields the process exit code.
 *
 * A spec violation and a broken transport are different findings and are tallied apart: only the
 * former says anything about the spool. Folding them together let a flaky network read as a
 * non-conformant spool.
 */
class Report(
    private val total: Int,
) {
    private var mustRun = 0
    private var mustPassed = 0
    private var skipped = 0
    private var advisories = 0
    private var failures = 0
    private var errors = 0

    fun begin() {
        println("TAP version 13")
        println("1..$total")
    }

    fun pass(
        index: Int,
        name: String,
        must: Boolean,
    ) {
        if (must) {
            mustRun++
            mustPassed++
        }
        println("ok $index - $name")
    }

    fun skip(
        index: Int,
        name: String,
        reason: String,
    ) {
        skipped++
        println("ok $index - $name # SKIP $reason")
    }

    fun advisory(
        index: Int,
        name: String,
        must: Boolean,
        reason: String,
    ) {
        advisories++
        if (must) {
            mustRun++
            mustPassed++
        }
        println("ok $index - $name # advisory: $reason")
    }

    fun fail(
        index: Int,
        name: String,
        reason: String,
    ) {
        mustRun++
        failures++
        println("not ok $index - $name")
        reason.lineSequence().forEach { line -> println("  # $line") }
    }

    /**
     * A check that could not reach a verdict: the transport broke, or this tool hit a bug. The
     * spool is not implicated, so this counts toward neither [mustRun] nor [mustPassed] — an
     * errored check is absent from the conformance ratio rather than counted against the spool.
     * It still prints `not ok`, because TAP has no third state and claiming a pass would be a lie.
     */
    fun error(
        index: Int,
        name: String,
        reason: String,
    ) {
        errors++
        println("not ok $index - $name # error: not a spec verdict")
        reason.lineSequence().forEach { line -> println("  # $line") }
    }

    /**
     * Prints the stderr summary line and returns the process exit code: 1 if a MUST check failed,
     * 3 if none failed but some could not be judged, 0 only when every MUST check reached a
     * verdict and passed. A run that errored is inconclusive, not clean — it must not exit 0.
     */
    fun summary(): Int {
        val errored = if (errors > 0) ", errored $errors (no verdict — transport or tool, not the spool)" else ""
        System.err.println(
            "MUST: $mustPassed/$mustRun passed, skipped $skipped, advisory-noted $advisories$errored",
        )
        return when {
            failures > 0 -> 1
            errors > 0 -> 3
            else -> 0
        }
    }
}
