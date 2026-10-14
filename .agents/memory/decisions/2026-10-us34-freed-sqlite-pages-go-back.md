---
id: "2026-10.us34"
slug: freed-sqlite-pages-go-back
title: "freed SQLite pages go back to the disk"
date: 2026-10-14
topics: [store, deploy]
---

# ADR 2026-10.us34 — freed SQLite pages go back to the disk

Status: Accepted (2026-10-14)

**Context:** Hosted spool 564699cc had a 58 MB `spool.db` holding 1.3 MB of live data.
`SqliteScopeStore` never set `auto_vacuum`, so SQLite left it at `NONE`. Every delete path (TTL
sweep, eviction, attachment drops, the watermark shed, bare-scope reclaim) puts pages on the
freelist. SQLite reuses those pages but never truncates the file, so the file is the size of the
most the spool ever held. That reads like the watermark not working, but `totalBytes` and
`knit_spool_live_bytes` were correct. They count payload, not the file, and nothing reported the
file. Attachment chunks are ~48 KiB overflow chains, so one burst of uploads that expires leaves
tens of MB behind.

**Decision:** The store runs `auto_vacuum=INCREMENTAL`. A new file gets the mode before its first
table. An existing `NONE` file is converted with one `VACUUM` on open, before the store serves
anything. The conversion is skipped with a warning when the data directory's filesystem has less
than twice the live data free. Its temp copy is kept in the data directory too, through
`PRAGMA temp_store_directory` for the length of the `VACUUM`, so that check covers it. A failed
conversion leaves the file untouched and the spool boots anyway. A `VACUUM` that throws but leaves
`auto_vacuum=2` and a clean `integrity_check` counts as converted. `ScopeStore.reclaimSpace` runs `incremental_vacuum` in 2,048-page batches and then
`wal_checkpoint(TRUNCATE)`, so the shorter file and an empty WAL reach the disk at once. It does
nothing until more than `RECLAIM_FLOOR_BYTES` (4 MiB) is free, because push-and-expire churn
would otherwise shrink and regrow the file on every tick. `sweepTick` calls it after `maybeShed`,
up to 64 batches (512 MiB) a tick, one store call per batch. `journal_size_limit` caps the WAL at
64 MiB. `knit_spool_store_file_bytes` reports the main file plus the WAL. It is absent for the
in-memory store.

A periodic full `VACUUM` is the first alternative a reader reaches for. It rebuilds the whole file
on the single store thread, so nothing is served for the length of the rebuild, and every run needs
the free disk the conversion needs. `auto_vacuum=FULL` truncates on every commit. That puts
page-moving on the push path and gives no way to batch or defer it.

**Consequences / limits:** A batch measured ~20 ms per 8 MiB on a desktop SSD with half the file
free, the worst layout for page moves. A VPS disk will be slower. Incremental mode adds a
pointer-map page per few hundred pages, about 0.2%. It returns whole free pages only.
Half-empty B-tree pages stay until they refill, so the file can sit above live data by
fragmentation as well as by the floor. The disk must still hold the peak, `SPOOL_MAX_BYTES`, so
this changes what a spool costs, not what it needs. The conversion runs at most once per file. On
a large file it can lengthen the first boot by as long as copying the live data takes.

Where the conversion's copy goes is a trap too. `VACUUM` builds it in a temp database that
spills to a file once it outgrows the page cache, and SQLite picks that file's directory itself. It
skips any directory it cannot write, so under a read-only root it skips `/var/tmp` and lands in
`/tmp`. The fleet runs spools with `tmpfs: /tmp:exec,size=16m`, charged to the container's memory. A
reviewer found that a file with more than ~15 MiB live failed with `SQLITE_FULL` there, left
`auto_vacuum` at 0 and retried on every boot, while the space check, reading `/data`, passed every
time. At 15–16 MiB the `VACUUM` threw after the file had converted. `temp_store_directory` is
deprecated and process-wide. The store sets it only while it opens, before anything else uses
SQLite, and clears it after. `VACUUM INTO` a file in the data directory and a swap would avoid the
global. It would also need the connection reopened and a stale `-wal` kept off the swapped-in
file, which is more to get wrong for a step that runs once per file.

The other trap is `Statement.execute`. sqlite-jdbc steps a statement once, and `incremental_vacuum`
frees one page per step. `execute("PRAGMA incremental_vacuum(2048)")` therefore frees one page
and looks like it works. `executeQuery` throws because the pragma has no columns. Only
`executeUpdate` steps it to completion. Guards:

- `SqliteScopeStoreTest.reclaimSpaceShrinksTheFileAfterFramesExpire` asserts one call frees
  exactly a full batch.
- `reclaimSpaceLeavesASmallFreelistAlone` covers the floor.
- `aNoneModeFileIsConvertedOnOpen` covers the conversion.
- `theConversionKeepsItsTempCopyInTheDataDirectory` points SQLite's temp directory elsewhere and
  watches with inotify where the copy is created.
- `aFreshDatabaseIsIncrementalAutoVacuum` covers new files.
- `SweeperTest.theSweepGivesExpiredFramesFileSpaceBack` and
  `OpsTest.metricsReportTheStoreFileOnlyForASqliteSpool` cover the sweeper and the gauge.
