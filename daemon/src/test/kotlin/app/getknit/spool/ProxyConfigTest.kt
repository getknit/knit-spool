// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every shipped proxy config seals `/metrics` at the edge. The daemon token-gates the endpoint
 * only on a private spool, so on a public one the proxy is the only thing between scope counts,
 * live bytes and the commons subscriber count and anyone who asks — and `SECURITY.md` promises
 * every shipped config does it. The host Caddyfile shipped two releases without the line while
 * its sibling for compose had it, which is how a promise about "the shipped configs" drifts: one
 * file at a time. Pinned here so a new config, or an edit to one, meets the same rule.
 */
class ProxyConfigTest {
    private val repoRoot: Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: fail("no settings.gradle.kts at or above ${Path.of("").toAbsolutePath()}")

    /** Each shipped config with the line that seals `/metrics` in its own syntax. */
    private val seals =
        mapOf(
            "deploy/Caddyfile" to Regex("""^\s*respond /metrics 404\s*$""", RegexOption.MULTILINE),
            "deploy/Caddyfile.compose" to Regex("""^\s*respond /metrics 404\s*$""", RegexOption.MULTILINE),
            "deploy/nginx.conf" to Regex("""location = /metrics \{\s*return 404;""", RegexOption.MULTILINE),
        )

    @Test
    fun everyShippedProxyConfigSealsMetrics() {
        val open =
            seals
                .filterNot { (file, seal) ->
                    val path = repoRoot.resolve(file)
                    if (!path.exists()) fail("$path is missing")
                    seal.containsMatchIn(path.readText())
                }.keys
        assertTrue(
            open.isEmpty(),
            "serve /metrics to the internet, which SECURITY.md says no shipped proxy config does: $open",
        )
    }

    @Test
    fun everyProxyConfigInDeployIsOneThisTestKnows() {
        val unknown =
            repoRoot
                .resolve("deploy")
                .toFile()
                .listFiles { f -> f.isFile && (f.name.startsWith("Caddyfile") || f.name.endsWith(".conf")) }!!
                .map { "deploy/${it.name}" }
                .filterNot { it in seals }
        assertTrue(unknown.isEmpty(), "proxy configs in deploy/ this test does not check for a /metrics seal: $unknown")
    }
}
