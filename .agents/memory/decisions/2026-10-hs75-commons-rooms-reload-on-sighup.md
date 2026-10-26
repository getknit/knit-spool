---
id: "2026-10.hs75"
slug: commons-rooms-reload-on-sighup
title: "commons rooms reload on SIGHUP, and only a changed room's members reconnect"
date: 2026-10-25
topics: [commons, config, reload]
---

# ADR 2026-10.hs75 — commons rooms reload on SIGHUP, and only a changed room's members reconnect

Status: Accepted (2026-10-25). Supersedes the "rooms stay boot-only" clause of ADR 2026-10.5cwn;
the rest of that ADR stands. Implements knit-next spec S-7.4-21 and amended S-7.4-3 (its ADR
2026-10.dn2a).

**Context:** Removing a member from a commons room means rotating its invite, and ADR 2026-10.5cwn
kept rooms boot-only, so a rotation was a restart. A restart reconnects every member of every room
and every private conversation on the spool, to change one room. 5cwn turned down reloadable rooms
because they "add runtime failure paths, such as a room that cannot be created at reload, for a
rotation that happens rarely". Rare is still the case. But the operator doing it is usually
removing someone, so it is not optional, and every rotation cost the whole spool's population a
reconnect. Two more things were wrong on the way. `bootOnly()` compared only room ids, so a reload
that changed a room's bounds was neither applied nor reported as needing a restart. And a reload
file that also named a boot-only limit could not have been checked against the right one.

**Decision:** The room set and `SPOOL_COMMONS_RATE_TOTAL` are reloadable, as one unit
(`SpoolServer.reloadRooms`).

- **One table.** The rooms, their buckets and the total bucket are one immutable `RoomTable`, swapped
  whole. Every reader takes one snapshot. A room keeps its bucket while its id and rate are unchanged,
  and the total bucket is kept while the total is, so a reload never refunds spent budget.
- **One store hop.** The store work and the swap happen on the store thread in one hop. Rooms the
  store lacks are created first, because only a creation can be refused (`SPOOL_MAX_SCOPES` with
  nothing bare). A refusal keeps the running unit and is returned as a line the handler logs. The
  rest of the reload still applies, so a full store cannot block a token change. A scope created
  before the refusal stays as an ordinary bare scope that the reclaim takes; there is no new delete
  path (ADR 2026-10.ywkw). Rooms whose bounds changed are re-applied only after every creation
  succeeds, because a lower `ttlMs` deletes frames at once.
- **Who is closed.** A connection keeps the table its `hello` came from (`Conn.rooms`). A room's
  members are the connections subscribed to its scope. When a reload retires a room, or changes its
  bounds or `attach`, the connections subscribed to it whose `hello` described it otherwise are
  closed `1001` (`closeForNewHello`). They reconnect to a `hello` that says what the room now is. A
  name, rate or number change closes nobody.
- **The race.** A `sub` racing the swap is closed by whichever side runs second under `roomLock`:
  either the reload's scan finds the subscriber entry, or the `sub`'s check after adding it finds
  the new table. A `sub` looks up the room's bounds on the store thread beside its write, so it
  cannot write a reshaped room's old bounds back.
- **Limits.** A reload's rooms are built and footprint-checked against the running store's hard
  limits (`configFromEnv(roomLimits, …)`), not against a boot-only limit the file names and the
  reload ignores.

The alternatives:

- **Keep restarting.** This is the cost that motivated the change.
- **Reload and close nobody.** A rotated room's members, the removed one included, would keep
  talking in the old scope until each reconnected on its own, since no record ends a subscription.
  A rotation done to remove someone would not remove them.
- **Close every connection on any room change.** A restart by another name.
- **Refuse the whole reload when a room cannot be created.** Then a full store would also block a
  token rotation in the same file, which the reload contract exists to prevent.

**Consequences / limits:**

- **Reconnect wave.** A rotation still reconnects the whole room at once, about 1–1.75 s later
  under the reference client's backoff. That is the room's population, not the spool's, and the
  closes are not capped or spread out.
- **Added rooms.** A room added by a reload is invisible to connections opened before it until they
  reconnect.
- **The old scope.** It stays as an ordinary scope and ages out on its TTL or under the watermark.
  Having just been active, it is shed after older private conversations, as after a restart.
- **Legacy clients.** A client that predates `rooms` sees `commons` change silently when room 1 is
  rotated or renumbered.
- **Lowering `maxFrames`.** Frames are evicted at the next push, so for a moment a room can hold
  more than its new footprint.
- **Removing rooms through the file.** A room the environment sets needs blank lines for its id and
  each other variable set for it. Removing every room also needs `SPOOL_COMMONS_RATE_TOTAL=`, which
  now reads blank as unset.
- **Metrics.** A removed room's series leave `/metrics`.
- **Version.** The contract moved, so the development version is `0.7.0-dev.2` (ADR 2026-10.8ymn).

The trap is reading `roomTable` twice in one handler, or moving the swap off the store thread. The
first sees a room in one generation and not the next. The second lets a `sub` or a shed see the
rooms half-installed.

Guards:

- `CommonsReloadTest`:
  - only a rotated room's members are closed;
  - a `sub` from an older `hello` after the swap is closed;
  - a reshape is pinned in the store;
  - renames, renumbering and an identical reload close nobody and keep spent budgets;
  - an added room needs no proof of work;
  - a refused creation keeps the rooms and applies the rest;
  - the retired scope is shed, and its replacement is not;
  - metrics and the attachment switch follow the reloaded table.
- `ConfigTest.aReloadBuildsItsRoomsAgainstTheRunningLimits` and
  `aRoomTheEnvironmentSetIsRemovedByBlankingItsVariables`.
- `EnvContractTest` pins the move between halves.
