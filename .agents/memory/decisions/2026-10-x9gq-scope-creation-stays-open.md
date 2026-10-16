---
id: "2026-10.x9gq"
slug: scope-creation-stays-open
title: "scope creation stays open, so ticketed and unticketed members share a spool"
date: 2026-10-15
topics: [tickets, auth, abuse]
---

# ADR 2026-10.x9gq — scope creation stays open, so ticketed and unticketed members share a spool

Status: Accepted (2026-10-16). Proposed 2026-10-15, split out of ADR 2026-10.bxsg, which had
drafted ticket-gated scope creation on 2026-10-08. Keeps creation open instead. Accepted as
written: the public spool the apps seed runs with no `SPOOL_TOKEN`, a ticket key and
`SPOOL_TICKET_GATES=turn,files,cast`, so tickets are its only feature gates. The three pieces
below are built.

**Context:** an operator may want members without a ticket to create scopes as they do
today, and only ticket holders to upload attachments, as the lab spool has piloted. Both
must be on the same spool, or a member without a ticket never receives a holder's
attachments. Neither should have to add a relay to talk to the other.

Clients already meet on shared spools. The reference client seeds a default spool once, on
first run (knit-next `res/values/spools.xml`, `SettingsStore.seedDefaultSpools`), and a
client subscribes every scope it holds on every relay in its list. Scope ids come from
secrets the members share, so two members of a conversation land on the same scope there
without registering anything.

The gates that exist are per connection and per verb, not per scope (ADR 2026-10.bxsg,
ADR 2026-10.h6pt, ADR 2026-10.3wdy). With `SPOOL_TICKET_GATES=turn,files,cast` and
creation left open, one spool gives:

| Verb | Unticketed connection | Ticketed connection |
| --- | --- | --- |
| create a scope; `sub`, `push` | open | open |
| `aget` | open | open |
| `aput` | refused `ticket` | charged to its own daily budget |
| `turnreq` | refused `ticket` | issued |
| `cast` | open while a holder subscribes the scope | open |

bxsg's draft assumed the opposite: that a spool gating TURN must gate creation so it can
run without `SPOOL_TOKEN`, because strangers' scopes would otherwise fill
`SPOOL_MAX_SCOPES` and leave a holder's DM scope refused `quota`, with no TURN from that
spool.

**Decision:** a ticketed spool can run public, with no `SPOOL_TOKEN`, a ticket
key and `SPOOL_TICKET_GATES=turn,files,cast`. Scope creation is not ticket-gated, and
`create` stays refused in `SPOOL_TICKET_GATES`. Bit 2 of `fl` stays reserved and `sc`
stays undefined.

## Why creation is not gated

- **It would stop members without a ticket creating scopes.** On a creation-gated spool,
  the first member to reach a scope must hold a ticket. A member without one who
  subscribes a DM scope first is refused, and so is any recreation after the scope was
  reclaimed bare (ADR 2026-10.d2zb) or shed at the watermark. Clients would need to treat
  `err ticket` on an unknown scope as "not here yet" and keep healing.
- **A second, creation-gated spool beside the shared one** would need every member without
  a ticket to add it. A relay is never added silently (knit-next `docs/RELAY_INVITE.md`
  §3): it sees the device's IP and every scope id, so adding one is a confirmed sheet.
- **A new hostname would not reach installed apps.** The seed runs once per install, and
  re-seeding a host the user removed would be a policy, not a default.

## What protects a holder instead

- **d2zb's displacement rule.** At `SPOOL_MAX_SCOPES` a new scope can displace only a
  bare scope nobody subscribes, never one holding frames, so a holder's live scopes are
  never pushed out by strangers.
- **The per-IP creation bucket** (`SPOOL_RATE_NEW_SCOPES`), **PoW** (`SPOOL_POW_BITS`; the
  spec suggests 20 bits for a public spool), and the `SPOOL_MAX_BYTES` watermark.
