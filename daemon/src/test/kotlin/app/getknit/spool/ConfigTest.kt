// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The commons half of `configFromEnv`. Every check here exists because the alternative is a spool
 * that starts and then misbehaves in a way no operator would connect back to a typo: a room whose
 * `list` no client can read, or one pinned so large the watermark has nothing left it may shed.
 */
class ConfigTest {
    private val secret = ByteArray(Commons.SECRET_BYTES) { (it + 1).toByte() }
    private val commonsId = Commons.scopeId(secret).joinToString("") { "%02x".format(it) }

    private fun config(vars: Map<String, String>) = configFromEnv { vars[it] }

    private fun withCommons(vararg extra: Pair<String, String>) = config(mapOf("SPOOL_COMMONS_ID" to commonsId) + extra.toMap())

    @Test
    fun commonsIsOffByDefault() {
        assertNull(config(emptyMap()).commons)
        // An empty value is unset, not a zero-length id — the same idiom as SPOOL_TOKEN.
        assertNull(config(mapOf("SPOOL_COMMONS_ID" to "")).commons)
    }

    @Test
    fun commonsDefaultsMatchTheDocumentedTable() {
        val commons = assertNotNull(withCommons().commons)
        assertTrue(commons.scopeId.contentEquals(Commons.scopeId(secret)))
        assertEquals(500, commons.bounds.maxFrames)
        assertEquals(86_400_000L, commons.bounds.ttlMs)
        assertEquals(65_536, commons.bounds.maxBlob, "SPOOL_COMMONS_MAX_BLOB should inherit SPOOL_MAX_BLOB")
        assertEquals(20, commons.ratePushes)
        assertEquals(false, commons.attach)
        assertNull(commons.name)
    }

    @Test
    fun commonsIdMustBeThirtyTwoBytesOfHex() {
        val tooShort = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_COMMONS_ID" to "abc123")) }
        assertTrue(tooShort.message!!.contains("SPOOL_COMMONS_ID"), tooShort.message!!)
        // The right length but not hex: an invite pasted where its hash belongs.
        assertFailsWith<IllegalArgumentException> {
            config(mapOf("SPOOL_COMMONS_ID" to "z".repeat(64)))
        }
    }

    @Test
    fun commonsBoundsMustFitTheSpoolWideCaps() {
        val frames =
            assertFailsWith<IllegalArgumentException> {
                withCommons("SPOOL_MAX_FRAMES" to "100", "SPOOL_COMMONS_MAX_FRAMES" to "101")
            }
        assertTrue(frames.message!!.contains("SPOOL_MAX_FRAMES"), frames.message!!)
        assertFailsWith<IllegalArgumentException> {
            withCommons("SPOOL_MAX_TTL_MS" to "1000", "SPOOL_COMMONS_TTL_MS" to "1001")
        }
        assertFailsWith<IllegalArgumentException> {
            withCommons("SPOOL_MAX_BLOB" to "2048", "SPOOL_COMMONS_MAX_BLOB" to "4096")
        }
    }

    @Test
    fun commonsFrameCapMustLeaveAListThatFitsOneRecord() {
        // 2000 frames needs a ~204 KB list reply against a 128 KB record cap. Unchecked, the room
        // would relay fine and then be impossible to catch up on: the transport kills the oversized
        // list with a 1009 and no protocol error ever explains why.
        val e =
            assertFailsWith<IllegalArgumentException> {
                withCommons("SPOOL_MAX_FRAMES" to "4000", "SPOOL_COMMONS_MAX_FRAMES" to "2000")
            }
        assertTrue(e.message!!.contains("SPOOL_MAX_RECORD"), e.message!!)
        // Raising the record cap alongside it is the documented way out.
        assertNotNull(
            withCommons(
                "SPOOL_MAX_FRAMES" to "4000",
                "SPOOL_COMMONS_MAX_FRAMES" to "2000",
                "SPOOL_MAX_RECORD" to "262144",
            ).commons,
        )
    }

    @Test
    fun commonsMustFitUnderTheStorageWatermark() {
        // It is pinned against the watermark, so a commons bigger than SPOOL_MAX_BYTES would leave
        // the watermark with nothing it is allowed to shed.
        val e =
            assertFailsWith<IllegalArgumentException> {
                withCommons("SPOOL_COMMONS_MAX_FRAMES" to "500", "SPOOL_MAX_BYTES" to "1048576")
            }
        assertTrue(e.message!!.contains("SPOOL_MAX_BYTES"), e.message!!)
        // 0 is unlimited, so there is no watermark to fit under.
        assertNotNull(withCommons("SPOOL_MAX_BYTES" to "0").commons)
    }

    @Test
    fun commonsAttachmentQuotaCountsTowardTheWatermark() {
        val frames = "10"
        val blob = "1024"
        // 10 x 1024 = 10 KiB of frames fits a 1 MiB watermark on its own...
        assertNotNull(
            withCommons(
                "SPOOL_COMMONS_MAX_FRAMES" to frames,
                "SPOOL_COMMONS_MAX_BLOB" to blob,
                "SPOOL_MAX_BYTES" to "1048576",
            ).commons,
        )
        // ...but not once the attachment quota it may also hold is counted.
        assertFailsWith<IllegalArgumentException> {
            withCommons(
                "SPOOL_COMMONS_MAX_FRAMES" to frames,
                "SPOOL_COMMONS_MAX_BLOB" to blob,
                "SPOOL_COMMONS_ATTACH" to "true",
                "SPOOL_MAX_BYTES" to "1048576",
            )
        }
    }

    @Test
    fun commonsNameAndAttachSwitchArePassedThrough() {
        val commons = assertNotNull(withCommons("SPOOL_COMMONS_NAME" to "lax", "SPOOL_COMMONS_ATTACH" to "true").commons)
        assertEquals("lax", commons.name)
        assertTrue(commons.attach)
    }
}
