---
id: "2026-10.bxsg"
slug: entitlement-tickets-gate-turn
title: "entitlement tickets gate turn, files and scope creation on a spool"
date: 2026-10-08
topics: [tickets, turn, attachments, auth]
---

# ADR 2026-10.bxsg — entitlement tickets gate turn, files and scope creation on a spool

Status: Proposed (2026-10-08). Draft, not yet reviewed; nothing here is built.

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
  the scope's `sub`.

The call sequence this design produces, STUN and TURN included, is drawn in
[`ticketed-call-sequence.md`](../../context/ticketed-call-sequence.md).

**Decision:** a spool configured with a ticket key accepts tickets, checks them itself,
and keeps no ticket state beyond memory.

## Presentation

- **A connection-scoped record.** A new record type, `ent { t, q, tk, sig }`, is sent
  after `hello`. The spool answers `ok`, or `err ticket`, a new code appended to the
  registry. The verified claims are bound to that connection until they expire, and a
  newer ticket on the same connection replaces them. `hello` advertises `ent: true` when
  a key is configured, with the same presence-is-the-signal rule as `turn` and
  `moderation`.
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
  kid: bstr(8)   // SHA-256(public key)[0..8]: picks KEY or KEY_NEXT
  aud: tstr      // the one spool it is good for (SPOOL_TICKET_AUDIENCE)
  nbf: uint      // Unix seconds
  exp: uint      // Unix seconds; nbf + at most 48 h
  ps:  bstr(16)  // daily pseudonym = HMAC(storefront key, account ‖ aud ‖ UTC day of nbf)[0..16]
  fl:  uint      // bit 0 turn, bit 1 files, bit 2 create scopes, bit 3 cast
  ab:  uint?     // attachment bytes this pseudonym may upload per ticket day
  sc:  uint?     // new scopes this pseudonym may create per ticket day
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

- **`turnreq`** needs `fl.turn`. Rate limiting is per pseudonym (`SPOOL_TICKET_TURN_RATE`)
  as well as per connection and per IP. With a ticket, the S-7.6-4 subscription gate is
  waived: a valid signature is a stronger gate than "holds some scope". The TURN username
  becomes `decimal(exp) ‖ ":" ‖ base32lower(SHA-256("knit/spool/v1/turn-user" ‖ ps)[0..10])`:
  16 characters, the shape unticketed credentials already have, and hashed so the TURN server's
  logs never carry the spool-side pseudonym. coturn's `user-quota` then counts per pseudonym.
  S-7.6-7, "random per issue", is amended for ticketed issues only.
- **`aput`** needs `fl.files`. Each stored chunk's `chunkCharge` is charged to the
  pseudonym's in-memory daily upload budget, `min(ab, SPOOL_TICKET_ATTACH_BYTES)`.
- **`cast`** (amended 2026-10-10, ADR 2026-10.3wdy) needs bit 3, held by *some* live
  subscriber of the cast's scope rather than by the caster, so one payer unlocks a walkie
  both ways. A refusal is a silent drop, since a cast is never answered.
- **Scope creation** (the unknown-scope path of `sub`, `push` and `aput`) needs
  `fl.create` and the `sc` budget when `SPOOL_TICKET_CREATE=true`. Existing scopes stay
  open to anyone who knows their id, so a customer's free friends can still text through
  the customer's scopes. This switch is what lets a ticketed spool run public with no
  `SPOOL_TOKEN`.

## Storage cap

- **The cap is a daily upload budget, not a storage cap.** It is stated honestly: held
  bytes per customer ≤ `ab × (⌈attachment retention / 1 day⌉ + 1)`. At the 48 h default
  scope `ttlMs` that is 3 × `ab`.
- **The alternative a reader will reach for** is a stable, storefront-salted account
  handle in the ticket, written to each attachment row so frees can be credited. It
  gives an exact cap that survives restarts, but it costs the property the daily
  pseudonym exists for: the spool, and anyone who takes its disk, could link one
  account's uploads to its scopes across days and addresses. It is deferred until the
  deployment wants retention much longer than two days, which is where the multiplier stops
  being tolerable.

## Keys

- `SPOOL_TICKET_KEY` and `SPOOL_TICKET_KEY_NEXT` are base64url raw 32-byte public keys,
  following the `SPOOL_TOKEN_NEXT` pattern, and are reloadable by `SIGHUP`. Rotation:
  1. render NEXT;
  2. sign with NEXT once every spool reports it;
  3. wait out the longest ticket;
  4. promote NEXT and unset it.

## Trial subset

What shipped on `exp/turn` for the paid-versus-free call trial (commits `0891e58` through
`62de11d`), and what is still only proposed here:

- **Shipped:**
  - the `ent` record, `err ticket` with a reason in `msg`, and `hello.ent`;
  - the `turnreq` gate and the pseudonym suffix;
  - `SPOOL_TICKET_KEY` and `SPOOL_TICKET_AUDIENCE`, both reloadable. Whether a connection may
    present a ticket is fixed by the `hello` it was sent;
  - `SPOOL_STUN_URLS`, the credential-free `stun` list in `hello`, which the trial needs so that
    a free phone can be reached through a paying peer's relay;
  - `knit-spool ticket-keygen` and `ticket-mint`. A signing key travels as
    `knit-ticket-key:v1:<base64url(seed ‖ public key)>`, because the JDK cannot derive the
    public half from a seed;
  - the metrics, and the conformance checks `ent-advertised`, `ent-foreign-key`, `ent-required`,
    `ent-accepts`, `ent-expired`, `ent-audience` and `stun-shape`. The `turn-user-is-pseudonym`
    guard lives inside `turn-cred-shape`.
- **Still proposed:**
  - the `ab` and `sc` claims and the upload budget;
  - ticket-gated scope creation and public ticketed spools;
  - `SPOOL_TICKET_KEY_NEXT` and per-pseudonym spool rate limits.

**Consequences / limits:**

- **The spool learns a pseudonym per day per customer.** It can link that customer's
  connections across addresses within that day. This is the accepted v1 leak.
  Blind-signed tickets remain the later answer.
- **Pseudonym state is memory only.** A restart resets every daily budget. Restarts are
  staged and rare, and the watermark still bounds the disk. A table of pseudonyms is
  capped and pruned like the per-IP client table.
- **A ticket's day is not a calendar day.** A call that spans `exp` keeps its
  allocations, because coturn checks a REST credential's expiry only at a session's first
  authentication, then caches the key (`check_stun_auth`, `hmackey_set`). A new
  credential after renewal counts in the new pseudonym's bucket.
- **The TURN secret stays boot-only, and coturn holds one `static-auth-secret`.**
  Rotating it is a recreate of both, which this decision does not fix.
- **Revocation latency is the ticket lifetime**, at most 48 h plus skew. Allocations
  already up outlive it by design.
- **The trap:** putting allocation or bandwidth limits into ticket claims. Only coturn
  can enforce those, and only server-wide. Another trap: calling `ab` a storage cap in
  product copy without the retention multiplier.
- **Guards, owed with the implementation:**
  - a `TicketVectorTest` golden vector (CBOR bytes, signature, `kid`), on the RFC 8032 TEST 1
    key pair and cross-checked against an independent encoder;
  - conformance checks `ent-required` (an unticketed `aput`, `turnreq` and, with
    `SPOOL_TICKET_CREATE`, scope creation are refused `ticket`), `ent-expired` (outside
    the skew window) and `turn-user-is-pseudonym` (two issues on one ticket share the
    part after the colon);
  - a `ConfigTest` case for each new key's bounds;
  - a `ReloadTest` case proving that the key pair is hitless.
