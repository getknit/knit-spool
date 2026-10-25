// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

/**
 * How SPOOL_PROTOCOL.md §7.6 names a TURN server by its URLs (S-7.6-48): which of them is its UDP
 * URL, the host it is on, and the `host:port` entry `hello`'s `turnHosts` carries and `turnreq`'s
 * `host` repeats. RFC 7065's grammar, read leniently: a URI this cannot read names no host, and the
 * caller decides whether that is a refusal.
 *
 * The entry is a server's identity on the wire. A client measures the entry's host and port, names
 * the entry it ranks best, and reads which server a credential is for from the entry of its URLs, so
 * every side must derive it exactly as this does.
 */
object TurnUris {
    /** RFC 7065 §3: the port a `turn:` URI means when it names none. */
    const val DEFAULT_PORT = "3478"

    /** RFC 3986's `port` is any run of digits; five is the most a port number needs. */
    private val PORT = Regex("[0-9]{1,5}")

    /**
     * Whether [uri] is a UDP URL: `turn:`, not `turns:`, with a `transport` of `udp` or none at all
     * (RFC 7065 §3: UDP is the default). ABNF literals are case-insensitive, so `UDP` is UDP.
     */
    fun isUdp(uri: String): Boolean {
        if (!uri.startsWith("turn:")) return false
        val transport =
            uri
                .substringAfter('?', missingDelimiterValue = "")
                .split('&')
                .firstOrNull { it.startsWith("transport=", ignoreCase = true) }
                ?.substringAfter('=')
        return transport == null || transport.equals("udp", ignoreCase = true)
    }

    /** The first of [urls] that [isUdp], or null: a server's UDP URL. */
    fun udpUrl(urls: List<String>): String? = urls.firstOrNull(::isUdp)

    /**
     * [uri]'s host, lowercased, with an IPv6 literal's brackets kept, or null when it names none.
     * Two URLs are on the same host when these are equal.
     */
    fun host(uri: String): String? = authority(uri)?.first

    /**
     * [uri]'s entry, `host:port`: the [host], then the port as written or [DEFAULT_PORT]. Null when
     * it names no host. `turn:US-West.turn.example?transport=udp` gives
     * `us-west.turn.example:3478`, and `turn:[2001:DB8::7]:3479` gives `[2001:db8::7]:3479`.
     */
    fun entry(uri: String): String? = authority(uri)?.let { (host, port) -> "$host:${port ?: DEFAULT_PORT}" }

    /** The entry of [urls]' UDP URL, or null when they have none or it names no host. */
    fun udpEntry(urls: List<String>): String? = udpUrl(urls)?.let(::entry)

    /** The lowercased host and the port as written (null when absent), or null when [uri] names no host. */
    private fun authority(uri: String): Pair<String, String?>? {
        val rest =
            when {
                uri.startsWith("turns:") -> uri.removePrefix("turns:")
                uri.startsWith("turn:") -> uri.removePrefix("turn:")
                else -> return null
            }.substringBefore('?')
        val (host, tail) =
            if (rest.startsWith('[')) {
                val close = rest.indexOf(']')
                if (close < 0) return null
                rest.substring(0, close + 1) to rest.substring(close + 1)
            } else {
                rest.substringBefore(':') to rest.substring(rest.substringBefore(':').length)
            }
        if (host.isEmpty() || host == "[]") return null
        val port =
            when {
                tail.isEmpty() -> null
                tail.startsWith(':') && PORT.matches(tail.substring(1)) -> tail.substring(1)
                else -> return null
            }
        return host.lowercase() to port
    }
}
