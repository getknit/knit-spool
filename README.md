# knit-spool

The reference **spool** — a scoped, blinded store-and-forward relay daemon for
[Knit](https://github.com/getknit/knit)'s Internet plane.

A spool holds, per conversation "scope", a bounded set of end-to-end-sealed frames and a digest
over them, streams new arrivals to connected subscribers, and heals divergence by digest
anti-entropy. It never learns node ids, message content, rosters, or delivery facts — it stores
ciphertext for scope ids it cannot map to anyone. Spools never talk to each other: clients
multi-home across several spools and union them, so no spool is load-bearing and a wiped spool is
refilled by any one conversation member.

**The protocol spec is the product.** The normative spec lives in the Knit repo:
[`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md).
This daemon implements the spec — never the other way around — and `SpecVectorTest` pins this
implementation to the spec's §13 vectors byte-for-byte. Third-party spool implementations are
first-class; this repo exists so nobody *has* to write one, and ships the conformance suite that
validates any implementation.

## Modules

| Module | Artifact | What |
|---|---|---|
| `:protocol` | library | Records, PoW (verify + mine), digest — spec §2/§6.3/§7/§8, no server code |
| `:daemon` | `knit-spool` | The reference daemon: WSS server, in-memory + SQLite stores, rate limits, ops |
| `:conformance` | `knit-spool-conformance` | CLI that validates **any** live spool over WebSocket (TAP output) |

`:conformance` depends only on `:protocol` — it tests the wire contract, not this repo's
internals.

## Status

Implements the full v1 protocol: record layer with spec-vector conformance, hello/version
negotiation, bearer-token private spools, sub/digest/list/pull/push with live `event` fan-out,
oldest-by-arrival eviction, count-bounded tombstones, per-scope digests with unsolicited
re-anchors after eviction/expiry, stateless PoW (SUB *and* the shed-scope PUSH-recreate path) with
the per-`(scope, day)` cache, per-connection and per-IP rate limits (`rate` + `retryMs`,
escalating to close 4003), a global storage watermark with oldest-scope shedding, SQLite
persistence (WAL, self-healing boot recompute), a periodic sweeper, `/healthz` + `/metrics`
(Prometheus text), and graceful shutdown.

## Run

```sh
./gradlew :daemon:run                      # listens on :9470, PoW off, public, in-memory
SPOOL_TOKEN=s3cret ./gradlew :daemon:run   # private spool: wss://host/spool/v1?k=s3cret
```

Configuration is environment variables only; invalid values refuse to start. Defaults follow the
spec's §12 constants.

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
| `SPOOL_MAX_RECORD` | `131072` | max CBOR record bytes |
| `SPOOL_MAX_PULL` | `64` | max blob ids per `pull` |
| `SPOOL_MAX_ATTACH_BYTES` | `16777216` | per-scope attachment byte quota (§6.5); **0 turns attachments off** — the three attachment limits then vanish from HELLO and a conforming client never sends `ahave`/`aget`/`aput` |
| `SPOOL_MAX_A_CHUNK` | `49221` | max sealed attachment-chunk bytes (the spec's structural 48 KiB plus framing) |
| `SPOOL_MAX_AGET` | `32` | max chunks per `aget`; an over-long request is truncated, never refused |
| `SPOOL_MAX_BYTES` | `268435456` | payload watermark; over it the least-active scope is shed; 0 = unlimited |
| `SPOOL_SWEEP_MS` | `60000` | sweeper cadence (expiry, cache pruning, watermark) |
| `SPOOL_TRUST_PROXY` | `false` | honor the proxy-appended `X-Forwarded-For` hop for per-IP limits |
| `SPOOL_MAX_CONNS_PER_IP` | `16` | connection cap per client IP |
| `SPOOL_RATE_RECORDS` | `50` | records/s per connection (burst 4×) |
| `SPOOL_RATE_PUSHES` | `10` | pushes/s per connection (burst 4×) |
| `SPOOL_RATE_NEW_SCOPES` | `6` | new scopes/min per IP (burst 4×) |
| `SPOOL_LOG_LEVEL` | `INFO` | root log level |

The daemon serves plain WebSocket; TLS terminates at a reverse proxy — either one you already run
([`deploy/Caddyfile`](deploy/Caddyfile), [`deploy/nginx.conf`](deploy/nginx.conf) alongside
[`deploy/docker-compose.yml`](deploy/docker-compose.yml)), or one compose brings up for you with
certificates issued and renewed automatically:

```sh
cd deploy && cp .env.example .env   # set SPOOL_DOMAIN (already resolving here) + ACME_EMAIL
docker compose -f docker-compose.tls.yml up -d
```

That runs Caddy on :80/:443 in front of the daemon, which is published nowhere but the compose
network; clients get `wss://$SPOOL_DOMAIN/spool/v1`. Sizing target: idles in ~128–256 MB on the
cheapest VPS tier (`-Xmx256m` is the default).

Operator surface: `GET /healthz` (liveness), `GET /metrics` (Prometheus text; token-gated with
`?k=` on private spools — the shipped proxy configs seal it off from the internet, so scrape it
from inside your network). The bearer token rides in the query string, so those configs also keep
it out of proxy access logs; do the same in any proxy of your own.

## Docker

```sh
docker build -t knit-spool .
docker run -p 9470:9470 -v spool-data:/data -e SPOOL_POW_BITS=20 knit-spool
```

The image persists to the `/data` volume by default and carries a `/healthz` HEALTHCHECK.

## Conformance

Validate any spool implementation — this one or a third party's — over a live connection:

```sh
./gradlew :conformance:installDist
conformance/build/install/knit-spool-conformance/bin/knit-spool-conformance \
    wss://spool.example.com/spool/v1 [--token T] [--pow-limit 24] [--destructive]
```

TAP output; exit 0 = all MUST checks pass. `--destructive` enables the quota/rate checks (they
fill real capacity — run them against spools you operate). CI runs the suite against the built
daemon on every pipeline (`conformance-selftest`).

## Build and test

JDK 21. `./gradlew check` compiles, lints (ktlint), and runs every suite: the §13 spec-vector
pins, the store contract against both backends, and the full server integration tests.

## License

AGPL-3.0-or-later — see [LICENSE](LICENSE). The Knit app is a separate GPL-3.0-or-later codebase;
the two share a protocol spec and no code.
