// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Leading bytes [shortHex] keeps — 32 bits, enough to follow one scope across a log stream. */
private const val SHORT_HEX_BYTES = 4

/**
 * A scope id as it is allowed to appear in a log line: the first [SHORT_HEX_BYTES] bytes as hex,
 * with an ellipsis when anything was dropped.
 *
 * A spool is blinded by design; the aggregator an operator ships these lines to is not. It retains
 * them, indexes them, and outlives the scope — so a full 32-byte id logged at INFO or WARN is the
 * one identifier this daemon holds that survives contact with the outside world. Those levels carry
 * a prefix and the full id stays behind DEBUG. Eight hex characters still follow one scope across a
 * single stream, and are far too few to match against anything else.
 *
 * Deliberately not the hot-path encoder: the full-width `hex` in the server and the store stay where
 * they are, one of them with a measured reason.
 */
internal fun shortHex(bytes: ByteArray): String {
    val kept = minOf(bytes.size, SHORT_HEX_BYTES)
    val out = CharArray(kept * 2)
    for (i in 0 until kept) {
        val v = bytes[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
    }
    return if (kept < bytes.size) String(out) + "…" else String(out)
}
