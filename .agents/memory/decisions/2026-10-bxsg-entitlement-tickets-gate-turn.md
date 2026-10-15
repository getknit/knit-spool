---
id: "2026-10.bxsg"
slug: entitlement-tickets-gate-turn
title: "entitlement tickets gate turn and files on a spool"
date: 2026-10-08
topics: [tickets, turn, attachments, auth]
---

# ADR 2026-10.bxsg — entitlement tickets gate turn and files on a spool

Status: Accepted (2026-10-15), for what is built on `exp/turn`. Proposed 2026-10-08, when it also
covered scope creation. Amended by ADR 2026-10.h6pt (the `files` gate) and ADR 2026-10.3wdy (the
`cast` gate). The unbuilt parts were moved on 2026-10-15 to ADR 2026-10.x9gq, still proposed:
`SPOOL_TICKET_KEY_NEXT` and per-pseudonym spool rate limits. The scope-creation gate drafted here
went with them, and x9gq now proposes keeping creation open instead. See [What shipped](#what-shipped).

**Context:** an operator may offer TURN and files to some callers and not others, and the
spool must tell them apart without holding an account. In the shape considered, a storefront
signs a daily ticket with an Ed25519 key it alone holds, and the spool gets only the public
half as a setting, deployed like any other setting, so nothing on the spool's side sees an
account. The first sketch put an optional `ent` field on `turnreq` and `aput`, limits keyed
on a daily pseudonym, and TURN usernames of the form `<exp>:<pseudonym>`. A reading of
`exp/turn` and coturn 4.18.0 on the day this was written found four things that sketch gets
wrong or leaves open:

- coturn's `user-quota` does key on the part after the colon when the part before it is
  all digits (`get_real_username` in `userdb.c`, used by `check_new_allocation_quota` and
  `release_allocation_quota`). But the quota is one number for the whole realm. A ticket
  claim cannot change it per customer, and the spool, which never sees an allocation,
  cannot enforce one either.
- A storage cap keyed on a pseudonym that changes daily resets every day, while the bytes
  stay on disk until the attachment expires. The real bound is the cap times the number
  of pseudonym-days that fit in the retention window.
- `aput` is accounted per scope (`attach_bytes` against `SPOOL_MAX_ATTACH_BYTES`, oldest
  whole attachment evicted) and globally (the `SPOOL_MAX_BYTES` watermark, which sheds the
  least-recently-active whole scope). Nothing is charged to an uploader. The disk is
  already bounded by the watermark. What is missing is fairness: one customer's uploads
  can shed another's scopes.
- In public mode, anyone who learns the hostname can create scopes. Hostnames leak by
  design, through invites and contact-card relay hints. Strangers' scopes then count
  against `SPOOL_MAX_SCOPES` and the watermark, and a customer whose DM scope is refused
  `quota` gets no TURN from that spool, because the client asks only spools that answered
  the scope's `sub`. ADR 2026-10.x9gq takes this one up.

The call sequence this design produces, STUN and TURN included, is drawn in
[`ticketed-call-sequence.md`](../../context/ticketed-call-sequence.md).

**Decision:** a spool configured with a ticket key accepts tickets, checks them itself,
and keeps no ticket state beyond memory.

## Presentation

- **A connection-scoped record.** A new record type, `ent { t, q, tk, sig }`, is sent
  after `hello`. The spool answers `ok`, or `err ticket`, a new code appended to the
  registry, with the reason in `msg`. The verified claims are bound to that connection
  until they expire, and a newer ticket on the same connection replaces them. `hello`
  advertises `ent: true` when a key is configured, with the same presence-is-the-signal
  rule as `turn` and `moderation`.
- **The alternative was the first sketch's `ent` field on `turnreq` and `aput`.** That re-sends
  about 150 bytes with every 48 KiB chunk and verifies a signature per record (or caches
  verifications by hash). It buys no unlinkability, because the spool already ties a
  connection to every scope it subscribes.

## Format

- `tk` is the house CBOR (definite length, short keys, unknown keys ignored), and `sig`
  is Ed25519 over `"knit/spool/v1/ticket" ‖ tk`. Verification uses the JDK's own Ed25519
  (`Signature.getInstance("Ed25519")`, JDK 15+; the daemon runs on 21), so no new
  dependency is added.

```text
Ticket {
  v:   1
  kid: bstr(8)   // SHA-256(public key)[0..8]: must match SPOOL_TICKET_KEY
  aud: tstr      // the one spool it is good for (SPOOL_TICKET_AUDIENCE)
  nbf: uint      // Unix seconds
  exp: uint      // Unix seconds; nbf + at most 48 h
  ps:  bstr(16)  // daily pseudonym = HMAC(storefront key, account ‖ aud ‖ UTC day of nbf)[0..16]
  fl:  uint      // bit 0 turn, bit 1 files (h6pt), bit 2 reserved (x9gq), bit 3 cast (3wdy)
  ab:  uint?     // attachment bytes this pseudonym may upload per ticket day (h6pt)
}
```

- **No allocation claim.** It would be a promise that neither the spool nor coturn can
  keep. The allocation limit is coturn's `user-quota`, set per TURN server.
- **Skew:** the spool accepts a ticket from `nbf − 300 s` to `exp + 300 s`.
- **Replay:** a ticket is a bearer grant, and reuse within its window is the design.
  Several devices and reconnects share it, and a forwarded ticket lends out only that
  pseudonym's own budgets, as a forwarded invite already does. There is no nonce and no
  replay cache.

## What a ticket gates

- **`turnreq`** needs `fl.turn`. Rate limiting stays per connection and per IP; a
  per-pseudonym limit is ADR 2026-10.x9gq's. With a ticket, the S-7.6-4 subscription gate
  is waived: a valid signature is a stronger gate than "holds some scope". The TURN
  username becomes
  `decimal(exp) ‖ ":" ‖ base32lower(SHA-256("knit/spool/v1/turn-user" ‖ ps)[0..10])`:
  16 characters, the shape unticketed credentials already have, and hashed so the TURN
  server's logs never carry the spool-side pseudonym. coturn's `user-quota` then counts
  per pseudonym. S-7.6-7, "random per issue", is amended for ticketed issues only.
- **`aput`** needs `fl.files`. Each stored chunk's `chunkCharge` is charged to the
  pseudonym's in-memory daily upload budget, `min(ab, SPOOL_TICKET_ATTACH_BYTES)`.
  Amended 2026-10-10 by ADR 2026-10.h6pt (accepted 2026-10-14): the gate is on only when
  `files` is in `SPOOL_TICKET_GATES`, only the uploader's own ticket counts, and `aget`
  stays open.
- **`cast`** (amended 2026-10-10, ADR 2026-10.3wdy) needs bit 3, held by *some* live
  subscriber of the cast's scope rather than by the caster, so one payer unlocks a walkie
  both ways. A refusal is a silent drop, since a cast is never answered.
- **Scope creation** is not gated. This ADR first drafted `fl.create` with an `sc`
  budget. ADR 2026-10.x9gq proposes keeping creation open on the shared default spool
  instead, and `create` in `SPOOL_TICKET_GATES` is refused at boot.

## Storage cap

- **The cap is a daily upload budget, not a storage cap.** It is stated honestly: held
  bytes per customer ≤ `ab × (⌈attachment retention / 1 day⌉ + 1)`. Attachments live as
  long as their scope's `ttlMs`, which the scope's creator picks up to `SPOOL_MAX_TTL_MS`.
  At the clients' 48 h default that is 3 × `ab`. At the spool's 7-day default ceiling it
  is 8 × `ab`. (Corrected 2026-10-15: this ADR first gave only the 3 × figure.)
- **The alternative a reader will reach for** is a stable, storefront-salted account
  handle in the ticket, written to each attachment row so frees can be credited. It
  gives an exact cap that survives restarts, but it costs the property the daily
  pseudonym exists for: the spool, and anyone who takes its disk, could link one
  account's uploads to its scopes across days and addresses. It is deferred until the
  deployment wants retention much longer than two days, which is where the multiplier stops
  being tolerable. The spool's default ceiling already allows seven, so a ticketed spool
  should set `SPOOL_MAX_TTL_MS` to the retention it intends to hold (`HOSTING.md`, "What
  ticketed uploads hold"). Whether the spool should enforce that is ADR 2026-10.x9gq's
  open question.

## Keys

- `SPOOL_TICKET_KEY` is a base64url raw 32-byte public key and `SPOOL_TICKET_AUDIENCE` the
  spool's audience string. Both are reloadable by `SIGHUP`. A ticket whose `kid` does not
  match the key is refused `ticket`.
- With one key, rotating it refuses every ticket signed by the old one until the
  storefront reissues. A second key, `SPOOL_TICKET_KEY_NEXT`, and the rotation order are
  ADR 2026-10.x9gq's.

## What shipped

On `exp/turn` after its rebase onto `main`. The hashes this ADR first cited predate that rebase and
survive only on `exp/turn-pre-rebase`.

- **The paid-versus-free call trial** (commits `292f6e6` through `5958db4`):
  - the `ent` record, `err ticket` with a reason in `msg`, and `hello.ent`;
  - the `turnreq` gate and the pseudonym suffix;
  - `SPOOL_TICKET_KEY` and `SPOOL_TICKET_AUDIENCE`, both reloadable. Whether a connection may
    present a ticket is fixed by the `hello` it was sent;
  - `SPOOL_STUN_URLS`, the credential-free `stun` list in `hello`, which the trial needs so that
    a free phone can be reached through a paying peer's relay;
  - `knit-spool ticket-keygen` and `ticket-mint`. A signing key travels as
    `knit-ticket-key:v1:<base64url(seed ‖ public key)>`, because the JDK cannot derive the
    public half from a seed;
  - the metrics, and the conformance checks listed under the guards below.
- **The cast gate**, by ADR 2026-10.3wdy (commit `e0355dc`).
- **The `files` gate**, by ADR 2026-10.h6pt (commits `dc26071` through `6cdcda0`): the `ab`
  claim and the daily upload budget, charged to the uploader only and opt-in through
  `SPOOL_TICKET_GATES`.

**Consequences / limits:**

- **The spool learns a pseudonym per day per customer.** It can link that customer's
  connections across addresses within that day. This is the accepted v1 leak.
  Blind-signed tickets remain the later answer.
- **Pseudonym state is memory only.** A restart resets every daily budget. Restarts are
  staged and rare, and the watermark still bounds the disk. The one table of pseudonyms,
  h6pt's `UploadBudgets`, is capped and prunes expired entries first.
- **A ticket's day is not a calendar day.** A call that spans `exp` keeps its
  allocations, because coturn checks a REST credential's expiry only at a session's first
  authentication, then caches the key (`check_stun_auth`, `hmackey_set`). A new
  credential after renewal counts in the new pseudonym's bucket.
- **The TURN secret stays boot-only, and coturn holds one `static-auth-secret`.**
  Rotating it is a recreate of both, which this decision does not fix.
- **Revocation latency is the ticket lifetime**, at most 48 h plus skew. Allocations
  already up outlive it by design.
- **Strangers can create scopes on a public spool that sells TURN.** With no creation
  gate, what keeps them from crowding out a customer is ADR 2026-10.d2zb's displacement
  rule, the creation rate, PoW and sizing (ADR 2026-10.x9gq).
- **The trap:** putting allocation or bandwidth limits into ticket claims. Only coturn
  can enforce those, and only server-wide. Another trap: calling `ab` a storage cap in
  product copy without the retention multiplier, or quoting the multiplier at 48 h on a
  spool whose `SPOOL_MAX_TTL_MS` is longer.
- **Guards:**
  - `TicketVectorTest`: golden vectors (CBOR bytes, signature, `kid`) on the RFC 8032 TEST 1
    key pair, with every hex string produced independently of this code;
  - `TicketCheckTest`: the skew window, audience, `kid` and claim checks;
  - `TicketTest`: the `turnreq` gate, the stable pseudonym suffix, refusal reasons with no
    strike, renewal, mid-connection expiry, a reload never changing a live connection's gate,
    and that neither the ticket nor its pseudonym is ever logged;
  - `ConfigTest`: the key and audience parse only together, and `create` is refused in
    `SPOOL_TICKET_GATES`;
  - conformance checks `ent-advertised`, `ent-foreign-key`, `ent-required`, `ent-accepts`,
    `ent-expired`, `ent-audience` and `stun-shape`, with the pseudonym guard (two issues on
    one ticket share the part after the colon, S-7.6-17) inside `turn-cred-shape`.
