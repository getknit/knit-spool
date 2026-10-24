// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pow
import app.getknit.spool.protocol.PowStamp
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.PushResult
import app.getknit.spool.store.ScopeBatch
import app.getknit.spool.store.ScopeStore
import app.getknit.spool.store.SqliteScopeStore
import app.getknit.spool.store.SweepChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The periodic sweeper: TTL expiry re-anchors, PoW-cache pruning. Driven via sweepTick(). */
class SweeperTest {
    @TempDir
    lateinit var tempDir: Path

    /**
     * Logs sweep and reclaim batches and pushes in the order the store runs them. Each sweep batch
     * pauses [sweepPauseMs] to stand in for a big store.
     */
    private class CallLogStore(
        private val inner: ScopeStore,
        private val sweepPauseMs: Long = 0L,
    ) : ScopeStore by inner {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        val firstBatch = CompletableDeferred<Unit>()

        override fun sweep(
            now: Long,
            after: ByteArray?,
            limit: Int,
        ): ScopeBatch<SweepChange> {
            calls += "sweep"
            firstBatch.complete(Unit)
            Thread.sleep(sweepPauseMs)
            return inner.sweep(now, after, limit)
        }

        override fun reclaimIdle(
            now: Long,
            held: (ByteArray) -> Boolean,
            after: ByteArray?,
            limit: Int,
        ): ScopeBatch<ByteArray> {
            calls += "reclaim"
            return inner.reclaimIdle(now, held, after, limit)
        }

        override fun push(
            scopeId: ByteArray,
            blobId: ByteArray,
            data: ByteArray,
            now: Long,
        ): PushResult {
            calls += "push"
            return inner.push(scopeId, blobId, data, now)
        }
    }

    /**
     * knit-spool#4, ADR 2026-10.d9ez: the sweep holds the store one batch at a time, so a record that
     * arrives mid-pass runs between batches instead of after the whole pass. A sweep that held the
     * store for the pass would log the push after its only batch.
     */
    @Test
    fun aRecordQueuedMidSweepRunsBeforeThePassEnds() {
        val store = CallLogStore(InMemoryScopeStore(manyScopes), sweepPauseMs = 20L)
        store.seedBareScopes(10 * SWEEP_BATCH_SCOPES, now = 0L)
        withServer(testConfig(hardLimits = manyScopes), store = store) {
            connect {
                helloHandshake()
                subscribe(testScope(1))
                val pass = launch { spool.sweepTick() }
                store.firstBatch.await()
                val (id, data) = testBlob(1)
                sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                expectRecord<Ok>(RecordType.OK)
                pass.join()
            }
        }
        val pushAt = store.calls.indexOf("push")
        assertTrue(pushAt in 1 until store.calls.lastIndexOf("sweep"), "the push ran at ${store.calls}")
    }

    /** ADR 2026-10.d9ez: the idle reclaim drops scopes a batch at a time too, and counts every one. */
    @Test
    fun theIdleReclaimRunsInBatches() {
        val store = CallLogStore(InMemoryScopeStore(manyScopes))
        withServer(testConfig(hardLimits = manyScopes), store = store) {
            store.seedBareScopes(3 * SWEEP_BATCH_SCOPES, now = clock.now)
            clock.advance(20_000)
            spool.sweepTick()
            assertEquals(0, store.scopeCount())
            assertEquals(3L * SWEEP_BATCH_SCOPES, spool.metrics.reclaimsTotal.sum())
        }
        assertTrue(store.calls.count { it == "reclaim" } >= 3, "the reclaim ran as ${store.calls}")
    }

    private val manyScopes = HardLimits(maxBlob = 1_024, maxFramesCap = 100, maxTtlMs = 86_400_000L, maxScopes = 16_384)

    /** [count] empty scopes, last active at [now], none of which collides with a `testScope`. */
    private fun ScopeStore.seedBareScopes(
        count: Int,
        now: Long,
    ) = repeat(count) { i ->
        val s = ByteArray(32)
        s[0] = 0x7f
        s[1] = (i ushr 8).toByte()
        s[2] = i.toByte()
        subscribe(s, testBounds(), now, held = { false })
    }

