---
changelog: "0.1"
product:
  name: knit-spool
  vendor: Knit
  homepage: https://github.com/getknit/knit-spool
  id: knit-spool
  description: Scoped, blinded store-and-forward relay for Knit's Internet plane.
  platforms: [linux]
  versioning: semver
  category: Developer Tools
document:
  updated: 2026-08-20T23:03:48Z
  coverage: complete
  canonical: https://github.com/getknit/knit-spool/blob/main/CHANGELOG.md
  locale: en
---

# knit-spool changelog

## Unreleased

### Added

- **A commons: one shared scope per spool** (spec §7.4), off unless `SPOOL_COMMONS_ID` is set. It
  turns a spool from pure infrastructure into a place — everyone on it who holds the invite can talk
  to everyone else, sealed end to end like any other scope.

  The operator mints an invite with `knit-spool commons-invite` and configures only
  `SHA-256("knit/spool/v1/commons" ‖ secret)`. The secret goes to members, so **the spool relays a
  room it cannot read**, and this repo deliberately implements no content-key derivation at all —
  the property is structural, not a promise.

  On the data path a commons is an ordinary scope: no new record types, no new error codes. What is
  new is the policy that makes a *shared* scope survivable. Its bounds are operator-pinned, because
  the store applies whatever the most recent subscriber declared and one member asking for
  `maxFrames = 1` would otherwise evict the whole room's history. It is created at boot, so it is
  never an unknown scope and the §6.4 PoW and new-scope gates never fire for a member joining. It is
  pinned against the storage watermark, which may never shed it. And it carries a spool-wide push
  budget (`SPOOL_COMMONS_RATE_PUSHES`, default 20/s) that the per-connection limit cannot bound —
  200 members at 10/s each is 2,000 pushes/s into one scope — which throttles *without* striking the
  connection, since congestion on a shared room is not evidence any one member misbehaved.

  `hello` advertises the room's bounds and an optional name but **never its scope id**: the id comes
  from the invite, and a spool that published it would turn a room only invite holders can find into
  one anybody who connects could subscribe to and flood. Observability is
  `knit_spool_commons_subscribers`, `knit_spool_commons_pushes_total`,
  `knit_spool_commons_rate_limited_total`, and `commons=Nsub/Nf` in the status line — all absent
  entirely on a spool with no commons. The conformance suite gains `commons-advertisement`,
  `commons-bounds-pinned`, and `commons-fanout`; the latter two need `--commons-invite` and skip
  without it.
- **`SPOOL_MAX_CONNS`, a total-connection cap** (default `0`, unlimited — the daemon had no global
  connection limit before this, only per-IP). At the cap the WebSocket upgrade is refused `503`
  with a `Retry-After` rather than accepted into a box that has no room for it. Deliberately not a
  close code: §7.1 defines four, none of them means "come back later", and `4003 abuse` would tell
  a client it misbehaved when it did not. A full spool is a property of the hardware, so it is
  answered at the transport, where a multi-homing client already handles it as one more unreachable
  spool. Counted by `knit_spool_conns_refused_total` and `refused=+N` in the status line; the
  configured ceiling is exported as `knit_spool_max_conns` and shown as `conns=N/max`.

### Changed

- **`deploy/docker-compose.tiny.yml` is sized against measurements** rather than caution. A 1 GB
  box holds ~2,400 concurrent clients before a container is OOM-killed — 41 KB of JVM heap and
  ~110 KB of Caddy per connection — so the overlay now targets ~2,000 of them: 4096 scopes (was
  512), 8 GiB of payload (was 256 MB), 32 KiB blobs (was 16 KiB), 4 MiB of attachments per scope
  (was 1 MiB), 256 connections per IP (was 64), 30 new scopes/min per IP (was 6), a 5-minute sweep
  interval to keep the sweeper's now-longer store-thread stall rare, and `SPOOL_MAX_CONNS=2000` so
  the ceiling is enforced rather than discovered.
- **The 1 GB overlay splits memory the other way.** Caddy, not the daemon, is what runs out first:
  at its old 128 MB ceiling it was OOM-killed at ~1,100 connections, taking every subscriber with
  it. It now gets 288 MB and a `GOMEMLIMIT`, and the daemon 352 MB with a 192 MB heap.
- **`HOSTING.md` documents what the tier actually holds**, with the per-connection, sweeper, store
  thread, transfer, and disk figures behind the numbers above.

## [0.1.0](https://github.com/getknit/knit-spool/releases/tag/v0.1.0) — 2026-08-18T19:29:49Z

> First implementation of the v1 spool protocol, and the first tagged release. Pre-1.0 every
> interface below is subject to change, and only the wire protocol's own compatibility rules, which
> are versioned separately from this daemon, are stable.

### Added

- **Record layer** — CBOR `hello`/`sub`/`digest`/`list`/`pull`/`blob`/`push`/`event`/`ok`/`err`,
  pinned byte-for-byte to the spec's §13 vectors by `SpecVectorTest`, with forward-compatible
  tolerance of unknown records and fields.
- **Handshake** — version negotiation, advertised limits, and bearer-token private spools
  (`wss://host/spool/v1?k=…`).
- **Fan-out** — live `event` delivery to every other subscriber of a scope, `q`-correlated replies,
  and idempotent duplicate pushes.
- **Retention** — oldest-by-arrival eviction, count-bounded tombstones, and per-scope digests with
  unsolicited re-anchors after eviction or expiry.
