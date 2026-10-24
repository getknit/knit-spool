---
id: "2026-10.ywkw"
slug: a-new-scope-is-admitted
title: "a new scope is admitted without counting every scope"
date: 2026-10-24
topics: [store, quota, capacity]
---

# ADR 2026-10.ywkw — a new scope is admitted without counting every scope

Status: Accepted (2026-10-24)

**Context:** found while measuring ADR 2026-10.d9ez, the sweep's fix for knit-spool#4.
Every new scope's `subscribe` in `SqliteScopeStore` ran `SELECT COUNT(*) FROM scopes`
against `SPOOL_MAX_SCOPES`, which walks the whole table. At the cap, it then looked for a
bare scope to evict (ADR 2026-10.d2zb) by walking `idx_scopes_activity` oldest first. That
walk tested every scope, including each one holding frames, which can never be bare. A
temporary benchmark on a desktop SSD with 100,000 scopes measured:

| a new scope | before |
| --- | ---: |
| below the cap | 0.47 ms |
| at the cap, every scope holding frames, refused | 16.5 ms |
| at the cap, the newest scope bare, evicted | 16.6 ms |

Seeding those 100,000 scopes took 32 s, and 256,000 took 185 s, so the cost grew with the
scope count. A refusal at the cap looks like the cheapest answer the spool gives, but each
one held the store for a walk of every scope. A full spool turning away new scopes stalled
everyone's pushes once per refusal. The same desktop ran the old sweep about three times
faster than a cx43 did.

**Decision:** `SqliteScopeStore` keeps the quota's count in memory. `scopeRows` is the row
count as of the last commit. `scopeDelta` is the open transaction's inserts minus deletes.
`tx` folds the delta into the count on commit and drops it on rollback, so a failed
transaction cannot skew the count. The count is rebuilt from the rows on every open, in
`recomputeOnBoot`. `subscribe` compares `scopeRows + scopeDelta` with the cap. Only two
places write `scopes`: the insert in `subscribe` and `deleteScope`, and both settle the
delta. `scopeCount()` still runs `COUNT(*)`, because `/healthz` uses it as the probe that
the store answers, and `aClosedStoreRefusesInsteadOfAnsweringStale` requires it to refuse
once the store is closed.

The eviction search runs on a new partial index,
`idx_scopes_empty ON scopes(last_activity) WHERE live_count = 0`. `BARE_BY_ACTIVITY_SQL`
starts with `live_count = 0`, and `EXPLAIN QUERY PLAN` shows a scan of `idx_scopes_empty`
as a covering index. A scope holding a frame is never visited. The index is created with
`CREATE INDEX IF NOT EXISTS` on open, so there is no `schema_version` step. An older binary
opening the file maintains the index like any other. `InMemoryScopeStore` already counted
with `scopes.size` and needs nothing.

After, on the same desktop and data:

| a new scope | after |
| --- | ---: |
| below the cap | 0.085 ms |
| at the cap, every scope holding frames, refused | 0.013 ms |
| at the cap, the newest scope bare, evicted | 0.058 ms |

Seeding 100,000 scopes took 17 s.

The first alternative is a persistent counter, a row in `meta` updated in the same
transaction. That would also be exact, but it costs a second write on every scope created
or dropped, to save a count the boot recompute already gets for free. A `bare` flag on each
scope row would make the search exact, skipping tombstoned and attached scopes as well. It
would need upkeep on every tombstone and attachment write path, which is the same reason
ADR 2026-10.d9ez turned down a `next_due` column.

**Consequences / limits:** the index skips only scopes holding frames. Empty scopes that
still hold tombstones or attachments, and bare scopes a live connection holds, are still
visited and rejected. At the cap, on a spool whose scopes are mostly in those states, the
search is still a walk of them. Each write to an empty scope's row now also rewrites its
entry in `idx_scopes_empty`, alongside the one in `idx_scopes_activity` that every row write
already rewrites. A file written before this builds the index once, on its first open.

The trap is a new statement that inserts into or deletes from `scopes` without settling
`scopeDelta`. The count would then drift until the next boot, refusing scopes there was room
for or admitting past the cap. Guards:

- `ScopeStoreContractTest.everyWayAScopeGoesGivesItsSlotBack` covers the shed, the idle
  reclaim and a full subscribe's eviction, each freeing exactly its slot.
- `SqliteScopeStoreTest.theScopeCapCountsWhatTheFileHoldsAfterAReopen` covers the rebuild
  on open.
- `SqliteScopeStoreTest.aFullSubscribeSearchesOnlyEmptyScopes` checks the query plan.
