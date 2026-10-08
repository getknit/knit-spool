// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The coturn REST credential, pinned to the draft spec's vector (knit-next `docs/VOICE_CALLS.md` §2.2). */
class TurnCredentialsTest {
    private fun fixture(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    @Test
    fun theDraftVectorHolds() {
        val user = TurnCredentials.user(1_800_000_000L, fixture(TurnCredentials.SUFFIX_BYTES, 11))
        assertEquals("1800000000:bmjbsibhfy2tyq2k", user)
        assertEquals("yV18C+LcL6biIfL7ZVOuV9xVuoU=", TurnCredentials.pass("knit-turn-test-secret", user))
    }

    /** RFC 4648 base32, lowercased and unpadded — values cross-checked against Python's `base64.b32encode`. */
    @Test
    fun base32MatchesRfc4648() {
        assertEquals("0:mzxw6ytboi4tgmzt", TurnCredentials.user(0L, "foobar9333".toByteArray()))
        assertEquals("0:aaaaaaaaaaaaaaaa", TurnCredentials.user(0L, ByteArray(TurnCredentials.SUFFIX_BYTES)))
        assertEquals("0:7777777777777777", TurnCredentials.user(0L, ByteArray(TurnCredentials.SUFFIX_BYTES) { -1 }))
    }

    @Test
    fun aSuffixOfTheWrongLengthIsRefused() {
        assertFailsWith<IllegalArgumentException> { TurnCredentials.user(0L, ByteArray(9)) }
    }
}
