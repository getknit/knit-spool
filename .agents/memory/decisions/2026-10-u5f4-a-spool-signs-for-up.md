---
id: "2026-10.u5f4"
slug: a-spool-signs-for-up
title: "a spool signs for up to four TURN servers, one per host"
date: 2026-10-25
topics: [turn, config, hello]
---

# ADR 2026-10.u5f4 — a spool signs for up to four TURN servers, one per host

Status: Accepted (2026-10-25). Builds the knit-spool brief of knit-spool-coordinator ADR 2026-10.w7hc,
as refined by the `nearest-turn` contract. The spec text is knit-next `58c0bded9`
(`docs/SPOOL_PROTOCOL.md` §7.6, "Several TURN servers": S-7.6-48 to S-7.6-50 for the spool, C-7.6-51 to
C-7.6-54 for the client).

**Context:** a spool signed for one TURN server: `SPOOL_TURN_SECRET` and `SPOOL_TURN_URLS`. The
coordinator's review of the production TURN settings on 2026-10-24 found one box serving every fleet
location, and growing past one box's quota needs a second. `SPOOL_TURN_URLS` already accepts URLs on
several hosts, which looks like the way to add one. It is not: libwebrtc allocates on every TURN URL
it is handed, so each box would hold an allocation for every relaying phone, and the second box would
mirror the first instead of adding to it. The client also ranks relay candidates by type and
transport before round-trip time, so it would not settle on the nearer box either. And one secret
would sign for both boxes, so one leaked box would mint credentials for the other.

**Decision:** a spool signs for up to four servers, each with its own secret and URLs, and a phone
names the one it wants.

## Configuration

- **Numbered variables.** Server 1 stays `SPOOL_TURN_SECRET` and `SPOOL_TURN_URLS`, so every spool
  that exists runs as it did. Servers 2 to 4 are `SPOOL_TURN_2_SECRET` and `SPOOL_TURN_2_URLS` through
  `_4_`, named after `SPOOL_COMMONS_<n>_*` (ADR 2026-10.5cwn). `MAX_TURN_SERVERS` keeps `KNOWN_VARS`
  finite, so the coordinator can pin every name.
- **Shared settings.** `SPOOL_TURN_TTL_S`, `SPOOL_TURN_RATE` and `SPOOL_TICKET_TURN_RATE` apply to
  every server, and still need server 1.
- **Boot-only**, like today's TURN keys. `bootOnly()` reports one URL count and one secret's presence
  per server, comma-joined, so a reload that adds or drops a server is reported as needing a restart,
  and a one-server spool's boot line reads as it always has.
- **Refused always:** a gap (a key of server n while server n−1 has neither of its keys), half a
  server (a secret without URLs, or URLs without a secret), a secret under 32 characters, and a URL
  that is not `turn:` or `turns:`. No refusal echoes a secret.
- **Refused only with two or more servers:** a server whose URLs name more than one host (or a URL
  this can read no host from), two servers on one host, and a server with no UDP URL. With one
  server, every URL list accepted before is still accepted. Equal secrets are allowed.

Rooms allow gaps and TURN servers do not. A room is found by its tag, so its number is only a
variable name. A TURN server's position in `turnHosts` is its slot, and the client's logs name a
server by that slot, so a gap would make the logs and the variables disagree.

## Wire

- **HELLO** carries `turnHosts`, beside `turn`: each server's entry, `host:port` of its UDP URL, the
  host lowercased and the port as written or 3478. It is declared last in `Hello`, after `rooms`, and
  omitted rather than empty when no server has a UDP URL, which only one server can.
- **`turnreq.host`** selects the server whose entry it equals byte for byte. An absent or unmatched one,
  or any one on a spool with no `turnHosts`, gets server 1, never an error and never a strike.
- **`turncred`** keeps its form, with the selected server's URLs and a `pass` under its secret. The
  selection comes after every gate and budget, so one per-connection, per-address and per-holder
  budget covers every server, and a ticketed holder's suffix is the same on all of them.

