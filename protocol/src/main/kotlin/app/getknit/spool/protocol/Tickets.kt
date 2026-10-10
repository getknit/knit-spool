// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The claims an entitlement ticket carries (experimental; knit-next docs/VOICE_CALLS.md §2.6, ADR
 * 2026-10.bxsg). Encoded with [RecordCodec] and signed as bytes; the spool verifies those bytes, never
 * a re-encoding.
 *
 * Every field is required and none has a default, because [RecordCodec] omits a field equal to its
 * default and the signed bytes must not depend on which defaults a decoder happens to declare.
 * Unknown keys are ignored, so later claims (an upload budget, a creation budget) add without a
 * version bump.
 */
@Serializable
class Ticket(
    /** Always [Tickets.VERSION]. */
    val v: Int,
    /** `SHA-256(raw public key)[0..8]`: which key signed it. */
    val kid: ByteArray,
    /** The one spool this ticket is good for — its `SPOOL_TICKET_AUDIENCE`. */
    val aud: String,
    /** Unix seconds. */
    val nbf: Long,
    /** Unix seconds; at most [Tickets.MAX_LIFETIME_S] after [nbf]. */
    val exp: Long,
    /** The daily pseudonym, [Tickets.PS_BYTES] opaque bytes the issuer derives. */
    val ps: ByteArray,
    /** What it entitles: a bitmask of [Tickets.FL_TURN], [Tickets.FL_CAST] and, later, more. */
    val fl: Long,
) {
    companion object {
        /** A current-version ticket for [signingKey], its [kid] filled in from the key's public half. */
        fun forKey(
            signingKey: ByteArray,
            aud: String,
            nbf: Long,
            exp: Long,
            ps: ByteArray,
            fl: Long,
        ): Ticket =
            Ticket(
                v = Tickets.VERSION,
                kid = Tickets.kid(Tickets.publicHalf(signingKey)),
                aud = aud,
                nbf = nbf,
                exp = exp,
                ps = ps,
                fl = fl,
            )
    }
}

/**
 * Entitlement tickets: an Ed25519 grant a storefront signs and a spool checks with only the public
 * half (experimental; knit-next docs/VOICE_CALLS.md §2.6, ADR 2026-10.bxsg).
 *
 * ```
 * tk  = CBOR(Ticket)
 * sig = Ed25519(signing key, "knit/spool/v1/ticket" ‖ tk)
 * kid = SHA-256(raw public key)[0..8]
 * ```
 *
 * Signing lives here beside checking so the conformance suite and the operator's `ticket-mint` can
 * issue what the daemon accepts without either depending on the daemon. Ed25519 is the JDK's own
 * (JEP 339), so this adds no dependency.
 *
 * A **signing key** travels as `knit-ticket-key:v1:<base64url(seed ‖ public key)>`. The public half
 * rides along because the JDK offers no way to derive it from the seed, and anything that mints needs
 * it for [kid].
 */
object Tickets {
    /** Domain separation for the signature, in the `"knit/spool/v1/…"` idiom of [Pow] and [Commons]. */
    val LABEL = "knit/spool/v1/ticket".toByteArray()

    const val VERSION = 1

    /** [Ticket.fl] bit: the holder may ask for TURN credentials. */
    const val FL_TURN = 1L

    /**
     * [Ticket.fl] bit 3: the holder may cast, and unlocks casts for every other subscriber of each scope
     * its connection holds (VOICE_CALLS.md §2.7). Bits 1 and 2 stay reserved for ADR 2026-10.bxsg's
     * proposed files and scope-creation claims.
     */
    const val FL_CAST = 8L

    const val KEY_BYTES = 32
    const val SIG_BYTES = 64
    const val KID_BYTES = 8
    const val PS_BYTES = 16

    /** Bigger than any honest ticket by a wide margin; refused before it is decoded. */
    const val MAX_TK_BYTES = 512

    /** Clock skew forgiven at either end of a ticket's window. */
    const val SKEW_S = 300L

    /** The longest window a ticket may claim: a daily ticket plus a day of slack for refresh. */
    const val MAX_LIFETIME_S = 172_800L

