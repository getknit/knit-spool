// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [pointSqliteAtTheExecutable] names the library beside the real executable, and only when it has
 * to (ADR 2026-11.bxce). The native acceptance run proves the same through a real symlink in PATH.
 */
class NativeSqliteTest {
    private val tmp: Path = Files.createTempDirectory("native-sqlite").toRealPath()

    @Test
    fun theLibraryBesideTheRealExecutableIsNamedThroughASymlink() {
        val lib = tmp.resolve("lib").createDirectories()
        val exe = lib.resolve("knit-spool").createFile()
        val so = lib.resolve("libsqlitejdbc.so").createFile()
        val link =
            tmp
                .resolve("bin")
                .createDirectories()
                .resolve("knit-spool")
                .createSymbolicLinkPointingTo(exe)
        val props = Properties()
        assertEquals(so, pointSqliteAtTheExecutable("native", link, props))
        assertEquals(lib.toString(), props.getProperty("org.sqlite.lib.path"))
        assertEquals("libsqlitejdbc.so", props.getProperty("org.sqlite.lib.name"))
    }

    @Test
    fun anExplicitPathTheJvmAndAMissingLibraryAreLeftAlone() {
        val lib = tmp.resolve("lib2").createDirectories()
        val exe = lib.resolve("knit-spool").createFile()
        lib.resolve("libsqlitejdbc.so").createFile()
        val explicit = Properties().apply { setProperty("org.sqlite.lib.path", "/elsewhere") }
        assertNull(pointSqliteAtTheExecutable("native", exe, explicit))
        assertEquals("/elsewhere", explicit.getProperty("org.sqlite.lib.path"))
        assertNull(explicit.getProperty("org.sqlite.lib.name"))
        val jvm = Properties()
        assertNull(pointSqliteAtTheExecutable("jvm", exe, jvm))
        assertNull(jvm.getProperty("org.sqlite.lib.path"))
        val bare =
            tmp
                .resolve("bare")
                .createDirectories()
                .resolve("knit-spool")
                .createFile()
        val missing = Properties()
        assertNull(pointSqliteAtTheExecutable("native", bare, missing))
        assertNull(missing.getProperty("org.sqlite.lib.path"))
    }
}
