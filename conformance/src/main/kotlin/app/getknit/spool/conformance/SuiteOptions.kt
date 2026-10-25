// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

/**
 * One run's knobs, as [main] parses them from the command line and as the daemon's in-process
 * self-test builds them directly. [connectUrl] carries the bearer token when there is one;
 * [url] never does, and is what the bare (unauthenticated) probes connect to.
 */
class SuiteOptions(
    val url: String,
    val connectUrl: String,
    val hasToken: Boolean,
    val timeoutMs: Long,
    val powLimit: Int,
    val destructive: Boolean,
    /**
     * One secret per commons room the operator supplied an invite for, in the order given; empty
     * leaves the checks that need a room's id skipped.
     */
    val commonsSecrets: List<ByteArray> = emptyList(),
    /** The TURN shared secret (experimental), when supplied; null skips the HMAC check. */
    val turnSecret: String? = null,
    /**
     * A ticket signing key, `seed ‖ public key` (experimental), when supplied; null skips the checks
     * that need a ticket the spool accepts — and every TURN check on a spool that requires one.
     */
    val ticketSigningKey: ByteArray? = null,
    /** The audience the spool's tickets must name; needed with [ticketSigningKey]. */
    val ticketAudience: String? = null,
    /**
     * The spool's second name while it is being renamed (S-7.6-47), when supplied; null skips
     * `ent-audience-alias`. Needs [ticketAudience], and differs from it.
     */
    val ticketAudienceAlias: String? = null,
)
