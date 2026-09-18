// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import java.io.PrintStream

/**
 * Runs every check in [allChecks] against the spool at [SuiteOptions.url] and returns the exit
 * code (see [main] for the code table). TAP goes to [out], the MUST tally and any probe failure to
 * [err]; both default to the process streams and are parameters so a test can capture the run
 * without swapping `System.out` under a daemon that is logging to it at the same time.
 */
suspend fun runSuite(
    httpClient: HttpClient,
    options: SuiteOptions,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
): Int {
    val client = SpoolClient(httpClient = httpClient, url = options.connectUrl, timeoutMs = options.timeoutMs)
    val bareClient = SpoolClient(httpClient = httpClient, url = options.url, timeoutMs = options.timeoutMs)
    val serverHello =
        try {
            client.connect { hello() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err.println("probe connection failed: ${e.message ?: e::class.simpleName}")
            return 2
        }
    val ctx =
        Ctx(
            client = client,
            bareClient = bareClient,
            serverHello = serverHello,
            timeoutMs = options.timeoutMs,
            powLimit = options.powLimit,
            hasToken = options.hasToken,
            commonsSecret = options.commonsSecret,
        )
    val checks = allChecks()
    val report = Report(checks.size, out, err)
    report.begin()
    checks.forEachIndexed { index, check ->
        val number = index + 1
        if (check.destructive && !options.destructive) {
            report.skip(number, check.name, "destructive (pass --destructive)")
            return@forEachIndexed
        }
        try {
            check.run(ctx)
            report.pass(number, check.name, check.must)
        } catch (e: SkipCheck) {
            report.skip(number, check.name, e.reason)
        } catch (e: Advisory) {
            report.advisory(number, check.name, check.must, e.reason)
        } catch (e: CheckFailure) {
            // The only category that judges the spool: a written expected-vs-got assertion.
            if (check.must) {
                report.fail(number, check.name, describeFailure(e))
            } else {
                report.advisory(number, check.name, false, describeFailure(e))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // TransportFailure, plus anything unclassified — a TLS fault, a bug in this tool. None
            // of it says whether the spool conforms, so it is reported apart from the tally rather
            // than charged to the implementation under test.
            report.error(number, check.name, describeFailure(e))
        }
    }
    return report.summary()
}

/**
 * A one-line failure reason that stays diagnosable when the throwable is not one of ours.
 *
 * [CheckFailure] is raised with a written expected-vs-got message, so its message alone is the
 * whole story and is returned unadorned — that is the spool failing the spec, which is what the
 * report is for.
 *
 * Everything else is a bug in this tool, in a library, or in the transport, and those arrive as
 * bare types with useless messages: a run against a remote spool reported `NullPointerException`
 * and `IllegalArgumentException: Failed requirement.`, neither of which says where it came from or
 * even whether the spool was at fault. For those, name the type, keep any message, and append the
 * first frame of our own code plus the cause chain, so the next run is diagnosable from its output
 * instead of needing a debugger attached to a remote endpoint.
 */
fun describeFailure(e: Throwable): String {
    if (e is CheckFailure) return e.message ?: "check failed"
    val type = e::class.simpleName ?: "unknown failure"
    val message = e.message?.let { ": $it" } ?: " (no message)"
    val origin =
        e.stackTrace.firstOrNull { it.className.startsWith("app.getknit.spool") }
            ?: e.stackTrace.firstOrNull()
    val where = origin?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
    val causedBy =
        generateSequence(e.cause) { it.cause }
            .take(CAUSE_CHAIN_DEPTH)
            .joinToString("") { c -> " <- ${c::class.simpleName}${c.message?.let { m -> ": $m" }.orEmpty()}" }
    return "$type$message$where$causedBy"
}

/** How far down a `cause` chain [describeFailure] walks before the line stops being readable. */
private const val CAUSE_CHAIN_DEPTH = 3