- **Sizing.** `SPOOL_MAX_SCOPES` defaults to 64, sized for a household spool. A shared
  public spool needs it set to the number of live conversations it expects, with the
  watermark to match (`HOSTING.md`).

The risk left is a brand-new DM scope refused `quota` on a spool already full of live
scopes. That conversation then gets no TURN from that spool. It is a sizing failure, and
it should be visible before members report it.

## What the shared spool needed, built

These were drafted in bxsg and are built with this decision's acceptance. They move the
environment contract, so the development version moved to `0.4.0-dev.2` (ADR 2026-10.8ymn).

- **A second ticket key.** `SPOOL_TICKET_KEY_NEXT`, a base64url raw 32-byte public key
  reloadable by `SIGHUP`, following the `SPOOL_TOKEN_NEXT` pattern: refused without
  `SPOOL_TICKET_KEY`, and refused when it equals it. A ticket's `kid` picks which key
  verifies it (`Tickets.check` over a key list); a ticket naming neither is refused `key`,
  and only the named key's signature is ever tried. Rotation:
  1. render NEXT;
  2. sign with NEXT once every spool reports it;
  3. wait out the longest ticket;
  4. promote NEXT and unset it.

  No step touches a ticket a live connection already holds: the keys judge only the next
  `ent`. The `hello` flag is unchanged by any step, because the primary key is always set.
- **A per-pseudonym TURN rate.** `SPOOL_TICKET_TURN_RATE`: TURN issues per pseudonym per
  ten minutes, taken after the per-connection and per-IP buckets so a request they refuse
  never spends the holder's share. It defaults to 16, one address's budget at the default
  `SPOOL_TURN_RATE` of 4, with a burst of half the rate and at least 2. 0 turns it off, and
  240, four times `SPOOL_TURN_RATE`'s ceiling, is the most it takes. It needs the ticket key
  and `SPOOL_TURN_SECRET`, and is read live, so a reload reaches the next issue; a moved
  rate rebuilds a holder's bucket full. The table, `TurnBudgets`, is capped and pruned like
  h6pt's `UploadBudgets`: memory only, never logged, an entry dropped once its ticket's
  window closes. It applies to every issue made under a ticket, gated or not, because the
  pseudonym is what the issue is keyed on either way.
- **A quota-refusal metric.** `knit_spool_new_scopes_quota_refused_total` counts new
  scopes refused `quota` at `SPOOL_MAX_SCOPES`, on a `sub` and on the push that recreates
  a shed scope. Attachment `quota` refusals stay in the `err` counts only. It is always
  emitted, from zero, so the sizing failure above shows on a dashboard.

## Retention, decided

Decided 2026-10-16 by the maintainer: **the operator sets the ceiling with `SPOOL_MAX_TTL_MS`,
and the daemon gains no refusal for a long `ttlMs`.** A spool with `files` gated is expected to
run `SPOOL_MAX_TTL_MS=172800000` (48 hours). The daemon's 7-day default is unchanged.

- bxsg's upload budget holds at most `ab × (⌈retention / 1 day⌉ + 1)` per holder. Attachment
  chunks live on their scope's `ttlMs`, so the ceiling, not what clients happen to declare,
  bounds what one holder can keep: 3 × `ab` at 48 hours, against 8 × `ab` at the 7-day
  default.
- The reference client declares 48 hours on every ordinary scope (knit-next
  `ScopeRegistry.DEFAULT_TTL_MS`), so a 48-hour ceiling costs a conforming client nothing.
- A client that declares more is clamped, not refused: both stores apply `coerceIn` in
  `subscribe`, and the `digest` reply carries the bounds it got. A refusal would break such a
  client for no gain, and a files-only bound would be a second knob for the same number.
- `SPOOL_MAX_TTL_MS` is boot-only, so it moves with a restart, in the same one as
  `SPOOL_MAX_ATTACH_BYTES`. `HOSTING.md` ("What ticketed uploads hold") tells an operator
  gating `files` to set it to the retention they intend to hold.

