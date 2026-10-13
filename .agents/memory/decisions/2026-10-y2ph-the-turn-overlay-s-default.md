---
id: "2026-10.y2ph"
slug: the-turn-overlay-s-default
title: "the TURN overlay's default caps are sized in allocations"
date: 2026-10-09
topics: [turn, deploy, abuse]
---

# ADR 2026-10.y2ph — the TURN overlay's default caps are sized in allocations

Status: Accepted (2026-10-09)

**Context:** `deploy/docker-compose.turn.yml` shipped coturn with `total-quota=16`,
`max-bps=16000` and `bps-capacity=125000` (bytes/s). coturn reserves `max-bps` out of
`bps-capacity` for every allocation, used or not, and refuses with 486 once the pool is
spent, so the real ceiling was 125000 / 16000 = 7 allocations — 3 to 7 concurrent relayed
calls, below the 16 the quota suggests. The relay port range 49160–49200 (41 ports) was
the next limit. On 2026-10-08 the lab TURN beside the lax spool gave phones 486 on most
allocations at 4 allocations / 64000 B/s and stopped once raised to 32 / 512000 with a
per-holder quota of 12: one caller holds an allocation per network, address family and
TURN URL it tries, so an allocation is not a call leg, which is what the old docs assumed.
They also costed a call at ~58 MB/hour from payload bitrate counted in both directions;
with packet headers (~56 kbps per direction on the wire) and only the metered outbound
half, it is ~50 MB/hour out.

**Decision:** The defaults become `total-quota=100`, `bps-capacity=1600000` (12.8 Mbps)
and relay ports 49160–49999. The pool is exactly `total-quota` × `max-bps`, so the two run
out together instead of the pool silently undercutting the quota. `max-bps` and
`user-quota` are unchanged. The port range is wider than the quota needs so an operator
can raise `TURN_TOTAL_QUOTA` without reopening the firewall. The alternative a reader
reaches for first is to keep the worst case inside a 1 TB allowance (`bps-capacity`
400000, 25 allocations); with several allocations per caller that is too few calls to be
a useful default, so it is documented as the setting for a public Spool without tickets
instead.

**Consequences / limits:** Worst-case abuse on a public, unticketed Spool rises from
~325 GB to ~4 TB a month outbound, four times a 1 TB allowance; HOSTING.md and the
overlay's header say so and name 400000 as the 1 TB setting. How many calls 100
allocations carries is unmeasured: anywhere from 100 down to a handful, depending on how
many allocations a phone holds per call. Count that on a real call before sizing a
deployment by users, and supersede this ADR if the count moves the defaults. An existing
firewall opened for 49160–49200 silently drops relay traffic on the new upper ports.
Nothing tests these numbers; `DeployConfigTest` checks only that the overlay wires both
halves. The guard is that the defaults appear in `deploy/coturn/entrypoint.sh`,
`deploy/.env.example`, the overlay's comment, README, HOSTING.md and CHANGELOG, and a
grep for the old value must come back empty after any change.

**Amended 2026-10-12** (`feat/turn-allocations`, knit-next S-7.6-25): the overlay's default
`SPOOL_TURN_URLS` drops `turn:…:3478?transport=tcp` and lists only the UDP URL, then `turns:`
on 5349. A phone opened an allocation for every URL on every network it tried, and the plain
TCP URL seldom reached a network the other two missed, so cutting it is the cheapest way to
carry more calls inside the same `total-quota`. The quotas above are unchanged until a
measured call sizes them. An operator who sets `SPOOL_TURN_URLS` keeps their list, and
3478/tcp needs opening only for a TCP URL they add back. `DeployConfigTest`'s
`theTurnOverlayDefaultsToOneUrlPerTransport` pins the default list and its order.
