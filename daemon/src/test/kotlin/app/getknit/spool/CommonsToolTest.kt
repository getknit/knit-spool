// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.RelayInvite
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `knit-spool commons-invite`: what an operator pastes into the environment and hands to members.
 * The secret is fixed here so the printed halves can be checked against the derivations.
 */
class CommonsToolTest {
    private val secret = ByteArray(Commons.SECRET_BYTES) { (it * 5 + 3).toByte() }

    private fun run(vararg args: String): Triple<Int, List<String>, List<String>> {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val code = commonsInvite(args.toList(), secret, out::add, err::add)
        return Triple(code, out, err)
    }

    @Test
    fun withNoFlagsItPrintsTheInviteAndTheFirstRoomsIdAsBefore() {
        val (code, out, _) = run()
        assertEquals(0, code)
        assertEquals(
            listOf(
                "invite (give this to members):  ${Commons.encodeInvite(secret)}",
                "spool config (put in env):      SPOOL_COMMONS_ID=${Commons.scopeId(secret).toHexString()}",
            ),
            out,
        )
    }

    @Test
    fun aRoomNumberNamesThatRoomsVariables() {
        val (code, out, _) = run("--room", "3", "--name", "Den")
        assertEquals(0, code)
        assertTrue(out.any { it.endsWith("SPOOL_COMMONS_3_ID=${Commons.scopeId(secret).toHexString()}") }, out.toString())
        assertTrue(out.any { it.endsWith("SPOOL_COMMONS_3_NAME=Den") }, out.toString())
        assertEquals(1, run("--room", "9").first)
        assertEquals(1, run("--room", "x").first)
    }

    @Test
    fun aUrlAddsTheLinkWithTheTokenReadFromAFile() {
        val tokenFile = Files.createTempFile("spool-token", "").also { it.writeText("fixture-token\n") }
        try {
            val (code, out, _) = run("--url", "wss://home.example.org/spool/v1", "--token-file", tokenFile.toString(), "--name", "Home")
            assertEquals(0, code)
            val expected = RelayInvite.mint("wss://home.example.org/spool/v1?k=fixture-token", secret, "Home")
            assertTrue(out.contains("link (give this to members):    $expected"), out.toString())
        } finally {
            Files.delete(tokenFile)
        }
        // No token for a public spool: the link carries the bare URL.
        val (_, bare, _) = run("--url", "wss://public.example.org/spool/v1")
        assertTrue(
            bare.contains("link (give this to members):    ${RelayInvite.mint("wss://public.example.org/spool/v1", secret)}"),
            bare.toString(),
        )
    }

    @Test
    fun aTokenOnTheCommandLineIsRefused() {
        // argv is readable by every local user for the life of the process.
        val (code, out, err) = run("--url", "wss://home.example.org/spool/v1?k=s3cret")
        assertEquals(1, code)
        assertTrue(out.isEmpty())
        assertTrue(err.single().contains("--token-file"), err.toString())
    }

    @Test
    fun whatThePhoneWouldRefuseOrClampIsRefusedBeforeAnythingIsPrinted() {
        assertEquals(1, run("--name", "a".repeat(RelayInvite.MAX_NAME_CHARS + 1)).first)
        assertEquals(1, run("--url", "https://home.example.org/spool/v1").first)
        assertEquals(1, run("--token-file", "/nonexistent").first, "a token file with no URL to put it in")
        assertEquals(2, run("--bogus").first)
        assertEquals(2, run("--url").first)
    }
}
