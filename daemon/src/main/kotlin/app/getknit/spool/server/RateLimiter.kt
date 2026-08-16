// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.min

/**
 * Token bucket for the spec §6.4 rate limits. `take()` returns 0 on success or the milliseconds
 * until the next token — the `retryMs` an `err rate` record carries. Burst capacity is the
 * caller's business; the daemon uses 4× the sustained rate everywhere (one knob fewer).
 */
class TokenBucket(
    private val ratePerSec: Double,
    private val burst: Double,
    private val clock: () -> Long,
) {
    private var tokens = burst
    private var lastRefillMs = clock()

    @Synchronized
    fun take(): Long {
        val now = clock()
        tokens = min(burst, tokens + (now - lastRefillMs) / 1000.0 * ratePerSec)
        lastRefillMs = now
        if (tokens >= 1.0) {
            tokens -= 1.0
            return 0L
        }
        return ceil((1.0 - tokens) / ratePerSec * 1000.0).toLong()
    }
}

/**
 * Rolling abuse window: every `err rate` strikes; [limit] strikes inside [windowMs] means the
 * client is ignoring backpressure and the connection is closed 4003 (spec §7.1).
 */
class StrikeWindow(
    private val limit: Int,
    private val windowMs: Long,
) {
    private var windowStart = 0L
    private var count = 0

    @Synchronized
    fun strike(now: Long): Boolean {
        if (now - windowStart > windowMs) {
            windowStart = now
            count = 0
        }
        count++
        return count >= limit
    }
}

/** Per-client-IP state: connection count and the new-scope-creation bucket. */
class IpState(
    rateNewScopesPerMin: Int,
    clock: () -> Long,
) {
    val connections = AtomicInteger(0)

    val newScopeBucket =
        TokenBucket(
            ratePerSec = rateNewScopesPerMin / 60.0,
            burst = 4.0 * rateNewScopesPerMin,
            clock = clock,
        )

    @Volatile
    var lastSeen = 0L
}