The entry form lives in `:protocol` (`TurnUris`), not in `:daemon`, because the conformance check
derives the entry of a credential's URLs exactly as a client does, and `:conformance` may depend on
`:protocol` alone.

## Alternatives rejected

- **A list variable** (`SPOOL_TURN_SERVERS=secret@urls;…`) invents a grammar the rest of the
  environment does not have, and puts secrets inside a value whose refusals would have to echo it.
  5cwn rejected the same shape for rooms.
- **The spool picks the server**, by its own location or the client's address. Where a spool is says
  nothing about where its phones are, and GeoIP is poor for carriers behind carrier-grade NAT.
  w7hc's alternatives cover this.
- **An error for an unknown `host`.** A call is waiting, and a credential for server 1 beats a
  refusal. A `host` naming a server the spool has since dropped then costs nothing.
- **Probing the boxes from the spool**, to steer away from a full one. The spool never relays and
  never probes; a refused allocation is the client's signal (C-7.6-54, revised 2026-10-25 so that a
  side moves on only before its own offer or answer leaves).

## Metrics

`knit_spool_turn_issued_total` and `knit_spool_turn_issued_ticketed_total` gain `server="<n>"`, one
series per configured server, rendered from zero. The label is the number, never the host. The
ticketed twin is no longer rendered on a spool with a ticket key and no TURN.

## The version

`0.6.0-dev.3` is the first build that reads the six variables. They move the environment contract,
so ADR 2026-10.8ymn's bump applies without a by-hand exception. An older daemon handed them logs
`unrecognized environment variable` and boots with server 1 alone, so the coordinator renders them
only to spools whose every image meets this version.

**Consequences / limits:**

- **Server 1 is the fallback, not the nearest.** Old clients, a client with no ranking yet, and an
  unknown host all get it. Moving a location to another box is still the coordinator's
  `TURN_REGION_OF`, not an order in this list.
- **The spool is load-blind.** The nearest box may be full, and the spool cannot tell. The client
  falls back once per call (C-7.6-54), inside the per-connection burst of 2.
- **Rotating one box's secret restarts the spool**, and every member reconnects. That was already
  true of the one secret.
- **A host is compared as a string.** `[2001:db8::7]` and `[2001:0db8::7]` are two hosts to this
  check and to every client. An operator writes each address one way.
- **The trap:** treating the entry as decoration and recomputing it differently on one side. The
  client reads which server a credential is for from the entry of its URLs, so a spool and a client
  that disagree on case, brackets or the default port hand libwebrtc the wrong server. Another trap:
  selecting the server before the budgets, so naming another host becomes a way around them.
- **Guards:**
  - `ConfigTest`: numbering, each of the seven refusals, empty-is-unset, and one server keeping every
    URL list it had;
  - `TurnUrisTest`: the UDP URL, host and entry, the contract's two examples among them;
  - `TurnTest`: `turnHosts`' shape, order and absence, selection by `host`, server 1 for an absent,
    unknown or near-miss one, a server 2 credential that does not verify under server 1's secret, the
    shared budget, a non-text `host` as malformed, and per-server metrics;
  - `TicketTest.aPseudonymsSuffixIsTheSameOnEveryServer`;
  - `ReloadTest.everyTurnServerIsBootOnly` and `ConfigDescribeTest`;
  - `TurnRecordVectorTest`: `helloSpoolTurnHosts` and `turnreqHost`, made by a hand-rolled CBOR encoder
    rather than kotlinx, and `SpecVectorTest`, which pins them as §13 carries them since knit-next
    `d7a7e1da7`;
  - the conformance check `turn-hosts-select`, run in process by `ConformanceSelfTest` against two
    servers;
  - `EnvContractTest` at `0.6.0-dev.3`, and `DeployConfigTest` for the compose lines.