    /**
     * ADR 2026-10.us34: the tick that expires a scope's frames also hands their pages back, so the
     * file follows what the spool holds rather than the most it ever held.
     */
    @Test
    fun theSweepGivesExpiredFramesFileSpaceBack() {
        val limits = HardLimits(maxBlob = 65_536, maxFramesCap = 1_000, maxTtlMs = 86_400_000L, maxScopes = 4)
        val store = SqliteScopeStore.open(tempDir, limits)
        withServer(testConfig(hardLimits = limits), store = store) {
            store.subscribe(testScope(1), ScopeBounds(maxFrames = 1_000, ttlMs = 10_000L, maxBlob = 65_536), clock.now, held = { false })
            val random = Random(7)
            repeat(320) {
                // 20 MiB
                val data = random.nextBytes(65_536)
                val id = MessageDigest.getInstance("SHA-256").digest(data)
                assertTrue(store.push(testScope(1), id, data, clock.now) is PushResult.Stored)
            }
            val full = assertNotNull(store.storeFileBytes())

            clock.advance(20_000)
            spool.sweepTick()

            val after = assertNotNull(store.storeFileBytes())
            assertTrue(after < full / 2 && after <= SqliteScopeStore.RECLAIM_FLOOR_BYTES + 1024 * 1024, "$full -> $after bytes")
            store.close()
        }
    }

    /** The loop `start()` launches is what runs [SpoolServer.sweepTick] in production: nothing here calls it. */
    @Test
    fun theScheduledSweeperRunsOnItsCadence() {
        withServer(testConfig(sweepMs = 20L)) {
            connect {
                helloHandshake()
                subscribe(testScope(1)) // ttlMs = 10_000
                val (id, data) = testBlob(1)
                sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                expectRecord<Ok>(RecordType.OK)

                clock.advance(20_000)
                assertEquals(0, expectRecord<Digest>(RecordType.DIGEST).count)
            }
        }
    }

    @Test
    fun expiryBroadcastsAFreshDigest() {
        withServer {
            connect {
                helloHandshake()
                subscribe(testScope(1)) // ttlMs = 10_000
                val (id, data) = testBlob(1)
                sendRecord(Push(t = RecordType.PUSH, q = 1L, scope = testScope(1), blobId = id, data = data))
                expectRecord<Ok>(RecordType.OK)

                clock.advance(20_000)
                spool.sweepTick()

                val digest = expectRecord<Digest>(RecordType.DIGEST)
                assertEquals(0, digest.count)
                assertTrue(digest.digest.contentEquals(ByteArray(8)))
            }
        }
    }

    /**
     * A bare scope idle past its TTL is dropped (ADR 2026-10.d2zb); one a live connection
     * subscribes to, and the commons, are kept however long they sit empty.
     */
    @Test
    fun theSweeperReclaimsIdleBareScopesNobodyHolds() {
        withServer(testConfig(commons = testCommons())) {
            store.subscribe(testScope(2), testBounds(), clock.now, held = { false })
            connect {
                helloHandshake()
                subscribe(testScope(1)) // ttlMs = 10_000

                clock.advance(5_000)
                spool.sweepTick()
                assertEquals(3, store.scopeCount(), "nothing is idle inside its TTL")

                clock.advance(120_000)
                spool.sweepTick()
                assertTrue(store.isUnknownScope(testScope(2)))
                assertTrue(!store.isUnknownScope(testScope(1)), "a subscribed scope was reclaimed")
                assertTrue(!store.isUnknownScope(commonsScope()), "the commons was reclaimed")
                assertEquals(1L, spool.metrics.reclaimsTotal.sum())
            }
        }
    }

    @Test
    fun powCacheIsPrunedAfterDayRollover() {
        val bits = 8
        withServer(testConfig(powBits = bits)) {
            val scope = testScope(1)
            val day = Pow.utcDay(clock.now)
            val staleStamp = PowStamp(n = Pow.stamp(scope, day, bits)!!, d = day)
            connect {
                helloHandshake()
                subscribe(scope, pow = staleStamp)
            }
            store.shedOldestScope()
            clock.advance(3 * Pow.DAY_MS)
            spool.sweepTick()
            connect {
                helloHandshake()
                // Without pruning, the cached (scope, day) pair would bypass the day window.
                sendRecord(
                    Sub(
                        t = RecordType.SUB,
                        q = 1L,
                        subs = listOf(ScopeSub(scope = scope, bounds = testBounds(), pow = staleStamp)),
                    ),
                )
                assertEquals(ErrCode.POW, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }
}
