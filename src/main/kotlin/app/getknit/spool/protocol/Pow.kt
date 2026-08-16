// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

import java.security.MessageDigest

/**
 * Proof-of-work stamp verification, SPOOL_PROTOCOL.md §8: stateless Hashcash over
 * `"knit/spool/v1/pow" ‖ scopeId ‖ u64be(day) ‖ u64be(n)`, valid iff the SHA-256 digest has at
 * least `bits` leading zero bits. The spool only ever verifies; clients mine. A spool accepts
 * `day ∈ {today − 1, today, today + 1}` and caches accepted `(scopeId, day)` pairs (the cache
 * lives in the server layer).
 */
object Pow {
    const val DAY_MS = 86_400_000L

    private val LABEL = "knit/spool/v1/pow".toByteArray()

    fun utcDay(nowMs: Long): Long = Math.floorDiv(nowMs, DAY_MS)

    fun dayInWindow(
        stampDay: Long,
        nowMs: Long,
    ): Boolean = stampDay in (utcDay(nowMs) - 1)..(utcDay(nowMs) + 1)

    fun digest(
        scopeId: ByteArray,
        day: Long,
        n: Long,
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(LABEL + scopeId + u64be(day) + u64be(n))

    fun verify(
        scopeId: ByteArray,
        day: Long,
        n: Long,
        bits: Int,
    ): Boolean = bits <= 0 || leadingZeroBits(digest(scopeId, day, n)) >= bits

    fun leadingZeroBits(digest: ByteArray): Int {
        var bits = 0
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += Byte.SIZE_BITS
            } else {
                bits += Integer.numberOfLeadingZeros(v) - (Integer.SIZE - Byte.SIZE_BITS)
                break
            }
        }
        return bits
    }

    private fun u64be(value: Long): ByteArray =
        ByteArray(Long.SIZE_BYTES) { (value ushr ((Long.SIZE_BYTES - 1 - it) * Byte.SIZE_BITS)).toByte() }
}
