// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `deploy/docker-compose.tls.yml` is the `.env`-driven deployment, and compose does **not** pass
 * `deploy/.env` into the container — it interpolates that file into the compose file and nothing
 * more. A variable the daemon reads therefore reaches it only if the compose file also names it
 * under `environment:`.
 *
 * Nothing about a missing line fails. The operator sets it, the daemon runs its own default, and
 * the two never meet: no error, no warning, the setting simply never happened. That is how
 * `SPOOL_SOURCE_URL` shipped unreachable — documented in `.env.example` for a whole release while
 * a fork that set it still served upstream's repository at `GET /source`, which is the wrong
 * answer to an AGPL §13 offer — and how everything added in 0.2.0 arrived the same way.
 *
 * So the compose file is pinned against [KNOWN_VARS], which [ConfigTest] independently pins against
 * what the parser actually reads. Adding a variable to the daemon now fails here until it is wired
 * through to an operator, and the failure names it.
 */
class DeployConfigTest {
    private val secret = ByteArray(Commons.SECRET_BYTES) { (it + 1).toByte() }
    private val commonsId = Commons.scopeId(secret).joinToString("") { "%02x".format(it) }

    /**
     * Deliberately not reachable from `.env`, because compose itself is built from them: it
     * publishes the port and mounts the volume, so letting `.env` move either would break the
     * proxy or the store rather than configure it. The image pins `SPOOL_DATA_DIR=/data`.
     */
    private val exempt = setOf("SPOOL_PORT", "SPOOL_DATA_DIR")

    private val repoRoot: Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: fail("no settings.gradle.kts at or above ${Path.of("").toAbsolutePath()}")

    private val tls = repoRoot.resolve("deploy/docker-compose.tls.yml")

    /** An `${X:-}` default, which resolves to the empty string rather than to an absent variable. */
    private val emptyDefault = Regex("""\$\{[A-Z_]+:-}""")

    private val entry = Regex("""^ {6}(SPOOL_[A-Z_]+):(.*)$""")

    /**
     * The `SPOOL_*` keys under the `spool` service's `environment:`, mapped to their raw value.
     *
     * Line-based rather than parsed: the alternative is a YAML library on the test classpath to
     * read one stanza of one file, and a file whose shape had drifted far enough to defeat this
     * would fail loudly here instead of silently passing. Scoped to the service because `caddy`
     * carries a `SPOOL_DOMAIN` that no daemon ever reads.
     */
    private fun declared(): Map<String, String> {
        if (!tls.exists()) fail("$tls is missing")
        val lines = tls.readText().lines()
        val service = lines.indexOfFirst { it == "  spool:" }
        if (service < 0) fail("no `  spool:` service in $tls")
        val environment =
            (service until lines.size).firstOrNull { lines[it] == "    environment:" }
                ?: fail("no `    environment:` under the spool service in $tls")

        val found = mutableMapOf<String, String>()
        for (i in environment + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            // A dedent ends the block: the next key of the service, or the next service.
            if (!line.startsWith("      ")) break
            val match = entry.matchEntire(line) ?: continue // a comment, or JAVA_OPTS
            found[match.groupValues[1]] = match.groupValues[2].trim()
        }
        return found
    }

    /**
     * Whether `configFromEnv` survives this variable being the empty string, asked of the parser
     * rather than answered from a list here — the two would drift, and this is the half that
     * would go quietly wrong. `intVar`/`longVar` fall back on `null` alone and reject `""` with
     * "must be an integer", so an `${X:-}` default on a numeric variable is a boot failure.
     */
    private fun toleratesEmpty(name: String): Boolean =
        runCatching {
            configFromEnv { requested ->
                when {
                    requested == name -> ""

                    // The commons half of the parser is only reached with an id, and seven of the
                    // variables under test live behind it.
                    requested == "SPOOL_COMMONS_ID" -> commonsId

                    else -> null
                }
            }
        }.isSuccess

    @Test
    fun everyVariableTheDaemonReadsIsReachableFromEnv() {
        val unreachable = KNOWN_VARS - declared().keys - exempt
        assertTrue(
            unreachable.isEmpty(),
            "read by the daemon but not declared in deploy/docker-compose.tls.yml, so setting it " +
                "in deploy/.env does nothing at all: $unreachable — add each under the spool " +
                "service's `environment:` as a bare `KEY:` (which forwards .env when set and omits " +
                "the variable when not), or add it to `exempt` here with the reason",
        )
    }

    @Test
    fun everyDeclaredVariableIsOneTheDaemonReads() {
        val dead = declared().keys - KNOWN_VARS
        assertTrue(
            dead.isEmpty(),
            "declared in deploy/docker-compose.tls.yml but nothing reads them, so the daemon warns " +
                "\"unrecognized environment variable\" on every boot of a correct deployment: $dead",
        )
    }

    @Test
    fun emptyDefaultsOnlyWhereTheParserToleratesThem() {
        val risky =
            declared()
                .filterValues { emptyDefault.containsMatchIn(it) }
                .keys
                .filterNot(::toleratesEmpty)
                .sorted()
        assertTrue(
            risky.isEmpty(),
            "declared with an \"\\\${VAR:-}\" default, which passes the empty string, but the " +
                "parser refuses an empty value — the daemon would not boot: $risky — use the bare " +
                "`KEY:` form instead, which omits the variable and lets its own default apply",
        )
    }
}
