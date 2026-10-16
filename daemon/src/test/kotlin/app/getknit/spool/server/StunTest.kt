// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.RecordCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The STUN list in `hello` (experimental; knit-next `docs/VOICE_CALLS.md` §2.6, S-7.6-13). It is
 * what lets a phone with no TURN of its own learn its public address, so a paying peer's relay can
 * permit it. It needs no secret and no subscription: every client that connects is told.
 */
class StunTest {
    @Test
    fun helloOmitsStunUnlessConfigured() {
        withServer(testConfig()) {
            connect {
                val hello = helloHandshake()
                assertNull(hello.stun)
                assertFalse(
                    RecordCodec.encode(hello).containsBytes("stun".toByteArray()),
                    "the server hello carried a stun key on a spool that advertises none",
                )
            }
        }
    }

    @Test
    fun helloCarriesTheListToAnUnauthenticatedClientWithoutTurn() {
        val urls = listOf("stun:spool.example:3478", "stuns:spool.example:5349")
        withServer(testConfig(stunUrls = urls)) {
            connect {
                val hello = helloHandshake()
                assertEquals(urls, hello.stun)
                assertNull(hello.turn, "STUN stands alone: no TURN is configured here")
            }
        }
    }
}
