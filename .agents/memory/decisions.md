# Architecture decision records

The load-bearing decisions and *why* they hold. One file each, in
[`decisions/`](decisions/) — this page is the router. Open the file whose
row matches; do not guess from the title alone. Supersede a decision by
writing a new ADR and changing the old one's `Status:` line, never by
deleting it.

**This file is generated. Do not edit it by hand.** Add a decision with
`python3 scripts/adr.py new "<title>" --topics a,b`, write the body,
then `python3 scripts/adr.py index`.

**Ids are minted, not chosen.** A new ADR is `YYYY-MM.suffix`
(`ADR 2026-08.k3f9`) with a random suffix, so parallel branches never take
the same number. Cite an ADR by its id, exactly as its heading spells it.

| ADR | Decision | Topics |
| --- | --- | --- |
| [2026-10.bxsg](decisions/2026-10-bxsg-entitlement-tickets-gate-turn.md) | entitlement tickets gate turn, files and scope creation on a spool | tickets, turn, attachments, auth |
| [2026-10.d2zb](decisions/2026-10-d2zb-bare-scopes-are-reclaimed.md) | bare scopes are reclaimed, not held against maxScopes | store, quota, abuse |
| [2026-10.y2ph](decisions/2026-10-y2ph-the-turn-overlay-s-default.md) | the TURN overlay's default caps are sized in allocations | turn, deploy, abuse |
| [2026-10.3wdy](decisions/2026-10-3wdy-casts-fan-out-unstored.md) | casts fan out unstored, unlocked by one ticket | casts, calls, tickets, abuse |
| [2026-10.h6pt](decisions/2026-10-h6pt-tickets-gate-verbs-per-connection.md) | tickets gate verbs per connection, and only an uploader's ticket pays for attachments | tickets, attachments, auth, abuse |
| [2026-10.us34](decisions/2026-10-us34-freed-sqlite-pages-go-back.md) | freed SQLite pages go back to the disk | store, deploy |
