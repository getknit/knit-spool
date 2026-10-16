// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Cast
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Casts at the record layer (knit-next `docs/SPOOL_PROTOCOL.md` §7.7). The §13 vectors are pinned by
 * [SpecVectorTest]; this checks what a HELLO without casts omits and that the vector round-trips.
 */
class CastRecordVectorTest {
    @Test
    fun aHelloWithoutCastOmitsTheField() {
        val hello = RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION))
        assertEquals("a261746568656c6c6f617601", hello.toHexString())
        assertNull(RecordCodec.decode<Hello>(hello)?.cast)
    }

    @Test
    fun aCastRoundTripsAndCarriesNoQ() {
        val decoded = RecordCodec.decode<Cast>(SpecVectorTest.EXPECTED.getValue("cast").hexToByteArray())
        assertContentEquals(SCOPE, decoded?.scope)
        assertContentEquals(DATA, decoded?.data)
        assertEquals(RecordType.CAST, RecordCodec.peekType(SpecVectorTest.EXPECTED.getValue("cast").hexToByteArray()))
    }

    private companion object {
        val SCOPE = ByteArray(32) { it.toByte() }
        val DATA = ByteArray(16) { (0xa0 + it).toByte() }
    }
}
