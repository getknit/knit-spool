# Security Policy

knit-spool is a blinded store-and-forward relay: it holds end-to-end-sealed frames for scope ids it
cannot map to anyone. Security reports are taken seriously, but note the project ships **as-is with
no warranty or guaranteed response** (see [`CONTRIBUTING.md`](CONTRIBUTING.md)).

## Supported versions

Pre-1.0. Only `main` is supported — fixes land there, and there are no backports to earlier tags.
If you run a spool, track `main` or a tagged image built from it.

## Reporting a vulnerability

**Please do not open a public issue or merge request for security vulnerabilities.** Public
disclosure before a fix exists puts the people relying on running spools at risk.

Report privately by email to **jeff.mixon@gmail.com**. If you already have an account on
<https://github.com>, a **confidential issue** on the project works too — tick
*This issue is confidential* so it stays visible only to project members. Please include:

- a description of the issue and its impact,
- steps to reproduce (or a proof of concept), and
- the affected version / commit, plus the relevant `SPOOL_*` configuration if it matters.

Please allow reasonable time for a fix before any public disclosure. As a best-effort hobby project,
there is no guaranteed acknowledgement or remediation timeline, but genuine reports will be
reviewed.

Vulnerabilities in the **sealed payload itself** — the E2E crypto envelope, key handling, safety
numbers — belong to the Knit app, not to this repo: report those through
[Knit's `SECURITY.md`](https://github.com/getknit/knit/blob/main/SECURITY.md). Anything about the
**spool wire protocol as specified** (rather than as implemented here) is also a Knit-repo matter,
since the spec lives there.

## What a spool is trusted with

A spool is deliberately given as little as possible:

| | |
|---|---|
| **Sees** | Scope ids, blob ids, sealed ciphertext, sizes, timing, client IPs |
| **Never sees** | Node ids, plaintext, rosters, who read what, which other spools a client uses |

Frames are sealed by the client before they are pushed, so a hostile or compromised spool learns
ciphertext and traffic patterns — never message content. No spool talks to another; clients
multi-home across several and union the results, so a spool that vanishes, lies by omission, or is
seized takes nothing with it that another member cannot re-push.

## Scope and known limitations

knit-spool is experimental. Several properties are **intentional design trade-offs, not
vulnerabilities** — they are documented and out of scope for reports:

- **Traffic metadata is visible to the operator by design.** Scope ids, blob sizes, arrival times,
  subscriber counts, and client IPs are exactly what a store-and-forward relay must handle to do its
  job. Correlating them is expected; a report that a spool operator can see them is not a finding.
- **The listener speaks plain WebSocket. TLS terminates at a reverse proxy** — see
  [`deploy/`](deploy/) for Caddy and nginx configurations. Exposing port 9470 to the internet
  directly is a deployment error, not a daemon vulnerability.
- **The bearer token rides in the query string** (`?k=`), because the spec puts it there. It gates
  access to a private spool; it decrypts nothing. Keep it out of proxy access logs — the shipped
  proxy configs do, and any proxy of your own should. A token in *this daemon's* logs, or returned
  by `/healthz` or `/metrics`, **is** a finding.
- **PoW defaults to off** (`SPOOL_POW_BITS=0`). A public spool run with the default will accept
  scope creation from anyone up to its configured rate limits and storage watermark. That is a
  configuration choice — the spec suggests 20 bits for public deployments.
- **Storage is not encrypted at rest.** SQLite holds bytes that were already sealed end-to-end; the
  spool has no key that would make encrypting them meaningful.
- **Capacity is bounded, not fair.** At the storage watermark the least-active scope is shed
  wholesale, and within a scope the oldest frame by arrival is evicted. A well-behaved client that
  shares a spool with a noisy one can lose held frames — the client's answer is multi-homing and
  re-push, not a promise from the spool.
- **A spool can withhold, delay, or forget.** Availability from any single spool is explicitly not
  guaranteed; that is why no spool is load-bearing.

Novel issues **beyond** these documented trade-offs are in scope and welcome, in particular:

- CBOR record-parser bugs — over-reads, unbounded allocation from an attacker-chosen length,
  crashes, or anything that gets past `SPOOL_MAX_RECORD` / `SPOOL_MAX_BLOB` / `SPOOL_MAX_A_CHUNK`.
- **Cross-scope leakage** — a frame, event, digest, or attachment chunk reaching a subscriber of a
  different scope.
- Authentication bypass on a private spool, or `/metrics` served unauthenticated when a token is
  set.
- PoW verification bypass, or a replay of the per-`(scope, day)` cache that skips work it should
  not.
- Rate-limit or quota bypass, per-IP accounting fooled by a forged `X-Forwarded-For` when
  `SPOOL_TRUST_PROXY` is off, or any path to unbounded memory, file-descriptor, or disk growth
  inside the configured limits.
- SQL injection, path traversal out of `SPOOL_DATA_DIR`, or corruption of the store that survives a
  restart.
- Digest computation that lets a client poison another's anti-entropy state.

## Hardening a deployment

Not a substitute for the above, but the short list operators ask for: terminate TLS at a proxy, set
`SPOOL_POW_BITS` on a public spool, set `SPOOL_TOKEN` on a private one and pass it with
`--token-file` rather than argv, keep `/metrics` off the public internet, leave
`SPOOL_TRUST_PROXY=false` unless a proxy you control appends `X-Forwarded-For`, and cap the process
(the shipped compose overlays do). See the README's *Deploy* and *Operating* sections.