- **Attachments** (§6.5/§7.3) — `ahave`/`ahas`/`aget`/`achunk`/`aput`, chunk presence bitmaps,
  first-write-wins with `conflict` on mismatch, truncated (never refused) over-long `aget`, and a
  per-scope byte quota. `SPOOL_MAX_ATTACH_BYTES=0` removes the whole family from `hello`.
- **Abuse control** — stateless PoW on SUB and on the shed-scope PUSH-recreate path, with the
  per-`(scope, day)` cache; per-connection and per-IP rate limits (`rate` + `retryMs`, escalating to
  close 4003); and a global storage watermark that sheds the least-active scope.
- **Persistence** — SQLite (WAL, self-healing boot recompute) or in-memory behind one store
  contract, plus a periodic sweeper.
- **Ops** — `GET /healthz`, `GET /metrics` (Prometheus text, token-gated on private spools),
  graceful shutdown, and a periodic one-line status log under its own `app.getknit.spool.Status`
  logger — gauges absolute, everything else a delta since the previous line
  (`SPOOL_STATUS_MS`, 5 min; `0` disables).
- **`knit_spool_egress_bytes_total`** — fan-out means one push leaves as (subscribers − 1) copies,
  and on a metered link the transfer allowance binds long before CPU or memory does.
- **Conformance suite** (`:conformance`) — a CLI that validates *any* live spool over WebSocket, TAP
  on stdout and a MUST tally on stderr. Depends only on `:protocol`, never on `:daemon`, so it tests
  the wire contract rather than this repo's internals. `--destructive` enables the quota and
  rate-limit checks; `--token-file` keeps a bearer token out of argv, where `ps` and shell history
  can read it.
- **Deployment** — a container image (JRE-only runtime stage, uid 65532, `/data` volume, `/healthz`
  HEALTHCHECK); Caddy and nginx reverse-proxy configurations, both keeping the `?k=` token out of
  access logs; a self-contained TLS compose stack that issues and renews certificates; and a *tiny*
  overlay for 1 GB boxes that side-loads or pulls the image instead of building it, caps every
  container, bounds the log driver, and re-sizes the limits for a metered link.
- **CI** — two pipelines over the same gating checks. GitHub Actions
  ([`.github/workflows/ci.yml`](https://github.com/getknit/knit-spool/blob/main/.github/workflows/ci.yml))
  runs `check` with merged coverage, `koverVerify`, and the conformance suite against the freshly
  built daemon on every pull request, and publishes the coverage badge on a default-branch push. The
  maintainer's internal GitLab pipeline
  ([`.gitlab-ci.yml`](https://github.com/getknit/knit-spool/blob/main/.gitlab-ci.yml)) runs the same
  two test jobs and adds what needs a registry credential: a kaniko image build, advisory Trivy
  filesystem/image and markdownlint scans, and a tag-only release job.
- **Releases** — a `v*` tag runs
  [`.github/workflows/release.yml`](https://github.com/getknit/knit-spool/blob/main/.github/workflows/release.yml),
  which is the default source of release images. It re-runs `check` and the conformance suite
  against the tagged tree, then publishes a multi-arch (`linux/amd64`, `linux/arm64`) image to GHCR
  and Docker Hub with a signed build provenance attestation, and opens a draft GitHub Release
  carrying the distribution archives and their `SHA256SUMS`. The image is built from
  [`Dockerfile.dist`](https://github.com/getknit/knit-spool/blob/main/Dockerfile.dist), which layers
  a natively compiled distribution onto the multi-arch JRE base instead of compiling under emulation.
- **Community and automation** — GitHub issue forms, a pull-request template, label-driven canned
  replies, keyword triage, and stale sweeps under
  [`.github/`](https://github.com/getknit/knit-spool/tree/main/.github).
- **Coverage reporting** (Kover) — one merged report over all three modules
  (`./gradlew koverHtmlReport`), plus per-module reports. `koverVerify` holds line and branch floors
  as a ratchet against tests being deleted, and the merged percentage is published as the README's
  coverage badge. Process entry points and generated serializers are excluded — the former only run
  out of process, under the conformance self-test, where Kover cannot see them.

### Changed

- Hot-path hex encoding, digest computation, and store queries reworked to cut per-record overhead.

### Fixed

- The WebSocket close path no longer surfaces a ping-timeout `IOException` as an error.
- The conformance runner reports non-assertion failures diagnosably, and tallies transport faults
  apart from spec violations — a spool that drops the connection no longer looks like a spool that
  answered wrongly.
- The container build no longer races the Kotlin compile daemon's `/tmp` lock file under kaniko
  (compilation runs in-process), and `mkdir -p /data` tolerates kaniko creating the `VOLUME` path
  during stage setup.

## About this file

**Wire compatibility is versioned separately from this daemon.** The spool protocol's own version is
negotiated in the handshake and specified in
[`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md); a major
version of this daemon does not imply a new protocol version, and a protocol change never arrives
here first. Entries that move the wire, the on-disk store, or a configuration default are called out
as such — those are the ones operators have to read.

This changelog follows the provisional changelog standard drafted at
[whatsnew.fyi](https://whatsnew.fyi/spec) — YAML frontmatter, one `##` heading per release newest
first, and [Keep a Changelog](https://keepachangelog.com)'s six categories, with versions following
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). This heading and `## Unreleased` carry no
date, so neither is a release-heading candidate and consumers skip both.
