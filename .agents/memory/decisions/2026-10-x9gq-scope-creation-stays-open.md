---
id: "2026-10.x9gq"
slug: scope-creation-stays-open
title: "scope creation stays open, so ticketed and unticketed members share a spool"
date: 2026-10-15
topics: [tickets, auth, abuse]
---

# ADR 2026-10.x9gq — scope creation stays open, so ticketed and unticketed members share a spool

Status: Proposed (2026-10-15). Unbuilt. Split out of ADR 2026-10.bxsg, which had drafted ticket-gated
scope creation on 2026-10-08. Keeps creation open instead.

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

**Decision (proposed):** a ticketed spool can run public, with no `SPOOL_TOKEN`, a ticket
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

## What the shared spool still needs

These were drafted in bxsg, are unbuilt, and are proposed here:

- **A second ticket key.** `SPOOL_TICKET_KEY_NEXT`, a base64url raw 32-byte public key
  reloadable by `SIGHUP`, following the `SPOOL_TOKEN_NEXT` pattern. A ticket's `kid`
  picks which key verifies it. Rotation:
  1. render NEXT;
  2. sign with NEXT once every spool reports it;
  3. wait out the longest ticket;
  4. promote NEXT and unset it.
- **A per-pseudonym TURN rate.** `SPOOL_TICKET_TURN_RATE`: TURN issues per pseudonym per
  ten minutes, in addition to the per-connection and per-IP buckets, in a table capped and
  pruned like h6pt's `UploadBudgets`. Today one ticket shared across many addresses is
  limited by nothing the spool keys on.
- **A quota-refusal metric.** A counter of new scopes refused `quota`, so the sizing
  failure above shows on a dashboard.

## Open questions for review

- **Retention.** bxsg's upload budget holds at most `ab × (⌈retention / 1 day⌉ + 1)` per
  holder, and retention is the scope's `ttlMs`, up to `SPOOL_MAX_TTL_MS` (7 days by
  default, so 8 × `ab`). Should a spool with `files` gated refuse a `ttlMs` above some
  bound, or should the deploy only set `SPOOL_MAX_TTL_MS` to the retention it intends to
  hold? The first holds whatever an operator sets; the second needs no code.
- **PoW cost on phones.** 20 bits prices creation for every member's phone, not just for
  strangers. Is a lower setting enough, given d2zb and the rate bucket?

**Consequences / limits:**

- **The operator carries unticketed members' traffic.** Their frames, and the attachments
  holders send them, sit on the shared spool. The upload budget makes uploads fair between
  holders; `SPOOL_MAX_BYTES` is what bounds the rest.
- **Attachments are one-way for members without a ticket**, as h6pt chose: they receive a
  holder's and cannot send their own on a conversation held only by gated spools.
- **Users off the shared spool do not meet there.** A member who removed the default, or
  uses only their own spool, never meets a holder there. A contact card's relay hint
  offers them an Add, which is a tap. This is the fringe, not the default path.
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
  - owed with `SPOOL_TICKET_KEY_NEXT`: a `ReloadTest` case proving the key-pair swap is
    hitless for live connections, and `ConfigTest` bounds for it;
  - owed with `SPOOL_TICKET_TURN_RATE`: `ConfigTest` bounds, and a `TicketTest` case
    where one ticket on two addresses shares one bucket;
  - owed with the metric: a `MetricsTest` case for a `quota` refusal of a new scope.
