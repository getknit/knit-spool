// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.server.SpoolServer
import app.getknit.spool.server.withLogCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private val commonsId = Commons.scopeId(secret).toHexString()

    private fun config(vars: Map<String, String>) = configFromEnv { vars[it] }

    private fun withCommons(vararg extra: Pair<String, String>) = config(mapOf("SPOOL_COMMONS_ID" to commonsId) + extra.toMap())

    /** The first room, which is all the single-room tests below are about. */
    private val SpoolServer.Config.commons get() = rooms.firstOrNull()

    private val secret2 = ByteArray(Commons.SECRET_BYTES) { (it + 101).toByte() }
    private val commonsId2 = Commons.scopeId(secret2).toHexString()
    private val commonsId3 = Commons.scopeId(ByteArray(Commons.SECRET_BYTES) { (it + 201).toByte() }).toHexString()

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

    @Test
    fun numberedRoomsFollowTheFirstInNumberOrderWithTheirOwnSettings() {
        val rooms =
            withCommons(
                "SPOOL_COMMONS_3_ID" to commonsId3,
                "SPOOL_COMMONS_2_ID" to commonsId2,
                "SPOOL_COMMONS_2_NAME" to "Den",
                "SPOOL_COMMONS_2_MAX_FRAMES" to "100",
                "SPOOL_COMMONS_2_ATTACH" to "true",
                "SPOOL_COMMONS_2_RATE_PUSHES" to "5",
            ).rooms
        assertEquals(listOf(1, 2, 3), rooms.map { it.number })
        assertEquals(listOf(commonsId, commonsId2, commonsId3), rooms.map { it.scopeId.toHexString() })
        assertEquals("Den", rooms[1].name)
        assertEquals(100, rooms[1].bounds.maxFrames)
        assertTrue(rooms[1].attach)
        assertEquals(5, rooms[1].ratePushes)
        // The third room's settings are its own defaults, not the second's.
        assertEquals(500, rooms[2].bounds.maxFrames)
        assertFalse(rooms[2].attach)
        assertNull(rooms[2].name)
    }

    @Test
    fun aNumberedRoomNeedsTheFirstAndItsOwnId() {
        // `commons` repeats the first room for clients that predate `rooms`; a spool whose only room
        // was numbered would hand them nothing, or the wrong room.
        val noFirst = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_COMMONS_2_ID" to commonsId2)) }
        assertTrue(noFirst.message!!.contains("SPOOL_COMMONS_ID"), noFirst.message!!)
        // A numbered room's settings without its id would otherwise do nothing, silently.
        val noId = assertFailsWith<IllegalArgumentException> { withCommons("SPOOL_COMMONS_2_NAME" to "Den") }
        assertTrue(noId.message!!.contains("SPOOL_COMMONS_2_ID"), noId.message!!)
        // Room nine is past the cap: not a variable the daemon reads, so `check` and boot call it a typo.
        assertTrue("SPOOL_COMMONS_${MAX_COMMONS_ROOMS}_ID" in KNOWN_VARS)
        assertFalse("SPOOL_COMMONS_${MAX_COMMONS_ROOMS + 1}_ID" in KNOWN_VARS)
    }

    @Test
    fun aNumberedRoomIsCheckedLikeTheFirstAndNamedInTheRefusal() {
        val frames =
            assertFailsWith<IllegalArgumentException> {
                withCommons(
                    "SPOOL_COMMONS_2_ID" to commonsId2,
                    "SPOOL_COMMONS_2_MAX_FRAMES" to "1001",
                )
            }
        assertTrue(frames.message!!.contains("SPOOL_COMMONS_2_MAX_FRAMES"), frames.message!!)
        val id = assertFailsWith<IllegalArgumentException> { withCommons("SPOOL_COMMONS_2_ID" to "abc") }
        assertTrue(id.message!!.contains("SPOOL_COMMONS_2_ID"), id.message!!)
    }

    @Test
    fun twoRoomsMayNotShareAnId() {
        val e = assertFailsWith<IllegalArgumentException> { withCommons("SPOOL_COMMONS_4_ID" to commonsId) }
        assertTrue(e.message!!.contains("SPOOL_COMMONS_4_ID repeats SPOOL_COMMONS_ID"), e.message!!)
    }

    @Test
    fun everyRoomTogetherMustFitUnderTheStorageWatermark() {
        // One 500 x 64 KiB room is 32 MiB and fits 48 MiB; two of them, both pinned, do not.
        val maxBytes = "SPOOL_MAX_BYTES" to (48L * 1_048_576).toString()
        assertNotNull(withCommons(maxBytes).commons)
        val e = assertFailsWith<IllegalArgumentException> { withCommons(maxBytes, "SPOOL_COMMONS_2_ID" to commonsId2) }
        assertTrue(e.message!!.contains("2 commons rooms") && e.message!!.contains("SPOOL_MAX_BYTES"), e.message!!)
    }

    @Test
    fun theSpoolWideBudgetDefaultsToFortyOrTheLargestRoomsOwn() {
        assertEquals(40, withCommons().commonsRateTotal)
        // Never below one room's own budget, so a one-room spool keeps what it allowed before rooms.
        assertEquals(100, withCommons("SPOOL_COMMONS_RATE_PUSHES" to "100").commonsRateTotal)
        assertEquals(7, withCommons("SPOOL_COMMONS_RATE_TOTAL" to "7").commonsRateTotal)
        val e = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_COMMONS_RATE_TOTAL" to "7")) }
        assertTrue(e.message!!.contains("SPOOL_COMMONS_ID"), e.message!!)
    }

    @Test
    fun aRoomWhoseTtlDiffersFromTheFirstsIsWarned() {
        // A client that predates `rooms` compares a room's echoed ttlMs with `commons`', and would
        // take this room for a rotated one.
        val logged =
            withLogCapture("app.getknit.spool.Main") {
                withCommons("SPOOL_COMMONS_2_ID" to commonsId2, "SPOOL_COMMONS_2_TTL_MS" to "3600000")
            }
        assertTrue(logged.any { it.formattedMessage.contains("SPOOL_COMMONS_2_TTL_MS") }, logged.map { it.formattedMessage }.toString())
        val quiet = withLogCapture("app.getknit.spool.Main") { withCommons("SPOOL_COMMONS_2_ID" to commonsId2) }
        assertTrue(quiet.none { it.formattedMessage.contains("TTL_MS") })
    }

    /**
     * A reload builds its rooms against the limits the running store was built from (ADR 2026-10.hs75).
     * A reload file that also raises a boot-only limit has that line ignored, so building the rooms
     * against it would install a room the spool cannot hold to its word.
     */
    @Test
    fun aReloadBuildsItsRoomsAgainstTheRunningLimits() {
        val running = configFromEnv { null }.hardLimits
        val raised = mapOf("SPOOL_COMMONS_ID" to commonsId, "SPOOL_MAX_BLOB" to "100000", "SPOOL_MAX_RECORD" to "131072")
        // The room's MAX_BLOB defaults to the spool's: the running one, not the one the file asks for.
        assertEquals(
            65_536,
            configFromEnv(running) { raised[it] }
                .rooms
                .single()
                .bounds.maxBlob,
        )
        assertEquals(
            100_000,
            configFromEnv { raised[it] }
                .rooms
                .single()
                .bounds.maxBlob,
        )
        // And a room that only fits the raised cap is refused, naming the cap it does not fit.
        val frames = raised + ("SPOOL_MAX_FRAMES" to "2000") + ("SPOOL_COMMONS_MAX_FRAMES" to "1500") + ("SPOOL_MAX_RECORD" to "262144")
        val refused = assertFailsWith<IllegalArgumentException> { configFromEnv(running) { frames[it] } }
        assertTrue(refused.message!!.contains("SPOOL_MAX_FRAMES (1000)"), refused.message!!)
    }

    /**
     * A reload file is layered over the environment, so a room the environment set is removed by blank
     * lines: its id and every other variable set for it. A blank id alone is refused, as a room's other
     * settings without its id always were, rather than silently keeping half a room.
     */
    @Test
    fun aRoomTheEnvironmentSetIsRemovedByBlankingItsVariables() {
        val environment = mapOf("SPOOL_COMMONS_ID" to commonsId, "SPOOL_COMMONS_2_ID" to commonsId2, "SPOOL_COMMONS_2_NAME" to "Den")
        val removed = environment + mapOf("SPOOL_COMMONS_2_ID" to "", "SPOOL_COMMONS_2_NAME" to "")
        assertEquals(1, config(removed).rooms.size)
        val idOnly = environment + ("SPOOL_COMMONS_2_ID" to "")
        val refused = assertFailsWith<IllegalArgumentException> { config(idOnly) }
        assertTrue(refused.message!!.contains("SPOOL_COMMONS_2_NAME needs SPOOL_COMMONS_2_ID"), refused.message!!)
        // Every room gone takes the environment's total budget with it, blanked like the rest.
        val withTotal = environment + ("SPOOL_COMMONS_RATE_TOTAL" to "80")
        val none =
            withTotal +
                mapOf("SPOOL_COMMONS_ID" to "", "SPOOL_COMMONS_2_ID" to "", "SPOOL_COMMONS_2_NAME" to "", "SPOOL_COMMONS_RATE_TOTAL" to "")
        assertTrue(config(none).rooms.isEmpty())
    }

    /**
     * Spec §7.5. Off is the default because the flag changes what every conforming client on the
     * spool will let its user send, which is not a decision to make for an operator who never set it.
     */
    @Test
    fun requireModerationDefaultsOffAndParsesTrue() {
        assertFalse(config(emptyMap()).requireModeration)
        assertFalse(config(mapOf("SPOOL_REQUIRE_MODERATION" to "false")).requireModeration)
        assertTrue(config(mapOf("SPOOL_REQUIRE_MODERATION" to "true")).requireModeration)
    }

    /** `yes`, `1`, `on`: each means something to somebody and nothing to the parser, by design. */
    @Test
    fun requireModerationRefusesAnythingButTrueOrFalse() {
        val e = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_REQUIRE_MODERATION" to "yes")) }
        assertTrue(e.message!!.contains("SPOOL_REQUIRE_MODERATION"), e.message!!)
    }

    private val turnSecret = "0123456789abcdef0123456789abcdef"
    private val turnUrls = "turn:spool.example:3478?transport=udp,turns:spool.example:5349?transport=tcp"

    private fun withTurn(vararg extra: Pair<String, String>) =
        config(mapOf("SPOOL_TURN_SECRET" to turnSecret, "SPOOL_TURN_URLS" to turnUrls) + extra.toMap())

    /** Experimental TURN issuing is off unless a secret is set, exactly as before it existed. */
    @Test
    fun turnIsOffByDefault() {
        assertNull(config(emptyMap()).turn)
        assertNull(config(mapOf("SPOOL_TURN_SECRET" to "")).turn)
    }

    @Test
    fun turnParsesWithItsDefaults() {
        val turn = assertNotNull(withTurn().turn)
        val server = turn.servers.single()
        assertEquals(listOf("turn:spool.example:3478?transport=udp", "turns:spool.example:5349?transport=tcp"), server.urls)
        assertEquals(1, server.number)
        assertEquals(listOf("spool.example:3478"), turn.hosts)
        assertEquals(300L, turn.ttlS)
        assertEquals(4, turn.ratePer10Min)
        val tuned = assertNotNull(withTurn("SPOOL_TURN_TTL_S" to "3600", "SPOOL_TURN_RATE" to "10").turn)
        assertEquals(3_600L, tuned.ttlS)
        assertEquals(10, tuned.ratePer10Min)
    }

    /** URLs or tuning with no secret means an operator who believes credentials are being issued. */
    @Test
    fun turnSettingsWithoutASecretRefuseToStart() {
        for (orphan in listOf("SPOOL_TURN_URLS" to turnUrls, "SPOOL_TURN_TTL_S" to "3600", "SPOOL_TURN_RATE" to "4")) {
            val e = assertFailsWith<IllegalArgumentException> { config(mapOf(orphan)) }
            assertTrue(e.message!!.contains("SPOOL_TURN_SECRET"), e.message!!)
        }
    }

    @Test
    fun aTurnSecretNeedsUrls() {
        val e = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TURN_SECRET" to turnSecret)) }
        assertTrue(e.message!!.contains("SPOOL_TURN_URLS"), e.message!!)
    }

    /** The refusal names the variable and the floor, and never echoes the value it refused. */
    @Test
    fun aShortTurnSecretIsRefusedWithoutBeingEchoed() {
        val short = "tooshort-secret"
        val e = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TURN_SECRET" to short, "SPOOL_TURN_URLS" to turnUrls)) }
        assertTrue(e.message!!.contains("SPOOL_TURN_SECRET"), e.message!!)
        assertFalse(e.message!!.contains(short), e.message!!)
    }

    @Test
    fun turnUrlsMustBeTurnUris() {
        for (bad in listOf("stun:spool.example:3478", "https://spool.example", "turn:", "turn:a b")) {
            assertFailsWith<IllegalArgumentException>(bad) { withTurn("SPOOL_TURN_URLS" to bad) }
        }
    }

    private val turnSecret2 = "fedcba9876543210fedcba9876543210"
    private val turnUrls2 = "turn:two.example:3478?transport=udp,turns:two.example:5349?transport=tcp"

    private fun withTwoTurnServers(vararg extra: Pair<String, String>) =
        withTurn(*(arrayOf("SPOOL_TURN_2_SECRET" to turnSecret2, "SPOOL_TURN_2_URLS" to turnUrls2) + extra))

    /**
     * Servers 2 to 4 follow server 1 in number order, each with its own secret and URLs, under the
     * one lifetime and rate (ADR 2026-10.u5f4). `turnHosts` is each one's UDP entry, in that order.
     */
    @Test
    fun numberedTurnServersFollowTheFirstWithTheirOwnSecretAndUrls() {
        val turn =
            assertNotNull(
                withTwoTurnServers(
                    "SPOOL_TURN_3_SECRET" to "3".repeat(32),
                    "SPOOL_TURN_3_URLS" to "turn:[2001:DB8::7]?transport=udp",
                    "SPOOL_TURN_TTL_S" to "600",
                ).turn,
            )
        assertEquals(listOf(1, 2, 3), turn.servers.map { it.number })
        assertEquals(listOf(turnSecret, turnSecret2, "3".repeat(32)), turn.servers.map { it.secret })
        assertEquals(listOf("turn:two.example:3478?transport=udp", "turns:two.example:5349?transport=tcp"), turn.servers[1].urls)
        assertEquals(listOf("spool.example:3478", "two.example:3478", "[2001:db8::7]:3478"), turn.hosts)
        assertEquals(600L, turn.ttlS)
    }

    /** Refusal 1: a numbered server needs the one before it, so `turnHosts` never skips a slot. */
    @Test
    fun aTurnServerWithAGapBeforeItIsRefused() {
        val third = arrayOf("SPOOL_TURN_3_SECRET" to "3".repeat(32), "SPOOL_TURN_3_URLS" to "turn:three.example")
        val e = assertFailsWith<IllegalArgumentException> { withTurn(*third) }
        assertTrue(e.message!!.contains("SPOOL_TURN_3_SECRET") && e.message!!.contains("SPOOL_TURN_2_SECRET"), e.message!!)
        val alone = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TURN_2_URLS" to turnUrls2)) }
        assertTrue(alone.message!!.contains("SPOOL_TURN_2_URLS") && alone.message!!.contains("SPOOL_TURN_SECRET"), alone.message!!)
        // Server 4 is the last a spool reads; a fifth is not a variable, so `check` warns about it as a typo.
        assertTrue("SPOOL_TURN_4_SECRET" in KNOWN_VARS && "SPOOL_TURN_5_SECRET" !in KNOWN_VARS)
        assertEquals(4, MAX_TURN_SERVERS)
    }

    /** Refusal 2: half a numbered server, either half, names both of its variables. */
    @Test
    fun aNumberedTurnServerNeedsBothItsSecretAndItsUrls() {
        val noUrls = assertFailsWith<IllegalArgumentException> { withTurn("SPOOL_TURN_2_SECRET" to turnSecret2) }
        assertTrue(noUrls.message!!.contains("SPOOL_TURN_2_URLS"), noUrls.message!!)
        assertFalse(noUrls.message!!.contains(turnSecret2), noUrls.message!!)
        val noSecret = assertFailsWith<IllegalArgumentException> { withTurn("SPOOL_TURN_2_URLS" to turnUrls2) }
        assertTrue(noSecret.message!!.contains("SPOOL_TURN_2_SECRET"), noSecret.message!!)
        // Empty is unset, so a blank `.env` line for server 2 is no server 2.
        assertEquals(1, withTurn("SPOOL_TURN_2_SECRET" to "", "SPOOL_TURN_2_URLS" to "").turn!!.servers.size)
    }

    /** Refusals 3 and 4 hold for every server, and the refusal names the server's own variable. */
    @Test
    fun aNumberedTurnServersSecretAndUrlsAreCheckedLikeTheFirsts() {
        val short = "tooshort-secret-for-two"
        val e = assertFailsWith<IllegalArgumentException> { withTurn("SPOOL_TURN_2_SECRET" to short, "SPOOL_TURN_2_URLS" to turnUrls2) }
        assertTrue(e.message!!.contains("SPOOL_TURN_2_SECRET"), e.message!!)
        assertFalse(e.message!!.contains(short), e.message!!)
        val notTurn = assertFailsWith<IllegalArgumentException> { withTwoTurnServers("SPOOL_TURN_2_URLS" to "stun:two.example:3478") }
        assertTrue(notTurn.message!!.contains("SPOOL_TURN_2_URLS"), notTurn.message!!)
    }

    /** Refusal 5: with two servers, a server is known by its host, so its URLs may name only one. */
    @Test
    fun withSeveralTurnServersEachServersUrlsNameOneHost() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                withTwoTurnServers("SPOOL_TURN_2_URLS" to "turn:two.example:3478?transport=udp,turns:other.example:5349")
            }
        assertTrue(e.message!!.contains("SPOOL_TURN_2_URLS") && e.message!!.contains("2 hosts"), e.message!!)
        // Hosts compare case-insensitively, and a URL with no host it can read is no identity at all.
        assertEquals(2, withTwoTurnServers("SPOOL_TURN_2_URLS" to "turn:two.example,turns:TWO.example:5349").turn!!.servers.size)
        val unreadable = assertFailsWith<IllegalArgumentException> { withTwoTurnServers("SPOOL_TURN_2_URLS" to "turn:?transport=udp") }
        assertTrue(unreadable.message!!.contains("names no host"), unreadable.message!!)
        for (secret in listOf(turnSecret, turnSecret2)) {
            assertFalse(e.message!!.contains(secret) || unreadable.message!!.contains(secret))
        }
    }

    /** Refusal 6: two servers on one host would be one identity for two secrets. Equal secrets are fine. */
    @Test
    fun withSeveralTurnServersNoTwoShareAHost() {
        val e = assertFailsWith<IllegalArgumentException> { withTwoTurnServers("SPOOL_TURN_2_URLS" to "turn:Spool.Example:3479") }
        assertTrue(e.message!!.contains("SPOOL_TURN_2_URLS") && e.message!!.contains("SPOOL_TURN_URLS"), e.message!!)
        assertEquals(2, withTwoTurnServers("SPOOL_TURN_2_SECRET" to turnSecret).turn!!.servers.size)
    }

    /** Refusal 7: with two servers, each needs the UDP URL a client measures and names it by. */
    @Test
    fun withSeveralTurnServersEachNeedsAUdpUrl() {
        for (urls in listOf("turns:two.example:5349", "turn:two.example:3478?transport=tcp")) {
            val e = assertFailsWith<IllegalArgumentException>(urls) { withTwoTurnServers("SPOOL_TURN_2_URLS" to urls) }
            assertTrue(e.message!!.contains("SPOOL_TURN_2_URLS") && e.message!!.contains("UDP"), e.message!!)
        }
        val first = assertFailsWith<IllegalArgumentException> { withTwoTurnServers("SPOOL_TURN_URLS" to "turns:spool.example:5349") }
        assertTrue(first.message!!.contains("SPOOL_TURN_URLS"), first.message!!)
    }

    /**
     * One server keeps every URL list it was accepted with before: several hosts, no UDP URL, a URL
     * with no host to read. With no UDP entry there is nothing to advertise, so `turnHosts` is omitted.
     */
    @Test
    fun oneTurnServerKeepsEveryUrlListItWasAcceptedWith() {
        val spread = assertNotNull(withTurn("SPOOL_TURN_URLS" to "turn:a.example?transport=udp,turns:b.example:5349").turn)
        assertEquals(listOf("a.example:3478"), spread.hosts)
        for (urls in listOf("turns:spool.example:5349?transport=tcp", "turn:spool.example:3478?transport=tcp", "turn:?transport=udp")) {
            val turn = assertNotNull(withTurn("SPOOL_TURN_URLS" to urls).turn, urls)
            assertNull(turn.hosts, urls)
            assertEquals(turn.servers.single(), turn.select("spool.example:3478"))
        }
    }

    /** STUN needs no secret, so it is off by default and otherwise stands on its own. */
    @Test
    fun stunUrlsParseAloneAndDefaultToNone() {
        assertTrue(config(emptyMap()).stunUrls.isEmpty())
        assertTrue(config(mapOf("SPOOL_STUN_URLS" to "")).stunUrls.isEmpty())
        val stun = config(mapOf("SPOOL_STUN_URLS" to "stun:spool.example:3478, stuns:spool.example:5349")).stunUrls
        assertEquals(listOf("stun:spool.example:3478", "stuns:spool.example:5349"), stun)
    }

    @Test
    fun stunUrlsMustBeAFewStunUris() {
        for (bad in listOf("turn:spool.example:3478", "https://spool.example", "stun:", "stun:a b")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { config(mapOf("SPOOL_STUN_URLS" to bad)) }
            assertTrue(e.message!!.contains("SPOOL_STUN_URLS"), e.message!!)
        }
        val five = List(5) { "stun:s$it.example:3478" }.joinToString(",")
        assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_STUN_URLS" to five)) }
    }

    /** Casts are off unless a rate is set, and need no secret, so they stand on their own like STUN. */
    @Test
    fun castsAreOffByDefaultAndParseAlone() {
        val off = config(emptyMap())
        assertEquals(0, off.rateCasts)
        assertEquals(2_048, off.maxCast)
        assertEquals(200, off.rateCastsTotal)
        val on = config(mapOf("SPOOL_RATE_CASTS" to "4", "SPOOL_MAX_CAST" to "1500", "SPOOL_RATE_CASTS_TOTAL" to "50"))
        assertEquals(4, on.rateCasts)
        assertEquals(1_500, on.maxCast)
        assertEquals(50, on.rateCastsTotal)
    }

    @Test
    fun castBoundsAreRefusedOutsideTheirRange() {
        for ((name, bad) in listOf(
            "SPOOL_RATE_CASTS" to "-1",
            "SPOOL_RATE_CASTS" to "101",
            "SPOOL_MAX_CAST" to "0",
            "SPOOL_RATE_CASTS_TOTAL" to "0",
        )) {
            val e = assertFailsWith<IllegalArgumentException>("$name=$bad") { config(mapOf(name to bad)) }
            assertTrue(e.message!!.contains(name), e.message!!)
        }
    }

    /** A cast is one record, so the largest one has to fit `SPOOL_MAX_RECORD` with its envelope. */
    @Test
    fun maxCastMustFitInARecord() {
        val e = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_MAX_CAST" to "131072")) }
        assertTrue(e.message!!.contains("SPOOL_MAX_RECORD"), e.message!!)
    }

    /** Signals are on with no configuration (SPOOL_PROTOCOL.md §7.8), and need no secret or ticket key. */
    @Test
    fun sigsAreOnByDefaultAndParseAlone() {
        val stock = config(emptyMap())
        assertEquals(2, stock.rateSigs)
        assertEquals(8_192, stock.maxSig)
        assertEquals(200, stock.rateSigsTotal)
        val tuned = config(mapOf("SPOOL_RATE_SIGS" to "0", "SPOOL_MAX_SIG" to "512", "SPOOL_RATE_SIGS_TOTAL" to "50"))
        assertEquals(0, tuned.rateSigs)
        assertEquals(512, tuned.maxSig)
        assertEquals(50, tuned.rateSigsTotal)
        assertEquals(100, config(mapOf("SPOOL_RATE_SIGS" to "100")).rateSigs)
    }

    @Test
    fun sigBoundsAreRefusedOutsideTheirRange() {
        for ((name, bad) in listOf(
            "SPOOL_RATE_SIGS" to "-1",
            "SPOOL_RATE_SIGS" to "101",
            "SPOOL_MAX_SIG" to "0",
            "SPOOL_RATE_SIGS_TOTAL" to "0",
            "SPOOL_RATE_SIGS" to "two",
        )) {
            val e = assertFailsWith<IllegalArgumentException>("$name=$bad") { config(mapOf(name to bad)) }
            assertTrue(e.message!!.contains(name), e.message!!)
        }
    }

    /**
     * A sig is one record, so the largest one has to fit `SPOOL_MAX_RECORD` with its envelope. The
     * default 8 KiB does under the default record, and a spool that shrinks the record below it has to
     * shrink the sig too.
     */
    @Test
    fun maxSigMustFitInARecord() {
        val e = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_MAX_SIG" to "130561")) }
        assertTrue(e.message!!.contains("SPOOL_MAX_RECORD"), e.message!!)
        assertEquals(130_560, config(mapOf("SPOOL_MAX_SIG" to "130560")).maxSig)
        val small = mapOf("SPOOL_MAX_BLOB" to "4096", "SPOOL_MAX_RECORD" to "8192")
        val refused = assertFailsWith<IllegalArgumentException> { config(small) }
        assertTrue(refused.message!!.contains("SPOOL_MAX_SIG"), refused.message!!)
        assertEquals(7_680, config(small + ("SPOOL_MAX_SIG" to "7680")).maxSig)
    }

    /** RFC 8032 §7.1 TEST 1's public key, as `ticket-keygen` prints one: unpadded base64url. */
    private val ticketKey =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray())

    private fun withTicket(vararg extra: Pair<String, String>) =
        config(mapOf("SPOOL_TICKET_KEY" to ticketKey, "SPOOL_TICKET_AUDIENCE" to "spool.example") + extra.toMap())

    @Test
    fun ticketsAreOffByDefaultAndParseWithAKeyAndAudience() {
        assertNull(config(emptyMap()).ticket)
        assertNull(config(mapOf("SPOOL_TICKET_KEY" to "")).ticket)
        val ticket = assertNotNull(withTicket().ticket)
        assertEquals("spool.example", ticket.audience)
        assertEquals("21fe31dfa154a261", ticket.kid.toHexString())
    }

    /** An audience with no key means an operator who believes tickets are being checked. */
    @Test
    fun anAudienceWithoutAKeyAndAKeyWithoutAnAudienceRefuseToStart() {
        val orphan = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TICKET_AUDIENCE" to "spool.example")) }
        assertTrue(orphan.message!!.contains("SPOOL_TICKET_KEY"), orphan.message!!)
        val bare = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TICKET_KEY" to ticketKey)) }
        assertTrue(bare.message!!.contains("SPOOL_TICKET_AUDIENCE"), bare.message!!)
    }

    /** Unset gates keep what a key gated before the list existed, so a keyed spool's upgrade changes nothing. */
    @Test
    fun ticketGatesDefaultToTurnAndCastAndParseAsAList() {
        val default = assertNotNull(withTicket().ticket)
        assertEquals(Tickets.LEGACY_GATES, default.gates)
        assertEquals(33_554_432L, default.attachBytes)
        assertEquals(Tickets.LEGACY_GATES, assertNotNull(withTicket("SPOOL_TICKET_GATES" to "").ticket).gates)
        val files = assertNotNull(withTicket("SPOOL_TICKET_GATES" to " files , turn,cast", "SPOOL_TICKET_ATTACH_BYTES" to "0").ticket)
        assertEquals(Tickets.FL_TURN or Tickets.FL_FILES or Tickets.FL_CAST, files.gates)
        assertEquals("turn,files,cast", files.gateWords())
        assertEquals(0L, files.attachBytes)
        assertEquals(Tickets.FL_FILES, assertNotNull(withTicket("SPOOL_TICKET_GATES" to "files").ticket).gates)
    }

    /** `create` is named so its refusal says why, rather than reading like a typo. */
    @Test
    fun anUnknownGateAndTheUnbuiltCreateGateRefuseToStart() {
        val create = assertFailsWith<IllegalArgumentException> { withTicket("SPOOL_TICKET_GATES" to "turn,create") }
        assertTrue(create.message!!.contains("not supported yet"), create.message!!)
        for (bad in listOf("tun", "turn,", "files;cast")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { withTicket("SPOOL_TICKET_GATES" to bad) }
            assertTrue(e.message!!.contains("SPOOL_TICKET_GATES"), e.message!!)
        }
        for (bad in listOf("-1", "", "lots")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { withTicket("SPOOL_TICKET_ATTACH_BYTES" to bad) }
            assertTrue(e.message!!.contains("SPOOL_TICKET_ATTACH_BYTES"), e.message!!)
        }
    }

    /** Gates or a budget with no key means an operator who believes uploads are being metered. */
    @Test
    fun gatesOrAnAttachBudgetWithoutAKeyRefuseToStart() {
        for (name in listOf("SPOOL_TICKET_GATES", "SPOOL_TICKET_ATTACH_BYTES")) {
            val e = assertFailsWith<IllegalArgumentException>(name) { config(mapOf(name to "files")) }
            assertTrue(e.message!!.contains("SPOOL_TICKET_KEY"), e.message!!)
        }
    }

    /** A second issuer key, as `ticket-keygen` would print it. */
    private val nextTicketKey =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(Tickets.publicHalf(Tickets.generateSigningKey()))

    /**
     * `SPOOL_TICKET_KEY_NEXT` is half of a rotation (ADR 2026-10.x9gq), refused on its own and when
     * it repeats the current key, for `SPOOL_TOKEN_NEXT`'s reasons; empty is unset. Its refusals
     * name it and never echo it.
     */
    @Test
    fun theNextTicketKeyIsHalfOfARotationAndMustDiffer() {
        assertNull(assertNotNull(withTicket().ticket).next)
        assertNull(assertNotNull(withTicket("SPOOL_TICKET_KEY_NEXT" to "").ticket).next)
        val rotating = assertNotNull(withTicket("SPOOL_TICKET_KEY_NEXT" to nextTicketKey).ticket)
        val next = assertNotNull(rotating.next)
        assertFalse(next.kid.contentEquals(rotating.kid))
        assertEquals(2, rotating.verifiers.size)
        assertEquals("set", withTicket("SPOOL_TICKET_KEY_NEXT" to nextTicketKey).reloadable()["ticketKeyNext"])

        val orphan = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TICKET_KEY_NEXT" to nextTicketKey)) }
        assertTrue(orphan.message!!.contains("SPOOL_TICKET_KEY"), orphan.message!!)
        val same = assertFailsWith<IllegalArgumentException> { withTicket("SPOOL_TICKET_KEY_NEXT" to ticketKey) }
        assertTrue(same.message!!.contains("must differ"), same.message!!)
        for (bad in listOf("not base64!", nextTicketKey.dropLast(4))) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { withTicket("SPOOL_TICKET_KEY_NEXT" to bad) }
            assertTrue(e.message!!.contains("SPOOL_TICKET_KEY_NEXT"), e.message!!)
            assertFalse(e.message!!.contains(bad), e.message!!)
        }
    }

    /**
     * `SPOOL_TICKET_TURN_RATE` defaults to one address's budget at the default per-connection rate,
     * accepts 0 as off and up to four times `SPOOL_TURN_RATE`'s ceiling, and is refused without a
     * ticket key or without TURN, where it would limit nothing (ADR 2026-10.x9gq).
     */
    @Test
    fun theHoldersTurnRateIsBoundedAndNeedsTicketsAndTurn() {
        val turn = arrayOf("SPOOL_TURN_SECRET" to turnSecret, "SPOOL_TURN_URLS" to turnUrls)
        assertEquals(16, assertNotNull(withTicket(*turn).ticket).turnRatePer10Min)
        assertEquals(0, assertNotNull(withTicket(*turn, "SPOOL_TICKET_TURN_RATE" to "0").ticket).turnRatePer10Min)
        assertEquals(240, assertNotNull(withTicket(*turn, "SPOOL_TICKET_TURN_RATE" to "240").ticket).turnRatePer10Min)
        for (bad in listOf("-1", "241", "", "lots")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { withTicket(*turn, "SPOOL_TICKET_TURN_RATE" to bad) }
            assertTrue(e.message!!.contains("SPOOL_TICKET_TURN_RATE"), e.message!!)
        }
        val noKey = assertFailsWith<IllegalArgumentException> { config(mapOf(*turn, "SPOOL_TICKET_TURN_RATE" to "8")) }
        assertTrue(noKey.message!!.contains("SPOOL_TICKET_KEY"), noKey.message!!)
        val noTurn = assertFailsWith<IllegalArgumentException> { withTicket("SPOOL_TICKET_TURN_RATE" to "8") }
        assertTrue(noTurn.message!!.contains("SPOOL_TURN_SECRET"), noTurn.message!!)
        // Unset needs neither: a ticketed spool without TURN keeps booting as it did.
        assertEquals(16, assertNotNull(withTicket().ticket).turnRatePer10Min)
    }

    /**
     * `SPOOL_TICKET_AUDIENCE_ALIAS` is a second name for a spool being renamed: empty is unset, it is a
     * lowercase host name like the audience, and it is refused without the key and the audience or
     * when it repeats the audience.
     */
    @Test
    fun theAudienceAliasIsASecondNameAndMustDiffer() {
        assertEquals(setOf("spool.example"), assertNotNull(withTicket().ticket).audiences)
        assertNull(assertNotNull(withTicket("SPOOL_TICKET_AUDIENCE_ALIAS" to "").ticket).audienceAlias)
        val renaming = withTicket("SPOOL_TICKET_AUDIENCE_ALIAS" to "old.example")
        assertEquals(setOf("spool.example", "old.example"), assertNotNull(renaming.ticket).audiences)
        assertEquals("old.example", renaming.reloadable()["ticketAudAlias"])
        assertEquals("unset", withTicket().reloadable()["ticketAudAlias"])

        val orphan = assertFailsWith<IllegalArgumentException> { config(mapOf("SPOOL_TICKET_AUDIENCE_ALIAS" to "old.example")) }
        assertTrue(orphan.message!!.contains("SPOOL_TICKET_KEY"), orphan.message!!)
        val noAudience =
            assertFailsWith<IllegalArgumentException> {
                config(mapOf("SPOOL_TICKET_KEY" to ticketKey, "SPOOL_TICKET_AUDIENCE_ALIAS" to "old.example"))
            }
        assertTrue(noAudience.message!!.contains("SPOOL_TICKET_AUDIENCE"), noAudience.message!!)
        val same = assertFailsWith<IllegalArgumentException> { withTicket("SPOOL_TICKET_AUDIENCE_ALIAS" to "spool.example") }
        assertTrue(same.message!!.contains("must differ"), same.message!!)
        for (bad in listOf("Old.Example", "old example", "old.example:443", "x".repeat(254))) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { withTicket("SPOOL_TICKET_AUDIENCE_ALIAS" to bad) }
            assertTrue(e.message!!.contains("SPOOL_TICKET_AUDIENCE_ALIAS"), e.message!!)
        }
    }

    @Test
    fun theAudienceIsALowercaseHostName() {
        for (bad in listOf("Spool.Example", "spool example", "spool.example:443", "x".repeat(254))) {
            assertFailsWith<IllegalArgumentException>(bad) { withTicket("SPOOL_TICKET_AUDIENCE" to bad) }
        }
    }

    /**
     * Not base64url, the wrong length, and a well-formed 32 bytes that is not a point on the curve
     * all stop the spool at boot — and the refusal never echoes what it was given.
     */
    @Test
    fun anUnusableTicketKeyRefusesToStartWithoutBeingEchoed() {
        val offCurve =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also { it[0] = 2 })
        for (bad in listOf("not base64!", ticketKey.dropLast(4), offCurve)) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { withTicket("SPOOL_TICKET_KEY" to bad) }
            assertTrue(e.message!!.contains("SPOOL_TICKET_KEY"), e.message!!)
            assertFalse(e.message!!.contains(bad), e.message!!)
        }
    }

    @Test
    fun turnTtlAndRateAreBounded() {
        assertFailsWith<IllegalArgumentException> { withTurn("SPOOL_TURN_TTL_S" to "59") }
        assertFailsWith<IllegalArgumentException> { withTurn("SPOOL_TURN_TTL_S" to "3601") }
        assertFailsWith<IllegalArgumentException> { withTurn("SPOOL_TURN_RATE" to "0") }
        assertFailsWith<IllegalArgumentException> { withTurn("SPOOL_TURN_RATE" to "61") }
    }
}
