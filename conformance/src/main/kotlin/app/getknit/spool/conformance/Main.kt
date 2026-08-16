// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val USAGE =
    "usage: knit-spool-conformance <ws(s)://host[:port]/spool/v1> " +
        "[--token T] [--timeout-ms 10000] [--pow-limit 24] [--destructive]"

/**
 * The conformance CLI: validates ANY spool implementation over a live WebSocket against
 * SPOOL_PROTOCOL.md §6–§8, speaking only the `:protocol` wire contract. TAP on stdout, a MUST
 * tally on stderr. Exit codes: 0 = every MUST check passed (skips and advisory shortfalls do not
 * fail the run), 1 = at least one MUST check failed, 2 = bad arguments or no handshake at all.
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    val httpClient =
        HttpClient(CIO) {
            install(WebSockets)
        }
    val exitCode =
        try {
            runBlocking { runSuite(httpClient, options) }
        } finally {
            httpClient.close()
        }
    exitProcess(exitCode)
}

private class Options(
    val url: String,
    val connectUrl: String,
    val hasToken: Boolean,
    val timeoutMs: Long,
    val powLimit: Int,
    val destructive: Boolean,
)

private fun parseArgs(args: Array<String>): Options {
    var url: String? = null
    var token: String? = null
    var timeoutMs = 10_000L
    var powLimit = 24
    var destructive = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--token" -> {
                token = args.getOrNull(++i) ?: usageExit()
            }

            "--timeout-ms" -> {
                timeoutMs = args.getOrNull(++i)?.toLongOrNull()?.takeIf { it > 0 } ?: usageExit()
            }

            "--pow-limit" -> {
                powLimit = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it >= 0 } ?: usageExit()
            }

            "--destructive" -> {
                destructive = true
            }

            else -> {
                if (url != null || arg.startsWith("--")) usageExit()
                url = arg
            }
        }
        i++
    }
    val parsed = url ?: usageExit()
    if (!parsed.startsWith("ws://") && !parsed.startsWith("wss://")) usageExit()
    val connectUrl =
        when {
            token == null -> parsed
            '?' in parsed -> "$parsed&k=$token"
            else -> "$parsed?k=$token"
        }
    return Options(
        url = parsed,
        connectUrl = connectUrl,
        hasToken = token != null,
        timeoutMs = timeoutMs,
        powLimit = powLimit,
        destructive = destructive,
    )
}

private fun usageExit(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

private suspend fun runSuite(
    httpClient: HttpClient,
    options: Options,
): Int {
    val client = SpoolClient(httpClient = httpClient, url = options.connectUrl, timeoutMs = options.timeoutMs)
    val bareClient = SpoolClient(httpClient = httpClient, url = options.url, timeoutMs = options.timeoutMs)
    val serverHello =
        try {
            client.connect { hello() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("probe connection failed: ${e.message ?: e::class.simpleName}")
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
        )
    val checks = allChecks()
    val report = Report(checks.size)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = e.message ?: (e::class.simpleName ?: "unknown failure")
            if (check.must) {
                report.fail(number, check.name, reason)
            } else {
                report.advisory(number, check.name, false, reason)
            }
        }
    }
    return report.summary()
}
