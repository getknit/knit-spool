// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Achunk
import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ent
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.store.HardLimits
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ticket-gated attachment uploads (S-7.6-22, ADR 2026-10.h6pt): where `files` is in
 * `SPOOL_TICKET_GATES`, an `aput` needs the uploader's own files ticket and room in its daily budget,
 * and nobody else's ticket stands in for it. Fetching is never gated (S-7.6-23).
 */
class TicketFilesTest {
    private val now = 1_800_000_000_000L
    private val gated = Tickets.LEGACY_GATES or Tickets.FL_FILES
    private val alice = ByteArray(Tickets.PS_BYTES) { 0x11 }
    private val bob = ByteArray(Tickets.PS_BYTES) { 0x22 }

    private fun filesConfig(
        gates: Long = gated,
        attachBytes: Long = 1_500,
        ratePushes: Int = 50,
    ) = testConfig(
        hardLimits =
            HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 8, maxAttachBytes = 1 shl 20),
        ticket = testTicketConfig(gates = gates, attachBytes = attachBytes),
        ratePushes = ratePushes,
    )

    /** A 600-byte chunk, over the 512-byte charge floor, so its charge is its size. */
    private fun chunk(seed: Int): Pair<ByteArray, ByteArray> {
        val data = ByteArray(600) { ((it * 13 + seed) and 0xFF).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(data) to data
    }

    private fun aid(seed: Int) = ByteArray(32) { seed.toByte() }

    private suspend fun DefaultClientWebSocketSession.present(ent: Ent) {
        sendRecord(ent)
        assertEquals(ent.q, expectRecord<Ok>(RecordType.OK).q)
    }

    private suspend fun DefaultClientWebSocketSession.upload(
        q: Long,
        scope: ByteArray,
        seed: Int,
    ) {
        val (cid, data) = chunk(seed)
        sendRecord(aput(q, scope, aid(seed), cid, data))
    }

    private suspend fun DefaultClientWebSocketSession.stored(
        q: Long,
        scope: ByteArray,
        seed: Int,
    ) {
        upload(q, scope, seed)
        assertEquals(q, expectRecord<Ok>(RecordType.OK).q)
    }

    private suspend fun DefaultClientWebSocketSession.refused(
        reason: Tickets.Reason,
        q: Long,
        scope: ByteArray,
        seed: Int,
    ) {
        upload(q, scope, seed)
        val err = expectRecord<Err>(RecordType.ERR)
        assertEquals(ErrCode.TICKET, err.code)
        assertEquals(q, err.q)
        assertEquals(reason.word, err.msg)
        assertContentEquals(scope, err.scope, "a refused aput carries its scope, as every other aput err does")
    }

    @Test
    fun anUnticketedUploadIsRefusedMissingWithItsQAndScope() {
        val scope = testScope(1)
        withServer(filesConfig(), clock = FakeClock(now)) {
            connect {
                assertEquals(gated, helloHandshake().entGates)
                subscribe(scope)
                refused(Tickets.Reason.MISSING, q = 5L, scope = scope, seed = 1)
            }
            assertEquals(0L, spool.metrics.attachChunksStoredTotal.sum())
        }
    }

    @Test
    fun aTicketWithoutFilesIsRefusedFlagsAndAnExpiredOneExpired() {
        val scope = testScope(2)
        val clock = FakeClock(now)
        withServer(filesConfig(), clock = clock) {
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(clock.now, q = 1L, fl = Tickets.FL_TURN))
                refused(Tickets.Reason.FLAGS, q = 2L, scope = scope, seed = 1)
                present(testEnt(clock.now, q = 3L, fl = Tickets.FL_FILES, expS = clock.now / 1_000 + 600))
                clock.advance((600 + Tickets.SKEW_S + 1) * 1_000)
                refused(Tickets.Reason.EXPIRED, q = 4L, scope = scope, seed = 1)
            }
        }
    }

    @Test
    fun theUploadersOwnTicketAdmitsTheChunkAndIsChargedItsSize() {
        val scope = testScope(3)
        withServer(filesConfig(), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(now, fl = Tickets.FL_FILES))
                stored(q = 2L, scope = scope, seed = 1)
            }
            assertEquals(600L, spool.metrics.ticketAttachBytesChargedTotal.sum())
            assertEquals(1, spool.uploadBudgetCount())
            val metrics = http.get("http://127.0.0.1:$port/metrics").bodyAsText()
            assertTrue(metrics.contains("knit_spool_ticket_attach_bytes_charged_total 600"), metrics)
        }
    }

    /** Only a chunk the store actually holds is charged: a re-send or a conflicting chunk costs nothing. */
    @Test
    fun aDuplicateOrConflictingChunkCostsNothing() {
        val scope = testScope(4)
        withServer(filesConfig(), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(now, fl = Tickets.FL_FILES))
                stored(q = 2L, scope = scope, seed = 1)
                stored(q = 3L, scope = scope, seed = 1)
                val (otherCid, otherData) = chunk(2)
                sendRecord(aput(4L, scope, aid(1), otherCid, otherData))
                assertEquals(ErrCode.CONFLICT, expectRecord<Err>(RecordType.ERR).code)
            }
            assertEquals(600L, spool.metrics.ticketAttachBytesChargedTotal.sum())
        }
    }

    /** The budget is `min(ab, SPOOL_TICKET_ATTACH_BYTES)`: a smaller claim caps, and a larger one is clamped. */
    @Test
    fun theBudgetIsTheSmallerOfTheClaimAndTheSpoolsCap() {
        val scope = testScope(5)
        withServer(filesConfig(attachBytes = 1_500), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(now, q = 1L, ps = alice, fl = Tickets.FL_FILES))
                stored(q = 2L, scope = scope, seed = 1)
                stored(q = 3L, scope = scope, seed = 2)
                refused(Tickets.Reason.BUDGET, q = 4L, scope = scope, seed = 3)
            }
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(now, q = 1L, ps = bob, fl = Tickets.FL_FILES, ab = 700))
                stored(q = 2L, scope = scope, seed = 4)
                refused(Tickets.Reason.BUDGET, q = 3L, scope = scope, seed = 5)
            }
            connect {
                helloHandshake()
                subscribe(scope)
                val carol = ByteArray(Tickets.PS_BYTES) { 0x33 }
                present(testEnt(now, q = 1L, ps = carol, fl = Tickets.FL_FILES, ab = 1_000_000))
                stored(q = 2L, scope = scope, seed = 6)
                stored(q = 3L, scope = scope, seed = 7)
                refused(Tickets.Reason.BUDGET, q = 4L, scope = scope, seed = 8)
            }
            assertEquals(3_000L, spool.metrics.ticketAttachBytesChargedTotal.sum())
        }
    }

    /** A renewed ticket on the same day shares the pseudonym, so it shares what that pseudonym spent. */
    @Test
    fun aRenewedTicketOnTheSameDayDoesNotRefillTheBudget() {
        val scope = testScope(6)
        withServer(filesConfig(attachBytes = 600), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(now, q = 1L, fl = Tickets.FL_FILES))
                stored(q = 2L, scope = scope, seed = 1)
                present(testEnt(now, q = 3L, fl = Tickets.FL_FILES, expS = now / 1_000 + 2 * 86_400 - 120))
                refused(Tickets.Reason.BUDGET, q = 4L, scope = scope, seed = 2)
            }
        }
    }

    /** The trap the ADR names: casts' one-payer rule must not leak into uploads. */
    @Test
    fun anotherSubscribersFilesTicketNeverAdmitsAnUnticketedUpload() {
        val scope = testScope(7)
        withServer(filesConfig(), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(now, ps = alice, fl = Tickets.FL_FILES or Tickets.FL_CAST))
                connect {
                    helloHandshake()
                    subscribe(scope)
                    refused(Tickets.Reason.MISSING, q = 2L, scope = scope, seed = 1)
                }
                stored(q = 3L, scope = scope, seed = 2)
            }
        }
    }

    /** S-7.6-23: a member with no ticket fetches every chunk a member with one stored. */
    @Test
    fun aMemberWithoutATicketFetchesWhatATicketHolderStored() {
        val scope = testScope(8)
        val (_, data) = chunk(1)
        withServer(filesConfig(), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                present(testEnt(now, fl = Tickets.FL_FILES))
                stored(q = 2L, scope = scope, seed = 1)
            }
            connect {
                helloHandshake()
                subscribe(scope)
                sendRecord(Aget(t = RecordType.AGET, q = 3L, scope = scope, aid = aid(1), from = 0, n = 1))
                assertContentEquals(data, expectRecord<Achunk>(RecordType.ACHUNK).data)
                assertEquals(3L, expectRecord<Ok>(RecordType.OK).q)
            }
        }
    }

    /** A refusal comes before every bucket, so a free phone's retries never drain its push budget. */
    @Test
    fun aRefusedUploadSpendsNoPushToken() {
        val scope = testScope(9)
        withServer(filesConfig(ratePushes = 1), clock = FakeClock(now)) {
            connect {
                helloHandshake()
                subscribe(scope)
                repeat(8) { refused(Tickets.Reason.MISSING, q = 10L + it, scope = scope, seed = 1) }
                present(testEnt(now, q = 20L, fl = Tickets.FL_FILES))
                stored(q = 21L, scope = scope, seed = 1)
            }
        }
    }

    /** Memory only: the budget a pseudonym spent does not outlive the spool, nor its ticket's window. */
    @Test
    fun budgetsResetOnRestartAndArePrunedOnceTheirTicketHasExpired() {
        val scope = testScope(10)
        val clock = FakeClock(now)
        repeat(2) {
            withServer(filesConfig(attachBytes = 600), clock = clock) {
                connect {
                    helloHandshake()
                    subscribe(scope)
                    present(testEnt(clock.now, q = 1L, fl = Tickets.FL_FILES, expS = clock.now / 1_000 + 3_600))
                    stored(q = 2L, scope = scope, seed = 1 + it)
                    refused(Tickets.Reason.BUDGET, q = 3L, scope = scope, seed = 10 + it)
                }
                spool.sweepTick()
                assertEquals(1, spool.uploadBudgetCount())
                clock.advance((3_600 + Tickets.SKEW_S + 1) * 1_000)
                spool.sweepTick()
                assertEquals(0, spool.uploadBudgetCount())
                clock.now = now
            }
        }
    }
}
