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

    /**
     * `KNOWN_VARS` drives the "unrecognized environment variable — typo?" warning, and drifts in two
     * directions that nothing else would catch: a variable the parser reads but the set omits is
     * warned about on every boot of a *correct* configuration, and one the set carries but nothing
     * reads is silently accepted while doing nothing at all.
     *
     * Pinned by recording what `configFromEnv` looks up rather than by reflection — the lookup is
     * already a function parameter, so the seam is free.
     */
    @Test
    fun knownVarsMatchesWhatTheParserActuallyReads() {
        val read = mutableSetOf<String>()
        // A commons id, so the commons half of the parser is reached rather than returning early.
        configFromEnv { name ->
            read += name
            if (name == "SPOOL_COMMONS_ID") commonsId else null
        }

        val unlisted = read - KNOWN_VARS
        assertTrue(unlisted.isEmpty(), "read by configFromEnv but missing from KNOWN_VARS: $unlisted")

        // The three nothing in configFromEnv reads: SPOOL_DATA_DIR and SPOOL_RELOAD_FILE go
        // straight from the environment map in serve() (and checkConfig(), for the data dir), and
        // SPOOL_LOG_LEVEL is substituted by logback and never touched by Kotlin at all. All three
        // still belong in KNOWN_VARS or a correct configuration warns about them on every boot.
        val readElsewhere = setOf("SPOOL_DATA_DIR", "SPOOL_RELOAD_FILE", "SPOOL_LOG_LEVEL")
        val unread = KNOWN_VARS - read - readElsewhere
        assertTrue(unread.isEmpty(), "in KNOWN_VARS but nothing reads them: $unread")
    }

    /**
     * `SPOOL_TOKEN_NEXT` is only ever half of a rotation. Alone it would still gate the spool — it
     * is just a second accepted credential — but it would mean the operator either believed they
     * had set the primary and had not, or finished a rotation by clearing the wrong half. Both are
     * worth refusing to boot over rather than accepting quietly.
     */
    @Test
    fun tokenNextRequiresATokenAndMustDifferFromIt() {
        val rotating = config(mapOf("SPOOL_TOKEN" to "old", "SPOOL_TOKEN_NEXT" to "new"))
        assertEquals("old", rotating.token)
        assertEquals("new", rotating.tokenNext)

        assertNull(config(mapOf("SPOOL_TOKEN" to "old")).tokenNext)
        // Empty is unset, the same as every other credential here.
        assertNull(config(mapOf("SPOOL_TOKEN" to "old", "SPOOL_TOKEN_NEXT" to "")).tokenNext)

        assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TOKEN_NEXT" to "new")) }
        assertFailsWith<IllegalArgumentException> {
            config(mapOf("SPOOL_TOKEN" to "same", "SPOOL_TOKEN_NEXT" to "same"))
        }
    }

    @Test
    fun sourceUrlDefaultsToUpstreamAndRefusesAnythingThatIsNotAnHttpUrl() {
        assertEquals(BuildInfo.UPSTREAM_SOURCE_URL, config(emptyMap()).sourceUrl)
        assertEquals(BuildInfo.UPSTREAM_SOURCE_URL, config(mapOf("SPOOL_SOURCE_URL" to "")).sourceUrl)
        assertEquals(
            "https://example.invalid/fork",
            config(mapOf("SPOOL_SOURCE_URL" to "https://example.invalid/fork")).sourceUrl,
        )
        // It is rendered into a JSON string and a Prometheus label; a quote in either is corruption.
        listOf("not-a-url", "ftp://example.invalid/x", "https://ex\"ample").forEach { bad ->
            val failure = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_SOURCE_URL" to bad)) }
            assertTrue(failure.message!!.contains("SPOOL_SOURCE_URL"), failure.message!!)
        }
    }

    @Test
    fun metricsTokenIsUnsetByDefaultAndTreatsEmptyAsUnset() {
        assertNull(config(emptyMap()).metricsToken)
        // The SPOOL_TOKEN idiom: an empty value is unset, not a zero-length secret.
        assertNull(config(mapOf("SPOOL_METRICS_TOKEN" to "")).metricsToken)
        assertEquals("scrape", config(mapOf("SPOOL_METRICS_TOKEN" to "scrape")).metricsToken)
    }

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
