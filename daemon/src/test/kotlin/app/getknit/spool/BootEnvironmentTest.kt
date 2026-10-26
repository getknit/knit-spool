// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Boot and `check` read `SPOOL_RELOAD_FILE` over the environment, as a `SIGHUP` does (ADR 2026-10.hnqs).
 * Before, a restart fell back to the environment for every line the file set, so a room rotated
 * through the file came back as the old room, with the removed member's invite working again.
 */
class BootEnvironmentTest {
    @TempDir
    lateinit var dir: Path

    private val oldRoom = Commons.scopeId(ByteArray(Commons.SECRET_BYTES) { (it + 1).toByte() }).toHexString()
    private val newRoom = Commons.scopeId(ByteArray(Commons.SECRET_BYTES) { (it + 201).toByte() }).toHexString()

    private fun reloadFile(vararg lines: String): Path = dir.resolve("reload.env").also { Files.writeString(it, lines.joinToString("\n")) }

    @Test
    fun theReloadFileIsLayeredOverTheEnvironment() {
        val file = reloadFile("# rotated 2026-10-25", "SPOOL_COMMONS_ID=$newRoom", "SPOOL_MAX_CONNS=500")
        val environment = mapOf("SPOOL_RELOAD_FILE" to file.toString(), "SPOOL_COMMONS_ID" to oldRoom, "SPOOL_POW_BITS" to "8")
        val booted = bootEnvironment(environment) { error("nothing to warn about: $it") }
        assertEquals(newRoom, booted["SPOOL_COMMONS_ID"], "boot came back with the room the reload had rotated away")
        assertEquals("500", booted["SPOOL_MAX_CONNS"])
        assertEquals("8", booted["SPOOL_POW_BITS"], "what the file omits falls back to the environment")
    }

    /** A spool provisioned before its first reload has a path and no file yet. */
    @Test
    fun aMissingFileIsNoOverrides() {
        val environment = mapOf("SPOOL_RELOAD_FILE" to dir.resolve("absent.env").toString(), "SPOOL_COMMONS_ID" to oldRoom)
        assertSame(environment, bootEnvironment(environment) { error("nothing to warn about: $it") })
        val unset = environment - "SPOOL_RELOAD_FILE"
        assertSame(unset, bootEnvironment(unset) { error("nothing to warn about: $it") })
    }

    /** Booting from the environment alone would undo whatever the file holds, quietly: refuse instead. */
    @Test
    fun anUnreadableFileRefusesToBoot() {
        val environment = mapOf("SPOOL_RELOAD_FILE" to dir.toString())
        assertFailsWith<IOException> { bootEnvironment(environment) {} }
    }

    /** The file's path comes from the environment, as the `SIGHUP` handler's does; one inside it is dropped. */
    @Test
    fun aReloadFilePathInsideTheFileIsIgnored() {
        val file = reloadFile("SPOOL_RELOAD_FILE=/elsewhere.env", "SPOOL_MAX_CONNS=7")
        val warnings = mutableListOf<String>()
        val booted = bootEnvironment(mapOf("SPOOL_RELOAD_FILE" to file.toString()), warnings::add)
        assertEquals(file.toString(), booted["SPOOL_RELOAD_FILE"])
        assertEquals("7", booted["SPOOL_MAX_CONNS"])
        assertTrue(warnings.single().contains("is ignored"), warnings.toString())
    }

    /** `check` validates what boot will run, so a provisioning check sees the rotated room too. */
    @Test
    fun checkReportsWhatTheFileSets() {
        val file = reloadFile("SPOOL_COMMONS_ID=$newRoom")
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true))
        val status =
            try {
                checkConfig(mapOf("SPOOL_RELOAD_FILE" to file.toString(), "SPOOL_COMMONS_ID" to oldRoom))
            } finally {
                System.setOut(original)
            }
        assertEquals(0, status)
        assertTrue(out.toString().contains("commons=${newRoom.take(8)}"), out.toString())
    }

    @Test
    fun checkRefusesAnUnreadableFile() {
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err, true))
        val status =
            try {
                checkConfig(mapOf("SPOOL_RELOAD_FILE" to dir.toString()))
            } finally {
                System.setErr(original)
            }
        assertEquals(1, status)
        assertTrue(err.toString().contains("cannot read SPOOL_RELOAD_FILE"), err.toString())
    }
}
