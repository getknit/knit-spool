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
 * diagnostic lines. [summary] prints the MUST tally to stderr and yields the process exit code.
 */
class Report(
    private val total: Int,
) {
    private var mustRun = 0
    private var mustPassed = 0
    private var skipped = 0
    private var advisories = 0
    private var failures = 0

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

    /** Prints the stderr summary line and returns the process exit code (1 iff a MUST check failed). */
    fun summary(): Int {
        System.err.println("MUST: $mustPassed/$mustRun passed, skipped $skipped, advisory-noted $advisories")
        return if (failures > 0) 1 else 0
    }
}
