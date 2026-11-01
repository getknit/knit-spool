---
id: "2026-10.fxcs"
slug: live-subscribers-per-scope-are
title: "live subscribers per scope are capped, the commons exempt"
date: 2026-10-31
topics: [scopes, fan-out, abuse, egress]
---

# ADR 2026-10.fxcs — live subscribers per scope are capped, the commons exempt

Status: Accepted (2026-10-31). Implements knit-next `docs/SPOOL_PROTOCOL.md` S-6.4-6 spool-side, as of
knit-next `18ac2f0b8` (the spec's revision of 2026-10-31, on branch `spec/scope-subscriber-cap`), with
its §10.4 paragraph. Tracked as knit-spool #7. Builds on ADR 2026-10.3wdy (casts) and ADR 2026-10.2q49
(sigs), and replaces the copies-out budget both name as the change to make if their lanes are ever
amplified. Moves the development version to `0.9.0-dev.2` (ADR 2026-10.8ymn).

**Context:** a spool's egress is every fanned-out record times the other live subscribers of its scope.
Each per-lane budget counts records accepted, never copies sent. Issue #7 first asked for a bound on cast
and sig fan-out, using a 2,000-member scope as its example. That framing was wrong in three ways:

- **No Knit scope is that large on its own.** knit-next caps a group at 8 members (`Wire.kt`,
  `MAX_MEMBERS = 8`). An ordinary scope's subscribers are those members' devices: a few dozen at most.
  The commons is the one scope that can hold most of a spool's connections, and it already refuses casts
  and sigs (S-7.7-3, S-7.8-3).
- **A crowded scope is built on purpose.** Anyone who learns a scope id can subscribe (spec §10.4), and
  nothing in the daemon bounds subscribers per scope. With `SPOOL_MAX_CONNS` unlimited by default and 16
  connections per address, one client on about 125 addresses puts 2,000 connections in one scope. Most of
  the copies then go to its own connections: copies leave by `trySend`, and a receiver that stops reading
  only loses them. So this spends the operator's egress at the client's own download speed. It cannot be
  aimed at a third party.
- **Pushes are the largest multiplier, not casts or sigs.** At the defaults, one connection's pushes are
  10 a second at up to 64 KiB (`SPOOL_MAX_BLOB`), with no spool-wide push budget outside the commons. A
  cast is 4 a second × 2 KiB (on a spool with casts on), and a sig 2 a second × 8 KiB. A bound on the live
  lanes alone closes the smallest of the three doors.

**Decision:** the daemon caps the live subscribers of one scope, and exempts every commons room.

- **`SPOOL_MAX_SCOPE_SUBSCRIBERS`, default 64.** That is twice what an 8-member group needs at four
  devices a member. It reloads on the usual terms (ADR 2026-10.8ymn), and a reload that lowers it closes
  and drops nobody: the cap is checked only when a `sub` would add a subscriber.
- **The refusal is `err quota`** on that entry of the `sub`, with its `q` and `scope`, as the new-scope
  quota already answers. A shipped client parks a scope refused `quota` (C-9.1-5) rather than re-subbing
  it every heal round, so no new error code, record or HELLO key is needed. A connection that already
  holds the scope, re-subbing to refresh it, always passes.
- **Checked in `handleSub`, before `store.subscribe`.** `reserveSubscriber` counts `subscribers` plus
  the in-flight `subscribing` places and takes one in a single `compute` on `subscribing`, so two
  concurrent subs cannot both take the last place. `Slot.admit` moves the place into `subscribers` under
  the same entry's lock, so no connection is counted twice. The check runs after the creation gates, so a
  crowded scope the store has shed may charge a gate first. Refusals are counted in
  `knit_spool_subs_crowded_total`.
- **The commons is exempt.** A room is meant to hold many members, and its pushes already pay the
  per-room and spool-wide commons budgets. Casts and sigs are refused there.

## Alternatives rejected

- **A fan-out cap per lane** (`SPOOL_MAX_CAST_FANOUT` / `SPOOL_MAX_SIG_FANOUT`), as #7 first asked. It
  leaves pushes unbounded. It also has to be sized per lane: a call's casts need a handful of copies, but a
  sig carries group typing to up to 7 other members and their devices, so a small sig cap breaks typing.
- **A copies-out budget on casts and sigs**, which 3wdy and 2q49 name as the fallback. It bounds bytes on
  those two lanes directly, but like the per-lane cap it leaves pushes open. It also needs a third set of
  budgets beside the per-lane ones.
- **Evicting the oldest subscriber at the cap**, so a newcomer always gets in. An attacker churning
  connections would then push real members out, which is worse than a newcomer being refused.

## Consequences / limits

- **It bounds the multiplier, not the total.** Egress per scope becomes what enters it, bounded by the
  per-connection and per-address budgets, times at most 63 copies. An attacker's amplification is about
  twice a large real group's, not unbounded. The total across many crowded scopes is still bounded only
  by those ingress budgets and `SPOOL_MAX_CONNS`.
- **A new lockout, inside §10.4's accepted harm.** Someone who knows a scope id can fill its 64 places and
  so refuse its real members on this spool. §10.4 already accepts that such a party can flood the scope
  and burn its quota. Multi-homing on other spools, and the re-mint that rotates the id when a member
  leaves, bound this as they bound that. A per-address share of the cap would raise the cost of filling
  it. It is left out until a lockout is seen, because carrier NAT puts real members behind one address.
- **The trap:** counting only `subscribers`. A burst of concurrent `sub`s for one scope all read the same
  count before any of them adds itself, and overshoot the cap. Count `subscribing` too, and move a place
  from one to the other in one step, or a racer counts it twice and refuses a newcomer below the cap.
- **The second trap:** applying the cap to a commons room, or to a refresh. Either refuses a member the
  spool already serves.
- **Guards:**
  - `ScopeSubscribersTest`: the sub past the cap refused `quota` with its `q` and scope, counted, never a
    strike, with the rest of its batch answered; a refresh at the cap; a place freed by a member leaving;
    a commons room past the cap; a reload that lowers the cap closing nobody; and eight concurrent subs
    for three places getting exactly three.
  - `ConfigTest` (default 64, at least 2), `ConfigDescribeTest` (in the reloadable half), `MetricsTest`,
    `DeployConfigTest` (the compose passthrough) and `EnvContractTest`'s pin at `0.9.0-dev.2`.
  - No conformance check: S-6.4-6 is a MAY, and the cap is not advertised.
