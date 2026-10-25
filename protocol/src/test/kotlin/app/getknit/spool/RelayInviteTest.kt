// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.RelayInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The relay invite link (the Knit repo's `docs/RELAY_INVITE.md`) is minted here and parsed by the
 * phone, so the bytes are the contract: `fullInvite` is that document's golden vector, and every
 * refusal is something the phone would refuse or silently clamp.
 */
class RelayInviteTest {
    private fun fixture(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    @Test
    fun theFullInviteMatchesTheGoldenVector() {
        val link = RelayInvite.mint("wss://home.example.org/spool/v1?k=fixture-token", fixture(32, 10), "Home")
        assertEquals(
            "https://getknit.app/r#" +
                "pGF2AWF1eC93c3M6Ly9ob21lLmV4YW1wbGUub3JnL3Nwb29sL3YxP2s9Zml4dHVyZS10b2tlbmFjWCAKERgfJi00O0JJUFdeZWxzeoGIj5adpKuy" +
                "ucDHztXc42FuZEhvbWU",
            link,
        )
    }

    @Test
    fun aRoomWithNoNameOmitsTheField() {
        val named = RelayInvite.mint("wss://h.example/spool/v1", fixture(32, 10), "Home")
        val bare = RelayInvite.mint("wss://h.example/spool/v1", fixture(32, 10))
        assertTrue(bare.length < named.length, "no `n` key at all, not an empty one: $bare")
    }

    @Test
    fun whatThePhoneWouldRefuseOrClampIsRefused() {
        val secret = fixture(32, 10)
        assertFailsWith<IllegalArgumentException> { RelayInvite.mint("https://h.example/spool/v1", secret) }
        assertFailsWith<IllegalArgumentException> { RelayInvite.mint("wss://h.example/spool/v1 ", secret) }
        assertFailsWith<IllegalArgumentException> { RelayInvite.mint("wss://" + "h".repeat(RelayInvite.MAX_URL_CHARS), secret) }
        assertFailsWith<IllegalArgumentException> { RelayInvite.mint("wss://h.example/spool/v1", secret, "a".repeat(33)) }
        assertFailsWith<IllegalArgumentException> { RelayInvite.mint("wss://h.example/spool/v1", secret, "two\nlines") }
        assertFailsWith<IllegalArgumentException> { RelayInvite.mint("wss://h.example/spool/v1", secret, " ") }
        assertFailsWith<IllegalArgumentException> { RelayInvite.mint("wss://h.example/spool/v1", ByteArray(Commons.SECRET_BYTES - 1)) }
        // At the caps it mints: a 32-character name and a 256-character URL fit the 512-byte body.
        RelayInvite.mint("wss://" + "h".repeat(RelayInvite.MAX_URL_CHARS - 6), secret, "a".repeat(RelayInvite.MAX_NAME_CHARS))
    }
}
