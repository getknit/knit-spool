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
[![Coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fgetknit%2Fknit-spool%2Fbadges%2Fcoverage.json)](https://github.com/getknit/knit-spool/actions/workflows/ci.yml)
![Footprint](https://img.shields.io/badge/RSS-~128%E2%80%93256%20MB-00BCD4)
![License](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)
[![knit-spool changelog on whatsnew.fyi](https://whatsnew.fyi/product/knit-spool/badge.svg)](https://whatsnew.fyi/product/knit-spool)

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
| **Ops** | `GET /healthz`, `GET /source`, `GET /metrics` (Prometheus text) |
| **Footprint** | Idles in ~128–256 MB on the cheapest VPS tier (`-Xmx256m` default) |
| **License** | AGPL-3.0-or-later |

## Contents

- [How it works](#how-it-works)
- [Modules](#-modules)
- [Status](#-status)
- [Run](#-run)
- [Configuration](#-configuration)
- [The commons](#-the-commons)
- [Deploy](#-deploy)
- [Docker](#-docker)
- [Operating](#-operating)
- [Conformance](#-conformance)
- [Build and test](#-build-and-test)
- [Contributing](#-contributing)
- [Security](#-security)
- [Support](#-support)
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
- **Commons** (§7.4) — one optional shared scope per spool, off unless `SPOOL_COMMONS_ID` is set.
  Ordinary records on the data path; what is added is the policy a *shared* scope needs. See
  [the commons](#-the-commons).
- **Capacity** — an optional total-connection cap that refuses the upgrade with `503` and a
  `Retry-After` rather than letting the box degrade into GC thrash. Not a protocol limit: a full
  spool is a property of the hardware, and a multi-homing client treats it as one more unreachable
  spool.
- **Persistence** — SQLite (WAL, self-healing boot recompute) or in-memory, behind one store
  contract, plus a periodic sweeper.
- **Ops** — `/healthz`, `/source` (build stamp and the §13 source offer), `/metrics` (Prometheus
  text), a periodic status log line, graceful shutdown.

## 🚀 Run

```sh
./gradlew :daemon:run                      # listens on :9470, PoW off, public, in-memory
SPOOL_TOKEN=s3cret ./gradlew :daemon:run   # private spool: wss://host/spool/v1?k=s3cret
```

`knit-spool check` validates the environment and prints what it resolved to, without binding a port,
opening a store, or creating a directory — for confirming a configuration before a container starts:

```sh
$ knit-spool check
port=9470 token=unset metricsToken=unset pow=0 maxRecord=131072 … store=memory
$ echo $?
0
```

Exit **0** valid, **1** invalid (with the reason on stderr), **2** unknown command. The resolved
config goes to stdout and warnings to stderr, so one can be parsed without filtering the other. It
checks *configuration*, not store state: the one boot failure it cannot predict is a commons that
will not fit because a persistent store already holds `SPOOL_MAX_SCOPES` scopes.

## 🔧 Configuration

Environment variables only; invalid values refuse to start, and an unrecognized `SPOOL_*` name is
logged as a probable typo. Defaults follow the spec's §12 constants.

| Variable | Default | Meaning |
|---|---|---|
| `SPOOL_PORT` | `9470` | listen port |
| `SPOOL_TOKEN` | unset | bearer token; unset = public spool |
| `SPOOL_METRICS_TOKEN` | unset | `?k=` credential for `/metrics`; unset = `SPOOL_TOKEN` gates it. Setting it **replaces** the spool token there, so a scrape that used `SPOOL_TOKEN` stops working |
| `SPOOL_DATA_DIR` | unset | unset = in-memory; set = SQLite at `$DIR/spool.db` |
| `SPOOL_SOURCE_URL` | upstream repo | corresponding-source URL served at `GET /source`; **set this if you run a modified build** (AGPL §13) |
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
| `SPOOL_LOG_LEVEL` | `INFO` | root log level |
| `SPOOL_TRUST_PROXY` | `false` | honor the proxy-appended `X-Forwarded-For` hop for per-IP limits |
| `SPOOL_MAX_CONNS` | `0` | total live connections; over it the upgrade is refused **503 + `Retry-After`**, never a close code. 0 = unlimited |
| `SPOOL_MAX_CONNS_PER_IP` | `16` | connection cap per client IP |
| `SPOOL_RATE_RECORDS` | `50` | records/s per connection (burst 4×) |
| `SPOOL_RATE_PUSHES` | `10` | pushes/s per connection (burst 4×) |
| `SPOOL_RATE_NEW_SCOPES` | `6` | new scopes/min per IP (burst 4×) |
| `SPOOL_COMMONS_ID` | unset | the commons scope id, 64 hex chars from `knit-spool commons-invite`; **unset = no commons** |
| `SPOOL_COMMONS_NAME` | unset | display label advertised in `hello` |
| `SPOOL_COMMONS_MAX_FRAMES` | `500` | commons frame cap — pinned, not client-declared |
| `SPOOL_COMMONS_TTL_MS` | `86400000` | commons frame TTL (24 h) |
| `SPOOL_COMMONS_MAX_BLOB` | `SPOOL_MAX_BLOB` | commons per-blob cap |
| `SPOOL_COMMONS_ATTACH` | `false` | allow attachments in the commons |
| `SPOOL_COMMONS_RATE_PUSHES` | `20` | **spool-wide** pushes/s into the commons (burst 4×) |

The commons bounds must fit the spool-wide caps, leave a `list` reply that fits `SPOOL_MAX_RECORD`,
and fit under `SPOOL_MAX_BYTES` — the commons is pinned against the watermark, so a room that cannot
fit is refused at startup rather than discovered at 3am.

## 🏛 The commons

A spool is normally pure infrastructure: it relays between clients that already paired out of band
and never introduces anybody. Set `SPOOL_COMMONS_ID` and it also runs **one shared scope** — a room
where everyone on that spool who holds the invite can talk to everyone else, still sealed end to
end.

Mint an invite, which never touches disk or the log:

```sh
$ knit-spool commons-invite
invite (give this to members):  knit-commons:v1:il03LpV71kiksIRvNBKlNln1t8k7NOBVJZtHW-QRr44
spool config (put in env):      SPOOL_COMMONS_ID=bf92a00e…cf4df842
```

The two halves go to different places. The **invite** goes to your members; the **id**, which is
`SHA-256("knit/spool/v1/commons" ‖ secret)`, goes in the spool's environment. The spool is never
given the secret, so it relays a room it cannot read — and this repo implements no content-key
derivation at all, which makes that structural rather than a promise.

On the wire a commons is an ordinary scope: `sub`, `push`, `event`, `digest`, `list`, `pull` all
behave exactly as they do for a private conversation, and no new record types or error codes exist.
What the spool adds is the policy a *shared* scope needs:

| | |
|---|---|
| **Bounds are pinned** | The store applies whatever the most recent subscriber declared. In a room shared with strangers that would let one member subscribe with `maxFrames = 1` and evict everyone's history, so a commons `sub` ignores what the client declares and answers with the truth in its `digest`. |
| **No PoW to join** | The scope is created at boot, so it is never an unknown scope and the §6.4 creation gates never fire — members join with a plain `sub` even at 20 bits. |
| **Never shed** | The storage watermark may not take the room away to make space for one client's conversation. |
| **A spool-wide push budget** | `SPOOL_COMMONS_RATE_PUSHES` bounds the room as a whole; 200 members at the per-connection 10/s would be 2,000 pushes/s into one scope. It throttles with `err rate` + `retryMs` but never strikes the connection — congestion on a shared room is not evidence any one member misbehaved. |
| **Attachments off by default** | A public room is where a 16 MiB upload costs the operator most and is worth least. `SPOOL_COMMONS_ATTACH=true` if you want them. |

`hello` carries the room's bounds, an optional name, and whether attachments are on — but **never
the scope id**. The id comes from the invite; a spool that published it would turn a room only
invite holders can find into one anybody who connects could subscribe to and flood. A client with no
invite learns only that the spool has a commons.

> [!NOTE]
> Every member shares one key, so a commons is exactly as private as its invite, and there is no
> per-member identity or ban list. Removing someone means rotating the room: mint a new invite, set
> the new id, restart. The old scope is no longer pinned and ages out on its TTL or under the
> watermark. The operator cannot moderate individual messages — that would need the key they do not
> have. See [`SECURITY.md`](SECURITY.md#the-commons).

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
> taking sshd, bounds the json log driver, and sizes every limit against what the tier actually
> holds — 32 KiB blobs, 4 MiB of attachments per scope, 4096 scopes, 8 GiB of payload, 256
> connections per IP, and a 192 MB heap in a 352 MB container with 288 MB left for Caddy. That
> split targets **~2,000 concurrent clients**, roughly 80% of where the first container is
> OOM-killed; [`HOSTING.md`](HOSTING.md#what-a-1-gb-box-actually-holds) shows the measurements.
> Side-load with `docker save knit-spool:latest | gzip | ssh root@host 'gunzip | docker load'` —
> `save`/`load`, not `export`/`import`, which flattens the image and drops its ENTRYPOINT and
> HEALTHCHECK.

## 🐳 Docker

Release images go to two registries, and they are the same bytes: the release workflow builds one
multi-arch manifest and pushes that manifest to both.

| Registry | Image | Notes |
|---|---|---|
| GHCR | `ghcr.io/getknit/knit-spool` | Carries the build provenance attestation. No anonymous pull limit. |
| Docker Hub | `docker.io/getknit/knit-spool` | Shorter to type. Anonymous pulls are rate-limited. |

Neither is populated yet; the first `v*` tag creates them. Until then, build from a checkout.

Both are `linux/amd64` and `linux/arm64`, so an Ampere or Graviton box, or a 64-bit Raspberry Pi,
pulls the same way an x86 VPS does.

```sh
docker pull ghcr.io/getknit/knit-spool:0.1.0
docker run -p 9470:9470 -v spool-data:/data -e SPOOL_POW_BITS=20 ghcr.io/getknit/knit-spool:0.1.0
```

Every release is tagged with its version, and a release that is not a prerelease also moves
`latest`. Pin the version in production, or a `@sha256:` digest for the strict form. `latest` moves
under you, and a restart on a moved tag brings back a daemon you never tested.

The GHCR copy traces back to the workflow run and the commit that built it:

```sh
gh attestation verify oci://ghcr.io/getknit/knit-spool:0.1.0 --repo getknit/knit-spool
```

There is no equivalent command for the Docker Hub copy. The attestation travels over the OCI
referrers API, which Docker Hub supports unevenly, so it is pushed to GHCR alone. Verifying there
covers the Docker Hub image as well, since both names resolve to the same digest.

Building your own is the other route, and the one to take if you have modified the daemon:

```sh
docker build -t knit-spool .
docker run -p 9470:9470 -v spool-data:/data -e SPOOL_POW_BITS=20 knit-spool
```

However the image arrives, it persists to the `/data` volume, runs as uid 65532, and carries a
`/healthz` HEALTHCHECK. [`Dockerfile`](Dockerfile) compiles from source;
[`Dockerfile.dist`](Dockerfile.dist) is what [the release workflow](.github/workflows/release.yml)
publishes: the same runtime stage over a distribution built ahead of time, which is how the arm64
image avoids an emulated compile.

## 📊 Operating

`GET /healthz` (liveness) and `GET /metrics` (Prometheus text; token-gated with `?k=` on private
spools — the shipped proxy configs seal it off from the internet, so scrape it from inside your
network). The bearer token rides in the query string, so those configs also keep it out of proxy
access logs; do the same in any proxy of your own.

Exported: the build stamp (`knit_spool_build_info`, a labelled gauge carrying version and commit —
`count by (version) (knit_spool_build_info)` is what a fleet dashboard joins against), connections
(current + total), records, pushes, events, PoW verifications, rate-limit
hits, upgrades refused for capacity and for draining (counted apart), sheds, attachment chunks stored, egress bytes, scopes held, live bytes, and `err` counts by
code.

> [!NOTE]
> **On a metered link, watch `knit_spool_egress_bytes_total`.** Fan-out means one push leaves as
> (subscribers − 1) copies, so egress is a multiple of ingest that the record and push counters
> cannot tell you the size of — and on the cheap VPS tiers the monthly transfer allowance binds
> long before CPU or memory does. It counts CBOR record payload, excluding WebSocket and TLS
> framing, so it runs a few percent under the figure your provider bills.

### Draining

`SIGUSR1` closes the door to new connections and leaves the live ones alone — what a rolling
upgrade needs between "serving" and "stopped", since a plain stop closes every session at once
and sends every client back on the same second:

```sh
docker kill --signal=USR1 spool   # drain: new upgrades get 503 + Retry-After, live conns served
docker kill --signal=USR1 spool   # again to lift it
```

Shift traffic to a sibling spool, wait for the connection count to fall, then stop. `/healthz`
deliberately keeps answering `200` throughout — the container HEALTHCHECK and compose's
`service_healthy` gate both probe it, and a drain that failed it would restart the container in
the middle of the drain. Watch `knit_spool_drain_refused_total`, which is counted apart from
`knit_spool_conns_refused_total` so a planned drain never looks like a box out of room, and
`draining=yes` on the status line.

### The status line

Every `SPOOL_STATUS_MS` (5 min by default; `0` switches it off) the daemon logs one line — the
`docker logs -f` view of a spool with no Prometheus in front of it:

```text
2026-08-17 14:05:00,123 INFO  a.getknit.spool.Status up=2h14m conns=3/2000 accepted=+12 \
scopes=12/64 live=4.2MiB/256.0MiB heap=96.4MiB/256.0MiB records=+142 pushes=+58 events=+170 \
egress=+21.1MiB limited=+0 refused=+0 sheds=+0 errs=+3{rate=2,quota=1}
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
at all, **3** = nothing failed but something could not be judged, because the transport broke or
this tool hit a bug. An inconclusive run is not a passing one, so treat **3** the way you treat
**1** in CI. The attachment checks skip themselves against a spool that advertised no §7.3 limits —
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
./gradlew koverXmlReport           # JaCoCo-format XML, what CI reports from
./gradlew koverVerify              # enforce the line/branch floors
./gradlew :daemon:koverHtmlReport  # one module on its own
```

Nothing is wired to `check` — reports are asked for explicitly. CI runs them alongside the tests,
gates on `koverVerify`, and publishes the merged percentage as the coverage badge above.

The merged total sits well under the per-module numbers (`:protocol` ~98%, `:daemon` ~91%) for a
structural reason worth knowing before reading it: `:conformance`'s check bodies only execute
against a live server, which happens in the `conformance-selftest` job — a separate process that
Kover does not instrument. Judge daemon and protocol changes by the merged report; judge conformance
changes by whether the self-test still passes.

## 🤝 Contributing

Contributions are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md), which also sets out the
(deliberately modest) **support expectations**: this is a best-effort hobby project shipped as-is,
with no warranty and no response-time guarantee. Development happens on GitHub at
[github.com/getknit/knit-spool](https://github.com/getknit/knit-spool); the issue and pull-request
templates cover what to include. Participation is governed by the
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

## 💛 Support

knit-spool is free and open source, with no ads, no tracking, and nothing to sell you — it's funded
entirely by tips. If you run a spool and it's been useful, you can leave a one-off tip on Ko-fi or set
up a recurring one on Liberapay:

[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-leave%20a%20tip-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/zaventh)
[![Support on Liberapay](https://img.shields.io/badge/Liberapay-give%20recurring-F6C915?logo=liberapay&logoColor=black)](https://liberapay.com/zaventh/)

Tips are optional and buy no special treatment — knit-spool is AGPLv3 and stays that way. Reporting
bugs, running a public spool, and telling people it exists help just as much.

## 📄 License

knit-spool is free software, licensed under the **GNU Affero General Public License v3.0 or later**
([`LICENSE`](LICENSE)).

§13 obliges anyone running a modified version that other people's clients connect to to offer
those users the source of *their* version. The daemon serves that offer itself, so it cannot go
stale: `GET /source` returns the running version, its commit, and a source URL. Run a fork and you
set [`SPOOL_SOURCE_URL`](#-configuration) to your own repository — the endpoint is unauthenticated
on purpose, because an offer nobody can read is not an offer.

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
