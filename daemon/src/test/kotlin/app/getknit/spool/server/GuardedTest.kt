// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.store.AttachmentInfo
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.ScopeStore
import ch.qos.logback.classic.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `guarded` answers every handler failure with `err internal` but writes its stack trace at most
 * once a minute, counting the rest onto the next trace. A failure a client can provoke arrives at
 * the record rate into a log nothing rotates; the count of every one lives in the metrics.
 */
class GuardedTest {
    private val attachLimits =
        HardLimits(
            maxBlob = 1_024,
            maxFramesCap = 100,
            maxTtlMs = 86_400_000L,
            maxScopes = 4,
            maxAttachBytes = 4_096,
            maxAChunk = 512,
        )

    /** A store whose presence lookup fails: the only way to reach the catch-all from the wire. */
    private class BrokenPresenceStore(
        private val inner: ScopeStore,
    ) : ScopeStore by inner {
        override fun attachmentPresence(
            scopeId: ByteArray,
            aid: ByteArray,
            now: Long,
        ): AttachmentInfo = throw IllegalStateException("presence lookup exploded")
    }

    @Test
    fun aHandlerFailureIsAnsweredEveryTimeButTracedOncePerMinute() {
        val config = testConfig(hardLimits = attachLimits)
        val clock = FakeClock()
        val aid = ByteArray(32) { 7 }
        val events =
            withLogCapture("app.getknit.spool.server.SpoolServer") {
                withServer(config, clock, store = BrokenPresenceStore(InMemoryScopeStore(config.hardLimits))) {
                    connect {
                        helloHandshake()
                        val scope = testScope(1)
                        subscribe(scope)
                        // Five failures inside one window: five answers, one trace.
                        (60L..64L).forEach { q ->
                            sendRecord(Ahave(t = RecordType.AHAVE, q = q, scope = scope, aid = aid))
                            val err = expectRecord<Err>(RecordType.ERR)
                            assertEquals(ErrCode.INTERNAL, err.code)
                            assertEquals(q, err.q)
                        }
                        // The next window opens with the four that went unwritten on its line.
                        clock.advance(60_000L)
                        sendRecord(Ahave(t = RecordType.AHAVE, q = 65L, scope = scope, aid = aid))
                        assertEquals(ErrCode.INTERNAL, expectRecord<Err>(RecordType.ERR).code)
                    }
                }
            }
        val traces = events.filter { it.level == Level.ERROR }
        assertEquals(2, traces.size, "one trace per window: ${traces.map { it.formattedMessage }}")
        assertEquals("record handling failed", traces[0].formattedMessage)
        assertEquals("record handling failed (4 more since the last trace)", traces[1].formattedMessage)
        assertTrue(traces.all { it.throwableProxy != null }, "each written trace carries the exception")
    }
}
