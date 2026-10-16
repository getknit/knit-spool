// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.TicketVectorTest.Companion.RFC_PUBLIC
import app.getknit.spool.TicketVectorTest.Companion.SIGNING_KEY
import app.getknit.spool.TicketVectorTest.Companion.fixture
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.Tickets.Outcome
import app.getknit.spool.protocol.Tickets.Reason
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Tickets.check] is the whole of what a spool asks of an `ent`, so every refusal reason is pinned
 * here, against the vector key pair, with both edges of the skew window and the integer shapes a
 * hostile CBOR encoder can produce.
 */
class TicketCheckTest {
    private val key = Tickets.publicKey(RFC_PUBLIC)
    private val kid = Tickets.kid(RFC_PUBLIC)
    private val nbf = fixture().nbf
    private val exp = fixture().exp

    private fun check(
        ticket: Ticket = fixture(),
        nowS: Long = nbf + 60,
        aud: String = "lab.example",
        tamper: (ByteArray, ByteArray) -> Pair<ByteArray, ByteArray> = { tk, sig -> tk to sig },
    ): Outcome {
        val (tk, sig) = Tickets.issue(SIGNING_KEY, ticket).let { (tk, sig) -> tamper(tk, sig) }
        return Tickets.check(tk, sig, listOf(key to kid), aud, nowS)
    }

    private fun refusal(outcome: Outcome): Reason = assertIs<Outcome.Refused>(outcome).reason

    private fun with(
        v: Int = 1,
        kid: ByteArray = this.kid,
        nbf: Long = this.nbf,
        exp: Long = this.exp,
        ps: ByteArray = fixture().ps,
        fl: Long = Tickets.FL_TURN,
        ab: Long? = null,
    ) = Ticket(v = v, kid = kid, aud = "lab.example", nbf = nbf, exp = exp, ps = ps, fl = fl, ab = ab)

    @Test
    fun aGoodTicketIsAcceptedWithItsClaims() {
        val accepted = assertIs<Outcome.Accepted>(check())
        assertContentEquals(fixture().ps, accepted.ticket.ps)
        assertEquals(Tickets.FL_TURN, accepted.ticket.fl)
    }

    @Test
    fun theSkewWindowIsInclusiveAtBothEnds() {
        assertIs<Outcome.Accepted>(check(nowS = nbf - Tickets.SKEW_S))
        assertEquals(Reason.EARLY, refusal(check(nowS = nbf - Tickets.SKEW_S - 1)))
        assertIs<Outcome.Accepted>(check(nowS = exp + Tickets.SKEW_S))
        assertEquals(Reason.EXPIRED, refusal(check(nowS = exp + Tickets.SKEW_S + 1)))
    }

    @Test
    fun anUploadBudgetIsCarriedWhenClaimedAndAbsentOtherwise() {
        assertEquals(1_024L, assertIs<Outcome.Accepted>(check(with(fl = Tickets.FL_FILES, ab = 1_024))).ticket.ab)
        assertEquals(0L, assertIs<Outcome.Accepted>(check(with(fl = Tickets.FL_FILES, ab = 0))).ticket.ab)
        assertNull(assertIs<Outcome.Accepted>(check()).ticket.ab)
    }

    @Test
    fun aTicketForAnotherSpoolIsRefused() {
        assertEquals(Reason.AUDIENCE, refusal(check(aud = "other.example")))
    }

    @Test
    fun aTicketFromAnotherKeyIsRefusedByItsKeyIdBeforeAnySignatureWork() {
        val stranger = Tickets.generateSigningKey()
        val (tk, sig) = Tickets.issue(stranger, fixture().let { Ticket.forKey(stranger, it.aud, it.nbf, it.exp, it.ps, it.fl) })
        assertEquals(Reason.KEY, refusal(Tickets.check(tk, sig, listOf(key to kid), "lab.example", nbf)))
    }

    @Test
    fun aTicketClaimingOurKeyIdButSignedByAnotherIsASignatureFailure() {
        val stranger = Tickets.generateSigningKey()
        val (tk, sig) = Tickets.issue(stranger, fixture())
        assertEquals(Reason.SIGNATURE, refusal(Tickets.check(tk, sig, listOf(key to kid), "lab.example", nbf)))
    }

