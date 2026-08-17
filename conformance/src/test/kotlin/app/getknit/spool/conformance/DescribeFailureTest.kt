// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A conformance run is usually the only artefact of a failure against a remote spool, so a reason
 * line that reads `NullPointerException` and nothing else is unactionable — these pin the shape
 * that made the difference.
 */
class DescribeFailureTest {
    @Test
    fun `check failure keeps its written message unadorned`() {
        val reason = describeFailure(CheckFailure("expected 'digest' record, got err code=rate"))
        assertEquals("expected 'digest' record, got err code=rate", reason)
    }

    @Test
    fun `messageless throwable names its type and where it came from`() {
        val reason = describeFailure(npeFromOurCode())
        assertContains(reason, "NullPointerException")
        assertContains(reason, "(no message)")
        // The frame must be ours, not the deepest library frame, or it points at the wrong project.
        assertContains(reason, "DescribeFailureTest.npeFromOurCode")
    }

    @Test
    fun `uninformative library message still gets a location`() {
        // The real one: kotlin's require() with no message, surfacing from a library.
        val reason = describeFailure(runCatching { require(false) }.exceptionOrNull()!!)
        assertContains(reason, "IllegalArgumentException")
        assertContains(reason, "Failed requirement.")
        assertContains(reason, "DescribeFailureTest")
    }

    @Test
    fun `cause chain is appended and bounded`() {
        val deep =
            RuntimeException(
                "one",
                RuntimeException("two", RuntimeException("three", RuntimeException("four", RuntimeException("five")))),
            )
        val reason = describeFailure(deep)
        assertContains(reason, "one")
        // Three causes deep, counting from the first cause rather than from the throwable itself.
        assertContains(reason, "<- RuntimeException: two")
        assertContains(reason, "<- RuntimeException: three")
        assertContains(reason, "<- RuntimeException: four")
        // Bounded, or a deep chain turns one TAP diagnostic line into a wall of text.
        assertFalse(reason.contains("five"), "cause chain should stop at $CAUSE_CHAIN_DEPTH causes: $reason")
    }

    @Test
    fun `reason stays a single line`() {
        val reason = describeFailure(npeFromOurCode())
        assertTrue('\n' !in reason, "TAP diagnostics are one line per reason: $reason")
    }

    @Test
    fun `transport failure is named as such so it reads apart from a spec verdict`() {
        // That it is not a CheckFailure — and so is never charged to the spool by the runner — the
        // compiler already guarantees: the two are unrelated types. What is worth pinning is that
        // the reason line says which of the two it was.
        val reason = describeFailure(TransportFailure("connection died while awaiting a record (no close frame)"))
        assertContains(reason, "TransportFailure")
        assertContains(reason, "connection died while awaiting a record")
    }

    private fun npeFromOurCode(): Throwable {
        val nothing: String? = null
        return runCatching { nothing!!.length }.exceptionOrNull()!!
    }
}

private const val CAUSE_CHAIN_DEPTH = 3
