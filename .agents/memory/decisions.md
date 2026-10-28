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
| [2026-10.bxsg](decisions/2026-10-bxsg-entitlement-tickets-gate-turn.md) | entitlement tickets gate turn and files on a spool | tickets, turn, attachments, auth |
| [2026-10.d2zb](decisions/2026-10-d2zb-bare-scopes-are-reclaimed.md) | bare scopes are reclaimed, not held against maxScopes | store, quota, abuse |
| [2026-10.y2ph](decisions/2026-10-y2ph-the-turn-overlay-s-default.md) | the TURN overlay's default caps are sized in allocations | turn, deploy, abuse |
| [2026-10.3wdy](decisions/2026-10-3wdy-casts-fan-out-unstored.md) | casts fan out unstored, unlocked by one ticket | casts, calls, tickets, abuse |
| [2026-10.h6pt](decisions/2026-10-h6pt-tickets-gate-verbs-per-connection.md) | tickets gate verbs per connection, and only an uploader's ticket pays for attachments | tickets, attachments, auth, abuse |
| [2026-10.us34](decisions/2026-10-us34-freed-sqlite-pages-go-back.md) | freed SQLite pages go back to the disk | store, deploy |
| [2026-10.x9gq](decisions/2026-10-x9gq-scope-creation-stays-open.md) | scope creation stays open, so ticketed and unticketed members share a spool | tickets, auth, abuse |
| [2026-10.8ymn](decisions/2026-10-8ymn-main-builds-carry-a-numbered.md) | main builds carry a numbered dev version that moves with the environment contract | versioning, config, release |
| [2026-10.h4ps](decisions/2026-10-h4ps-a-ticket-s-am-claim.md) | a ticket's am claim caps the attachments it may upload | tickets, attachments |
| [2026-10.zprp](decisions/2026-10-zprp-a-renamed-spool-accepts-tickets.md) | a renamed spool accepts tickets for both its names | tickets, config |
| [2026-10.5cwn](decisions/2026-10-5cwn-one-spool-runs-up-to.md) | one spool runs up to eight commons rooms, configured as numbered variables | commons, config, hello |
| [2026-10.d9ez](decisions/2026-10-d9ez-the-sweep-runs-in-batches.md) | the sweep runs in batches and visits only due scopes | store, sweep, capacity |
| [2026-10.ywkw](decisions/2026-10-ywkw-a-new-scope-is-admitted.md) | a new scope is admitted without counting every scope | store, quota, capacity |
| [2026-10.3kgr](decisions/2026-10-3kgr-the-conformance-runner-speaks-websocket.md) | the conformance runner speaks WebSocket through the JDK | conformance, tls, dependencies |
| [2026-10.hnqs](decisions/2026-10-hnqs-boot-layers-spool-reload-file.md) | boot layers SPOOL_RELOAD_FILE over the environment, as SIGHUP does | config, reload |
| [2026-10.hs75](decisions/2026-10-hs75-commons-rooms-reload-on-sighup.md) | commons rooms reload on SIGHUP, and only a changed room's members reconnect | commons, config, reload |
| [2026-10.u5f4](decisions/2026-10-u5f4-a-spool-signs-for-up.md) | a spool signs for up to four TURN servers, one per host | turn, config, hello |
| [2026-10.zm4z](decisions/2026-10-zm4z-public-copy-names-supporters.md) | Public copy names supporters, never payers | docs, tickets, support |
| [2026-10.2q49](decisions/2026-10-2q49-a-signal-lane.md) | A signal lane: live fan-out that no ticket gates | sigs, casts, tickets, typing |