    @Test
    fun oneFlippedByteAnywhereIsRefused() {
        val flipSig = check(tamper = { tk, sig -> tk to sig.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } })
        assertEquals(Reason.SIGNATURE, refusal(flipSig))
        // The last byte of tk is fl's value: still valid CBOR, so it reaches the signature check.
        val flipBody = check(tamper = { tk, sig -> tk.copyOf().also { it[it.lastIndex] = 0 } to sig })
        assertEquals(Reason.SIGNATURE, refusal(flipBody))
    }

    @Test
    fun shapesNoHonestIssuerProducesAreMalformed() {
        val cases =
            mapOf(
                "another version" to with(v = 2),
                "a short key id" to with(kid = kid.copyOf(4)),
                "a short pseudonym" to with(ps = ByteArray(8)),
                "a negative nbf" to with(nbf = -1, exp = 10),
                "exp before nbf" to with(exp = nbf - 1),
                "a window over two days" to with(exp = nbf + Tickets.MAX_LIFETIME_S + 1),
                "negative flags" to with(fl = -1),
                "a negative upload budget" to with(ab = -1),
            )
        cases.forEach { (name, ticket) -> assertEquals(Reason.MALFORMED, refusal(check(ticket)), name) }
    }

    @Test
    fun extremeButWellFormedTimesNeitherOverflowNorPass() {
        val far = with(nbf = Long.MAX_VALUE - 10, exp = Long.MAX_VALUE)
        assertEquals(Reason.EARLY, refusal(check(far)))
        assertEquals(Reason.EXPIRED, refusal(check(with(nbf = 0, exp = 10))))
    }

    @Test
    fun undecodableOrOversizedInputIsMalformed() {
        assertEquals(Reason.MALFORMED, refusal(check(tamper = { _, sig -> byteArrayOf(0x01) to sig })))
        assertEquals(Reason.MALFORMED, refusal(check(tamper = { _, sig -> ByteArray(Tickets.MAX_TK_BYTES + 1) to sig })))
        assertEquals(Reason.MALFORMED, refusal(check(tamper = { tk, sig -> tk to sig.copyOf(32) })))
    }

    @Test
    fun aPublicKeyMustBeAUsablePoint() {
        assertFailsWith<GeneralSecurityException> { Tickets.publicKey(ByteArray(31)) }
        // y = 2 has no x on the curve; the JDK only notices when a verifier is initialised.
        assertFailsWith<GeneralSecurityException> { Tickets.publicKey(ByteArray(32).also { it[0] = 2 }) }
    }

    @Test
    fun aTicketRoundTripsAsOnePasteSafeLine() {
        val (tk, sig) = Tickets.issue(SIGNING_KEY, fixture())
        val text = Tickets.encodeText(tk, sig)
        assertTrue(text.startsWith("knit-ticket:v1:"), text)
        assertTrue(text.none { it == '\n' || it == '=' || it == '+' || it == '/' }, "paste-safe, unpadded: $text")
        val (tk2, sig2) = assertNotNull(Tickets.decodeText("  $text\n"))
        assertContentEquals(tk, tk2)
        assertContentEquals(sig, sig2)
        assertNull(Tickets.decodeText(text.removePrefix("knit-ticket:v1:")))
        assertNull(Tickets.decodeText("knit-ticket:v1:not base64!"))
        assertNull(Tickets.decodeText(""))
    }

    @Test
    fun aSigningKeyRoundTripsAndAMismatchedPairIsRefused() {
        val text = Tickets.encodeSigningKey(SIGNING_KEY)
        assertTrue(text.startsWith("knit-ticket-key:v1:"), text)
        assertContentEquals(SIGNING_KEY, Tickets.decodeSigningKey(text))
        assertContentEquals(RFC_PUBLIC, Tickets.publicHalf(SIGNING_KEY))
        val spliced = SIGNING_KEY.copyOf(Tickets.KEY_BYTES) + Tickets.publicHalf(Tickets.generateSigningKey())
        assertNull(Tickets.decodeSigningKey(Tickets.encodeSigningKey(spliced)), "a seed with someone else's public half")
        assertNull(Tickets.decodeSigningKey(Tickets.encodeSigningKey(SIGNING_KEY).dropLast(4)))
        assertNull(Tickets.decodeSigningKey("knit-commons:v1:AAAA"))
    }

    @Test
    fun aFreshKeyIsSeedThenPublicAndSignsWhatItsPublicHalfVerifies() {
        val fresh = Tickets.generateSigningKey()
        assertEquals(2 * Tickets.KEY_BYTES, fresh.size)
        assertNotNull(Tickets.decodeSigningKey(Tickets.encodeSigningKey(fresh)), "seed and public half must belong together")
    }

    /**
     * Mid-rotation a spool holds two keys (`SPOOL_TICKET_KEY_NEXT`, ADR 2026-10.x9gq): the ticket's
     * `kid` picks one, a ticket naming neither is still `key`, and a ticket naming one key but
     * signed by the other is a signature failure, never a second try against the other key.
     */
    @Test
    fun theKidPicksWhichOfTwoKeysVerifies() {
        val nextSigning = Tickets.generateSigningKey()
        val nextPublic = Tickets.publicHalf(nextSigning)
        val keys = listOf(key to kid, Tickets.publicKey(nextPublic) to Tickets.kid(nextPublic))
        val base = fixture()

        val (oldTk, oldSig) = Tickets.issue(SIGNING_KEY, base)
        assertIs<Outcome.Accepted>(Tickets.check(oldTk, oldSig, keys, "lab.example", nbf))
        val (nextTk, nextSig) = Tickets.issue(nextSigning, Ticket.forKey(nextSigning, base.aud, base.nbf, base.exp, base.ps, base.fl))
        assertIs<Outcome.Accepted>(Tickets.check(nextTk, nextSig, keys, "lab.example", nbf))

        val stranger = Tickets.generateSigningKey()
        val (strangerTk, strangerSig) = Tickets.issue(stranger, Ticket.forKey(stranger, base.aud, base.nbf, base.exp, base.ps, base.fl))
        assertEquals(Reason.KEY, refusal(Tickets.check(strangerTk, strangerSig, keys, "lab.example", nbf)))

        val (crossTk, crossSig) = Tickets.issue(SIGNING_KEY, Ticket.forKey(nextSigning, base.aud, base.nbf, base.exp, base.ps, base.fl))
        assertEquals(Reason.SIGNATURE, refusal(Tickets.check(crossTk, crossSig, keys, "lab.example", nbf)))
    }
}
