// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.Ent
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.TurnCred
import app.getknit.spool.protocol.TurnReq
import app.getknit.spool.store.HardLimits
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SIGHUP` reload (plan item 4.2): the reloadable half of the configuration is swapped without
 * disturbing a live connection, and the boot half is refused rather than half-applied.
 *
 * The signal itself is not exercised here — `sun.misc.Signal` is process-global and a test that
 * raised SIGHUP would reconfigure whatever else the JVM is running. What the handler does with the
 * configuration it parsed is what these cover; `Main` is the thin part.
 */
class ReloadTest {
    @Test
    fun rotatingCredentialsTakesEffectWithoutARestart() {
        withServer(testConfig(token = "old")) {
            connect(token = "old") { helloHandshake() }
            connect(token = "new") { awaitClose(CloseCode.AUTH) }

            // Mid-rotation: both credentials live, nothing refused.
            assertTrue(spool.reload(testConfig(token = "old", tokenNext = "new")).isEmpty())
            connect(token = "old") { helloHandshake() }
            connect(token = "new") { helloHandshake() }

            // Promoting retires the old one, still without a restart.
            assertTrue(spool.reload(testConfig(token = "new")).isEmpty())
            connect(token = "new") { helloHandshake() }
            connect(token = "old") { awaitClose(CloseCode.AUTH) }
        }
    }

    /**
     * `requireModeration` is announced in `hello`, so it belongs to the half a reload may move and
     * takes effect on the next connection — the same contract as `powBits`. A live client keeps the
     * hello it negotiated; the test only asserts what a new one is told.
     */
    @Test
    fun moderationRequestAppliesToConnectionsOpenedAfterReload() {
        withServer(testConfig()) {
            connect { assertNull(helloHandshake().moderation) }
            assertTrue(spool.reload(testConfig(requireModeration = true)).isEmpty())
            connect { assertEquals(true, helloHandshake().moderation) }
            assertTrue(spool.reload(testConfig()).isEmpty())
            connect { assertNull(helloHandshake().moderation) }
        }
    }

    /**
     * `maxConns` is read on every use rather than captured, so a reload moves both what is enforced
     * and what `/metrics` reports. The alternative is a dashboard that disagrees with the daemon.
     *
     * `/metrics` reports the ceiling it is actually enforcing. It is read live rather than captured,
     * so a reload moves the enforced value and the reported one together — the alternative is an
     * operator reading a dashboard that disagrees with the daemon.
     */
    @Test
    fun capacityCeilingMovesOnReload() {
        withServer(testConfig(maxConns = 0)) {
            assertTrue(spool.reload(testConfig(maxConns = 7)).isEmpty())
            connect { helloHandshake() }
            assertContains(http.get("http://127.0.0.1:$port/metrics").bodyAsText(), "knit_spool_max_conns 7")
        }
    }

    /**
     * The boot half cannot move, and saying so is the whole contract: silently ignoring a changed
     * `maxScopes` would leave an operator believing a quota they can see in their file is in force,
     * while the store was built from a different one.
     */
    @Test
    fun bootOnlyFieldsAreReportedAndNotApplied() {
        withServer(testConfig()) {
            val ignored =
                spool.reload(
                    testConfig(
                        hardLimits = HardLimits(maxBlob = 2_048, maxFramesCap = 7, maxTtlMs = 1_000L, maxScopes = 9),
                    ),
                )
            assertTrue(ignored.any { it.startsWith("maxBlob=") }, ignored.toString())
            assertTrue(ignored.any { it.startsWith("maxFrames=") }, ignored.toString())
            assertTrue(ignored.any { it.startsWith("maxScopes=") }, ignored.toString())
            // Unchanged boot fields are not a complaint — only what actually differs is reported.
            assertTrue(ignored.none { it.startsWith("port=") }, ignored.toString())
            // And the spool still announces what it was built with, not what was asked for.
            connect { assertEquals(1_024, helloHandshake().limits!!.maxBlob) }
        }
    }

    /**
     * TURN is boot-only: its secret has to change in lockstep with the TURN server's, and the
     * per-connection and per-address budgets are built when a client arrives.
     */
    @Test
    fun turnIsBootOnly() {
        withServer(testConfig()) {
            val ignored = spool.reload(testConfig(turn = testTurn()))
            assertTrue(ignored.any { it.startsWith("turn=") }, ignored.toString())
            connect { assertNull(helloHandshake().turn) }
        }
    }

    /**
     * Every TURN server is boot-only too, each with its own secret: a reload that adds one, drops one
     * or changes how many URLs one has is reported as needing a restart, and `hello` keeps the boot list.
     */
    @Test
    fun everyTurnServerIsBootOnly() {
        withServer(testConfig(turn = testTurn())) {
            val added = spool.reload(testConfig(turn = testTurn(more = listOf(testTurnServer2()))))
            assertTrue(added.any { it.startsWith("turn=2urls,2urls/") }, added.toString())
            assertTrue(added.any { it.startsWith("turnSecret=set,set") }, added.toString())
            connect { assertEquals(listOf("spool.example:3478"), helloHandshake().turnHosts) }
            val shrunk = spool.reload(testConfig(turn = testTurn(more = listOf(testTurnServer2(urls = TEST_TURN_URLS_2.take(1))))))
            assertTrue(shrunk.any { it.startsWith("turn=2urls,1urls/") }, shrunk.toString())
        }
    }

    /**
     * The STUN list is read when `hello` is built, so a reload reaches every client that connects
     * after it — and only those: an open connection keeps the hello it was given.
     */
    @Test
    fun stunUrlsReloadForNewConnections() {
        withServer(testConfig()) {
            connect {
                assertNull(helloHandshake().stun)
                assertTrue(spool.reload(testConfig(stunUrls = listOf("stun:spool.example:3478"))).isEmpty())
            }
            connect { assertEquals(listOf("stun:spool.example:3478"), helloHandshake().stun) }
        }
    }

    /**
     * Casts switch on for connections opened after the reload, like STUN, and every cast setting is
     * runtime: a reload names none of them as ignored.
     */
    @Test
    fun castsSwitchOnForNewConnections() {
        withServer(testConfig()) {
            connect {
                assertNull(helloHandshake().cast)
                assertTrue(spool.reload(testConfig(rateCasts = 4, maxCast = 1_024, rateCastsTotal = 20)).isEmpty())
            }
            connect {
                val cast = helloHandshake().cast
                assertEquals(4, cast?.rate)
                assertEquals(1_024, cast?.maxBytes)
            }
        }
    }

    /**
     * The sig trio is runtime like the cast trio: a reload names none of them as ignored, the next
     * connection's `hello` carries the new bounds, and a rate of 0 leaves `sig` out of it.
     */
    @Test
    fun sigSettingsReloadForNewConnections() {
        withServer(testConfig()) {
            connect {
                assertEquals(2, helloHandshake().sig?.rate)
                assertTrue(spool.reload(testConfig(rateSigs = 5, maxSig = 1_024, rateSigsTotal = 20)).isEmpty())
            }
            connect {
                val sig = helloHandshake().sig
                assertEquals(5, sig?.rate)
                assertEquals(1_024, sig?.maxBytes)
                assertTrue(spool.reload(testConfig(rateSigs = 0)).isEmpty())
            }
            connect { assertNull(helloHandshake().sig) }
        }
    }

    /** A reload that changes nothing reports nothing. */
    @Test
    fun anIdenticalReloadIsSilent() {
        withServer(testConfig(token = "s3cret")) {
            assertTrue(spool.reload(testConfig(token = "s3cret")).isEmpty())
        }
    }

    /**
     * The two halves have to cover every field between them. A field in neither would be silently
     * unreloadable *and* absent from the boot log; one in both would be reported twice and, worse,
     * compared against itself when deciding what a reload may touch.
     */
    @Test
    fun everyDescribedFieldBelongsToExactlyOneHalf() {
        val config = testConfig(token = "s3cret")
        val boot = config.bootOnly().keys
        val runtime = config.reloadable().keys
        assertTrue(boot.intersect(runtime).isEmpty(), "in both halves: ${boot.intersect(runtime)}")

        val described =
            config
                .describe()
                .split(' ')
                .map { it.substringBefore('=') }
                .toSet()
        assertEquals(boot + runtime, described)
        // Spot-check the two that matter most for a reload's blast radius.
        assertContains(runtime, "token")
        assertContains(boot, "port")
    }

    /**
     * `SPOOL_TICKET_KEY_NEXT` rotation (ADR 2026-10.x9gq) is hitless for live connections: no reload
     * in it touches a ticket a connection already holds, and each step only changes which keys judge
     * the next `ent`. Rendering NEXT accepts both; promoting it retires the old key for new `ent`s
     * while the connection that presented an old-key ticket keeps fetching credentials on it.
     */
    @Test
    fun rotatingTheTicketKeyIsHitlessForLiveConnections() {
        val clock = FakeClock(1_800_000_000_000L)
        val old = TEST_TICKET_SIGNING_KEY
        val new = Tickets.generateSigningKey()

        fun keyed(ticket: SpoolServer.TicketConfig) = testConfig(turn = testTurn(), ticket = ticket)
        withServer(keyed(testTicketConfig(signingKey = old)), clock = clock) {
            connect {
                helloHandshake()
                present(testEnt(clock.now, signingKey = old))
                credential(q = 1L)

                // 1. Render NEXT. The live connection's ticket is untouched; a new one may use either key.
                assertTrue(spool.reload(keyed(testTicketConfig(signingKey = old, nextSigningKey = new))).isEmpty())
                clock.advance(TURN_TOKEN_MS)
                credential(q = 2L)
                connect {
                    helloHandshake()
                    present(testEnt(clock.now, signingKey = new))
                    credential(q = 1L)
                }
                // A renewal under the old key still lands while it is current.
                present(testEnt(clock.now, q = 3L, signingKey = old))

                // 4. Promote NEXT and unset it. Old-key `ent`s are refused from here, on new
                //    connections and this one alike, but what this connection already holds still works.
                assertTrue(spool.reload(keyed(testTicketConfig(signingKey = new))).isEmpty())
                clock.advance(TURN_TOKEN_MS)
                credential(q = 4L)
                connect {
                    helloHandshake()
                    sendRecord(testEnt(clock.now, q = 5L, signingKey = old))
                    val err = expectRecord<Err>(RecordType.ERR)
                    assertEquals(ErrCode.TICKET, err.code)
                    assertEquals(Tickets.Reason.KEY.word, err.msg)
                    present(testEnt(clock.now, q = 6L, signingKey = new))
                }
                clock.advance(TURN_TOKEN_MS)
                credential(q = 7L)
            }
            assertEquals(0L, spool.metrics.errCount(ErrCode.RATE))
        }
    }

    /**
     * Renaming a ticketed spool (`SPOOL_TICKET_AUDIENCE_ALIAS`) is hitless the same way. The new name
     * becomes the audience with the old one as its alias, so a ticket for either is accepted while
     * both names reach the spool; dropping the alias refuses old-name tickets from the next `ent` on,
     * without touching one a live connection already holds.
     */
    @Test
    fun renamingATicketedSpoolIsHitlessForLiveConnections() {
        val clock = FakeClock(1_800_000_000_000L)
        val oldName = TEST_TICKET_AUD
        val newName = "renamed.test"

        fun named(ticket: SpoolServer.TicketConfig) = testConfig(turn = testTurn(), ticket = ticket)
        withServer(named(testTicketConfig(audience = oldName)), clock = clock) {
            connect {
                helloHandshake()
                present(testEnt(clock.now, aud = oldName))
                credential(q = 1L)

                // The rename: the new name is the audience and the old one its alias. Either is good.
                assertTrue(spool.reload(named(testTicketConfig(audience = newName, audienceAlias = oldName))).isEmpty())
                clock.advance(TURN_TOKEN_MS)
                credential(q = 2L)
                connect {
                    helloHandshake()
                    present(testEnt(clock.now, aud = oldName))
                    present(testEnt(clock.now, q = 3L, aud = newName))
                    credential(q = 4L)
                }

                // Every client has moved: drop the alias. Old-name `ent`s are refused from here, but
                // what this connection already holds still works.
                assertTrue(spool.reload(named(testTicketConfig(audience = newName))).isEmpty())
                clock.advance(TURN_TOKEN_MS)
                credential(q = 5L)
                connect {
                    helloHandshake()
                    sendRecord(testEnt(clock.now, q = 6L, aud = oldName))
                    val err = expectRecord<Err>(RecordType.ERR)
                    assertEquals(ErrCode.TICKET, err.code)
                    assertEquals(Tickets.Reason.AUDIENCE.word, err.msg)
                    present(testEnt(clock.now, q = 7L, aud = newName))
                }
                clock.advance(TURN_TOKEN_MS)
                credential(q = 8L)
            }
            assertEquals(0L, spool.metrics.errCount(ErrCode.RATE))
        }
    }

    private companion object {
        /** One per-connection TURN token at [testTurn]'s 60 per ten minutes, so the bucket never runs dry here. */
        const val TURN_TOKEN_MS = 10_000L
    }

    private suspend fun DefaultClientWebSocketSession.present(ent: Ent) {
        sendRecord(ent)
        assertEquals(ent.q, expectRecord<Ok>(RecordType.OK).q)
    }

    private suspend fun DefaultClientWebSocketSession.credential(q: Long) {
        sendRecord(TurnReq(t = RecordType.TURNREQ, q = q))
        assertEquals(q, expectRecord<TurnCred>(RecordType.TURNCRED).q)
    }
}
