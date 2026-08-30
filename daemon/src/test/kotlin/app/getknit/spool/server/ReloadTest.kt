// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.store.HardLimits
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SIGHUP` reload (plan item 4.2): the reloadable half of the configuration is swapped without
 * disturbing a live connection, and the boot half is refused rather than half-applied.
 *
 * The signal itself is not exercised here — `sun.misc.Signal` is process-global and a test that
 * raised SIGHUP would reconfigure whatever else the JVM is running. What the handler does with the
 * configuration it parsed is what these cover; `Main` is the thin part.
 */
class ReloadTest {
    @Test
    fun rotatingCredentialsTakesEffectWithoutARestart() {
        withServer(testConfig(token = "old")) {
            connect(token = "old") { helloHandshake() }
            connect(token = "new") { awaitClose(CloseCode.AUTH) }

            // Mid-rotation: both credentials live, nothing refused.
            assertTrue(spool.reload(testConfig(token = "old", tokenNext = "new")).isEmpty())
            connect(token = "old") { helloHandshake() }
            connect(token = "new") { helloHandshake() }

            // Promoting retires the old one, still without a restart.
            assertTrue(spool.reload(testConfig(token = "new")).isEmpty())
            connect(token = "new") { helloHandshake() }
            connect(token = "old") { awaitClose(CloseCode.AUTH) }
        }
    }

    /**
     * `maxConns` is read on every use rather than captured, so a reload moves both what is enforced
     * and what `/metrics` reports. The alternative is a dashboard that disagrees with the daemon.
     *
     * `/metrics` reports the ceiling it is actually enforcing. It is read live rather than captured,
     * so a reload moves the enforced value and the reported one together — the alternative is an
     * operator reading a dashboard that disagrees with the daemon.
     */
    @Test
    fun capacityCeilingMovesOnReload() {
        withServer(testConfig(maxConns = 0)) {
            assertTrue(spool.reload(testConfig(maxConns = 7)).isEmpty())
            connect { helloHandshake() }
            assertContains(http.get("http://127.0.0.1:$port/metrics").bodyAsText(), "knit_spool_max_conns 7")
        }
    }

    /**
     * The boot half cannot move, and saying so is the whole contract: silently ignoring a changed
     * `maxScopes` would leave an operator believing a quota they can see in their file is in force,
     * while the store was built from a different one.
     */
    @Test
    fun bootOnlyFieldsAreReportedAndNotApplied() {
        withServer(testConfig()) {
            val ignored =
                spool.reload(
                    testConfig(
                        hardLimits = HardLimits(maxBlob = 2_048, maxFramesCap = 7, maxTtlMs = 1_000L, maxScopes = 9),
                    ),
                )
            assertTrue(ignored.any { it.startsWith("maxBlob=") }, ignored.toString())
            assertTrue(ignored.any { it.startsWith("maxFrames=") }, ignored.toString())
            assertTrue(ignored.any { it.startsWith("maxScopes=") }, ignored.toString())
            // Unchanged boot fields are not a complaint — only what actually differs is reported.
            assertTrue(ignored.none { it.startsWith("port=") }, ignored.toString())
            // And the spool still announces what it was built with, not what was asked for.
            connect { assertEquals(1_024, helloHandshake().limits!!.maxBlob) }
        }
    }

    /** A reload that changes nothing reports nothing. */
    @Test
    fun anIdenticalReloadIsSilent() {
        withServer(testConfig(token = "s3cret")) {
            assertTrue(spool.reload(testConfig(token = "s3cret")).isEmpty())
        }
    }

    /**
     * The two halves have to cover every field between them. A field in neither would be silently
     * unreloadable *and* absent from the boot log; one in both would be reported twice and, worse,
     * compared against itself when deciding what a reload may touch.
     */
    @Test
    fun everyDescribedFieldBelongsToExactlyOneHalf() {
        val config = testConfig(token = "s3cret")
        val boot = config.bootOnly().keys
        val runtime = config.reloadable().keys
        assertTrue(boot.intersect(runtime).isEmpty(), "in both halves: ${boot.intersect(runtime)}")

        val described =
            config
                .describe()
                .split(' ')
                .map { it.substringBefore('=') }
                .toSet()
        assertEquals(boot + runtime, described)
        // Spot-check the two that matter most for a reload's blast radius.
        assertContains(runtime, "token")
        assertContains(boot, "port")
    }
}