    private const val ALGORITHM = "Ed25519"
    private const val KEY_TEXT_PREFIX = "knit-ticket-key:v1:"
    private const val TICKET_TEXT_PREFIX = "knit-ticket:v1:"

    /** The fixed DER around a raw Ed25519 public key (RFC 8410 SubjectPublicKeyInfo). */
    private val X509_PREFIX = "302a300506032b6570032100".hexToByteArray()

    /** The fixed DER around a raw Ed25519 seed (RFC 8410 OneAsymmetricKey, v1). */
    private val PKCS8_PREFIX = "302e020100300506032b657004220420".hexToByteArray()

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    /** Why a ticket was refused: the word goes in `err ticket`'s `msg` and in a metrics label. */
    enum class Reason(
        val word: String,
    ) {
        MALFORMED("malformed"),
        KEY("key"),
        SIGNATURE("signature"),
        AUDIENCE("audience"),
        EARLY("early"),
        EXPIRED("expired"),

        /** A `turnreq` on a connection that holds no live ticket. */
        MISSING("missing"),

        /** A live ticket that does not grant what was asked. */
        FLAGS("flags"),
    }

    /** The outcome of [check]: the verified claims, or the first reason they were refused. */
    sealed interface Outcome {
        class Accepted(
            val ticket: Ticket,
        ) : Outcome

        class Refused(
            val reason: Reason,
        ) : Outcome
    }

    /**
     * A raw 32-byte public key as a JDK [PublicKey], ready to verify.
     *
     * The JDK decodes an Ed25519 point lazily, so a signature is initialised here as well: a key the
     * provider cannot use fails at configuration time, not at the first customer's ticket.
     *
     * @throws GeneralSecurityException if [raw] is not a usable Ed25519 public key.
     */
    fun publicKey(raw: ByteArray): PublicKey {
        if (raw.size != KEY_BYTES) throw GeneralSecurityException("an Ed25519 public key is $KEY_BYTES bytes, got ${raw.size}")
        val key = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
        Signature.getInstance(ALGORITHM).initVerify(key)
        return key
    }

    /** `SHA-256(raw public key)[0..8]`. */
    fun kid(rawPublicKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(rawPublicKey).copyOf(KID_BYTES)

    /** A fresh signing key, `seed ‖ public key`. */
    fun generateSigningKey(): ByteArray {
        val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        val seed =
            pair.private.encoded
                .takeLast(KEY_BYTES)
                .toByteArray()
        val public =
            pair.public.encoded
                .takeLast(KEY_BYTES)
                .toByteArray()
        return seed + public
    }

    /** The raw public half of a `seed ‖ public key` signing key. */
    fun publicHalf(signingKey: ByteArray): ByteArray {
        require(signingKey.size == 2 * KEY_BYTES) { "a signing key is ${2 * KEY_BYTES} bytes, got ${signingKey.size}" }
        return signingKey.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)
    }

