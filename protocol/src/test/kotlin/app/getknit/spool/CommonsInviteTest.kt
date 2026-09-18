// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The commons invite (§7.4) is the one secret an operator pastes by hand, so the parser is strict
 * where a typo could otherwise become a valid-looking scope id — every malformed input is null,
 * never a "close enough" secret — and the scope id derived from it is the public half, computed
 * under the labelled hash rather than being the secret in disguise.
 */
class CommonsInviteTest {
    private val secret = ByteArray(Commons.SECRET_BYTES) { (it * 3 + 1).toByte() }

    @Test
    fun anInviteRoundTripsAndIsOneLineOfUrlSafeBase64() {
        val invite = Commons.encodeInvite(secret)
        assertTrue(invite.startsWith("knit-commons:v1:"), invite)
        assertTrue(invite.none { it == '\n' || it == '=' || it == '+' || it == '/' }, "paste-safe, unpadded: $invite")
        assertContentEquals(secret, Commons.decodeInvite(invite))
        // Surrounding whitespace is what a copy-paste adds; it must not turn a good invite bad.
        assertContentEquals(secret, Commons.decodeInvite("  $invite\n"))
    }

    @Test
    fun anythingThatIsNotAnInviteDecodesToNull() {
        val body = Commons.encodeInvite(secret).removePrefix("knit-commons:v1:")
        assertNull(Commons.decodeInvite(body), "the bare secret without its prefix")
        assertNull(Commons.decodeInvite("knit-commons:v2:$body"), "another version")
        assertNull(Commons.decodeInvite("knit-commons:v1:not base64!"), "undecodable body")
        assertNull(Commons.decodeInvite("knit-commons:v1:" + body.dropLast(4)), "a secret of the wrong length")
        assertNull(Commons.decodeInvite(""), "nothing at all")
    }

    @Test
    fun theScopeIdIsTheLabelledHashOfTheSecretNotTheSecret() {
        val expected = MessageDigest.getInstance("SHA-256").digest("knit/spool/v1/commons".toByteArray() + secret)
        val scopeId = Commons.scopeId(secret)
        assertContentEquals(expected, scopeId)
        assertEquals(32, scopeId.size)
        assertTrue(!scopeId.contentEquals(secret) && !scopeId.contentEquals(MessageDigest.getInstance("SHA-256").digest(secret)))
    }

    @Test
    fun aSecretOfTheWrongLengthIsRefusedBeforeItBecomesAnything() {
        val short = ByteArray(Commons.SECRET_BYTES - 1)
        assertFailsWith<IllegalArgumentException> { Commons.scopeId(short) }
        assertFailsWith<IllegalArgumentException> { Commons.encodeInvite(short) }
    }
}
