// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.store

/**
 * A whole pass over every scope, cursor by cursor. Tests only: the server makes one store call per
 * batch (ADR 2026-10.d9ez). The small default limit makes every pass a test runs a multi-batch one,
 * so each sweep and reclaim assertion also checks that batching changes nothing.
 */
private fun <T> drain(batch: (after: ByteArray?) -> ScopeBatch<T>): List<T> {
    val items = ArrayList<T>()
    var cursor: ByteArray? = null
    do {
        val done = batch(cursor)
        items += done.items
        cursor = done.next
    } while (cursor != null)
    return items
}

/** A whole [ScopeStore.sweep] pass; see [drain]. */
fun ScopeStore.sweepAll(
    now: Long,
    limit: Int = 2,
): List<SweepChange> = drain { sweep(now, it, limit) }

/** A whole [ScopeStore.reclaimIdle] pass; see [drain]. */
fun ScopeStore.reclaimIdleAll(
    now: Long,
    held: (ByteArray) -> Boolean,
    limit: Int = 2,
): List<ByteArray> = drain { reclaimIdle(now, held, it, limit) }
