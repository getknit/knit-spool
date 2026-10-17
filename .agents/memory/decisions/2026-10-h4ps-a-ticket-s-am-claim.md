---
id: "2026-10.h4ps"
slug: a-ticket-s-am-claim
title: "a ticket's am claim caps the attachments it may upload"
date: 2026-10-17
topics: [tickets, attachments]
---

# ADR 2026-10.h4ps — a ticket's am claim caps the attachments it may upload

Status: Accepted (2026-10-17). Amends ADR 2026-10.h6pt (the `files`
gate gains a size rule) and ADR 2026-10.bxsg (the claim layout gains `am`). The spec text is
knit-next `cb3e5b27` (`docs/SPOOL_PROTOCOL.md` §7.6: `am`, S-7.6-31, C-7.6-32, and S-7.6-22's word
list).

**Context:** h6pt's `files` gate lets an issuer meter how many bytes a holder uploads a day, with
`ab`. It cannot say how large one attachment may be. The only per-attachment bound is the spool's
own `SPOOL_MAX_ATTACH_BYTES`, 16 MiB by default, which is one number for every holder. An issuer
that wants to hand out a ticket good for small attachments only has no way to say so: with `ab` alone, one 1.25 MiB budget is spent by a single attachment as easily as by
five small ones.

h6pt's rule still holds: a ticket gates a verb and never changes a stored bound. A per-attachment
cap fits it, because it is checked when a chunk arrives and is never written to the scope.

**Decision:** a ticket gains an optional `am`, the largest attachment its holder may `aput`, in
bytes of `A`. Where `files` is gated, an `aput` whose `total` exceeds `⌊am / aChunkBytes⌋` under the
uploading connection's ticket is refused `err ticket` with `msg` `size` (S-7.6-31).

## The claim

```text
Ticket { v, kid, aud, nbf, exp, ps, fl, ab: uint?, am: uint? }
```

- **Written last, only when set.** `am` is declared after `ab`, so a ticket's map has 7, 8 or 9
  entries, and a ticket without it encodes exactly as before. `MAX_TK_BYTES` (512) is unchanged:
  `am` adds at most 12 bytes.
- **A negative `am` is `malformed`**, beside a negative `ab`. An `am` below one chunk, `0` included,
  is well formed and admits no `aput` at all.
- **Absent means the spool's own limit.** `am` only lowers a limit: an attachment within it still
  meets `maxATotal`, and the store still refuses a larger `total` with `quota`.
- **Where `files` is not gated, `am` is ignored**, as `ab` is.

## The size rule

- **Order.** `not_subscribed` and `malformed` first, then S-7.6-22's `missing`, `expired` or
  `flags`, then `size`, then `budget`. It sits in `SpoolServer.filesAdmitted`, before every bucket,
  so a refused chunk spends no push token, no PoW and no budget.
- **Every chunk is checked**, a re-sent one included. The store keys chunks by scope, `aid` and index
  alone, so one attachment's chunks may arrive on different connections under different tickets. Each
  uploader is held to its own `am`. A re-send of a chunk already held is refused `size` rather than
  acked as a duplicate, because the check comes before the store is asked.
- **Never a strike, nothing stored or charged.** `size` says only that this attachment is too large
  for this holder here. It locks nothing: a smaller attachment goes up on the same connection, and the
  ticket's TURN and casts are untouched (C-7.6-32).
- **Metrics.** `size` joins the closed `reason` set of `knit_spool_ticket_refused_total`, rendered
  from zero. `knit_spool_ticket_attach_bytes_charged_am_total` counts, out of
  `knit_spool_ticket_attach_bytes_charged_total`, the bytes charged under a ticket that carries `am`.
  It is a second counter rather than a label, so an existing query on the first keeps meaning the
  whole. Neither is per holder.

## The version

`0.4.0-dev.3` is the first build that reads `am`. ADR 2026-10.8ymn moves `n` with the environment
contract, and this change moves no variable, no reload half and no default, so `EnvContractTest`'s
fingerprint is unchanged. 8ymn's "Not covered" list already allows a by-hand bump for behaviour the
boot log does not show, and this is one: a tool that renders a spool's settings for an issuer that
mints `am` must tell an `am` build from the one before it, and a spool that ignores the key admits
attachments the ticket meant to refuse. The version half of `EnvContractTest.PINNED` moved by hand
and the fingerprint half did not.

## Alternatives rejected

- **A class bit in `fl`.** A bit naming a class of ticket would let a spool apply limits of its own
  choosing. Spools ignore unknown bits, so an older one would grant the full limit, and the limit
  itself would still have to live somewhere. An issuer's own number in the ticket says everything a
  bit would, and needs no agreement on what the class means. Bit 4 stays unassigned.
- **A spool-wide ceiling for ticketed uploads**, a `SPOOL_*` variable. It is a second meter beside
  `SPOOL_MAX_ATTACH_BYTES`, the same for every holder, so it cannot tell two tickets apart, which is
  the whole point.
- **Chunks as the unit.** `ab` and the spool's attachment limits are bytes, and bytes outlive a change
  of `aChunkBytes`. A chunk count would also make the issuer do the spool's arithmetic.
- **Rounding up**, `⌈am / aChunkBytes⌉`. A cap must not admit more than it names: at `am` = 262,144
  rounding up would admit a sixth chunk, 294,912 bytes of `A`. The spool's own `maxATotal` rounds up
  because it answers a different question, whether an attachment can fit a scope at all.

**Consequences / limits:**

- **An attachment stored by a holder with a larger `am` cannot be completed by one with a smaller
  one.** That is the rule working: the second holder is uploading something its ticket does not
  allow.
- **A client that does not read `am` learns the cap only from `size`.** Only a connection whose ticket
  carries `am` can see it, and the spec's client rule (C-7.6-32) keeps a reading client from trying.
  An older client retries every heal round, as h6pt's consequences already describe for refusals.
- **`am` says nothing about storage.** The held bytes are still bounded by `ab`, the retention
  multiplier and the watermark, exactly as h6pt states.
- **The trap:** letting `am` raise a limit, for example by reading it in place of `maxATotal` when it
  is larger. A ticket never raises a bound (h6pt). Another trap: moving the size check after the
  store, so that a re-sent chunk is acked as a duplicate. A holder could then complete, chunk by
  chunk, an attachment it was never allowed to send.
- **Guards:**
  - `TicketCheckTest`: `am` decodes, a negative `am` is `malformed`, an unknown key after `am` is
    accepted, and an unknown `fl` bit is accepted;
  - `TicketVectorTest`: `tkFilesAm`/`sigFilesAm` (9 entries) and `tkAmOnly`/`sigAmOnly` (8 entries),
    produced by a hand-rolled CBOR encoder and pyca/cryptography's Ed25519, not by this code;
  - `TicketFilesTest`: 5 chunks pass and 6 fail at `am` = 262,144; an `am` under one chunk and `0`
    refuse everything; a later chunk and a re-sent chunk under a smaller `am` are refused; a `size`
    refusal stores, charges and strikes nothing; with `files` ungated `am` is ignored; an `am` above
    the store's limit still gets `quota`; an unknown `fl` bit grants no upload; the metrics, the
    split included;
  - `TicketToolTest`: `ticket-mint --am`;
  - the conformance check `files-size`, run in process by `ConformanceSelfTest`;
  - `EnvContractTest` pins `0.4.0-dev.3` beside the unchanged fingerprint.
