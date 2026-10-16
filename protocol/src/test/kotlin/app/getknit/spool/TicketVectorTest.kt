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

        private val SPEC = SpecVectorTest.EXPECTED
    }
}