## Open question, with a recommendation

Still open for the maintainer. What follows is a recommendation, not a decision.

- **PoW cost on phones.** 20 bits prices creation for every member's phone, not just for
  strangers. Is a lower setting enough, given d2zb and the rate bucket?
  - *Recommended: keep 20 bits, the spec's default, and watch the new metric.* A phone
    pays only for a scope the spool does not hold (C-8-4): the first member to reach a
    conversation's scope, or a recreation after a d2zb reclaim or a watermark shed. It pays
    once per scope per day (S-8-2's cache), not on every `sub`. The spec puts 20 bits at
    about 10⁶ hashes and sub-second on a phone (§8).
  - Each bit dropped halves what a squatter pays per scope, while the per-IP bucket
    bounds only one address. d2zb already keeps squatted bare scopes from displacing live
    ones, so what squatting costs a member is a `quota` refusal at a full spool. That is
    now `knit_spool_new_scopes_quota_refused_total`, and it is better answered by sizing
    `SPOOL_MAX_SCOPES` than by cheapening the stamp.
  - No phone timing has been measured in this repo. Before the public spool turns PoW on,
    time a 20-bit stamp on the slowest supported Android device. Step down a bit at a time
    only if it is not sub-second there.

**Consequences / limits:**

- **The operator carries unticketed members' traffic.** Their frames, and the attachments
  holders send them, sit on the shared spool. The upload budget makes uploads fair between
  holders; `SPOOL_MAX_BYTES` is what bounds the rest.
- **Attachments are one-way for members without a ticket**, as h6pt chose: they receive a
  holder's and cannot send their own on a conversation held only by gated spools.
- **Users off the shared spool do not meet there.** A member who removed the default, or
  uses only their own spool, never meets a holder there. A contact card's relay hint
  offers them an Add, which is a tap. This is the fringe, not the default path.
- **Rotation needs every spool to have NEXT before signing moves.** Step 2 is the one an
  operator can get wrong: a spool still on the old key alone refuses new tickets `key`
  until it is reloaded. Nothing in the daemon can see the other spools, so that check
  belongs to whatever renders their environment.
- **The per-pseudonym budget resets with the pseudonym,** at each ticket day and on a
  restart, like the upload budget. A shared ticket is limited to the budget per day per
  spool, not in total.
- **The lab pilot does not cover the public side.** The lab spool runs with a token, so
  strangers' scopes, PoW and `quota` at a full spool are untested until a public spool
  runs the gates.
- **Turning this on is a settings change on the spool clients already share,** not a new
  spool: the ticket key, audience and gates, plus its scope and byte sizing.
- **The trap:** gating creation "to stop abuse" without solving the first-member problem
  above, or standing up a separate ticketed hostname that installed apps never seed.
  Either needs a new ADR that supersedes this one.
- **Guards:**
  - present: `ConfigTest` refuses `create` in `SPOOL_TICKET_GATES`, and conformance
    `ent-gates` checks that `entGates` agrees with the refusals;
  - `SPOOL_TICKET_KEY_NEXT`: `ReloadTest.rotatingTheTicketKeyIsHitlessForLiveConnections`
    (all four steps, with one connection live throughout),
    `ConfigTest.theNextTicketKeyIsHalfOfARotationAndMustDiffer`, and
    `TicketCheckTest.theKidPicksWhichOfTwoKeysVerifies`;
  - `SPOOL_TICKET_TURN_RATE`: `ConfigTest.theHoldersTurnRateIsBoundedAndNeedsTicketsAndTurn`,
    `TicketTest.oneTicketOnTwoAddressesSharesOneTurnBucket` and
    `TicketTest.aHoldersTurnBucketIsPrunedOnceItsTicketExpires`;
  - the metric: `MetricsTest.aNewScopeRefusedQuotaIsCounted`;
  - `EnvContractTest`, pinned at `0.4.0-dev.2` for the two new variables.
