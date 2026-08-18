<div align="center">

<img src="assets/icon-256.png" width="128" height="128" alt="knit-spool">

# knit-spool

**The reference *spool* — a scoped, blinded store-and-forward relay for
[Knit](https://github.com/getknit/knit)'s Internet plane.**

It holds sealed frames for scope ids it cannot map to anyone, and forgets everything else.

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-3.3.0%20CIO-087CFA?logo=ktor&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-orange?logo=openjdk&logoColor=white)
![Protocol](https://img.shields.io/badge/spool%20protocol-v1%20(%C2%A713%20vectors%20pinned)-2EA043)
![Coverage](https://github.com/getknit/knit-spool/badges/main/coverage.svg?style=flat)
![Footprint](https://img.shields.io/badge/RSS-~128%E2%80%93256%20MB-00BCD4)
![License](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)

</div>

---

## What a spool is

A spool holds, per conversation **scope**, a bounded set of end-to-end-sealed frames and a digest
over them, streams new arrivals to connected subscribers, and heals divergence by digest
anti-entropy.

It never learns node ids, message content, rosters, or delivery facts — it stores ciphertext for
scope ids it cannot map to anyone. Spools never talk to each other: clients multi-home across
several spools and union them, so **no spool is load-bearing** and a wiped spool is refilled by any
one conversation member.

> [!IMPORTANT]
> **The protocol spec is the product.** The normative spec lives in the Knit repo:
> [`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md).
> This daemon implements the spec — never the other way around — and `SpecVectorTest` pins this
> implementation to the spec's §13 vectors byte-for-byte. Third-party spool implementations are
> first-class; this repo exists so nobody *has* to write one, and ships the conformance suite that
> validates any implementation.

### At a glance

| | |
|---|---|
| **What** | Store-and-forward relay daemon for Knit's optional Internet plane |
| **Wire** | CBOR records over one WebSocket, `wss://host/spool/v1` (`?k=` token on private spools) |
| **Stack** | Kotlin 2.4.0 · Ktor 3.3.0 (CIO) · kotlinx-serialization CBOR · SQLite (WAL) · JDK 21 |
| **Sees** | Scope ids, blob ids, ciphertext, sizes, timing |
| **Never sees** | Node ids, plaintext, rosters, who read what, which spools a client also uses |
| **Config** | Environment variables only; invalid values refuse to start |
| **Ops** | `GET /healthz`, `GET /metrics` (Prometheus text) |
| **Footprint** | Idles in ~128–256 MB on the cheapest VPS tier (`-Xmx256m` default) |
| **License** | AGPL-3.0-or-later |

## Contents

- [How it works](#how-it-works)
- [Modules](#-modules)
- [Status](#-status)
- [Run](#-run)
- [Configuration](#-configuration)
- [Deploy](#-deploy)
- [Docker](#-docker)
- [Operating](#-operating)
- [Conformance](#-conformance)
- [Build and test](#-build-and-test)
- [Contributing](#-contributing)
- [Security](#-security)
- [License](#-license)

## How it works

```
   ┌─────────┐   push   ┌────────────┐   event   ┌─────────┐
   │ Phone A │─────────►│  spool-1   │──────────►│ Phone B │
   │  seals  │          └────────────┘           │ unions  │
   │  frame  │   push   ┌────────────┐   pull    │ + opens │
   │  once   │─────────►│  spool-2   │◄──────────│         │
   └─────────┘          └────────────┘           └─────────┘
                  no spool-to-spool link, ever
```

The sender seals a frame once and pushes the same bytes to each spool it knows. Every member
subscribes to the scope on the spools *it* knows and unions what comes back, so overlap is the only
thing two members need — not agreement on a spool list. Each spool sees an opaque 32-byte scope id,
a blob id, ciphertext, and timing; a spool that vanishes takes nothing with it that another member
can't re-push.

A client that has been away sends its scope digest instead of a full pull. Same digest, nothing to
do — an idle conversation costs one round trip.

## 📦 Modules

| Module | Artifact | What |
|---|---|---|
| `:protocol` | library | Records, PoW (verify + mine), digest — spec §2/§6.3/§7/§8, no server code |
| `:daemon` | `knit-spool` | The reference daemon: WSS server, in-memory + SQLite stores, rate limits, ops |
| `:conformance` | `knit-spool-conformance` | CLI that validates **any** live spool over WebSocket (TAP output) |

`:conformance` depends only on `:protocol` — it tests the wire contract, not this repo's
internals.

## ✅ Status

Implements the full **v1** protocol:

- **Record layer** — CBOR `hello`/`sub`/`digest`/`list`/`pull`/`blob`/`push`/`event`/`ok`/`err`,
  with spec-vector conformance and forward-compatible tolerance of unknown records and fields.
- **Handshake** — version negotiation, advertised limits, bearer-token private spools.
- **Fan-out** — live `event` delivery to every other subscriber of the scope, `q`-correlated
  replies, idempotent duplicate pushes.
- **Retention** — oldest-by-arrival eviction, count-bounded tombstones, per-scope digests with
  unsolicited re-anchors after eviction or expiry.
- **Attachments** (§6.5/§7.3) — `ahave`/`ahas`/`aget`/`achunk`/`aput`, chunk presence bitmaps,
  first-write-wins with `conflict` on mismatch, truncated (never refused) over-long `aget`, and a
  per-scope byte quota. Set `SPOOL_MAX_ATTACH_BYTES=0` and the family disappears from `hello`.
- **Abuse control** — stateless PoW (SUB *and* the shed-scope PUSH-recreate path) with the
  per-`(scope, day)` cache, per-connection and per-IP rate limits (`rate` + `retryMs`, escalating
  to close 4003), a global storage watermark with oldest-scope shedding.
- **Persistence** — SQLite (WAL, self-healing boot recompute) or in-memory, behind one store
  contract, plus a periodic sweeper.
- **Ops** — `/healthz`, `/metrics` (Prometheus text), a periodic status log line, graceful shutdown.

## 🚀 Run

```sh
./gradlew :daemon:run                      # listens on :9470, PoW off, public, in-memory
SPOOL_TOKEN=s3cret ./gradlew :daemon:run   # private spool: wss://host/spool/v1?k=s3cret
```

## 🔧 Configuration

Environment variables only; invalid values refuse to start, and an unrecognized `SPOOL_*` name is
logged as a probable typo. Defaults follow the spec's §12 constants.

| Variable | Default | Meaning |
|---|---|---|
| `SPOOL_PORT` | `9470` | listen port |
| `SPOOL_TOKEN` | unset | bearer token; unset = public spool |
| `SPOOL_DATA_DIR` | unset | unset = in-memory; set = SQLite at `$DIR/spool.db` |
| `SPOOL_POW_BITS` | `0` | PoW difficulty for unknown scopes (spec suggests 20; 0 = off) |
| `SPOOL_MAX_BLOB` | `65536` | max sealed-blob bytes |
| `SPOOL_MAX_SCOPES` | `64` | max scopes held |
| `SPOOL_MAX_FRAMES` | `1000` | per-scope frame-cap ceiling |
| `SPOOL_MAX_TTL_MS` | `604800000` | per-scope TTL ceiling (7 d) |
| `SPOOL_MAX_RECORD` | `131072` | max CBOR record bytes (must fit `SPOOL_MAX_BLOB` + 512) |
| `SPOOL_MAX_PULL` | `64` | max blob ids per `pull` |
| `SPOOL_MAX_ATTACH_BYTES` | `16777216` | per-scope attachment byte quota (§6.5); **0 turns attachments off** — the three attachment limits then vanish from HELLO and a conforming client never sends `ahave`/`aget`/`aput` |
| `SPOOL_MAX_A_CHUNK` | `49221` | max sealed attachment-chunk bytes (the spec's structural 48 KiB plus framing) |
| `SPOOL_MAX_AGET` | `32` | max chunks per `aget`; an over-long request is truncated, never refused |
| `SPOOL_MAX_BYTES` | `268435456` | payload watermark; over it the least-active scope is shed; 0 = unlimited |
| `SPOOL_SWEEP_MS` | `60000` | sweeper cadence (expiry, cache pruning, watermark) |
| `SPOOL_STATUS_MS` | `300000` | status log line cadence (5 min); 0 = off |
| `SPOOL_TRUST_PROXY` | `false` | honor the proxy-appended `X-Forwarded-For` hop for per-IP limits |
| `SPOOL_MAX_CONNS_PER_IP` | `16` | connection cap per client IP |
| `SPOOL_RATE_RECORDS` | `50` | records/s per connection (burst 4×) |
| `SPOOL_RATE_PUSHES` | `10` | pushes/s per connection (burst 4×) |
| `SPOOL_RATE_NEW_SCOPES` | `6` | new scopes/min per IP (burst 4×) |
| `SPOOL_LOG_LEVEL` | `INFO` | root log level |

## 🌐 Deploy

Picking a host first? [`HOSTING.md`](HOSTING.md) covers what a spool needs from a box, which
providers fit, and which container platforms are the wrong shape for a long-lived WebSocket.

The daemon serves plain WebSocket; **TLS terminates at a reverse proxy**. Either one you already
run ([`deploy/Caddyfile`](deploy/Caddyfile), [`deploy/nginx.conf`](deploy/nginx.conf) alongside
[`deploy/docker-compose.yml`](deploy/docker-compose.yml)), or one compose brings up for you with
certificates issued and renewed automatically:

```sh
cd deploy && cp .env.example .env   # set SPOOL_DOMAIN (already resolving here) + ACME_EMAIL
docker compose -f docker-compose.tls.yml up -d
```

That runs Caddy on :80/:443 in front of the daemon, which is published nowhere but the compose
network; clients get `wss://$SPOOL_DOMAIN/spool/v1`.

> [!TIP]
> **On a 1 GB box** (Linode Nanode and friends), layer the tiny overlay on top:
>
> ```sh
> docker compose -f docker-compose.tls.yml -f docker-compose.tiny.yml up -d
> ```
>
> It pulls or side-loads the image instead of building it (Gradle wants more memory than the whole
> box has), caps each container so an overrun is a restart rather than the kernel's OOM killer
> taking sshd, bounds the json log driver, and re-sizes the limits for a metered link — 32 KiB
> blobs, 1 MiB of attachments per scope, 512 scopes, 64 connections per IP. Side-load with
> `docker save knit-spool:latest | gzip | ssh root@host 'gunzip | docker load'` — `save`/`load`,
> not `export`/`import`, which flattens the image and drops its ENTRYPOINT and HEALTHCHECK.

## 🐳 Docker

```sh
docker build -t knit-spool .
docker run -p 9470:9470 -v spool-data:/data -e SPOOL_POW_BITS=20 knit-spool
```

The image persists to the `/data` volume by default, runs as uid 65532, and carries a `/healthz`
HEALTHCHECK.

## 📊 Operating

`GET /healthz` (liveness) and `GET /metrics` (Prometheus text; token-gated with `?k=` on private
spools — the shipped proxy configs seal it off from the internet, so scrape it from inside your
network). The bearer token rides in the query string, so those configs also keep it out of proxy
access logs; do the same in any proxy of your own.

Exported: connections (current + total), records, pushes, events, PoW verifications, rate-limit
hits, sheds, attachment chunks stored, egress bytes, scopes held, live bytes, and `err` counts by
code.

> [!NOTE]
> **On a metered link, watch `knit_spool_egress_bytes_total`.** Fan-out means one push leaves as
> (subscribers − 1) copies, so egress is a multiple of ingest that the record and push counters
> cannot tell you the size of — and on the cheap VPS tiers the monthly transfer allowance binds
> long before CPU or memory does. It counts CBOR record payload, excluding WebSocket and TLS
> framing, so it runs a few percent under the figure your provider bills.

### The status line

Every `SPOOL_STATUS_MS` (5 min by default; `0` switches it off) the daemon logs one line — the
`docker logs -f` view of a spool with no Prometheus in front of it:

```text
2026-08-17 14:05:00,123 INFO  a.getknit.spool.Status up=2h14m conns=3 accepted=+12 \
scopes=12/64 live=4.2MiB/256.0MiB heap=96.4MiB/256.0MiB records=+142 pushes=+58 events=+170 \
egress=+21.1MiB limited=+0 sheds=+0 errs=+3{rate=2,quota=1}
```

(Wrapped with `\` here to fit the page; in the log it is one line.)

Gauges (`conns`, `scopes`, `live`, `heap`) are absolute and shown against their caps; everything
with a `+` is the delta **since the previous line**, because on a scrolling log the useful question
is what the last five minutes did, not what the process has done since boot — `/metrics` answers
that one exactly. The error breakdown names the three busiest codes and summarizes the rest as
`+Nmore`, so the line stays one line under any load.

It logs under its own logger name, `app.getknit.spool.Status`, so a logback override can silence or
re-level just this line; `SPOOL_LOG_LEVEL` is the root level and would take the rest of the daemon
with it.

## 🧪 Conformance

Validate any spool implementation — this one or a third party's — over a live connection:

```sh
./gradlew :conformance:installDist
conformance/build/install/knit-spool-conformance/bin/knit-spool-conformance \
    wss://spool.example.com/spool/v1 \
    [--token T | --token-file PATH] [--timeout-ms 10000] [--pow-limit 24] [--destructive]
```

TAP on stdout, a MUST tally on stderr. Exit **0** = every MUST check passed (skips and advisory
shortfalls don't fail the run), **1** = a MUST check failed, **2** = bad arguments or no handshake
at all. The attachment checks skip themselves against a spool that advertised no §7.3 limits —
which is exactly the client behaviour the spec requires. `--destructive` enables the quota and
rate-limit checks; they fill real capacity, so run them against spools you operate. CI runs the
whole suite against the freshly built daemon on every pipeline (`conformance-selftest`).

> [!WARNING]
> Prefer `--token-file` against a spool you care about. `--token` puts the bearer token in argv,
> where every local user can read it out of `ps` for the life of the run, and most shells record it
> in history. The file is read once and may be mode 0600.

## 🔨 Build and test

JDK 21 — the Gradle wrapper pins Gradle 9.5.0.

```sh
./gradlew check                 # compile + ktlint + every suite
./gradlew ktlintFormat          # autoformat
./gradlew :daemon:installDist   # runnable dist at daemon/build/install/knit-spool/
```

`check` runs the §13 spec-vector pins, the store contract against both backends (in-memory and
SQLite), and the full server integration tests.

### Coverage

[Kover](https://github.com/Kotlin/kotlinx-kover), merged across all three modules:

```sh
./gradlew koverHtmlReport          # build/reports/kover/html/index.html
./gradlew koverXmlReport           # JaCoCo-format XML, what CI hands GitLab
./gradlew koverVerify              # enforce the line/branch floors
./gradlew :daemon:koverHtmlReport  # one module on its own
```

Nothing is wired to `check` — reports are asked for explicitly. CI runs them in the `test` job and
publishes the XML as a GitLab coverage report, which paints the merge-request diff line by line.

The merged total sits well under the per-module numbers (`:protocol` ~98%, `:daemon` ~91%) for a
structural reason worth knowing before reading it: `:conformance`'s check bodies only execute
against a live server, which happens in the `conformance-selftest` job — a separate process that
Kover does not instrument. Judge daemon and protocol changes by the merged report; judge conformance
changes by whether the self-test still passes.

## 🤝 Contributing

Contributions are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md), which also sets out the
(deliberately modest) **support expectations**: this is a best-effort hobby project shipped as-is,
with no warranty and no response-time guarantee. Development happens on
[github.com/getknit/knit-spool](https://github.com/getknit/knit-spool); the issue and
merge-request templates cover what to include. Participation is governed by the
[Code of Conduct](CODE_OF_CONDUCT.md), and notable changes are recorded in
[`CHANGELOG.md`](CHANGELOG.md).

The one rule worth repeating here: **the spec is the product.** If the daemon and
[`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md)
disagree, the daemon is wrong — and a change to the protocol itself is a Knit-repo discussion that
this repo follows, not leads.

## 🔐 Security

To report a vulnerability, see [`SECURITY.md`](SECURITY.md) — please **do not** open a public issue
for security problems. That file also documents what a spool is trusted with, and which properties
are intentional trade-offs (visible traffic metadata, TLS terminating at a proxy, PoW off by
default) rather than findings.

## 📄 License

knit-spool is free software, licensed under the **GNU Affero General Public License v3.0 or later**
([`LICENSE`](LICENSE)).

```
Copyright (C) 2026 Jeffrey Walter Mixon

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU Affero General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along with this program.
If not, see <https://www.gnu.org/licenses/>.
```

AGPL rather than GPL because a spool is a network service handling other people's ciphertext: under
§13, running a **modified** version that other people's clients connect to obliges you to offer
those users the source of your version. Publish your fork and say where it is.

knit-spool depends on third-party open-source libraries, all under AGPL-compatible licenses; see
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) for the component list and their licenses.

The Knit app is a separate GPL-3.0-or-later codebase; the two share a protocol spec and no code.

---

<div align="center">
<sub>AGPL-3.0-or-later — <code>app.getknit.spool</code></sub>
</div>
