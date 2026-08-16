// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

/**
 * The per-scope set digest, SPOOL_PROTOCOL.md §6.3: XOR fold of FNV-1a-64 over the raw 32-byte blob
 * ids. Order-independent and self-inverse (add/remove are one XOR); the empty set digests to 0.
 * Wire form: 8 bytes big-endian, as a byte string.
 */
object ScopeDigest {
    const val DIGEST_BYTES = 8

    private const val FNV64_OFFSET = -0x340D631B7BDDDCDBL // 0xcbf29ce484222325
    private const val FNV64_PRIME = 0x100000001B3L
    private const val BYTE_MASK = 0xFFL

    fun fnv64(bytes: ByteArray): Long {
        var h = FNV64_OFFSET
        for (b in bytes) {
            h = h xor (b.toLong() and BYTE_MASK)
            h *= FNV64_PRIME
        }
        return h
    }

    fun fold(blobIds: Iterable<ByteArray>): Long = blobIds.fold(0L) { acc, id -> acc xor fnv64(id) }

    fun toBytes(digest: Long): ByteArray =
        ByteArray(DIGEST_BYTES) { (digest ushr ((DIGEST_BYTES - 1 - it) * Byte.SIZE_BITS)).toByte() }
}
