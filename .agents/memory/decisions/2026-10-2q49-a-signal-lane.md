---
id: "2026-10.2q49"
slug: a-signal-lane
title: "A signal lane: live fan-out that no ticket gates"
date: 2026-10-28
topics: [sigs, casts, tickets, typing]
---

# ADR 2026-10.2q49 — A signal lane: live fan-out that no ticket gates

Status: Accepted (2026-10-28). Implements knit-next `docs/SPOOL_PROTOCOL.md` §7.8 spool-side:
S-7.8-1 to S-7.8-8, with the client rules C-7.8-9 to C-7.8-16 on knit-next's side. B-7.1-8 and
S-7.7-2 now name casts and sigs as the two records with no `q`. Builds on ADR 2026-10.3wdy (casts),
ADR 2026-10.x9gq (ticketed and unticketed members share a spool) and ADR 2026-10.h4ps (bit 4 of `fl`
stays unassigned). Moves the development version to `0.8.0-dev.2` (ADR 2026-10.8ymn).

**Context:** Knit's typing indicators ride the radios only. Two phones in a DM, or a group, with
the Internet but no radio path between them see no typing, although every message they send crosses
a spool. Typing is messaging, and Internet messaging stays free, so whatever carries it must carry
it for a phone with no ticket.

The lane already exists in shape. A cast (3wdy) is fanned out live, never stored, never answered and
never struck, which is what a typing cue needs. It is the wrong lane to reuse as it stands:

- **It is sold.** Beside a ticket key, `cast` is in `SPOOL_TICKET_GATES` by default, and a cast is
  carried only on a scope a cast ticket unlocked. On the public spool, which gates `turn,files,cast`
  (x9gq), two free phones could not type to each other.
- **It is off.** `SPOOL_RATE_CASTS` defaults to 0, so a stock spool carries nothing.
- **A spool cannot tell a typing cast from a walkie chunk.** Both are sealed under the scope's key,
  so an exemption for typing would be an exemption for every cast, walkies included.

`push` stays the wrong carrier for 3wdy's reasons: a stored cue would churn a scope's history, be
listed and pulled on every heal round, and be served back as history.

**Decision:** a new record, `sig { t, scope, data }`, used for both directions, advertised in
`hello` as `sig: { maxBytes, rate }`. It is a cast's lane with no ticket in it.

- **The cast's rules.** `handleSig` reads only the connection's subscriptions and `subscribers`,
  never the store (S-7.8-5). A sig has no `q` and is never answered (S-7.8-2). A refused one is a
  silent drop counted in `knit_spool_sigs_dropped_total{reason}`, in the order off, not subscribed,
  the commons, too large, rate (S-7.8-3), plus malformed before and a full channel after. A drop is
  never a strike (S-7.8-7), and a lost copy never counts toward the slow-consumer close (S-7.8-6).
  Nothing about a sig, or the scope it names, is logged (S-7.8-8).
- **No ticket gates it** (S-7.8-4). `sigRefusal` has no ticket check, `entGates` has no bit for it,
  and `SPOOL_TICKET_GATES` has no word for it. A spool fans a sig out whatever its `ent`, its
  `entGates` or the connection's ticket. Ticket flags are unchanged, and bit 4 stays unassigned.
- **On by default.** `SPOOL_RATE_SIGS` is 2 a second per connection, `SPOOL_MAX_SIG` 8 KiB and
  `SPOOL_RATE_SIGS_TOTAL` 200, so a spool on this version carries typing with no configuration, and a
  tool that renders a spool's environment needs none of the three names. A rate of 0 leaves `sig` out
  of `hello` (S-7.8-1) and, on a reload, stops sigs on live connections at once, as casts' kill switch
  does. All three reload on the cast trio's terms.
- **Budgets of its own.** Each is a token bucket with a burst of four times its rate: per
  connection, snapshotted from its `hello`; per address (the cast `clientKey`: an IPv4 literal or an
  IPv6 /64), 8× the connection's; and spool-wide, rebuilt on reload. None is shared with casts, so a
  walkie never starves a typing cue or the other way round. The address gets 8× rather than casts'
  4× because typing is every phone's, and carrier NAT puts many phones behind one address: 16 a
  second with a burst of 64 at the defaults.
