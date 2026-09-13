// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

import app.getknit.spool.protocol.A_CHUNK_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals

class HardLimitsTest {
    private fun limits(maxAttachBytes: Int) =
        HardLimits(maxBlob = 65_536, maxFramesCap = 400, maxTtlMs = 1L, maxScopes = 1, maxAttachBytes = maxAttachBytes)

    @Test
    fun maxATotalIsTheChunkCountOfAnAttachmentThatExactlyFillsTheQuota() {
        // §4.5: total = ceil(|A| / aChunkBytes), and no attachment inside the quota is larger than it.
        assertEquals(342, limits(16_777_216).maxATotal)
        assertEquals(1, limits(1).maxATotal)
        assertEquals(1, limits(A_CHUNK_BYTES).maxATotal)
        assertEquals(2, limits(A_CHUNK_BYTES + 1).maxATotal)
        // Attachments off: nothing is admissible, and requireAttachments refuses first anyway.
        assertEquals(0, limits(0).maxATotal)
        // The ceiling is taken in Long, so a budget at the top of Int does not wrap it negative.
        assertEquals(43_691, limits(Int.MAX_VALUE).maxATotal)
    }

    @Test
    fun theSealedChunkDefaultIsTheStructuralChunkPlusFraming() {
        assertEquals(49_221, HardLimits.DEFAULT_MAX_A_CHUNK)
    }
}
