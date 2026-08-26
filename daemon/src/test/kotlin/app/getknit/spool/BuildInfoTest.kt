// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The build stamp the daemon reports at runtime. */
class BuildInfoTest {
    /**
     * Deliberately not an equality check against `0.1.0-SNAPSHOT`: the release workflow runs `check`
     * with `-PspoolVersion="$VERSION"`, so pinning the string here would fail on release day.
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
}
