// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TURN credentials in the REST shared-secret shape coturn's `use-auth-secret` verifies
 * (experimental; knit-next `docs/SPOOL_PROTOCOL.md` §7.6):
 *
 * ```
 * user = decimal(exp) ‖ ":" ‖ base32lower(suffix)      // suffix: SUFFIX_BYTES fresh random bytes
 * pass = base64(HMAC-SHA1(secret, UTF8(user)))
 * ```
 *
 * The suffix is random per issue and derived from nothing — not a scope, an address or a token — so
 * a credential links to no conversation, and the TURN server learns no more from it than the expiry.
 * HMAC-SHA1 is what coturn checks, not a choice made here.
 */
object TurnCredentials {
    /** 10 bytes is exactly 16 base32 characters, so the suffix carries no padding. */
    const val SUFFIX_BYTES = 10

    private val TURN_USER_LABEL = "knit/spool/v1/turn-user".toByteArray()

    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private const val BITS_PER_CHAR = 5
    private const val CHAR_MASK = 0x1f
    private const val BYTE_MASK = 0xff
    private const val BITS_PER_BYTE = 8

    fun user(
        expS: Long,
        suffix: ByteArray,
    ): String {
        require(suffix.size == SUFFIX_BYTES) { "a TURN user suffix is $SUFFIX_BYTES bytes, got ${suffix.size}" }
        return "$expS:${base32(suffix)}"
    }

    /**
     * The suffix of a ticketed credential (SPOOL_PROTOCOL.md §7.6, S-7.6-17):
     * `SHA-256("knit/spool/v1/turn-user" ‖ ps)[0..SUFFIX_BYTES]`. The same for every issue under one
     * pseudonym, so coturn's `user-quota` — which counts the part after the colon — counts the holder.
     * Hashed rather than the pseudonym itself, so the TURN server's logs never carry the spool's id.
     */
    fun pseudonymSuffix(ps: ByteArray): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .run {
                update(TURN_USER_LABEL)
                digest(ps)
            }.copyOf(SUFFIX_BYTES)

    fun pass(
        secret: String,
        user: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(user.toByteArray(Charsets.UTF_8)))
    }

    /** RFC 4648 base32, lowercase, unpadded: the suffix sits in a username, where `=` is noise. */
    private fun base32(bytes: ByteArray): String =
        buildString {
            var buffer = 0
            var bits = 0
            for (byte in bytes) {
                buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and BYTE_MASK)
                bits += BITS_PER_BYTE
                while (bits >= BITS_PER_CHAR) {
                    bits -= BITS_PER_CHAR
                    append(BASE32[(buffer shr bits) and CHAR_MASK])
                }
            }
            if (bits > 0) append(BASE32[(buffer shl (BITS_PER_CHAR - bits)) and CHAR_MASK])
        }
}
