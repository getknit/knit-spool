---
id: "2026-10.d2zb"
slug: bare-scopes-are-reclaimed
title: "bare scopes are reclaimed, not held against maxScopes"
date: 2026-10-09
topics: [store, quota, abuse]
---

# ADR 2026-10.d2zb — bare scopes are reclaimed, not held against maxScopes

Status: Accepted (2026-10-09)

**Context:** At `SPOOL_MAX_SCOPES` (default 64) a `sub` for a new scope was refused
`quota`, and the only path that ever deleted a scope row was the byte watermark's
`shedOldestScope`. The TTL sweep expires frames, attachments and tombstones but kept the
row. A scope whose conversation had ended, with its frames all expired, therefore held one
of the 64 slots until byte pressure happened to reach it. A small or quiet spool never gets
byte pressure, so it could fill with empty rows and refuse every new conversation
indefinitely. This looked like the quota doing its job, but the quota exists to bound
creation, not to keep rows that hold nothing.

**Decision:** A scope is **bare** when it holds nothing but its row: no live frames, no
attachments, no frame or attachment tombstones. Bare scopes are given back two ways. The
sweeper drops every bare scope whose last activity is older than its own `ttlMs`
(`ScopeStore.reclaimIdle`). A new scope at the cap evicts the least-recently-active bare
scope before it is refused (`ScopeStore.subscribe`). Neither ever drops a scope in the
server's `held` set: the commons, and any scope a live connection subscribes to. Both
count in `knit_spool_reclaims_total`. The alternative a reader reaches for first is to
evict the least-recently-active scope outright, the way the watermark does. That turns
scope creation into a way to flush other people's held frames, which the `quota` refusal
exists to prevent. Tombstones are part of "bare" because dropping them would let a stale
member re-push frames the scope had already expired.

**Consequences / limits:** A dropped bare scope is unknown again, so its next `sub` or
`push` goes through the new-scope gates: PoW on a mining spool, plus the per-IP new-scope
bucket. The push-recreate path already handles a subscribed-but-missing scope after a
shed. A `sub` counts as held from before its store call until its subscriber entry
exists (`SpoolServer.subscribing`), so a newcomer at the cap cannot evict a scope out from
under a connection that is subscribing to it. `held` is a required argument, so no caller
can forget it. A refused attempt at the cap scans the bare candidates each time. In one
run of 300 attempts at 4,096 scopes it took a median of 0.5 ms with frames everywhere and
1.2 ms with tombstones everywhere. The per-address new-scope bucket bounds how often that
is paid. This does not help a spool whose
slots are held by frames or by live subscribers. An attacker holding 64 subscriptions
open still blocks creation exactly as before, bounded by the connection caps. The guards
are `ScopeStoreContractTest` (`subscribeAtTheCapEvictsTheLeastRecentlyActiveBareScope`,
`reclaimIdleDropsOnlyBareScopesIdlePastTheirTtl`, run against both stores),
`SubPushPullTest.aNewScopeAtMaxScopesReclaimsTheLeastRecentlyActiveBareScope` and
`SweeperTest.theSweeperReclaimsIdleBareScopesNobodyHolds`. `maxScopesQuotaErrsPerScope`
pins that held scopes still answer `quota`.
