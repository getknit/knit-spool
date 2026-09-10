// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** The build stamp the daemon reports at runtime. */
class BuildInfoTest {
    /**
     * Deliberately not an equality check against the snapshot literal: the release workflow runs
     * `check` with `-PspoolVersion="$VERSION"`, so pinning the string here would fail on release day.
     */
    @Test
    fun theStampIsPresentAndNeverBlank() {
        assertTrue(BuildInfo.version.isNotBlank())
        assertTrue(BuildInfo.commit.isNotBlank())
    }

    /** Both values are rendered into JSON and into a Prometheus label, so neither may carry a quote. */
    @Test
    fun theStampCarriesNothingThatWouldBreakItsRenderSites() {
        listOf(BuildInfo.version, BuildInfo.commit).forEach { value ->
            assertFalse(value.contains('"'), value)
            assertFalse(value.contains('\\'), value)
            assertFalse(value.contains('\n'), value)
        }
    }

    /**
     * The snapshot label has to lead the newest released version, and nothing about it moving on
     * its own is possible: the version is not stored in the tree beyond this one literal, so a
     * release cut from a tag never touches it and it simply stays where it was.
     *
     * That is how every main build between 0.2.0 and this check reported `0.1.0-SNAPSHOT` — a
     * version that reads as *older* than the release it supersedes, at `GET /source`, whose entire
     * job is to answer "what is running". The `commit` field beside it was right the whole time,
     * which is what makes the stale version worse than an absent one: the record looks answered.
     *
     * So the literal is pinned against `CHANGELOG.md`, the file that does carry the version — the
     * release workflow already refuses a tag with no section here. Cutting a release renames
     * `## Unreleased` to `## <version>`, which trips this check until the literal moves to the
     * next release's SNAPSHOT, making the bump part of the release commit rather than a thing to
     * remember afterwards.
     */
    @Test
    fun theSnapshotVersionLeadsTheNewestReleasedVersion() {
        val version = BuildInfo.version
        // A release build carries the tag's version instead, and the workflow checks that against
        // CHANGELOG.md itself. Nothing here should fail on release day.
        if (!version.endsWith(SNAPSHOT)) return

        if (!changelog.exists()) fail("$changelog is missing")
        val newest = released().maxOrNull() ?: fail("$changelog names no released version")
        val snapshot =
            parse(version.removeSuffix(SNAPSHOT))
                ?: fail("the tree's version $version is not <major>.<minor>.<patch>-SNAPSHOT")

        assertTrue(
            snapshot > newest,
            "the tree builds as $version but CHANGELOG.md's newest released version is ${newest.text}. " +
                "A snapshot that trails a release reports as older than the release it supersedes at " +
                "GET /source — bump `version` in build.gradle.kts to the next release's -SNAPSHOT.",
        )
    }

    private val repoRoot: Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: fail("no settings.gradle.kts at or above ${Path.of("").toAbsolutePath()}")

    private val changelog = repoRoot.resolve("CHANGELOG.md")

    /** Every released section, in either the linked `## [0.2.0](…) — <date>` or a bare `## 0.2.0`. */
    private fun released(): List<Version> = heading.findAll(changelog.readText()).mapNotNull { parse(it.groupValues[1]) }.toList()

    private fun parse(raw: String): Version? {
        val m = semver.matchEntire(raw.trim()) ?: return null
        val core = (1..3).map { m.groupValues[it].toInt() }
        return Version(core, m.groupValues[4].ifBlank { null }, raw.trim())
    }

    /**
     * Ordered by numeric core, then by semver's rule that a pre-release sorts below the release it
     * leads up to. Pre-release identifiers compare as text, which orders `rc.1` before `rc.2` and
     * is enough for any heading this file will carry; semver's numeric-identifier rule, where `2`
     * sorts below `10`, is not modelled.
     */
    private data class Version(
        val core: List<Int>,
        val pre: String?,
        val text: String,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            core.zip(other.core).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
            return when {
                pre == other.pre -> 0
                pre == null -> 1
                other.pre == null -> -1
                else -> pre.compareTo(other.pre)
            }
        }
    }

    private companion object {
        const val SNAPSHOT = "-SNAPSHOT"
        val heading = Regex("""^## \[?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)]?""", RegexOption.MULTILINE)
        val semver = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")
    }
}
