// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.util.Properties

/**
 * What this build is: the version Gradle was given and the commit it was cut from.
 *
 * Read once from `build-info.properties`, a resource `:daemon`'s build writes into this package. A
 * resource rather than the jar manifest because `./gradlew :daemon:run` has no jar — it runs from
 * exploded class and resource directories, where `Package.getImplementationVersion()` is null.
 *
 * Neither value is stored in the tree. Both arrive as `-PspoolVersion` / `-PspoolCommit`, so a build
 * that was told nothing reports [UNKNOWN] rather than a stale constant somebody forgot to bump —
 * which is the honest answer for a local `./gradlew :daemon:run`.
 */
object BuildInfo {
    /** What an unstamped build reports. Never blank: it is rendered into JSON and a metric label. */
    const val UNKNOWN = "unknown"

    /**
     * Upstream's corresponding source, and the default `SPOOL_SOURCE_URL` replaces.
     *
     * Correct for an unmodified build and wrong for every fork, which is why it is only a default:
     * AGPL §13 obliges an operator to offer the source of *their* version, not of the code it
     * diverged from.
     */
    const val UPSTREAM_SOURCE_URL = "https://github.com/getknit/knit-spool"

    private val stamp: Properties =
        Properties().apply {
            // Absent only if someone repackaged the daemon by hand. A build stamp must never be the
            // reason a spool refuses to boot, so a missing resource degrades to UNKNOWN rather than
            // throwing out of a static initializer — which would surface as an unreadable
            // ExceptionInInitializerError from whichever call site happened to touch it first.
            BuildInfo::class.java.getResourceAsStream("build-info.properties")?.use { load(it) }
        }

    val version: String = stamp.value("version")

    val commit: String = stamp.value("commit")

    private fun Properties.value(key: String): String = getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN
}
