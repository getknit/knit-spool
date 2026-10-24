---
id: "2026-10.d9ez"
slug: the-sweep-runs-in-batches
title: "the sweep runs in batches and visits only due scopes"
date: 2026-10-24
topics: [store, sweep, capacity]
---

# ADR 2026-10.d9ez — the sweep runs in batches and visits only due scopes

Status: Accepted (2026-10-24)

**Context:** knit-spool#4. `sweepTick` called `ScopeStore.sweep(now)` once per
`SPOOL_SWEEP_MS`, and `SqliteScopeStore` ran it as one transaction on the single store
thread. That transaction read every `scope_id`. For every scope it ran `readRow`, the four
expiry statements in `sweepScope`, and a `writeRow` that rewrote the row even when nothing
had changed. Every push, sub, pull and aput waited behind the whole pass, so the spool
stopped delivering once a period. The pass did the same work for a scope with nothing to
expire as for one with frames due, which made the stall linear in the scope count. On
0.5.0 on a Hetzner cx43, `bench/cx43` measured the stall at ~0.8 s with 28,000 scopes,
~3.7 s with 127,000 and ~7.6 s with 256,000, which is ~29 ms per 1,000 scopes. A stored
scope costs only ~340 B of heap, so the stall was what bounded the scope count, not memory
(ADR 2026-10.4ynr, recorded on the `bench/cx43` branch).

It looked like a memory limit, because the worst trials also ran with a starved page cache.
`SPOOL_SWEEP_MS=20000` moved the stalls to every 20 s at the same size, which confirmed the
sweep as the cause.

**Decision:** `ScopeStore.sweep(now, after, limit)` sweeps at most `limit` scopes in
unsigned scope-id order, starting after the cursor `after`. It returns a `ScopeBatch` of
changes plus the next cursor, which is null once the pass is done. `reclaimIdle` (ADR
2026-10.d2zb) pages the same way and returns the ids it dropped. It was the other
statement over every scope in the tick. `sweepTick` runs each pass through `inBatches`:
one store call per batch of `SWEEP_BATCH_SCOPES` (512), with each batch's digests
broadcast as it lands. This is the pattern `reclaimSpace` uses (ADR 2026-10.us34). The
store dispatcher is `limitedParallelism(1)` and runs work FIFO, so records queued during a
batch run before the next batch. There is no whole-pass form in main; tests loop the cursor
with `sweepAll` and `reclaimIdleAll`.

In `SqliteScopeStore` a batch is one windowed `SELECT` over `scopes` (`SWEEP_WINDOW_SQL`).
It returns each scope's row together with a due flag built from four `EXISTS` clauses,
one for each kind of row `sweepScope` and `sweepAttachments` expire. Only due scopes are
swept. A swept row is written back only when an expired frame or a dropped attachment
changed it. A tombstone ageing out does not change the row. Each cutoff keeps the bare
indexed column on the left (`b.arrived_at < ? - s.ttl_ms`), and `EXPLAIN QUERY PLAN`
shows a primary-key range on `scopes` with covering-index seeks on `idx_blobs_eviction`,
`idx_tombstones_expiry` and `idx_attachments_eviction`. `attachment_tombstones` has no
expiry index. It seeks by `scope_id` and filters, the same as the delete
`sweepAttachments` already runs. The reclaim's window (`RECLAIM_WINDOW_SQL`) carries an
idle-and-bare flag the same way. Both step through one `window` helper that reads the rows
out before any delete. `InMemoryScopeStore` keys its scopes in a `TreeMap`, so it pages the
same way, since lowercase hex sorts like the unsigned bytes it spells.

The alternative a reader reaches for first is a per-scope `next_due` column with an index,
so the sweep reads only due scopes and never walks the rest. That would be exact, but it
needs a schema bump. It would also need upkeep on every mutation path: push, eviction, the
tombstone caps, attachment put and drop, and a `ttl` lowered on re-subscribe. A missed
path would make expiry late. A batch bounded by elapsed time instead of scope count would
adapt to the disk, but which scopes land in a batch would then depend on the clock, and the
tests could not pin it.

**Consequences / limits:** measured on a desktop SSD with a temporary benchmark (not
committed) that seeded 100,000 and 256,000 scopes with one 512 B frame each. Half the
frames were set to expire mid-run. On that machine `main`'s whole-pass sweep cost ~10 µs a
scope, about a third of the cx43's ~29 µs.

| 256,000 scopes | `main`, one hold | batched, total | batched, slowest batch |
| --- | ---: | ---: | ---: |
| nothing due | 2,660 ms | 190 ms | 0.4 ms |
| half due | 4,350 ms | 3,438 ms | 25 ms |
| every scope due | 4,098 ms | 3,700 ms | 28 ms |

A batch of due scopes averaged ~7 ms. The slowest batches are the ones whose commit crosses
SQLite's 1,000-page WAL autocheckpoint, a cost pushes pay too. Total work is still
proportional to the scopes with something to expire, and the expiry itself is not cheaper.
What changed is that it no longer holds the store for the whole pass. A scope with nothing
due still costs four index seeks inside native SQLite per pass.

The whole-pass `reclaimIdle` cost ~10–38 ms at 256,000 on that desktop, depending on how
many scopes were past their TTL. A pass that drops many scopes at once, after a mass
disconnect for example, also paid every delete and its cascades in that one call. Batched,
a window drops at most 512. The same benchmark showed seeding 256,000 scopes was
superlinear (185 s, against 32 s for 100,000), because `subscribe` counted every scope for
each new one. ADR 2026-10.ywkw takes that count out.

The `TreeMap` has a trap. `TreeMap.remove` of a node with two children moves its
successor's key and value into that node. An entry held across the `remove` then reads the
wrong scope. `subscribe` and `shedOldestScope` copy the key and value out first. The
`TreeMap` also breaks `lastActivity` ties by scope id, as SQLite's `idx_scopes_activity`
already did. `PowGateTest.aFullStampCacheVerifiesAgainInsteadOfGrowing` had leaned on
insertion order, and now advances the clock instead.

Guards:

- `SweeperTest.aRecordQueuedMidSweepRunsBeforeThePassEnds` asserts that a push arriving
  mid-pass reaches the store before the last batch. A whole-pass sweep fails it.
- `SqliteScopeStoreTest.aSweepWithNothingDueWritesNothing` reads `PRAGMA data_version`
  from a second connection, so an unconditional `writeRow` fails it.
- `ScopeStoreContractTest.eachKindOfExpiryMakesAScopeDue` fails if the due flag drops one
  of the four kinds.
- `ScopeStoreContractTest.sweepBatchesWalkEveryScopeOnceInIdOrder` covers the cursor, the
  unsigned order, and a cursor scope shed between batches.
- `SweeperTest.theIdleReclaimRunsInBatches` fails if the reclaim runs in one call, and
  `ScopeStoreContractTest.reclaimBatchesSurviveDroppingTheScopeTheyEndedOn` covers its
  cursor.
- `sweepAll` and `reclaimIdleAll` default to batches of 2, so every other sweep and reclaim
  assertion in the contract tests also runs a multi-batch pass on both stores.

The capacity numbers come from `bench/cx43`. Re-run its `scopes128k`/`scopes256k` trials
before raising `SPOOL_MAX_SCOPES` past the ~20,000 ADR 2026-10.4ynr holds it to.
