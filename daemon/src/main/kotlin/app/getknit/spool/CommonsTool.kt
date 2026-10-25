// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.RelayInvite
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val INVITE_USAGE =
    "usage: knit-spool commons-invite [--room N] [--url wss://HOST/spool/v1 [--token-file PATH]] [--name NAME]"

/**
 * `knit-spool commons-invite`: mints a room's invite and prints both halves — the secret for members,
 * and the hash of it for the room's `SPOOL_COMMONS_ID` (or `SPOOL_COMMONS_<n>_ID` with `--room`).
 * Returns the exit code.
 *
 * With `--url` it also prints the `https://getknit.app/r#…` link the Knit repo's
 * `docs/RELAY_INVITE.md` asks for, which joins the relay and the room in one tap and carries the
 * room's `--name`. The link is the only way a name reaches members of a spool with no token, whose
 * `hello` leaves names out (spec S-7.4-19). A private spool's token goes into the link, as that
 * document intends, but it comes from `--token-file` and never from the URL: argv is readable by every
 * local user for the life of the process (the conformance CLI's `readTokenFile` reasoning).
 *
 * Written to stdout and nowhere else — never logged, never persisted. The daemon that later serves
 * the room is given only the hash, so it never holds the key to the room it relays.
 */
internal fun commonsInvite(
    args: List<String>,
    secret: ByteArray,
    out: (String) -> Unit,
    err: (String) -> Unit,
): Int {
    val options = parseInviteArgs(args) ?: return inviteUsage(err)
    val lines =
        try {
            inviteLines(options, secret)
        } catch (e: IllegalArgumentException) {
            err(e.message ?: "invalid arguments")
            return 1
        }
    lines.forEach(out)
    return 0
}

/** What [commonsInvite] prints, checked whole before any of it is: a half-printed invite is worse than none. */
private fun inviteLines(
    options: Map<String, String>,
    secret: ByteArray,
): List<String> {
    val room = options["--room"]?.let { it.toIntOrNull() ?: 0 } ?: 1
    require(room in 1..MAX_COMMONS_ROOMS) { "--room must be 1-$MAX_COMMONS_ROOMS" }
    // The phone's rule for a room's name, link or not: it would clamp a longer one, and the operator
    // would see a different title than the one they typed.
    val name = options["--name"]?.also(RelayInvite::requireName)
    val url = options["--url"]
    require(url != null || "--token-file" !in options) { "--token-file needs --url" }
    require(url == null || !Regex("[?&]k=").containsMatchIn(url)) {
        "--url must not carry the token: argv is readable by every local user; use --token-file"
    }
    val link = url?.let { RelayInvite.mint(withToken(it, options["--token-file"]), secret, name) }
    val prefix = if (room == 1) "SPOOL_COMMONS_" else "SPOOL_COMMONS_${room}_"
    return listOfNotNull(
        "invite (give this to members):  ${Commons.encodeInvite(secret)}",
        link?.let { "link (give this to members):    $it" },
        "spool config (put in env):      ${prefix}ID=${Commons.scopeId(secret).toHexString()}",
        name?.let { "                                ${prefix}NAME=$it" },
    )
}

/** [url] with `?k=` and the token from [tokenFile] appended, or [url] alone for a public spool. */
private fun withToken(
    url: String,
    tokenFile: String?,
): String {
    if (tokenFile == null) return url
    val token =
        try {
            Files.readString(Path.of(tokenFile)).trim()
        } catch (e: IOException) {
            throw IllegalArgumentException("cannot read --token-file $tokenFile: ${e.message ?: e::class.simpleName}", e)
        }
    require(token.isNotEmpty()) { "--token-file $tokenFile is empty" }
    return "$url${if ('?' in url) '&' else '?'}k=$token"
}

/** `--flag value` pairs, or null on anything else. */
private fun parseInviteArgs(args: List<String>): Map<String, String>? {
    val options = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        when (val flag = args[i]) {
            "--room", "--url", "--token-file", "--name" -> options[flag] = args.getOrNull(++i) ?: return null
            else -> return null
        }
        i++
    }
    return options
}

private fun inviteUsage(err: (String) -> Unit): Int {
    err(INVITE_USAGE)
    return 2
}