- **Sized beyond typing.** 8 KiB fits the link offers and call signals that may join the lane later
  without another protocol change. Neither is built here: call signals stay stored DMs (deferred by
  the maintainer, 2026-10-28), and link offers are a follow-up. The client clamps `rate` to 100 and
  `maxBytes` to `maxRecord` (C-7.8-9), which `configFromEnv` enforces from this side.
- **Shared code, unchanged casts.** The fan-out loop (`fanOutLive`) and the per-address bucket that
  follows a reloadable rate (`RateFollowingBucket`) are shared by both lanes. Casts' behaviour,
  settings and metric names did not move, and `CastTest` passes unchanged.

## Alternatives rejected

- **Reuse `cast`, ungated for typing.** The spool cannot see that a cast is typing, so this is
  ungating casts.
- **A ticket bit for sigs.** It would make typing over the Internet a paid feature, when typing is
  messaging and messaging stays free, and it would split a conversation between a ticketed and a free
  member, which x9gq's shared spool exists to prevent.
- **A "nobody received" answer**, so a sender could stop when nobody is listening. It tells the
  sender who is connected, and an answer on a record with no `q` reads as a refused `sub` to a
  shipped client (3wdy).
- **Off by default, like casts.** Nothing an operator does not set reaches the field, and typing is
  meant to work on every spool a phone already uses.

## Consequences / limits

- **A typing signal the operator can see.** The spool sees that someone in a scope is typing, when,
  and the sender's address. It sees nothing of what is typed or of who: the client seals each sig
  under the scope's key, with the frame's signature inside, and pads it to a multiple of 128 bytes
  (C-7.8-11, C-7.8-12). No ticket is involved, so no pseudonym is tied to that timing. `SECURITY.md`
  states it.
- **Anyone subscribed may send one.** No ticket narrows the senders, and anyone who learns a scope
  id can subscribe (spec §10.4). The budgets bound it: egress at saturation is 200 sigs a second
  times 8 KiB times each scope's other subscribers, about 1.6 MB/s per listener. `HOSTING.md` states
  the bound and what to lower on a metered link. A copies-out budget is the change to make if a
  public spool ever sees sigs amplified, as 3wdy says for casts.
- **An upgrade can refuse to boot.** `SPOOL_MAX_SIG` plus the 512-byte envelope must fit
  `SPOOL_MAX_RECORD`, so a spool that set the record below 8,704 bytes must lower `SPOOL_MAX_SIG`
  too. The default record is 131,072, and the CHANGELOG says so.
- **The trap:** adding a ticket check to `sigRefusal` for parity with `castRefusal`, or a `sig`
  word to `SPOOL_TICKET_GATES`. S-7.8-4 forbids both, and a sig is how a free phone's typing works.
- **The second trap:** naming a §13 vector `sig`. `SpecVectorTest` looks vectors up by name, and
  `sig` is the ticket signature's. This repo's vectors are `helloSpoolSig` and `sigRecord`.
- **Guards:**
  - `SigTest`: fan-out excluding the sender; nothing stored, listed or tombstoned; every refusal
    silent and no strike; the per-connection, per-address (8×) and spool-wide budgets, apart from the
    casts'; the reload kill switch and a connection never told of sigs; a sig between two connections
    with no ticket on a spool that gates every capability; the metrics; nothing logged.
  - `SigRecordVectorTest`, whose `helloSpoolSig` and `sigRecord` were made by a hand-rolled encoder,
    not kotlinx. knit-next's §13 copies them, and `SpecVectorTest` pins them once it does.
  - The conformance checks `sig-advertised`, `sig-fanout` (never presenting a ticket, so S-7.8-4 on a
    ticketed spool), `sig-silent-refusal` and the advisory `sig-rate`, run in process by
    `ConformanceSelfTest`, which also runs one suite with sigs off.
  - `ConfigTest`, `ReloadTest`, `MetricsTest`, `StatusLineTest`, `DeployConfigTest`, and
    `EnvContractTest`'s pin at `0.8.0-dev.2`.
- **Not covered by a test:** the full-channel drop, for 3wdy's reason.
