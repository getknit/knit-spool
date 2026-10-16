---
id: "2026-10.h6pt"
slug: tickets-gate-verbs-per-connection
title: "tickets gate verbs per connection, and only an uploader's ticket pays for attachments"
date: 2026-10-10
topics: [tickets, attachments, auth, abuse]
---

# ADR 2026-10.h6pt — tickets gate verbs per connection, and only an uploader's ticket pays for attachments

Status: Accepted (2026-10-14). Proposed 2026-10-10, built on `exp/turn` 2026-10-11. Amends ADR
2026-10.bxsg's `aput` and scope-creation gates. On 2026-10-15 ADR 2026-10.x9gq (proposed)
replaced the scope-creation gate with keeping creation open. Corrected 2026-10-15: the commit hashes after `exp/turn`'s rebase,
and the retention multiplier at the spool's `SPOOL_MAX_TTL_MS` ceiling.

**Context:** an operator should be able to run one kind of spool, with each ticketed
capability switched on per ticket holder, so there is no separate spool, configured
differently, for each set of features. Two ticket gates exist on `exp/turn` on the day this was written: `turnreq`
(ADR 2026-10.bxsg) and `cast` (ADR 2026-10.3wdy). A third, `aput`, is drafted in bxsg but
not built. Reading the three side by side found three things:

- **The principle was never stated.** Each gate was decided on its own, so nothing says
  what a ticket may and may not unlock. bxsg already warns against putting allocation
  claims in a ticket. That warning is one case of a rule nobody wrote down.
- **A key turns every gate on at once.** With `SPOOL_TICKET_KEY` set, `turnreq` and
  `cast` both require a ticket on any connection that was sent `ent: true`. If `aput`
  followed the same pattern, every spool that already has a key, the lab spool among
  them, would refuse attachments from unticketed clients when the gate deployed. bxsg's
  draft has a separate switch for scope creation (`SPOOL_TICKET_CREATE`), which is the
  right instinct, but it is one boolean per gate.
