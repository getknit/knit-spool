// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

/**
 * A whole sweep pass, cursor by cursor. Tests only: the server sweeps one batch per store call
 * (ADR 2026-10.d9ez). The small default [limit] makes every pass a test runs a multi-batch one, so
 * each sweep assertion also checks that batching changes nothing.
 */
fun ScopeStore.sweepAll(
    now: Long,
    limit: Int = 2,
): List<SweepChange> {
    val changes = ArrayList<SweepChange>()
    var cursor: ByteArray? = null
    do {
        val batch = sweep(now, cursor, limit)
        changes += batch.changes
        cursor = batch.next
    } while (cursor != null)
    return changes
}
