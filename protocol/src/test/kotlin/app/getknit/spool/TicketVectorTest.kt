// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Ent
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
 * **Experimental.** Vectors for entitlement tickets, from the draft in knit-next's
 * `docs/VOICE_CALLS.md` §2.6. The key pair is RFC 8032 §7.1 TEST 1, so the public half comes from
 * the RFC rather than from this code, and every hex string below was produced independently: a
 * hand-rolled CBOR encoder and pyca/cryptography's Ed25519, not kotlinx or the JDK. Ed25519 is
 * deterministic, so the signature is a vector like any other byte string.
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
        assertEquals(EXPECTED.getValue("sig"), Tickets.sign(SIGNING_KEY, EXPECTED.getValue("tk").hexToByteArray()).toHexString())
    }

    @Test
    fun ticketVectorsMatchTheDraft() {
        val (tk, sig) = Tickets.issue(SIGNING_KEY, fixture())
        assertEquals(EXPECTED.getValue("kid"), Tickets.kid(RFC_PUBLIC).toHexString())
        assertEquals(EXPECTED.getValue("tk"), tk.toHexString(), "ticket body diverges from the draft")
        assertEquals(EXPECTED.getValue("sig"), sig.toHexString(), "ticket signature diverges from the draft")
        assertEquals(
            EXPECTED.getValue("ent"),
            RecordCodec.encode(Ent(t = RecordType.ENT, q = 7, tk = tk, sig = sig)).toHexString(),
            "ent record diverges from the draft",
        )
        val hello = Hello(t = RecordType.HELLO, v = RECORD_VERSION, ent = true, stun = listOf("stun:lab.example:3478"))
        assertEquals(EXPECTED.getValue("helloEntStun"), RecordCodec.encode(hello).toHexString())
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
        val ticket = RecordCodec.decode<Ticket>(EXPECTED.getValue("tk").hexToByteArray())
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

        val EXPECTED =
            mapOf(
                "kid" to "21fe31dfa154a261",
                "tk" to
                    "a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200636578701a6b4b2380" +
                    "62707350000102030405060708090a0b0c0d0e0f62666c01",
                "sig" to
                    "c0740d14d5f46f2771721c593fa34a6e926b39182955cc6f8fb15365dddcf229" +
                    "da2cb33bdf6f23881cf4b153eedc4238e143fa53ef1a834961040bb55818b809",
                "ent" to
                    "a4617463656e7461710762746b584ba7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e6266" +
                    "1a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c0d0e0f62666c01637369675840c0740d14d5f46f27" +
                    "71721c593fa34a6e926b39182955cc6f8fb15365dddcf229da2cb33bdf6f23881cf4b153eedc4238e143fa53ef1a834961040bb5" +
                    "5818b809",
                "helloEntStun" to
                    "a461746568656c6c6f61760163656e74f5647374756e81757374756e3a6c61622e6578616d706c653a33343738",
            )
    }
}