    /** Ed25519 over [LABEL] ‖ [tk]. Deterministic: the same key and bytes always sign alike. */
    fun sign(
        signingKey: ByteArray,
        tk: ByteArray,
    ): ByteArray {
        require(signingKey.size == 2 * KEY_BYTES) { "a signing key is ${2 * KEY_BYTES} bytes, got ${signingKey.size}" }
        val seed = signingKey.copyOf(KEY_BYTES)
        val private = KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed))
        return Signature.getInstance(ALGORITHM).run {
            initSign(private)
            update(LABEL)
            update(tk)
            sign()
        }
    }

    /** Encodes [ticket] and signs it: the `tk` and `sig` of an `ent`. */
    fun issue(
        signingKey: ByteArray,
        ticket: Ticket,
    ): Pair<ByteArray, ByteArray> {
        val tk = RecordCodec.encode(ticket)
        return tk to sign(signingKey, tk)
    }

    /**
     * Everything a spool asks of an `ent`, in the order that spends least on a ticket it will refuse:
     * sizes and shape, then the key id, then the signature, then the audience and the window.
     *
     * Integers are checked for sign before any arithmetic — a CBOR negative or an unsigned value past
     * `Long.MAX_VALUE` decodes as a negative `Long` — and the window is compared against [nowS] by
     * subtraction, so no claimed value can overflow it.
     */
    fun check(
        tk: ByteArray,
        sig: ByteArray,
        key: PublicKey,
        kid: ByteArray,
        aud: String,
        nowS: Long,
    ): Outcome {
        if (tk.size > MAX_TK_BYTES || sig.size != SIG_BYTES) return Outcome.Refused(Reason.MALFORMED)
        val ticket = RecordCodec.decode<Ticket>(tk)
        if (ticket == null || !wellFormed(ticket)) return Outcome.Refused(Reason.MALFORMED)
        if (!ticket.kid.contentEquals(kid)) return Outcome.Refused(Reason.KEY)
        if (!verify(key, tk, sig)) return Outcome.Refused(Reason.SIGNATURE)
        if (ticket.aud != aud) return Outcome.Refused(Reason.AUDIENCE)
        if (nowS < ticket.nbf - SKEW_S) return Outcome.Refused(Reason.EARLY)
        if (nowS - SKEW_S > ticket.exp) return Outcome.Refused(Reason.EXPIRED)
        return Outcome.Accepted(ticket)
    }

    private fun wellFormed(ticket: Ticket): Boolean =
        ticket.v == VERSION &&
            ticket.kid.size == KID_BYTES &&
            ticket.ps.size == PS_BYTES &&
            ticket.nbf >= 0 &&
            ticket.exp >= ticket.nbf &&
            ticket.exp - ticket.nbf <= MAX_LIFETIME_S &&
            ticket.fl >= 0

    private fun verify(
        key: PublicKey,
        tk: ByteArray,
        sig: ByteArray,
    ): Boolean =
        try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(LABEL)
                update(tk)
                verify(sig)
            }
        } catch (_: GeneralSecurityException) {
            false
        }

    /** `knit-ticket-key:v1:<base64url(seed ‖ public key)>`. */
    fun encodeSigningKey(signingKey: ByteArray): String {
        require(signingKey.size == 2 * KEY_BYTES) { "a signing key is ${2 * KEY_BYTES} bytes, got ${signingKey.size}" }
        return KEY_TEXT_PREFIX + urlEncoder.encodeToString(signingKey)
    }

    /** Parses [encodeSigningKey]'s output, or null. A key whose halves do not match is refused too. */
    fun decodeSigningKey(text: String): ByteArray? {
        val key = unwrap(text, KEY_TEXT_PREFIX)?.takeIf { it.size == 2 * KEY_BYTES } ?: return null
        val public =
            try {
                publicKey(publicHalf(key))
            } catch (_: GeneralSecurityException) {
                return null
            }
        // A signing key is two halves pasted together; one from a different pair would mint
        // tickets no spool configured with the printed public key could ever accept.
        return key.takeIf { verify(public, LABEL, sign(key, LABEL)) }
    }

    /** `knit-ticket:v1:<base64url(CBOR {tk, sig})>`: one paste-safe line to hand a test client. */
    fun encodeText(
        tk: ByteArray,
        sig: ByteArray,
    ): String = TICKET_TEXT_PREFIX + urlEncoder.encodeToString(RecordCodec.encode(TicketText(tk = tk, sig = sig)))

    /** Parses [encodeText]'s output into `tk` and `sig`, or null. Says nothing about validity. */
    fun decodeText(text: String): Pair<ByteArray, ByteArray>? {
        val body = unwrap(text, TICKET_TEXT_PREFIX) ?: return null
        val parsed = RecordCodec.decode<TicketText>(body) ?: return null
        return (parsed.tk to parsed.sig).takeIf { parsed.tk.size <= MAX_TK_BYTES && parsed.sig.size == SIG_BYTES }
    }

    private fun unwrap(
        text: String,
        prefix: String,
    ): ByteArray? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(prefix)) return null
        return runCatching { urlDecoder.decode(trimmed.removePrefix(prefix)) }.getOrNull()
    }

    @Serializable
    private class TicketText(
        val tk: ByteArray,
        val sig: ByteArray,
    )
}
