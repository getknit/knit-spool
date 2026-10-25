// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

import java.security.MessageDigest
import java.util.Base64

/**
 * The commons invite, SPOOL_PROTOCOL.md §7.4. A commons is a shared scope on a spool, which may run
 * several (each one a *room*): members who hold a room's invite can talk to everyone else in it, and
 * the spool relays their sealed frames exactly as it relays a private conversation's.
 *
 * The invite is a 32-byte secret, and it splits in two:
 *
 *  - the **scope id**, `SHA-256("knit/spool/v1/commons" ‖ secret)` — public, and the only half the
 *    spool is ever given ([SPOOL_COMMONS_ID][scopeId]);
 *  - the **content key**, derived under the label `"knit/spool/v1/commons-key"` — private to
 *    members, and *deliberately not implemented here*.
 *
 * A third value, the room's [tag], is derived from the scope id rather than the secret, so the spool
 * can compute it: it is how `hello` lets a member find its own room without the spool publishing the id.
 *
 * That omission is the point. The daemon in this repo has no code path that can turn a secret into
 * a content key, so "the spool cannot read its own commons" is a structural property rather than a
 * promise: an operator holds a hash of the secret and nothing else. Key derivation belongs to the
 * client, and is normative in the Knit repo's spec.
 */
object Commons {
    /** Domain separation, matching the `"knit/spool/v1/pow"` idiom in [Pow]. */
    private val SCOPE_LABEL = "knit/spool/v1/commons".toByteArray()

    /** The tag's label (B-7.4-16): a bare hash of the *id*, which the spool holds, not of the secret. */
    private val TAG_LABEL = "knit/spool/v1/commons-tag".toByteArray()

    const val SECRET_BYTES = 32

    /** A tag's width (§12.1). It only sets the odds of an accidental match; no width inverts a random id. */
    const val TAG_BYTES = 8

    private const val PREFIX = "knit-commons:v1:"

    /** The public half of an invite: what an operator puts in `SPOOL_COMMONS_ID`. */
    fun scopeId(secret: ByteArray): ByteArray {
        require(secret.size == SECRET_BYTES) { "commons secret must be $SECRET_BYTES bytes, got ${secret.size}" }
        return MessageDigest.getInstance("SHA-256").digest(SCOPE_LABEL + secret)
    }

    /**
     * A room's `tag` in `hello`'s `rooms` (B-7.4-16): the first [TAG_BYTES] of
     * `SHA-256("knit/spool/v1/commons-tag" ‖ scopeId)`. One-way, so publishing it lets a member who
     * derived the id match its room and lets nobody else subscribe to it.
     */
    fun tag(scopeId: ByteArray): ByteArray {
        require(scopeId.size == SECRET_BYTES) { "commons scope id must be $SECRET_BYTES bytes, got ${scopeId.size}" }
        return MessageDigest.getInstance("SHA-256").digest(TAG_LABEL + scopeId).copyOf(TAG_BYTES)
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
