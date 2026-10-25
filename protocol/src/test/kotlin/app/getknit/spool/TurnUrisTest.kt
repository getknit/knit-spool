// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.TurnUris
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A TURN server's UDP URL, host and entry (S-7.6-48), which every side must derive the same way. */
class TurnUrisTest {
    @Test
    fun theUdpUrlIsTurnWithUdpOrNoTransport() {
        assertTrue(TurnUris.isUdp("turn:a.example:3478?transport=udp"))
        assertTrue(TurnUris.isUdp("turn:a.example"), "RFC 7065 §3: UDP is the default")
        assertTrue(TurnUris.isUdp("turn:a.example?transport=UDP"), "ABNF literals are case-insensitive")
        assertFalse(TurnUris.isUdp("turn:a.example:3478?transport=tcp"))
        assertFalse(TurnUris.isUdp("turns:a.example:5349"))
        assertFalse(TurnUris.isUdp("turns:a.example:5349?transport=udp"), "turns: is TLS, whatever it says")
        assertFalse(TurnUris.isUdp("stun:a.example:3478"))
    }

    @Test
    fun theFirstUdpUrlIsTheServers() {
        val urls =
            listOf("turns:a.example:5349?transport=tcp", "turn:a.example:3478?transport=tcp", "turn:a.example:3479", "turn:a.example:3480")
        assertEquals("turn:a.example:3479", TurnUris.udpUrl(urls))
        assertNull(TurnUris.udpUrl(listOf("turns:a.example:5349", "turn:a.example?transport=tcp")))
    }

    /** The contract's two examples, then the edges of the form. */
    @Test
    fun anEntryIsTheLowercasedHostAndThePortAsWrittenOrTheDefault() {
        assertEquals("us-west.turn.getknit.app:3478", TurnUris.entry("turn:US-West.turn.getknit.app?transport=udp"))
        assertEquals("[2001:db8::7]:3479", TurnUris.entry("turn:[2001:DB8::7]:3479"))
        assertEquals("[2001:db8::7]:3478", TurnUris.entry("turn:[2001:db8::7]?transport=udp"))
        assertEquals("203.0.113.5:03478", TurnUris.entry("turn:203.0.113.5:03478"), "the port as written, not as a number")
        assertEquals("a.example:5349", TurnUris.entry("turns:a.example:5349?transport=tcp"))
        assertEquals("a.example:3478", TurnUris.udpEntry(listOf("turns:a.example:5349", "turn:A.example?transport=udp")))
        assertNull(TurnUris.udpEntry(listOf("turns:a.example:5349")))
    }

    @Test
    fun aHostKeepsAnIpv6LiteralsBracketsAndIgnoresCase() {
        assertEquals("[2001:db8::7]", TurnUris.host("turns:[2001:DB8::7]:5349?transport=tcp"))
        assertEquals("a.example", TurnUris.host("turn:A.Example:3478"))
        assertEquals(TurnUris.host("turn:A.example"), TurnUris.host("turns:a.EXAMPLE:5349"))
    }

    /** Each of these passes the daemon's one-server URL rule, and names no host a client could measure. */
    @Test
    fun aUriWithNoReadableHostNamesNone() {
        for (bad in listOf(
            "turn:?transport=udp",
            "turn::3478",
            "turn:[2001:db8::7",
            "turn:[]:3478",
            "turn:a.example:",
            "turn:a.example:34x8",
            "turn:a:b:c",
        )) {
            assertNull(TurnUris.host(bad), bad)
            assertNull(TurnUris.entry(bad), bad)
        }
        assertNull(TurnUris.host("stun:a.example:3478"))
    }
}
