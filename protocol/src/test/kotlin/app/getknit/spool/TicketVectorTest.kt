// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key pair and ticket fixtures behind knit-next `docs/SPOOL_PROTOCOL.md` §13's ticket vectors,
 * and checks around them. The key pair is RFC 8032 §7.1 TEST 1, so the public half comes from the
 * RFC rather than from this code. The vectors themselves are pinned by [SpecVectorTest]; they were
 * produced independently, by a hand-rolled CBOR encoder and pyca/cryptography's Ed25519, not kotlinx
 * or the JDK.
 *
 * The four `am` vectors are this repo's to produce (S-7.6-31, ADR 2026-10.h4ps) and live here until the
 * spec's §13 carries them. They were made the same independent way, by a hand-rolled CBOR encoder and
 * pyca/cryptography 45.0.7's Ed25519, from [filesFixture]'s inputs with `fl`, `ab` and `am` replaced;
 * the same script reproduced the spec's `tkFiles` and `sigFiles` byte for byte first.
 */
class TicketVectorTest {
    @Test
    fun theRfcKeyPairRoundTripsThroughTheDerPrefixes() {
        // The X.509 prefix: the RFC's own signature over the empty message verifies under the RFC's
        // raw public key, wrapped by publicKey().
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(Tickets.publicKey(RFC_PUBLIC))
        assertTrue(verifier.verify(RFC_EMPTY_SIG), "the RFC 8032 TEST 1 signature must verify")
        // The PKCS#8 prefix: what sign() makes from the seed verifies under that same public key.
        assertEquals(SPEC.getValue("sig"), Tickets.sign(SIGNING_KEY, SPEC.getValue("tk").hexToByteArray()).toHexString())
    }

    @Test
    fun theFilesVectorsDecodeToTheirClaimsAndGates() {
        val decoded = RecordCodec.decode<Ticket>(SPEC.getValue("tkFiles").hexToByteArray())
        assertEquals(Tickets.FL_TURN or Tickets.FL_FILES, decoded?.fl)
        assertEquals(134_217_728L, decoded?.ab)
        val gates = Tickets.FL_TURN or Tickets.FL_FILES or Tickets.FL_CAST
        assertEquals(gates, RecordCodec.decode<Hello>(SPEC.getValue("helloEntGates").hexToByteArray())?.ticketGates)
    }

    /** `am` encodes last, after `ab`, and only when set: 9 entries with both, 8 with `am` alone. */
    @Test
    fun theAmVectorsMatchTheEncoder() {
        val (tkFilesAm, sigFilesAm) = Tickets.issue(SIGNING_KEY, amFixture(ab = 1_310_720L))
        assertEquals(TK_FILES_AM, tkFilesAm.toHexString(), "tkFilesAm diverges")
        assertEquals(SIG_FILES_AM, sigFilesAm.toHexString(), "sigFilesAm diverges")
        val (tkAmOnly, sigAmOnly) = Tickets.issue(SIGNING_KEY, amFixture(ab = null))
        assertEquals(TK_AM_ONLY, tkAmOnly.toHexString(), "tkAmOnly diverges")
        assertEquals(SIG_AM_ONLY, sigAmOnly.toHexString(), "sigAmOnly diverges")
        assertEquals(0xa9, tkFilesAm[0].toInt() and 0xFF, "a map of 9")
        assertEquals(0xa8, tkAmOnly[0].toInt() and 0xFF, "a map of 8")
    }

    @Test
    fun theAmVectorsDecodeToTheirClaims() {
        val both = RecordCodec.decode<Ticket>(TK_FILES_AM.hexToByteArray())
        assertEquals(Tickets.FL_FILES, both?.fl)
        assertEquals(1_310_720L, both?.ab)
        assertEquals(262_144L, both?.am)
        val amOnly = RecordCodec.decode<Ticket>(TK_AM_ONLY.hexToByteArray())
        assertNull(amOnly?.ab)
        assertEquals(262_144L, amOnly?.am)
    }

