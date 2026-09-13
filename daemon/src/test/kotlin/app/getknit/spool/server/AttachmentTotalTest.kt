// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Ahas
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.store.HardLimits
import ch.qos.logback.classic.Level
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An `aput` declaring more chunks than `HardLimits.maxATotal` — `⌈maxAttachBytes / aChunkBytes⌉`,
 * the most any attachment inside the quota can have (§4.5) — is refused `quota` (S-6.5-3) before
 * its first chunk is stored. Every later `ahave` would otherwise allocate and send a bitmap of
 * `⌈total / 8⌉` bytes: a client-chosen `total` of 80,000,000 was a 10 MB reply to an 80-byte
 * request, and `Int.MAX_VALUE` wrapped the arithmetic into `err internal` with a stack trace.
 */
class AttachmentTotalTest {
    /** A 4 KiB budget derives a bound of exactly one chunk, so `total = 2` is already past it. */
    private val attachLimits =
        HardLimits(
            maxBlob = 1_024,
            maxFramesCap = 100,
            maxTtlMs = 86_400_000L,
            maxScopes = 4,
            maxAttachBytes = 4_096,
            maxAChunk = 512,
        )

    private val data = ByteArray(16) { it.toByte() }
    private val cid: ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    private val aid = ByteArray(32) { 7 }

    private fun aputOf(
        q: Long,
        scope: ByteArray,
        total: Int,
    ): Aput = Aput(t = RecordType.APUT, q = q, scope = scope, aid = aid, idx = 0, total = total, cid = cid, data = data)

    @Test
    fun anOverBoundTotalIsRefusedQuotaBeforeAnyChunkIsStored() {
        assertEquals(1, attachLimits.maxATotal)
        withServer(testConfig(hardLimits = attachLimits)) {
            connect {
                helloHandshake()
                val scope = testScope(1)
                subscribe(scope)
                var q = 40L
                for (total in listOf(2, 80_000_000, Int.MAX_VALUE)) {
                    sendRecord(aputOf(q, scope, total))
                    val err = expectRecord<Err>(RecordType.ERR)
                    assertEquals(ErrCode.QUOTA, err.code, "total $total")
                    assertEquals(q, err.q)
                    assertTrue(err.scope!!.contentEquals(scope))
                    q++

                    // No header was written: presence is absent, not dead, and costs nothing to build.
                    sendRecord(Ahave(t = RecordType.AHAVE, q = q, scope = scope, aid = aid))
                    val has = expectRecord<Ahas>(RecordType.AHAS)
                    assertEquals(q, has.q)
                    assertEquals(0, has.total)
                    assertEquals(0, has.bits.size)
                    assertFalse(has.dead)
                    q++
                }
                assertEquals(0L, store.totalBytes())

                // The bound itself is admissible, and the connection was never in any trouble.
                sendRecord(aputOf(q, scope, total = attachLimits.maxATotal))
                assertEquals(q, expectRecord<Ok>(RecordType.OK).q)
                assertTrue(store.totalBytes() > 0L)
            }
        }
    }

    @Test
    fun anOverBoundTotalNeverReachesTheGuardedCatchAll() {
        val events =
            withLogCapture("app.getknit.spool.server.SpoolServer") {
                withServer(testConfig(hardLimits = attachLimits)) {
                    connect {
                        helloHandshake()
                        val scope = testScope(1)
                        subscribe(scope)
                        sendRecord(aputOf(50L, scope, total = Int.MAX_VALUE))
                        assertEquals(ErrCode.QUOTA, expectRecord<Err>(RecordType.ERR).code)
                        // Unguarded, this is where `(total + 7) / 8` went negative.
                        sendRecord(Ahave(t = RecordType.AHAVE, q = 51L, scope = scope, aid = aid))
                        assertEquals(0, expectRecord<Ahas>(RecordType.AHAS).total)
                    }
                }
            }
        assertTrue(events.none { it.level == Level.ERROR }, "an over-bound total must never become err internal")
    }
}
