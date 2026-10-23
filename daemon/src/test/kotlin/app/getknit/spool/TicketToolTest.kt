// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ticket-keygen` and `ticket-mint` end to end, in process: what keygen prints configures a spool
 * through the daemon's own parser, and what mint prints is accepted by that spool's check. The
 * operator copies text between the two by hand, so the text forms are what is under test.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class TicketToolTest {
    private val dir: Path = Files.createTempDirectory("ticket-tool")
    private val now = 1_800_000_000_000L

    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    /** Runs keygen, files the signing key, and returns the key file and the `SPOOL_TICKET_KEY` value. */
    private fun keygen(): Pair<Path, String> {
        val lines = mutableListOf<String>()
        ticketKeygen(lines::add)
        assertEquals(2, lines.size, lines.toString())
        val signing = lines[0].substringAfterLast(' ')
        val public = lines[1].substringAfter("SPOOL_TICKET_KEY=")
        val file = dir.resolve("ticket.key").also { it.writeText(signing + "\n") }
        return file to public
    }

    private fun mint(vararg args: String): Pair<Int, List<String>> {
        val out = mutableListOf<String>()
        val code = ticketMint(args.toList(), now, out::add, out::add)
        return code to out
    }

    private fun claims(text: String): Ticket = assertNotNull(RecordCodec.decode<Ticket>(assertNotNull(Tickets.decodeText(text)).first))

    @Test
    fun aMintedTicketIsAcceptedByASpoolConfiguredFromKeygen() {
        val (keyFile, public) = keygen()
        val config = assertNotNull(configFromEnv(mapOf("SPOOL_TICKET_KEY" to public, "SPOOL_TICKET_AUDIENCE" to "lab.example")::get).ticket)
        val (code, out) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--account", "alice")
        assertEquals(0, code, out.toString())
        val (tk, sig) = assertNotNull(Tickets.decodeText(out.single()))
        val accepted = assertIs<Tickets.Outcome.Accepted>(Tickets.check(tk, sig, config.verifiers, config.audiences, now / 1_000))
        assertEquals(Tickets.FL_TURN, accepted.ticket.fl)
        assertEquals(now / 1_000 + 25 * 3_600, accepted.ticket.exp)
    }

    /** What a storefront would do: one pseudonym per account per day, a new one the next day. */
    @Test
    fun anAccountsPseudonymIsStableWithinADayAndNotAcrossThem() {
        val (keyFile, _) = keygen()
        val args = arrayOf("--key-file", keyFile.toString(), "--aud", "lab.example")
        val alice = claims(mint(*args, "--account", "alice").second.single()).ps
        assertContentEquals(alice, claims(mint(*args, "--account", "alice").second.single()).ps)
        val bob = claims(mint(*args, "--account", "bob").second.single()).ps
        assertFalse(alice.contentEquals(bob))
        val signingKey = assertNotNull(Tickets.decodeSigningKey(Files.readString(keyFile)))
        val day = now / 86_400_000
        assertContentEquals(alice, pseudonym(signingKey, "alice", "lab.example", day))
        assertFalse(alice.contentEquals(pseudonym(signingKey, "alice", "lab.example", day + 1)))
        // The length prefixes keep "ab"+"c" and "a"+"bc" apart.
        assertFalse(pseudonym(signingKey, "ab", "c", day).contentEquals(pseudonym(signingKey, "a", "bc", day)))
        // Without an account each ticket gets a fresh random pseudonym.
        assertFalse(claims(mint(*args).second.single()).ps.contentEquals(claims(mint(*args).second.single()).ps))
    }

    @Test
    fun noTurnMintsATicketWithoutTheFlag() {
        val (keyFile, _) = keygen()
        val (_, out) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--no-turn", "--hours", "48")
        val ticket = claims(out.single())
        assertEquals(0L, ticket.fl)
        assertEquals(48L * 3_600, ticket.exp - ticket.nbf)
    }

    /** Casts are minted only on request, with TURN or without it. */
    @Test
    fun castMintsBitThreeBesideWhateverTurnSays() {
        val (keyFile, _) = keygen()
        val (_, both) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--cast")
        assertEquals(Tickets.FL_TURN or Tickets.FL_CAST, claims(both.single()).fl)
        val (_, castOnly) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--cast", "--no-turn")
        assertEquals(Tickets.FL_CAST, claims(castOnly.single()).fl)
        val (_, turnOnly) = mint("--key-file", keyFile.toString(), "--aud", "lab.example")
        assertEquals(Tickets.FL_TURN, claims(turnOnly.single()).fl)
    }

    /** Files are minted only on request, and a budget only when given; absent, the spool's own applies. */
    @Test
    fun filesMintsBitOneAndAbClaimsABudget() {
        val (keyFile, _) = keygen()
        val (_, files) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--files", "--ab", "1024")
        val ticket = claims(files.single())
        assertEquals(Tickets.FL_TURN or Tickets.FL_FILES, ticket.fl)
        assertEquals(1_024L, ticket.ab)
        val (_, plain) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--files", "--no-turn")
        assertEquals(Tickets.FL_FILES, claims(plain.single()).fl)
        assertNull(claims(plain.single()).ab)
    }

    /** A size cap only when given, beside the budget or alone; absent, the spool's own applies. */
    @Test
    fun amClaimsTheLargestAttachment() {
        val (keyFile, _) = keygen()
        val (_, both) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--files", "--ab", "1310720", "--am", "262144")
        val ticket = claims(both.single())
        assertEquals(1_310_720L, ticket.ab)
        assertEquals(262_144L, ticket.am)
        val (_, zero) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--files", "--am", "0")
        assertEquals(0L, claims(zero.single()).am)
        assertNull(claims(zero.single()).ab)
        val (_, plain) = mint("--key-file", keyFile.toString(), "--aud", "lab.example", "--files")
        assertNull(claims(plain.single()).am)
    }

    @Test
    fun badInvocationsExitNonZeroWithoutPrintingAKey() {
        val (keyFile, _) = keygen()
        val key = keyFile.toString()
        val wrongFile = dir.resolve("not-a-key").also { it.writeText("knit-commons:v1:AAAA") }.toString()
        val cases =
            mapOf(
                listOf("--aud", "lab.example") to 2,
                listOf("--key-file", key) to 2,
                listOf("--key-file", key, "--aud", "lab.example", "--bogus") to 2,
                listOf("--key-file", key, "--aud", "Lab.Example") to 1,
                listOf("--key-file", key, "--aud", "lab.example", "--hours", "49") to 1,
                listOf("--key-file", key, "--aud", "lab.example", "--hours", "0") to 1,
                listOf("--key-file", dir.resolve("missing").toString(), "--aud", "lab.example") to 1,
                listOf("--key-file", wrongFile, "--aud", "lab.example") to 1,
                listOf("--key-file", key, "--aud", "lab.example", "--ab", "-1") to 1,
                listOf("--key-file", key, "--aud", "lab.example", "--ab", "lots") to 1,
                listOf("--key-file", key, "--aud", "lab.example", "--ab") to 2,
                listOf("--key-file", key, "--aud", "lab.example", "--am", "-1") to 1,
                listOf("--key-file", key, "--aud", "lab.example", "--am", "big") to 1,
                listOf("--key-file", key, "--aud", "lab.example", "--am") to 2,
            )
        val secret = Files.readString(keyFile).trim()
        cases.forEach { (args, expected) ->
            val out = mutableListOf<String>()
            assertEquals(expected, ticketMint(args, now, out::add, out::add), args.toString())
            assertTrue(out.none { it.contains(secret) }, "a refusal must never echo the signing key")
        }
    }
}
