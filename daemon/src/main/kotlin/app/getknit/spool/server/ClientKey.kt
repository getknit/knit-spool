// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** Leading bytes of an IPv6 address that make its /64 — the on-link assignment unit. */
private const val IPV6_KEY_BYTES = 8

private const val HEXTET_BYTES = 2

/**
 * The key the per-client limits — [IpState]: the connection cap and the new-scope bucket — are
 * kept under, from the peer address Ktor reports for the connection: the socket peer, or with
 * `trustProxy` the hop the proxy appended to `X-Forwarded-For`.
 *
 * Always derived from the address *literal*, never a name. `origin.remoteAddress` is the literal;
 * `remoteHost` would have been `InetSocketAddress.hostName`, a blocking reverse lookup and forward
 * confirmation on the accepting worker for every connection, tying accept latency to the
 * operator's resolver and sending every client address to it and to the client ISP's PTR servers
 * — which a blinded relay has no business doing. Nothing in here resolves either: an IPv4 literal
 * is its own key untouched, and only a string made of hex digits, colons and dots reaches the
 * literal parser, which the JDK never hands to a resolver. Anything else — CIO's `unknown`, a
 * proxy that forwarded a name — is keyed as written.
 *
 * An IPv6 client is keyed by its /64. A host owns every address in its on-link prefix and rotates
 * through them by design (SLAAC privacy extensions), so a per-address cap on IPv6 is no cap at
 * all; a /64 is the unit a network hands one subscriber, the way one IPv4 address is. A shared
 * /64 sees the same knob carrier-grade NAT already does: `SPOOL_MAX_CONNS_PER_IP`. An IPv4-mapped
 * address (`::ffff:a.b.c.d`) is the IPv4 client it wraps, and a zone id (`%eth0`) is not part of
 * a peer's identity.
 */
internal fun clientKey(remoteAddress: String): String {
    if (':' !in remoteAddress) return remoteAddress
    val literal = remoteAddress.substringBefore('%')
    if (literal.isEmpty() || !literal.all { it.isIpv6LiteralChar() }) return remoteAddress
    val parsed =
        try {
            InetAddress.getByName(literal)
        } catch (_: UnknownHostException) {
            return remoteAddress
        }
    if (parsed !is Inet6Address) return parsed.hostAddress
    val bytes = parsed.address
    return (0 until IPV6_KEY_BYTES step HEXTET_BYTES).joinToString(":", postfix = "::/64") { i ->
        hextet(bytes[i], bytes[i + 1])
    }
}

private fun Char.isIpv6LiteralChar(): Boolean = this == ':' || this == '.' || isHexDigit()

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

@Suppress("MagicNumber") // byte-to-hextet arithmetic
private fun hextet(
    hi: Byte,
    lo: Byte,
): String = (((hi.toInt() and 0xff) shl 8) or (lo.toInt() and 0xff)).toString(16)