    @Test
    fun aHelloReadsItsGatesFromEntAndEntGates() {
        assertEquals(0L, Hello(t = RecordType.HELLO, v = RECORD_VERSION).ticketGates)
        assertEquals(0L, Hello(t = RecordType.HELLO, v = RECORD_VERSION, entGates = Tickets.FL_FILES).ticketGates)
        assertEquals(Tickets.LEGACY_GATES, Hello(t = RecordType.HELLO, v = RECORD_VERSION, ent = true).ticketGates)
        assertEquals(0L, Hello(t = RecordType.HELLO, v = RECORD_VERSION, ent = true, entGates = 0).ticketGates)
    }

    @Test
    fun aHelloWithNeitherFieldOmitsBoth() {
        val hello = RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION))
        assertEquals("a261746568656c6c6f617601", hello.toHexString())
        val decoded = RecordCodec.decode<Hello>(hello)
        assertNull(decoded?.ent)
        assertNull(decoded?.stun)
    }

    @Test
    fun theVectorTicketDecodesToItsClaims() {
        val ticket = RecordCodec.decode<Ticket>(SPEC.getValue("tk").hexToByteArray())
        assertEquals(1_800_086_400L, ticket?.exp)
        assertEquals("lab.example", ticket?.aud)
        assertContentEquals(ByteArray(Tickets.PS_BYTES) { it.toByte() }, ticket?.ps)
    }

    internal companion object {
        val RFC_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToByteArray()
        val RFC_PUBLIC = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray()
        val RFC_EMPTY_SIG =
            (
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
            ).hexToByteArray()
        val SIGNING_KEY = RFC_SEED + RFC_PUBLIC

        fun fixture(): Ticket =
            Ticket.forKey(
                signingKey = SIGNING_KEY,
                aud = "lab.example",
                nbf = 1_800_000_000L,
                exp = 1_800_086_400L,
                ps = ByteArray(Tickets.PS_BYTES) { it.toByte() },
                fl = Tickets.FL_TURN,
            )

        /** The [fixture] with files beside TURN and a 128 MiB budget claimed outright, as the spec's vector has it. */
        fun filesFixture(): Ticket =
            Ticket.forKey(
                signingKey = SIGNING_KEY,
                aud = "lab.example",
                nbf = 1_800_000_000L,
                exp = 1_800_086_400L,
                ps = ByteArray(Tickets.PS_BYTES) { it.toByte() },
                fl = Tickets.FL_TURN or Tickets.FL_FILES,
                ab = 134_217_728L,
            )

        /** [filesFixture]'s inputs with files alone and `am` = 262,144, beside [ab] when given. */
        fun amFixture(ab: Long?): Ticket =
            filesFixture().let {
                Ticket.forKey(SIGNING_KEY, it.aud, it.nbf, it.exp, it.ps, fl = Tickets.FL_FILES, ab = ab, am = 262_144L)
            }

        /** `fl` = 2, `ab` = 1,310,720, `am` = 262,144: 9 entries. */
        const val TK_FILES_AM =
            "a9617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c6563" +
                "6e62661a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c" +
                "0d0e0f62666c026261621a0014000062616d1a00040000"
        const val SIG_FILES_AM =
            "6ca646d923b0d93996c771aed64d590d8fc515214eb04ec01cbc7ad838863e45" +
                "c60d831eaa035258542758592712cd7a33e23f7969884f6ae9585053275bc000"

        /** `fl` = 2, `am` = 262,144, no `ab`: 8 entries. */
        const val TK_AM_ONLY =
            "a8617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c6563" +
                "6e62661a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c" +
                "0d0e0f62666c0262616d1a00040000"
        const val SIG_AM_ONLY =
            "705b1efffa580108dd8eabad93b680366701020bb37fa5b728c65f9f2a86e1c6" +
                "0689cf76ae17a6584b30c2b3e312a5c309d0cece323fc17cad480a61998f9b06"

        private val SPEC = SpecVectorTest.EXPECTED
    }
}
