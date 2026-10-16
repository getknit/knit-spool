// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.fail

/**
 * The development version as the tree spells it: the fallback literal in the root
 * `build.gradle.kts`, read from source rather than from [BuildInfo].
 *
 * The stamp is the wrong thing to test against: a release passes `-PspoolVersion` from its tag, so
 * on release day [BuildInfo.version] is the tag and the literal is invisible to every check that
 * reads it. The literal is the one that has to move by hand, so the checks read it directly.
 */
internal object TreeVersion {
    val repoRoot: Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: fail("no settings.gradle.kts at or above ${Path.of("").toAbsolutePath()}")

    /** `0.4.0-dev.1`: a release's core, then `-dev.` and a counter that only ever moves up on main. */
    val devForm = Regex("""(\d+\.\d+\.\d+)-dev\.([1-9]\d*)""")

    private val fallback = Regex("""version = .*\?: "([^"]+)"""")

    val literal: String by lazy {
        val script = repoRoot.resolve("build.gradle.kts")
        fallback.find(script.readText())?.groupValues?.get(1)
            ?: fail("$script has no `version = … ?: \"<version>\"` fallback for this check to read")
    }
}
