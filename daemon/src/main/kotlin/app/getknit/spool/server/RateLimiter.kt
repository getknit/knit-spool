// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.min

private const val MS_PER_SECOND = 1000.0
private const val IP_TURN_FACTOR = 4.0
private const val TURN_WINDOW_S = 600.0
private const val TURN_IP_BURST = 2.0
private const val IP_CAST_FACTOR = 4.0

/** Eight times one connection's sigs, not four: typing is every phone's, and carrier NAT puts many behind one address. */
private const val IP_SIG_FACTOR = 8.0
private const val LIVE_BURST_FACTOR = 4.0

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
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            return 0L
        }
        return retryMs()
    }

    /** What [take] would return, without taking: 0 when a token is there, else the wait for one. */
    @Synchronized
    fun peek(): Long {
        refill()
        return if (tokens >= 1.0) 0L else retryMs()
    }

    /**
     * Gives back a token [take] granted, for a caller that charged this bucket and then found a
     * second one refusing the same record — what it was refused for must not cost it here as well.
     */
    @Synchronized
    fun refund() {
        refill()
        tokens = min(burst, tokens + 1.0)
    }

    private fun refill() {
        val now = clock()
        tokens = min(burst, tokens + (now - lastRefillMs) / MS_PER_SECOND * ratePerSec)
        lastRefillMs = now
    }

    private fun retryMs(): Long = ceil((1.0 - tokens) / ratePerSec * MS_PER_SECOND).toLong()
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

/**
 * Per-client-IP state: connection count, the new-scope-creation bucket and, on a spool that issues
 * TURN credentials or fans out casts or sigs, their per-address budgets — a multiple of one
 * connection's, so a household behind one address is not one caller.
 */
class IpState(
    rateNewScopesPerMin: Int,
    clock: () -> Long,
    turnRatePer10Min: Int? = null,
) {
    val connections = AtomicInteger(0)

    val newScopeBucket =
        TokenBucket(
            ratePerSec = rateNewScopesPerMin / 60.0,
            burst = 4.0 * rateNewScopesPerMin,
            clock = clock,
        )

    val turnBucket =
        turnRatePer10Min?.let {
            TokenBucket(ratePerSec = IP_TURN_FACTOR * it / TURN_WINDOW_S, burst = IP_TURN_FACTOR * TURN_IP_BURST, clock = clock)
        }

    private val castBuckets = RateFollowingBucket(IP_CAST_FACTOR, clock)
    private val sigBuckets = RateFollowingBucket(IP_SIG_FACTOR, clock)

    /** This address's cast budget, four times one connection's like [turnBucket], built for [rate] casts a second. */
    fun castBucket(rate: Int): TokenBucket = castBuckets.forRate(rate)

    /** This address's sig budget, eight times one connection's, built for [rate] sigs a second (SPOOL_PROTOCOL.md §7.8). */
    fun sigBucket(rate: Int): TokenBucket = sigBuckets.forRate(rate)

    @Volatile
    var lastSeen = 0L
}

/**
 * A per-address budget of [factor] times a per-connection rate, with the burst of four times that.
 * Built on first use and rebuilt when a reload moves the rate, because a live lane can be switched
 * on long after its address first connected.
 */
private class RateFollowingBucket(
    private val factor: Double,
    private val clock: () -> Long,
) {
    private var bucket: TokenBucket? = null
    private var rate = 0

    @Synchronized
    fun forRate(rate: Int): TokenBucket {
        val current = bucket
        if (current != null && this.rate == rate) return current
        this.rate = rate
        return TokenBucket(factor * rate, factor * LIVE_BURST_FACTOR * rate, clock).also { bucket = it }
    }
}
