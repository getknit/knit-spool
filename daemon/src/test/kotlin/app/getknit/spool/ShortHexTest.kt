// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import kotlin.test.Test
import kotlin.test.assertEquals

/** The log-safe scope-id prefix — what an operator's log aggregator is allowed to keep. */
class ShortHexTest {
    @Test
    fun keepsEightHexCharactersOfAScopeId() {
        assertEquals("01020304…", shortHex(ByteArray(32) { (it + 1).toByte() }))
    }

    @Test
    fun bytesEncodeUnsigned() {
        assertEquals("ff80017f…", shortHex(byteArrayOf(-1, -128, 1, 127, 0)))
    }

    /** The ellipsis is a claim that something was dropped, so it is absent when nothing was. */
    @Test
    fun aValueShortEnoughToShowWholeCarriesNoEllipsis() {
        assertEquals("", shortHex(ByteArray(0)))
        assertEquals("0102", shortHex(byteArrayOf(1, 2)))
        assertEquals("01020304", shortHex(byteArrayOf(1, 2, 3, 4)))
    }
}
