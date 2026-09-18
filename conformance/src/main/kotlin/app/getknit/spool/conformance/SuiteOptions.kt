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
    /** The commons secret, when the operator supplied one; null leaves those checks skipped. */
    val commonsSecret: ByteArray?,
)
