---
id: "2026-10.5cwn"
slug: one-spool-runs-up-to
title: "one spool runs up to eight commons rooms, configured as numbered variables"
date: 2026-10-24
topics: [commons, config, hello]
---

# ADR 2026-10.5cwn — one spool runs up to eight commons rooms, configured as numbered variables

Status: Accepted (2026-10-24). "Rooms stay boot-only" superseded by ADR 2026-10.hs75 (2026-10-25); the rest stands.

**Context:** knit-next amended spec §7.4 from one commons per spool to several rooms
(its ADR 2026-10.wqu2, commits da6a4355e and 058d10e02 on its `main`). A household or
team wants a second room without a second relay, and running one daemon per room costs a
JVM, a store and a TLS name per room, plus one WebSocket per room on every member's phone.
The daemon assumed one room everywhere:

- `isCommons` compared against one id, and one spool-wide bucket bounded its pushes;
- `shedOldestScope` took one pinned scope;
- `hello` carried one `commons` map, and seven flat `SPOOL_COMMONS_*` variables
  configured it.

The amended spec adds `rooms` to `hello`, each entry led by a one-way `tag` of the scope
id. It also adds a spool-wide budget beside each room's own, and S-7.4-19, which keeps
names off the `hello` of a spool with no access token, a one-room spool included.

**Decision:** Up to eight rooms. The first is the unnumbered `SPOOL_COMMONS_*`, and the
rest are `SPOOL_COMMONS_2_*` through `SPOOL_COMMONS_8_*`, the same seven settings each.
The cap keeps `KNOWN_VARS` a finite list, so a typo in a room's variable still warns and
a tool that renders a spool's environment can know every name (ADR 2026-10.8ymn). A
numbered room needs the first, because `commons` repeats the first for clients that
predate `rooms`. Rooms stay boot-only. The alternatives:

- **One list variable** (`SPOOL_COMMONS_IDS=a,b` plus inline bounds) invents a small
  grammar for names and bounds that the rest of the environment does not have.
- **A rooms file** is a second configuration mechanism beside the environment and
  `SPOOL_RELOAD_FILE`.
- **Reloadable rooms** add runtime failure paths, such as a room that cannot be created
  at reload, for a rotation that happens rarely.

The server keeps each room with its own bucket and tests membership by comparing at most
eight ids. `SPOOL_COMMONS_RATE_TOTAL` bounds all rooms together. It defaults to the spec's
suggested 40/s or the largest room's own rate, whichever is higher, so a one-room spool
keeps every push it allowed before. A push the spool-wide budget refuses gives the room's
token back. `hello` decides per connection whether to carry names, because `SPOOL_TOKEN`
reloads and rooms do not. `commons-invite` prints the relay invite link with `--url`, but
reads the token from `--token-file` and refuses a `?k=` in the URL: argv is readable by
every local user (the conformance CLI's reasoning). Metrics and logs name a room by its
number, never by its id or name.

**Consequences / limits:** The environment contract grows by 50 names, so the development
version moved to `0.6.0-dev.2`, and `deploy/docker-compose.tls.yml` declares them all.
Rotating one room is still a restart that reconnects every room's members. A client that
predates `rooms` sees only the first room on every spool. It compares a room's echoed
`ttlMs` with `commons`', so boot warns when a room's `TTL_MS` differs from the first
room's. Every room is pinned against the watermark, so boot checks their footprints
together against `SPOOL_MAX_BYTES`. A public spool's members see no room name in `hello`,
and one who joined with a plain `knit-commons:v1:` invite gets the client's generic title.
The guards:

- `ConfigTest` covers numbering, the first-room rule, the cap, duplicate ids, the summed
  footprint, the budget default and the TTL warning.
- `CommonsTest` covers per-room bounds, attachments, budget isolation, the uncharged
  refusal, the tags in `hello`, withheld names, shedding and per-room metrics.
- `ScopeStoreContractTest.shedOldestScopeNeverPicksAPinnedScope` runs on both stores.
- `SpecVectorTest.commonsVectorsMatchTheSpec` and the `helloSpoolRooms` record vector pin
  §13, and `RelayInviteTest` pins the link's golden vector.
- `ConformanceSelfTest` runs the suite against two rooms.
- `EnvContractTest` and `DeployConfigTest` fail when a room variable is added without the
  version bump or the compose line.
