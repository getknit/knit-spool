# Security Policy

knit-spool is a blinded store-and-forward relay: it holds end-to-end-sealed frames for scope ids it
cannot map to anyone. Security reports are taken seriously, but note the project ships **as-is with
no warranty or guaranteed response** (see [`CONTRIBUTING.md`](CONTRIBUTING.md)).

## Supported versions

Pre-1.0. Only `main` is supported — fixes land there, and there are no backports to earlier tags.
If you run a spool, track `main` or a tagged image built from it.

## Reporting a vulnerability

**Please do not open a public issue or pull request for security vulnerabilities.** Public
disclosure before a fix exists puts the people relying on running spools at risk.

Report privately through GitHub's [**private vulnerability reporting**][report] — the
"Report a vulnerability" button on the repository's *Security* tab — which opens a private draft
advisory visible only to the maintainers. If you'd prefer email, write to **jeff.mixon@gmail.com**.
Please include:

- a description of the issue and its impact,
- steps to reproduce (or a proof of concept), and
- the affected version / commit, plus the relevant `SPOOL_*` configuration if it matters.

Please allow reasonable time for a fix before any public disclosure. As a best-effort hobby project,
there is no guaranteed acknowledgement or remediation timeline, but genuine reports will be
reviewed.

[report]: https://github.com/getknit/knit-spool/security/advisories/new

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

### The commons

A spool running a commons (`SPOOL_COMMONS_ID`) holds the same shape of secret it always did. The
invite is a 32-byte secret that splits in two: the scope id, `SHA-256("knit/spool/v1/commons" ‖
secret)`, which is what the operator configures, and the content key, which members derive and the
spool is never given. This repo implements the first derivation and deliberately not the second, so
"the spool cannot read its own commons" is a property of the code rather than a promise about it.

Two things a commons does move:

- **The operator can count the room.** `knit_spool_commons_subscribers` is how many connections are
  subscribed — an aggregate on an endpoint that is token-gated on private spools and 404'd by both
  shipped proxy configs, next to a connection count the operator already had. It is not a roster,
  and no such count is ever offered to clients: who is in the room stays a delivery fact the spool
  does not deal in.
- **The scope id is a weaker secret than the invite.** A member can hand out the id without the
  secret, and the holder could then subscribe and collect ciphertext they cannot read. That is
  traffic analysis, not disclosure, and it is the same exposure every scope id already carries.

Removing a member means rotating the room: mint a new invite, set the new `SPOOL_COMMONS_ID`, and
restart. The old scope is no longer pinned, so it ages out on its TTL or under the watermark. The
operator cannot moderate individual messages — that would need the key they do not have.

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
- **A commons is only as private as its invite, and every member shares one key.** Anyone the secret
  reaches can read and write the room, and there is no per-member identity, ban list, or revocation
  short of rotating the invite. Whether a member can forge another's display name is a property of
  the sealed frame, which this daemon never opens — it belongs to the client and the spec, not here.

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