- **TURN and casts follow a one-payer rule, and attachments could too.** One paying
  member unlocks a call or a walkie for the whole conversation, so a free friend can
  answer. An earlier draft of this ADR carried that rule over to attachments as a
  *sponsor*: one member's files ticket would pay for every member's uploads in that
  scope, even after its holder disconnected. That draft was turned down for how it would
  behave for members, not for technical reasons. See [Alternatives rejected](#alternatives-rejected).

What the shipped client does with a refused `aput` was read in knit-next `ScopeSync.kt`
(`putChunk`, `survivable`). It treats any code other than `rate`, `tombstoned`,
`too_large` and `bad_id` as the end of that attachment for that round, and tries again
on the next heal round. The `err` carries the record's `q`, so it does not trip the
trap 3wdy hit, where a scoped `err` with no `q` reads as a refused `sub`.

**Decision:** a ticket unlocks verbs on one connection, inside limits the operator set.
Sending an attachment needs the sender's own ticket. Receiving one needs nothing.

## Tickets gate verbs, not bounds

- **A gate is an action a connection takes**: `turnreq`, `cast`, `aput` and creating a
  scope. A ticket decides whether this connection may take it, and whose budget pays.
- **A ticket never changes a stored bound.** A per-scope bound such as `ttlMs`,
  `maxBlob` or `maxFrames` is pinned on the scope, shared with members who never paid,
  and outlives the connection. A ticket that raised one would be a promise that lasts
  past the ticket's expiry. This is the general form of bxsg's warning against allocation
  claims.
- **The operator sets the ceiling.** A gate applies only to a capability the spool
  already has switched on, and inside its hard limits. `SPOOL_MAX_ATTACH_BYTES`, the
  `SPOOL_MAX_BYTES` watermark, the cast rates and coturn's quotas bound a ticketed
  connection exactly as they bound any other. A spool with attachments off has nothing a
  ticket can unlock.

## Which gates are on

- **`SPOOL_TICKET_GATES`** is a comma list drawn from `turn`, `cast`, `files` and
  `create`. It defaults to `turn,cast`, the gates a key turns on today, so deploying this
  changes nothing on a spool that already has a key. Setting it without
  `SPOOL_TICKET_KEY`, or with an unknown word, fails at boot. It is reloadable by
  `SIGHUP`, and each connection snapshots it from the `hello` it was sent, as it already
  snapshots `ent`.
- **It replaces bxsg's proposed `SPOOL_TICKET_CREATE`**, which becomes `create` in the
  list. The `create` gate's behaviour is unchanged from bxsg.
- **A verb whose gate is off is open**, under the same rules as on a spool with no key.
  A connection that holds a live ticket with `FL_TURN` still gets the pseudonym TURN
  username, whether or not `turn` is gated.
- **`hello` names the gates.** `ent: true` is shipped and stays. Beside it, a new
  `entGates: uint` carries the gated capabilities in `fl`'s bit positions, present only
  with `ent`. A ticket-aware client compares it with its ticket's `fl`. If `files` is
  gated and the client holds no files ticket, it does not offer to send attachments
  through that spool, and still fetches the ones it receives. An older client ignores
  the unknown key (B-2-2).
- **Bit 1 of `fl` becomes `Tickets.FL_FILES`** (value 2), the bit bxsg reserved.

## The files gate

- **`aput` is admitted** while the `files` gate is on only if the uploading connection
  holds a live ticket with `FL_FILES` and budget left. No other member's ticket counts.
  This differs on purpose from the one-payer rule of `turnreq` and `cast`.
- **The charge** is each newly stored chunk's `chunkCharge`, the same unit as the
  per-scope and watermark accounting, charged to the uploader's pseudonym. It is charged
  only on `AputResult.Stored`. A duplicate chunk, or one refused for any reason, costs
  nothing. The budget is checked before the store and charged after it, so one
  pseudonym's concurrent connections can overshoot its budget by at most one chunk each.
- **The budget** is bxsg's daily upload budget, `min(ab, SPOOL_TICKET_ATTACH_BYTES)` per
  pseudonym. The pseudonym already changes each day, so the budget does too.
  `SPOOL_TICKET_ATTACH_BYTES` defaults to 32 MiB, two full scopes at the 16 MiB default
  `SPOOL_MAX_ATTACH_BYTES`. An absent `ab` means the spool's value.
- **Sized against the disk, 2026-10-13.** The draft's 128 MiB was cut to 32 MiB. The
  planning case is a 2,000-connection public spool with 1,000 holders active at once,
  each keeping up to 3 × the budget. At 128 MiB that is 375 GiB held, more than the whole
  160 GB disk of the box in the planning case. At 32 MiB it is
  94 GiB, and 4,096 scopes × 16 MiB caps attachments at 64 GiB before that. The cost of
  the cut is about ten phone photos a day per holder. Fitting even that on a box is the
  deployment's sizing problem, not this daemon's.
- **A refusal is `err ticket`** with the record's `q`, and with `missing`, `expired` or
  `flags` in `msg` as bxsg defines them. A new reason, `budget`, means the pseudonym's
  budget for the day is spent. `quota` was the alternative and was rejected: the shipped
  client parks a whole scope's `sub` on a scoped `quota`, and a spent budget is no reason
  to stop syncing a conversation's text.
- **`aget` is never gated.** Uploads are already gated and budgeted, so download volume
  is bounded by the stored bytes times the members fetching them. A free member opens
  every attachment a paying member sends.

## Alternatives rejected

- **Sponsorship: one ticket per scope, held past disconnect.** One member's files
  ticket would have paid for every member's uploads in that scope until the ticket
  expired. It matches how TURN and casts work, and it keeps a conversation two-way for a
  member without a ticket. It was rejected:
  - *It cannot be explained simply.* A free member would find that sending files works
    in some conversations and not others, for reasons they cannot see and never chose,
    and that it stops working when the payer's ticket lapses. Every client would need
    copy to explain that. "Sending files needs a ticket" needs none.
  - It would also have kept the link between a pseudonym and its scopes in memory for
    up to 48 h after disconnect. That would have been a wider leak than bxsg accepted.
- **A live payer, the cast rule.** Uploads would be admitted while some member holding a
  files ticket is connected. It has the sponsor's explanation problem, made worse:
  whether a photo sends would depend on whether someone else's phone was online.
- **A persisted sponsorship, or a stable account handle on each attachment row.** It
  would survive restarts and support an exact storage cap. bxsg deferred it because it
  lets the spool, and anyone who takes its disk, link one account's scopes across days.
  That still holds.
- **Gating on key presence**, as `turn` and `cast` do today. It breaks every spool that
  already has a key the day `files` ships.
- **A separate spool for ticketed features.** This is the shape the decision exists to
  avoid. Clients would have to send attachments to a different host from the
  conversation's text, every scope would need a second set of relay hints and a second
  `sub`, and the operator would run and monitor two spools for one service.

## What shipped

Commits `577c35e` through `37412e2` on `exp/turn` (`17a6b84` through `319f0ce` before its rebase), with the spec rows in knit-next `0874ea3b`
(`docs/VOICE_CALLS.md` §2.6, S-7.6-22/23, C-7.6-24):

- `FL_FILES`, the `ab` claim, `Reason.BUDGET` and `hello.entGates`, with vectors from the spec.
- `SPOOL_TICKET_GATES` (`turn`, `files`, `cast`) and `SPOOL_TICKET_ATTACH_BYTES`. The `create`
  word is refused at boot until bxsg's creation budget is built, so no operator believes scope
  creation is gated when it is not. (Since 2026-10-15, ADR 2026-10.x9gq proposes never building it.)
- The `aput` gate in `SpoolServer.filesAdmitted`, the per-pseudonym ledger in `UploadBudgets`, and
  `ticket-mint --files --ab`.
- The guards listed below, all present. The conformance checks run in process three ways: every
  gate, the default gates, and `files` alone.

A choice the draft left open: with `turn` left out of the gates, a live TURN ticket still waives
the subscription gate, as it does when `turn` is gated. The gate decides only whether a connection
without a ticket is refused.

**Consequences / limits:**

- **Attachments are one-way for a free member** on a conversation held only by gated
  spools. They receive a holder's attachments and cannot send their own. This is the
  intended behaviour, chosen over a two-way rule that would need explaining. A scope also held
  by a spool without the gate still accepts the free member's uploads at that spool.
- **The rule differs between features.** A free friend can answer a call or talk back on
  a walkie that a customer started, but cannot send a photo back. The apps should
  describe each feature by what it lets a member do, not by a single rule for "ticketed
  conversations".
- **The cap is per upload day, not a storage cap.** As in bxsg, held bytes per customer
  stay at or below `ab × (⌈attachment retention / 1 day⌉ + 1)`, which is 3 × `ab` at the
  clients' 48 h default and 8 × `ab` at the spool's 7-day `SPOOL_MAX_TTL_MS` default.
  Product copy must not call `ab` a storage cap.
- **A restart resets every budget.** Budgets are memory only, as in bxsg. The watermark
  still bounds the disk.
- **Older clients retry every heal round.** A client that does not read `entGates` sees
  the attachment limits in `hello`, sends `aput`, is refused, and tries again on the next
  round. The push bucket bounds the cost. It is wasteful, not fatal: the conversation's
  text keeps syncing.
- **The trap:** letting a ticket raise a bound, such as a longer `ttlMs` or a bigger
  `maxBlob` for paying customers. Bounds are pinned on the scope and shared, so the raise
  outlives the ticket and benefits members who never paid. Another trap: "fixing" the
  one-way case by admitting an `aput` when another subscriber holds `FL_FILES`, as
  `castUnlocked` does for casts. That is the sponsorship this ADR rejected. Reversing it
  takes a new ADR. Also, never charge the budget before the store: a refused or duplicate
  chunk would then cost the customer bytes that were never held.
- **Guards, owed with the implementation:**
  - `TicketFilesTest`:
    - the uploader's ticket admits and is charged;
    - another subscriber's files ticket does not admit an unticketed upload;
    - only `Stored` is charged;
    - `budget`, `missing` and `flags` refusals carry the `q`;
    - `aget` stays open to an unticketed connection;
    - a restart resets budgets.
  - `TicketGatesTest`: the default `turn,cast` keeps today's behaviour on a keyed spool;
    with `files` absent, `aput` stays open on a keyed spool; the gates are snapshotted
    per connection across a reload.
  - `TicketVectorTest`: a golden vector carrying `fl` with `FL_FILES` and an `ab` claim (in
    `SpecVectorTest` since 2026-10-15, when the spec took §7.6).
  - Conformance checks:
    - `ent-gates`: the shape of `entGates`, and that it agrees with the refusals;
    - `files-ticket`: an unticketed `aput` is refused `ticket` and a ticketed one is
      stored;
    - `files-receive`: an unticketed connection fetches an attachment a ticketed
      connection stored.

    All three run in process under `ConformanceSelfTest`.
  - `ConfigTest` cases for parsing `SPOOL_TICKET_GATES` and the bounds of
    `SPOOL_TICKET_ATTACH_BYTES`, a `ReloadTest` case for the gates, and `TicketToolTest`
    cases for `ticket-mint --files` and `--ab`.
