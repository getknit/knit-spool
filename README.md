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
first-class; this repo exists so nobody *has* to write one.

## Status

Skeleton (pre-release). Working: the record layer with spec-vector conformance, hello/version
negotiation, bearer-token private spools, sub/digest/list/pull/push against an in-memory store
with oldest-by-arrival eviction, tombstones, per-scope digests, live `event` fan-out, and
stateless PoW verification with the per-`(scope, day)` cache.

Not yet (tracked, roughly in order): disk persistence (spools are cattle, but restarts shouldn't
dump every scope), per-IP/per-connection rate limits (`rate` + `retryMs`), global storage
watermark with oldest-scope shedding, the conformance suite as a separate reusable artifact,
metrics/health endpoint, container image publishing.

## Run

```sh
./gradlew run                          # listens on :9470, PoW off, public
SPOOL_TOKEN=s3cret ./gradlew run       # private spool: wss://host/spool/v1?k=s3cret
```

Configuration is environment variables only (defaults follow the spec's §12 constants):
`SPOOL_PORT` (9470) · `SPOOL_TOKEN` (unset = public) · `SPOOL_POW_BITS` (0 = off; the spec
suggests 20) · `SPOOL_MAX_BLOB` (65536) · `SPOOL_MAX_SCOPES` (64) · `SPOOL_MAX_FRAMES` (1000) ·
`SPOOL_MAX_TTL_MS` (604800000).

The daemon serves plain WebSocket; terminate TLS at the reverse proxy you already run (any
`wss://` → `ws://localhost:9470` proxy works). Sizing target: idles in ~128–256 MB on the cheapest
VPS tier.

## Docker

```sh
docker build -t knit-spool .
docker run -p 9470:9470 -e SPOOL_TOKEN=s3cret knit-spool
```

## Build and test

JDK 21. `./gradlew build` compiles and runs the tests, including the spec-vector conformance pins.

## License

AGPL-3.0-or-later — see [LICENSE](LICENSE). The Knit app is a separate GPL-3.0-or-later codebase;
the two share a protocol spec and no code.
