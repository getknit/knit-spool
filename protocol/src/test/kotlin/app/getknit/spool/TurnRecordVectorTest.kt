// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.TurnCred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TURN credentials at the record layer (knit-next `docs/SPOOL_PROTOCOL.md` §7.6). The §13 vectors
 * are pinned by [SpecVectorTest]; this checks what a HELLO without TURN omits and that the
 * `turncred` vector round-trips.
 */
class TurnRecordVectorTest {
    @Test
    fun aHelloWithoutTurnOmitsTheField() {
        val hello = RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION))
        assertEquals("a261746568656c6c6f617601", hello.toHexString())
        assertNull(RecordCodec.decode<Hello>(hello)?.turn)
    }

    @Test
    fun turnCredRoundTrips() {
        val decoded = RecordCodec.decode<TurnCred>(SpecVectorTest.EXPECTED.getValue("turncred").hexToByteArray())
        assertEquals(7L, decoded?.q)
        assertEquals(2, decoded?.urls?.size)
        assertEquals(1_800_000_000L, decoded?.exp)
    }
}
