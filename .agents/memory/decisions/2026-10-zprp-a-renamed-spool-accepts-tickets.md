---
id: "2026-10.zprp"
slug: a-renamed-spool-accepts-tickets
title: "a renamed spool accepts tickets for both its names"
date: 2026-10-22
topics: [tickets, config]
---

# ADR 2026-10.zprp — a renamed spool accepts tickets for both its names

Status: Accepted (2026-10-22). Amends ADR 2026-10.bxsg: `aud` names the one spool a ticket is good
for, by either of its names while it is renamed. The spec wording is pending in knit-next; see
[The spec](#the-spec).

**Context:** a ticket names one host in `aud`, and a spool refuses one whose `aud` is not its
`SPOOL_TICKET_AUDIENCE` with `err ticket` `audience` (S-7.6-14). The Knit client asks the issuer for
a ticket for the host it dialled and requires `aud` to equal that host. Renaming a spool that clients
already know therefore cannot happen in one step. Installed apps keep dialling the old name until
each one moves, so both names have to reach the same spool for a while. During that time a client on
the old name holds a ticket for the old name and a client on the new name holds one for the new name.
A spool with one audience refuses one of the two.

**Decision:** `SPOOL_TICKET_AUDIENCE_ALIAS` names a second host the spool accepts in `aud`. During a
rename the new name is the audience and the old name is the alias. Once every client has moved, the
alias is cleared.

- **Validated like the audience.** A lowercase host name by the same pattern. It is refused without
  `SPOOL_TICKET_KEY` and `SPOOL_TICKET_AUDIENCE`, and refused when it repeats the audience: a second
  copy of the same name would look like a rename in progress when none is. Empty is unset.
- **Membership, not equality.** `Tickets.check` takes the set of names (`TicketConfig.audiences`) and
  refuses `audience` when `aud` is in neither. The order of the checks is unchanged: shape, `kid`,
  signature, then the audience and the window.
- **Reloadable, read live by every `ent`,** like `SPOOL_TICKET_KEY_NEXT`. A ticket a connection
  already holds stays held when the alias is set or cleared. The report line carries
  `ticketAudAlias`.
- **Never announced.** `hello` carries no audience today, and none is added. A client learns
  nothing about the spool's other name from the spool.

Alternatives rejected:

- **A list, `SPOOL_TICKET_AUDIENCES`.** This changes the meaning of a variable every ticketed spool
  already sets, and it invites a spool that answers to many names for good. Each name gives a holder
  another pseudonym (below), so a spool with many names multiplies every per-holder budget. A rename
  needs exactly two names, and one alias is the smallest setting that covers it.
- **Skipping the audience check during a rename.** Then a ticket for any spool the same issuer serves
  would be good here, and per-spool tickets would stop meaning anything.
- **Two spools, one per name.** Members on the old name and members on the new name would hold their
  scopes on different spools and stop meeting there, which is exactly what a rename must not do.
- **Minting every ticket for the new name.** The client checks `aud` against the host it dialled, so
  an app still on the old name refuses the ticket before the spool ever sees it.

## The spec

S-7.6-14's normative text already says "`aud` is its own", and this daemon reads a spool's own names
as its audience and its alias. The `Ticket` layout's comment in `docs/SPOOL_PROTOCOL.md` §7.6 still
says "`aud`: the one spool it is good for", which a reader takes as one name. The amendment ("one of
its own names") belongs in knit-next and is not made from this repo. Until it lands, `:conformance`
gains no check for a second audience: conformance tests what the spec says, and a check that only
this server passes does not belong there.

## The version

`0.5.0-dev.2` is the first build that reads `SPOOL_TICKET_AUDIENCE_ALIAS`. The variable and its
`ticketAudAlias` report field move the environment contract, so ADR 2026-10.8ymn's bump applies
without a by-hand exception. An older build handed the variable logs `unrecognized environment
variable` and boots, then refuses every old-name ticket as `audience`. So whatever renders a spool's
environment must gate the alias on this version rather than set it and hope.

**Consequences / limits:**

- **A holder can have two pseudonyms mid-rename.** The issuer derives `ps` as
  HMAC(account ‖ `aud` ‖ day), so one account carrying tickets for both names presents two
  pseudonyms. Every budget keyed on `ps` doubles for it until the alias is cleared: the
  per-holder TURN rate (`SPOOL_TICKET_TURN_RATE`), the daily upload budget
  (`SPOOL_TICKET_ATTACH_BYTES`, `ab`), and coturn's `TURN_USER_QUOTA`, which counts the credential
  suffix derived from `ps`. This is accepted: it is bounded by two and lasts only as long as the
  rename. The spool cannot see that two pseudonyms are one account, and must not try.
- **The trap: leaving the alias set.** While it is set, a ticket minted for the old name is good
  here. If the old name is later given to a different spool under the same issuer, that spool's
  tickets are good here too. Clear the alias before the old name is given to another spool.
- **The daemon cannot tell when every client has moved.** It never learns which name a client
  dialled, beyond what the ticket says. Deciding when to clear the alias belongs to whoever runs
  the rename.
- **TLS, DNS and the proxy are out of scope.** Both names must reach the spool with valid
  certificates. Nothing here arranges that.
- **Guards:**
  - `TicketCheckTest.aTicketNamingEitherOfTwoNamesIsTheSpoolsOwn`: either name is accepted, and a
    third is refused `audience`;
  - `ConfigTest.theAudienceAliasIsASecondNameAndMustDiffer`: refused alone, without the audience,
    equal to it, or not a host name, and reported as `ticketAudAlias`;
  - `ReloadTest.renamingATicketedSpoolIsHitlessForLiveConnections`: set and cleared with one
    connection live throughout;
  - `ConfigDescribeTest` reports the field;
  - `DeployConfigTest` checks that `docker-compose.tls.yml` passes the variable through;
  - `EnvContractTest`, pinned at `0.5.0-dev.2`.
