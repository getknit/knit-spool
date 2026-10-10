// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `knit-spool ticket-keygen` and `ticket-mint`: an operator's stand-in for the storefront that will
 * issue entitlement tickets (experimental; knit-next docs/VOICE_CALLS.md §2.6, ADR 2026-10.bxsg), so
 * a field test can tell paying callers from free ones before any storefront exists.
 *
 * The daemon itself never reads a signing key: `serve` and `check` take only the public half. These
 * two commands are the only code paths here that hold the private half, and they print to stdout
 * and nowhere else — never a log line, never a file they did not get handed.
 */
private const val SECONDS_PER_HOUR = 3_600L
private const val DEFAULT_HOURS = 25L
private const val MS_PER_SECOND = 1_000L
private const val LENGTH_PREFIX_BYTES = 2
private const val MAX_ACCOUNT_BYTES = 255

/** The pseudonym derivation's domain separation, distinct from every spool-side label. */
private val PS_LABEL = "knit/anywhere/v1/ps".toByteArray()

private val TICKET_AUD = Regex("^[a-z0-9.-]{1,253}$")

private const val MINT_USAGE =
    "usage: knit-spool ticket-mint --key-file PATH --aud HOST [--account NAME] [--hours 25] [--no-turn] [--cast]"

/** Mints a signing key and prints both halves: the private half for the issuer, the public for the spool. */
internal fun ticketKeygen(out: (String) -> Unit) {
    val signingKey = Tickets.generateSigningKey()
    val public = Base64.getUrlEncoder().withoutPadding().encodeToString(Tickets.publicHalf(signingKey))
    out("signing key (keep secret; ticket-mint reads it from a file):  ${Tickets.encodeSigningKey(signingKey)}")
    out("spool config (put in env):                                     SPOOL_TICKET_KEY=$public")
}

/**
 * Mints one ticket and prints it as `knit-ticket:v1:…`; returns the exit code.
 *
 * The key comes from a file only, never a flag value: argv is readable by every local user for the
 * life of the process (the conformance CLI's `readTokenFile` reasoning).
 *
 * With `--account`, the pseudonym is what a storefront would derive — the same account on the same
 * UTC day always gets the same one, a new day a new one:
 * `HMAC-SHA256(seed, "knit/anywhere/v1/ps" ‖ lp(account) ‖ lp(aud) ‖ be64(utcDay))[0..16]`, each
 * variable part behind a two-byte length so no two inputs run together. The seed doubles as the HMAC
 * key here, separated by the label; a real storefront may hold a separate secret. Without
 * `--account` the pseudonym is random, as for a one-off test caller.
 */
internal fun ticketMint(
    args: List<String>,
    nowMs: Long,
    out: (String) -> Unit,
    err: (String) -> Unit,
): Int {
    val options = parseMintArgs(args) ?: return usage(err)
    val keyFile = options["--key-file"] ?: return usage(err)
    val aud = options["--aud"] ?: return usage(err)
    if (!TICKET_AUD.matches(aud)) return fail(err, "--aud must be a lowercase host name, got \"$aud\"")
    val hours = options["--hours"]?.toLongOrNull() ?: DEFAULT_HOURS
    if (hours !in 1..Tickets.MAX_LIFETIME_S / SECONDS_PER_HOUR) {
        return fail(err, "--hours must be 1-${Tickets.MAX_LIFETIME_S / SECONDS_PER_HOUR}")
    }
    val signingKey =
        try {
            Tickets.decodeSigningKey(Files.readString(Path.of(keyFile)))
        } catch (e: IOException) {
            return fail(err, "cannot read --key-file $keyFile: ${e.message ?: e::class.simpleName}")
        } ?: return fail(err, "--key-file $keyFile does not hold a knit-ticket-key:v1: signing key")
    val account = options["--account"]
    if (account != null && account.toByteArray().size !in 1..MAX_ACCOUNT_BYTES) {
        return fail(err, "--account must be 1-$MAX_ACCOUNT_BYTES bytes")
    }
    val nowS = nowMs / MS_PER_SECOND
    val ps = account?.let { pseudonym(signingKey, it, aud, Math.floorDiv(nowS, SECONDS_PER_HOUR * 24)) } ?: randomPseudonym()
    // TURN unless refused; casts only when asked, since the walkie relay is sold on its own.
    val turn = if ("--no-turn" in options) 0L else Tickets.FL_TURN
    val fl = turn or if ("--cast" in options) Tickets.FL_CAST else 0L
    val ticket = Ticket.forKey(signingKey, aud = aud, nbf = nowS, exp = nowS + hours * SECONDS_PER_HOUR, ps = ps, fl = fl)
    val (tk, sig) = Tickets.issue(signingKey, ticket)
    out(Tickets.encodeText(tk, sig))
    return 0
}

/** `HMAC-SHA256(seed, label ‖ lp(account) ‖ lp(aud) ‖ be64(day))[0..16]`; see [ticketMint]. */
internal fun pseudonym(
    signingKey: ByteArray,
    account: String,
    aud: String,
    utcDay: Long,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(signingKey.copyOf(Tickets.KEY_BYTES), "HmacSHA256"))
    mac.update(PS_LABEL)
    for (part in listOf(account.toByteArray(), aud.toByteArray())) {
        mac.update(ByteBuffer.allocate(LENGTH_PREFIX_BYTES).putShort(part.size.toShort()).array())
        mac.update(part)
    }
    return mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(utcDay).array()).copyOf(Tickets.PS_BYTES)
}

private fun randomPseudonym(): ByteArray = ByteArray(Tickets.PS_BYTES).also(SecureRandom()::nextBytes)

/** `--flag value` pairs plus the bare `--no-turn` and `--cast`, or null on anything else. */
private fun parseMintArgs(args: List<String>): Map<String, String>? {
    val options = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        when (val flag = args[i]) {
            "--no-turn", "--cast" -> options[flag] = ""
            "--key-file", "--aud", "--account", "--hours" -> options[flag] = args.getOrNull(++i) ?: return null
            else -> return null
        }
        i++
    }
    return options
}

private fun usage(err: (String) -> Unit): Int {
    err(MINT_USAGE)
    return 2
}

private fun fail(
    err: (String) -> Unit,
    message: String,
): Int {
    err(message)
    return 1
}
