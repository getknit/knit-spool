---
id: "2026-10.3wdy"
slug: casts-fan-out-unstored
title: "casts fan out unstored, unlocked by one ticket"
date: 2026-10-10
topics: [casts, calls, tickets, abuse]
---

# ADR 2026-10.3wdy — casts fan out unstored, unlocked by one ticket

Status: Accepted (2026-10-10)

**Context:** knit-next's walkie-talkie (its ADR 2026-10.79yb, `feat/voip-calling`)
streams Opus in sealed `talk` frames of about 700 bytes every 480 ms, and carries them over
the mesh only. Two phones with Internet but no radio path and no working live call get a
walkie session that carries nothing. That happens when TURN is ticket-gated (ADR
2026-10.bxsg) and STUN fails behind a symmetric NAT. Call signalling already crosses a
spool as ordinary stored blobs, so `push` looks like the obvious carrier. It is the wrong
one. A stored chunk costs:

- a digest entry, an eviction and a tombstone;
- a place in every `list`, and a pull by every member on every heal round;
- client-side accounting.

At two a second a minute of talk is about 125 blobs, which pushes a 400-frame scope's
real history out within minutes and serves the talk back as history. A private
maintainer note of 2026-09-17 had worked this lane out once already, as a `cue` record
for the typing cue. This decision is that design, renamed and with `talk` as its first
frame.

**Decision:** a new record, `cast { t, scope, data }`, used for both directions. It is
drafted in knit-next `docs/VOICE_CALLS.md` §2.7 as proposed spec §7.7.

- **Never stored.** `handleCast` reads only the connection's subscriptions and the
  `subscribers` table, never the store. So a cast cannot create, recreate or hold a
  scope, fold a digest, leave a tombstone or reach SQLite, and a shed scope still carries
  casts while its subscribers hold it.
- **No `q`, never answered.** Every refusal is a silent drop counted in
  `knit_spool_casts_dropped_total{reason}`: off, not subscribed, oversize, the commons,
  over budget, no ticket, malformed. `hello`'s `cast: { maxBytes, rate }` states the
  bounds up front instead. An answer was rejected because the shipped client reads a
  scoped `err` with no `q` as a refused `sub` and drops the conversation. A `q` was
  rejected because it would put an ack, a 30 s pending slot and the client's
  silent-request strikes on every chunk.
- **Never struck, never a slow consumer.** The cast budgets drop and do not strike. A
  flood still pays the record bucket, which strikes record by record. `fanOutCast` uses
  `trySend` and does not touch `eventMisses`, so a phone in the middle of a pull is not
  closed 4003 for being talked to.
- **Budgets:**
  - per connection, `SPOOL_RATE_CASTS`, burst 4×, snapshotted from the hello it was sent;
  - per address, 4× that;
  - Spool-wide, `SPOOL_RATE_CASTS_TOTAL`, rebuilt on reload.

  `SPOOL_MAX_CAST` bounds a cast's size. All three are reloadable. A rate of 0, the
  default, omits `cast` from `hello` and stops casts on live connections at once, as the
  operator's kill switch.
- **Sold with tickets, one payer per scope.** A new ticket flag, `Tickets.FL_CAST` (bit 3;
  bits 1–2 stay bxsg's proposed files and create claims), is checked on a connection whose
  hello carried `ent`. There a cast is admitted only while some live subscriber of its
  scope holds an accepted ticket with the flag: the caster first, then the others. So one
  payer unlocks the scope both ways, as one payer is enough for TURN, and a free friend
  can talk back. A spool without a ticket key admits any subscriber. The rejected
  alternative was a ticket on every caster. It is simpler, but over the Internet it makes
  the walkie one-way for a free friend.
- **The commons is refused.** One cast there reaches the whole room.

**Consequences / limits:**

- **A finer live signal.** The operator can see when a conversation is in a walkie
  session, who talks when and for how long, and both addresses. It sees nothing of what is
  said: the client seals each cast under the scope key and the chunk inside it under the
  ratchet, and pads it. None of it reaches disk or the log. The ticket gate ties the
  payer's daily pseudonym to that timing, which is inside bxsg's accepted leak, since the
  spool already ties a ticketed connection to its subscriptions.
- **The Spool-wide budget counts casts in, not copies out.** Egress is that rate × the
  cast size × each scope's other subscribers, and anyone who learns a scope id can
  subscribe (spec §10.4). HOSTING.md states the bound. A copies-out budget would bound
  egress directly; it is the change to make if a public spool ever sees casts amplified.
- **Live or lost.** A subscriber with a full channel, or not connected at the time, never
  gets that cast, and nothing tells anyone. That is what a walkie is.
- **The trap:** reaching for `push` with a marker such as `ttlMs: 0`. An older spool
  ignores the unknown key (B-2-2) and stores the chunk. The `hello` gate is what makes
  this lane safe to add.
- **Guards:**
  - `CastTest`: fan-out excludes the caster; nothing stored, listed or tombstoned; every
    refusal silent; no strike; the reload kill switch; the ticket gate, one payer, expiry,
    and the payer leaving; nothing logged.
  - `CastRecordVectorTest`, whose hex knit-next's client must match (in `SpecVectorTest` since
    2026-10-15, when the spec took §7.7).
  - The conformance checks `cast-advertised`, `cast-fanout`, `cast-silent-refusal`,
    `cast-ticket` and the advisory `cast-rate`, run in process by `ConformanceSelfTest`.
  - `ConfigTest`, `ReloadTest` and `TicketToolTest`'s cast cases.
- **Not covered by a test:** the full-channel drop. Filling Ktor's outbound channel
  deterministically needs a client that stops reading at the socket. The code path is one
  `trySend` with no miss counter.
