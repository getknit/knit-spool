// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * The relay invite link, the Knit repo's `docs/RELAY_INVITE.md`: one tappable
 * `https://getknit.app/r#…` that carries a spool's URL, a commons room's secret and the room's name,
 * so a member joins the relay and the room on one confirmation instead of pasting two secrets.
 *
 * Mint-only. The phone parses these; the daemon only prints one beside the `knit-commons:v1:…`
 * invite, which that document asks of `knit-spool commons-invite`. Like [Commons] it never leaves the
 * operator's terminal: the secret is in it, and so is the spool's token when the spool is private.
 *
 * The link's optional `i`/`k` pair, a ticket issuer and its key, is never written here: nothing in
 * the daemon holds one.
 */
object RelayInvite {
    const val URL_PREFIX = "https://getknit.app/r#"

    /** A released app refuses a body past this, so a minter refuses to make one. */
    const val MAX_BYTES = 512

    /** `u`'s cap, the phone's `MAX_URL_CHARS`. */
    const val MAX_URL_CHARS = 256

    /** `n`'s cap in UTF-16 units: the phone clamps a room's name like a group title, at 32. */
    const val MAX_NAME_CHARS = 32

    private const val VERSION = 1

    /** The link body, in this key order; absent fields are omitted, as the phone's own encoder does. */
    @Serializable
    private class Body(
        val v: Int,
        val u: String,
        val c: ByteArray? = null,
        val n: String? = null,
    )

    /**
     * The link for [url] and the room [secret], with [name] when the room has one.
     *
     * Refuses rather than repairs: [url] must be `wss://` or `ws://` (the phone dials `ws://` only in a
     * debug build), at most [MAX_URL_CHARS] with no whitespace or control character, and [name] one
     * line of at most [MAX_NAME_CHARS]. The phone would clamp a longer name, so the operator would see a
     * different title than they typed; better to say so here.
     */
    fun mint(
        url: String,
        secret: ByteArray,
        name: String? = null,
    ): String {
        require(secret.size == Commons.SECRET_BYTES) { "commons secret must be ${Commons.SECRET_BYTES} bytes, got ${secret.size}" }
        require(url.startsWith("wss://") || url.startsWith("ws://")) { "the spool URL must start wss:// (or ws:// for a debug build)" }
        require(url.length <= MAX_URL_CHARS) { "the spool URL is ${url.length} characters, over $MAX_URL_CHARS" }
        require(url.none { it.isWhitespace() || it.isISOControl() }) { "the spool URL must not contain whitespace or control characters" }
        name?.let(::requireName)
        val body = RecordCodec.encode(Body(v = VERSION, u = url, c = secret, n = name))
        require(body.size <= MAX_BYTES) { "the invite is ${body.size} bytes, over $MAX_BYTES; shorten the URL or the name" }
        return URL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body)
    }

    /** [mint]'s rule for a room's name: one non-blank line of at most [MAX_NAME_CHARS]. */
    fun requireName(name: String) {
        require(name.isNotBlank() && name.none { it.isISOControl() }) { "the room name must be one non-blank line" }
        require(name.length <= MAX_NAME_CHARS) { "the room name is ${name.length} characters, over $MAX_NAME_CHARS" }
    }
}
