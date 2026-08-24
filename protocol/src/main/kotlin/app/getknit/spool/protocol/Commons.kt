// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

import java.security.MessageDigest
import java.util.Base64

/**
 * The commons invite, SPOOL_PROTOCOL.md §7.4. A commons is one shared scope per spool: members who
 * hold the invite can talk to everyone else on that spool, and the spool relays their sealed frames
 * exactly as it relays a private conversation's.
 *
 * The invite is a 32-byte secret, and it splits in two:
 *
 *  - the **scope id**, `SHA-256("knit/spool/v1/commons" ‖ secret)` — public, and the only half the
 *    spool is ever given ([SPOOL_COMMONS_ID][scopeId]);
 *  - the **content key**, derived under the label `"knit/spool/v1/commons-key"` — private to
 *    members, and *deliberately not implemented here*.
 *
 * That omission is the point. The daemon in this repo has no code path that can turn a secret into
 * a content key, so "the spool cannot read its own commons" is a structural property rather than a
 * promise: an operator holds a hash of the secret and nothing else. Key derivation belongs to the
 * client, and is normative in the Knit repo's spec.
 */
object Commons {
    /** Domain separation, matching the `"knit/spool/v1/pow"` idiom in [Pow]. */
    private val SCOPE_LABEL = "knit/spool/v1/commons".toByteArray()

    const val SECRET_BYTES = 32

    private const val PREFIX = "knit-commons:v1:"

    /** The public half of an invite: what an operator puts in `SPOOL_COMMONS_ID`. */
    fun scopeId(secret: ByteArray): ByteArray {
        require(secret.size == SECRET_BYTES) { "commons secret must be $SECRET_BYTES bytes, got ${secret.size}" }
        return MessageDigest.getInstance("SHA-256").digest(SCOPE_LABEL + secret)
    }

    /** `knit-commons:v1:<base64url, unpadded>` — one line, paste-safe, no separator collisions. */
    fun encodeInvite(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES) { "commons secret must be $SECRET_BYTES bytes, got ${secret.size}" }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }

    /** Parses [encodeInvite]'s output, or null if it is not one — a typo must not become a scope id. */
    fun decodeInvite(invite: String): ByteArray? {
        val body = invite.trim().removePrefix(PREFIX)
        if (body.length == invite.trim().length) return null
        val secret = runCatching { Base64.getUrlDecoder().decode(body) }.getOrNull() ?: return null
        return secret.takeIf { it.size == SECRET_BYTES }
    }
}
