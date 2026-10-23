// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.Tickets
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE =
    "usage: knit-spool-conformance <ws(s)://host[:port]/spool/v1> " +
        "[--token T | --token-file PATH] [--timeout-ms 10000] [--pow-limit 24] [--destructive] " +
        "[--commons-invite knit-commons:v1:...] [--turn-secret-file PATH] " +
        "[--ticket-key-file PATH --ticket-audience HOST [--ticket-audience-alias HOST]]"

/**
 * The conformance CLI: validates ANY spool implementation over a live WebSocket against
 * SPOOL_PROTOCOL.md §6–§8, speaking only the `:protocol` wire contract. TAP on stdout, a MUST
 * tally on stderr. Exit codes: 0 = every MUST check reached a verdict and passed (skips and
 * advisory shortfalls do not fail the run), 1 = at least one MUST check failed, 2 = bad arguments
 * or no handshake at all, 3 = no MUST check failed but one or more could not be judged because the
 * transport broke or this tool hit a bug — an inconclusive run, which is not a passing one.
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

// One `when` arm per flag: a switch table, and splitting it would hide that shape.
@Suppress("CyclomaticComplexMethod")
private fun parseArgs(args: Array<String>): SuiteOptions {
    var url: String? = null
    var token: String? = null
    var tokenFile: String? = null
    var timeoutMs = 10_000L
    var powLimit = 24
    var destructive = false
    var commonsInvite: String? = null
    var turnSecretFile: String? = null
    var ticketKeyFile: String? = null
    var ticketAudience: String? = null
    var ticketAudienceAlias: String? = null
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--token" -> {
                token = args.getOrNull(++i) ?: usageExit()
            }

            "--token-file" -> {
                tokenFile = args.getOrNull(++i) ?: usageExit()
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

            "--commons-invite" -> {
                commonsInvite = args.getOrNull(++i) ?: usageExit()
            }

            // A file only, never a flag value: this is an HMAC key that mints relay credentials,
            // and argv is readable by every local user for the life of the run (see readTokenFile).
            "--turn-secret-file" -> {
                turnSecretFile = args.getOrNull(++i) ?: usageExit()
            }

            // A file only, for the same reason: this key mints tickets the spool will honour.
            "--ticket-key-file" -> {
                ticketKeyFile = args.getOrNull(++i) ?: usageExit()
            }

            "--ticket-audience" -> {
                ticketAudience = args.getOrNull(++i) ?: usageExit()
            }

            "--ticket-audience-alias" -> {
                ticketAudienceAlias = args.getOrNull(++i) ?: usageExit()
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
    // Refuse both rather than letting one silently win: which token was actually sent is exactly
    // the thing an operator must not have to guess when a run fails to authenticate.
    if (token != null && tokenFile != null) argExit("--token and --token-file are mutually exclusive")
    val bearer = token ?: tokenFile?.let(::readTokenFile)
    val connectUrl =
        when {
            bearer == null -> parsed
            '?' in parsed -> "$parsed&k=$bearer"
            else -> "$parsed?k=$bearer"
        }
    return SuiteOptions(
        url = parsed,
        connectUrl = connectUrl,
        hasToken = bearer != null,
        timeoutMs = timeoutMs,
        powLimit = powLimit,
        destructive = destructive,
        // A malformed invite is an argument error, not a failed spool: without the secret the
        // commons checks would silently skip and the run would look clean.
        commonsSecret =
            commonsInvite?.let {
                Commons.decodeInvite(it) ?: run {
                    System.err.println("--commons-invite must be a knit-commons:v1: invite")
                    exitProcess(2)
                }
            },
        turnSecret = turnSecretFile?.let { readSecretFile("--turn-secret-file", it) },
        // Like a malformed invite, an unreadable key is an argument error: without it the ticket
        // checks would skip and the run would look clean.
        ticketSigningKey =
            ticketKeyFile?.let {
                Tickets.decodeSigningKey(readSecretFile("--ticket-key-file", it))
                    ?: argExit("--ticket-key-file $it does not hold a knit-ticket-key:v1: signing key")
            },
        ticketAudience = ticketAudience,
        ticketAudienceAlias = ticketAudienceAlias,
    ).also { options ->
        if ((options.ticketSigningKey == null) != (options.ticketAudience == null)) {
            argExit("--ticket-key-file and --ticket-audience go together")
        }
        // Like a key with no audience: an alias the run cannot use would skip its check and look clean.
        if (options.ticketAudienceAlias != null && options.ticketAudience == null) {
            argExit("--ticket-audience-alias needs --ticket-key-file and --ticket-audience")
        }
        if (options.ticketAudienceAlias != null && options.ticketAudienceAlias == options.ticketAudience) {
            argExit("--ticket-audience-alias must differ from --ticket-audience")
        }
    }
}

/**
 * Reads the bearer token from a file, so it never reaches argv. `--token` is visible to every
 * local user for the life of the run (`ps`, `/proc/<pid>/cmdline`) and lands in shell history;
 * a file can be mode 0600. Surrounding whitespace is stripped, so a plain `... > token` with its
 * trailing newline works.
 */
private fun readTokenFile(path: String): String = readSecretFile("--token-file", path)

/** [readTokenFile]'s reading, for any flag that names a file holding a secret. */
private fun readSecretFile(
    flag: String,
    path: String,
): String {
    val text =
        try {
            Files.readString(Path.of(path))
        } catch (e: Exception) {
            // The filesystem exceptions carry the path as their whole message, which would print
            // it twice; say what actually went wrong instead.
            val reason =
                when (e) {
                    is NoSuchFileException -> "no such file"
                    is AccessDeniedException -> "permission denied"
                    else -> e.message ?: e::class.simpleName ?: "unreadable"
                }
            argExit("cannot read $flag $path: $reason")
        }
    return text.trim().ifEmpty { argExit("$flag $path is empty") }
}

private fun usageExit(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

/** Exit 2 (bad arguments) with a specific reason rather than the bare usage line. */
private fun argExit(message: String): Nothing {
    System.err.println(message)
    System.err.println(USAGE)
    exitProcess(2)
}
