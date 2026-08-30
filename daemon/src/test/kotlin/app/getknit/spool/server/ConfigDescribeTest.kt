// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.shortHex
import ch.qos.logback.classic.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The boot line that says what this spool actually resolved to. */
class ConfigDescribeTest {
    private fun fields(line: String): Map<String, String> =
        line.split(' ').associate { field ->
            field.substringBefore('=') to field.substringAfter('=')
        }

    @Test
    fun describeCarriesEveryResolvedValueIncludingDefaults() {
        val fields = fields(testConfig().describe())
        // The harness configures port 0 and reads the bound port back; SPOOL_PORT itself is >= 1.
        assertEquals("0", fields["port"])
        assertEquals("0", fields["pow"])
        assertEquals("1024", fields["maxBlob"])
        assertEquals("4", fields["maxScopes"])
        assertEquals("16", fields["maxConnsPerIp"])
        assertEquals("false", fields["trustProxy"])
        assertEquals("off", fields["commons"])
    }

    /** SECURITY.md makes a token in this daemon's log a reportable vulnerability. */
    @Test
    fun secretsAreReportedAsPresenceNeverAsValue() {
        val line = testConfig(token = "s3cret", metricsToken = "scrape").describe()
        assertFalse(line.contains("s3cret"), line)
        assertFalse(line.contains("scrape"), line)
        assertEquals("set", fields(line)["token"])
        assertEquals("set", fields(line)["metricsToken"])
        assertEquals("unset", fields(testConfig().describe())["token"])

        // A rotation puts a second live secret in the process; it is reported the same way.
        val rotating = testConfig(token = "s3cret", tokenNext = "n3xt").describe()
        assertEquals("set", fields(rotating)["tokenNext"])
        assertFalse(rotating.contains("n3xt"), rotating)
        assertEquals("unset", fields(testConfig(token = "s3cret").describe())["tokenNext"])
    }

    /**
     * `hello` deliberately never carries the commons scope id — publishing it would turn a room only
     * invite holders can find into one anybody who connects could subscribe to and flood. The boot
     * log must not undo that.
     */
    @Test
    fun theCommonsIdIsTruncatedTheSameWayEverythingElseIs() {
        val commons = testCommons()
        val line = testConfig(commons = commons).describe()
        assertFalse(line.contains(commons.scopeId.joinToString("") { "%02x".format(it) }), line)
        assertEquals(shortHex(commons.scopeId), fields(line)["commons"])
    }

    @Test
    fun theServerLogsItOnceAtStartupAsASingleLine() {
        val logged =
            withLogCapture("app.getknit.spool.server.SpoolServer", Level.INFO) {
                withServer(testConfig(token = "s3cret")) {}
            }
        val configLines = logged.map { it.formattedMessage }.filter { it.startsWith("effective config: ") }
        assertEquals(1, configLines.size, "one boot, one config line: $configLines")
        val line = configLines.single()
        assertFalse(line.contains('\n'), "the boot line must stay a single line: $line")
        assertFalse(line.contains("s3cret"), line)
        assertEquals("set", fields(line.substringAfter("effective config: "))["token"], line)
    }
}
