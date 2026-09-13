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
  updated: 2026-09-04T19:49:37Z
  coverage: complete
  canonical: https://github.com/getknit/knit-spool/blob/main/CHANGELOG.md
  locale: en
---

# knit-spool changelog

## Unreleased

### Added

- **`SPOOL_REQUIRE_MODERATION`, a send-side moderation request in `hello`.** A spool holds
  ciphertext and cannot screen a message, so an operator who answers for the people on their spool
  had no moderation lever at all. This is the one the design allows: set the flag and `hello`
  carries `moderation: true`, asking every conforming client to run its on-device content screen —
  the text and image classifier it already runs on what it receives — on what its user *sends* into
  any scope this spool carries, and to refuse what the screen flags with no "send anyway". Spec
  §7.5. The spool checks nothing and must not try; a modified client can ignore the field exactly as
  it can skip any sender-side check, and what the flag buys is that every conforming client on the
  spool refuses the same content the same way. Receiving is untouched: what a member hides or reveals
  stays their own setting.

  Strictest wins across a multi-homed conversation, because a sender seals once and pushes identical
  bytes to every spool it knows — a per-spool split would fork the conversation by operator. Off by
  default, and off is the absence of the field, never `false`: an unset spool's `hello` is
  byte-identical to before, the §13 vectors are untouched, and one new vector pins the on state.
  Reloadable on `SIGHUP` for the next connection, like `SPOOL_POW_BITS`. The conformance suite
  gains an advisory `moderation-advertisement` check, and the compose files declare the variable.

### Changed

- **The compose containers are named `knit-spool` and `knit-caddy`.** Compose derives a name from
  the project and the service and appends a replica index, so the daemon came up as
  `knit-spool-spool-1` — and as `deploy-spool-1` under `docker-compose.yml`, which sets no project
  name and so was named for whatever directory you ran it in. Both now pin `container_name`, which
  is what the `docker exec` lines in `README.md` and `HOSTING.md` had already been written against:
  they said `docker exec spool`, a name nothing created. Pinning costs `--scale`, meaningless for
  one SQLite store on one volume, and makes the name host-global, so a second stack on one host
  needs its own. The project name is deliberately unchanged: it is also the volume prefix, and
  renaming it would leave an existing `knit-spool_spool-data` orphaned and start the spool on an
  empty store. Existing deployments need `docker compose up -d` to pick the name up; the volume,
  and everything in it, is untouched.

- **Routine connection drops no longer log.** `connection dropped: ping timeout` was `INFO`, so
  the default log carried one line every time a client slept, changed network or died without
  closing — the ordinary churn of mobile peers, at a rate that scales with the fleet and buries
  the lines that mean something. It logs at `DEBUG` now. Nothing is lost: the drop was never a
  fault, the connection unwinds identically, and the status line's `conns` gauge and
  `knit_spool_connections_total` already count churn in a form you can actually read. Individual
  drops come back with a `DEBUG` override on `app.getknit.spool.server.SpoolServer` — that one
  logger, not `SPOOL_LOG_LEVEL`, which would take Ktor and the rest of the daemon with it. An
  `IOException` that is *not* a ping timeout still propagates untouched.

### Fixed

- **Every build that was not a release reported version `0.1.0-SNAPSHOT`.** The version reaches a
  build one of two ways: a tagged release passes it on the command line, and everything else falls
  back to a single literal in `build.gradle.kts`. Cutting 0.1.0 and 0.2.0 went through the first
  path, so nothing ever moved the second — and every main build since went on reporting a version
  two releases old, one that sorts *below* the release it supersedes. `GET /source` exists to
  answer "what is running", and the `commit` field beside it was correct throughout, which is what
  made the stale version worse than an absent one: the record looked answered.

  The literal is now `0.3.0-SNAPSHOT`, and `BuildInfoTest` pins it against this file — the build
  fails while the snapshot trails the newest released section here. Cutting a release renames
  `## Unreleased` to `## <version>`, which trips that check until the literal moves to the next
  release's snapshot, so the bump lands in the release commit rather than being remembered after
  it. A tag build is unaffected: it carries the tag's own version, which the release workflow
  already refuses to ship without a section here.

- **The documented way to drain or reload stopped the spool coming back after a reboot.** Both
  `README.md` and `HOSTING.md` said `docker kill --signal=USR1|HUP <container>`, and *any*
  `docker kill` marks a container manually stopped — whatever signal it carries, and even when the
  process keeps running and the reload succeeds. `restart: unless-stopped`, which every compose
  file here ships, means "restart unless the operator stopped it", so Docker then declined to start
  the container after the next reboot: silently, `RestartCount=0` because it never tried, while
  every other container on the box came back.

  The two operations that exist to *avoid* disruption were the two that armed it, and the damage
  was invisible until a reboot that might be weeks later and look unrelated. It cost this project's
  own reference spool 1h23m of downtime on an unattended-upgrades reboot, with Caddy up in front of
  it returning `502` the whole time.

  Both now document `docker exec <container> kill -HUP 1` (or `-USR1`), which delivers the same
  signal and touches none of Docker's stop bookkeeping. `restart: always` is deliberately **not**
  the advice: it ignores the flag at boot, but a `docker kill` still suppresses the ordinary
  restart-on-exit, so a signal that did stop the process would leave the spool down until the next
  reboot rather than back in seconds. The signal reaches PID 1 because the daemon installs handlers
  for `HUP`, `USR1` and `TERM` — a namespace init ignores a signal raised inside it unless there is
  a handler, which is why `kill -KILL 1` from in there does nothing.

- **The shipped compose files silently dropped most of `deploy/.env`.** Compose interpolates that
  file into the compose file; it does not pass it to the container, so a variable reaches the
  daemon only if `docker-compose.tls.yml` also names it under `environment:` — and it named eight.
  Everything else an operator set there was accepted without complaint and then ignored, the daemon
  running its own default instead. Nothing failed; the setting just never happened.

  Worst of these was **`SPOOL_SOURCE_URL`**, which `deploy/.env.example` has always documented: a
  fork setting it still served upstream's repository at `GET /source`, which is the wrong answer to
  an AGPL §13 offer. Every variable added in 0.2.0 was unreachable the same way —
  `SPOOL_TOKEN_NEXT`, `SPOOL_RELOAD_FILE` and all seven `SPOOL_COMMONS_*`, so the commons could not
  be switched on from `.env` at all, and a `SIGHUP` logged `SPOOL_RELOAD_FILE` unset no matter what
  the file said.

  Every variable the daemon reads is now declared, using compose's bare `KEY:` form rather than
  `KEY: "${KEY:-}"`. It forwards the value when `.env` sets one and omits the variable entirely
  when it does not, so the daemon's own default applies and no default is duplicated here to drift
  out of step. The empty-string form would also have been a boot failure waiting to happen: every
  numeric variable rejects `""` with `must be an integer`. `SPOOL_COMMONS_MAX_BLOB` shows why the
  distinction matters — it defaults to whatever `SPOOL_MAX_BLOB` is, and a literal default would
  refuse to boot under the 1 GB overlay, which lowers `SPOOL_MAX_BLOB` to 32 KiB.

  `SPOOL_PORT` and `SPOOL_DATA_DIR` stay undeclared on purpose: compose publishes the port and
  mounts the volume, so letting `.env` move either would break the proxy or the store rather than
  configure it.

  Pinned so it cannot happen again: `DeployConfigTest` fails the build if a variable the daemon
  reads is not reachable from `.env`, if one is declared that nothing reads, or if a numeric one
  is given the empty-string form. It asks the parser whether an empty value is tolerated rather
  than keeping a list to answer that, and `deploy/` is declared an input to the test task — Gradle
  would otherwise hold the task up to date across exactly the edit the check exists to catch.

### Security

- **An off-length wire id is refused as `malformed` before it touches anything.** Scope ids, blob
  ids, attachment ids and chunk ids are `bstr32` on the wire (spec B-2-6, §7.2, §7.3), but the daemon
  never checked, and the codec cannot: a CBOR byte string of any length decodes into a `ByteArray`,
  so an empty scope or a 100 KB `aid` arrived looking exactly like a real one. Past the handler an
  id becomes a map key and a row key, copied into every dependent row, index and tombstone, while
  only `data` was metered against quota — 200 one-byte `aput`s under such an `aid` counted 200 bytes
  and wrote 82 MB — and a `sub` naming an empty or 1000-byte scope was answered with a `digest`.
  Finding F1 of the 2026-09-13 security review of `d58d1fb`.

  Every handler now checks each id first, ahead of the subscription check and the push bucket, and
  answers `err malformed` with the request's `q`. The scope is echoed only when it is itself 32
  bytes, so an oversized one is never reflected back — `err.scope` is `bstr32` too. Nothing in a
  refused record is processed: a `sub` with one bad entry subscribes none of them, and a `pull` with
  a bad id anywhere in the list, past `maxPull` included, is refused whole. No strike and no log
  line, as for every other in-band `malformed`. A record that could never be stored spends no push
  token and never reaches the store thread.

  **One visible change:** a `push` whose `blobId`, or an `aput` whose `cid`, is not 32 bytes long
  now answers `malformed` rather than `bad_id`; `bad_id` still means a 32-byte hash that does not
  match `data`. No conforming client ever sent one, and the §13 vectors are untouched. `ID_BYTES` in
  `:protocol` names the length for the daemon, the tests and the conformance suite, which gains
  `sub-off-length-scope` — an advisory check that a 31-byte scope is answered with an `err`, not a
  `digest`, on a connection that keeps working. Pinned by `IdLengthTest`.

- **Every attachment chunk row is charged at least 512 bytes.** The attachment quota counted payload
  bytes, and a one-byte chunk is not one byte: it is a header row, a chunk row and an index entry,
  about 390 B of SQLite file (measured: 3,000 one-byte `aput`s were 3,000 B counted and 1,144 KiB
  of file) and the same order of heap on the in-memory store. Frames are count-capped by
  `maxFrames`; attachments had no count cap, so the 16 MiB default `SPOOL_MAX_ATTACH_BYTES` admitted
  about 16 million rows — some 6 GB of file — per scope before `quota` fired, and `SPOOL_MAX_BYTES`
  saw 16 MiB of it. Finding F7 of the same review, and the one that survives the id fix above.

  Every chunk is now charged `max(size, 512)` at the put, the drop and the boot recompute, in both
  stores, which bounds a scope at `SPOOL_MAX_ATTACH_BYTES / 512` chunk rows (32,768 at the default)
  and the spool at `SPOOL_MAX_BYTES / 512`, without a new variable. A conforming client never
  notices: only an attachment's last chunk can be shorter than the structural 48 KiB, so the charge
  moves by at most 511 bytes per attachment, never for a full chunk, and two maximal 8 MiB
  attachments still fit the default.

  **This changes what the store counts, not its schema.** There is no migration: the first boot
  after the upgrade re-derives every scope's `attach_bytes` under the new rule, and the `live=`
  figure in the status line and `knit_spool_live_bytes` move with it — by at most 511 bytes per
  stored attachment on a spool that has only ever seen conforming clients. If that carries the
  total over `SPOOL_MAX_BYTES`, the watermark sheds at the first sweep exactly as it would for any
  other growth. Pinned by the store contract tests on both backends (a one-byte chunk charges 512;
  tiny attachments evict and refuse at the row cap; expiry, refusal and shed release the charged
  amount), a SQLite test that a pre-floor `attach_bytes` column is healed on reopen, and a server
  test that two one-byte chunks trip a 1,000-byte watermark.

- **An `aput` declaring more chunks than the quota could hold is refused `quota` before its first
  chunk is stored.** The store accepted any `total ≥ 1`, and every later `ahave` for that `aid`
  allocated a presence bitmap of `⌈total / 8⌉` bytes on the single store thread and sent it back:
  a client-chosen `total` of 80,000,000 turned an 80-byte request into a 10 MB `ahas` costing
  125 ms of the thread every other connection shares, `Int.MAX_VALUE` wrapped the `Int` arithmetic
  into `NegativeArraySizeException` — `err internal` and a stack trace per request, 50 a second per
  connection into a log the plain compose file never rotates — and anything between asked for more
  than a 192 MiB heap holds. Finding F2 of the same review.

  Chunking is structural: §4.5 fixes `total = ceil(|A| / aChunkBytes)` with `aChunkBytes = 49152`
  (C-4.5-3), so a `total` above `⌈SPOOL_MAX_ATTACH_BYTES / 49152⌉` — 342 at the default — describes
  an attachment larger than the whole quota, one that "cannot fit the budget even alone" and that
  S-6.5-3 says to refuse `quota`. Both stores now do, first among the cheap rejections and ahead of
  the hash, reaching the verdict the byte quota would have reached at the last chunk before the
  first one exists. `A_CHUNK_BYTES` in `:protocol` names the constant; `HardLimits.maxATotal`
  derives the bound from the quota, so there is nothing new to declare, document or reload, and the
  bitmap arithmetic is bounded by construction — every stored `total` is at most `maxATotal`, which
  is 43,691 even at a quota of `Int.MAX_VALUE`, so `(total + 7) / 8` cannot wrap. No conforming
  client is touched: its `total` is at most the bound by definition.

  **This changes what an upgraded SQLite store holds.** A spool that ran before this fix may hold a
  header carrying whatever `total` a client chose; on its first boot the daemon drops every
  attachment declaring more than `maxATotal` chunks — header and chunks, no tombstone, since nothing
  conforming wrote it and `ahave` should answer absent rather than dead — logs one WARN naming the
  count, and re-derives `attach_bytes` from what remains. The same drop covers an operator lowering
  `SPOOL_MAX_ATTACH_BYTES` below an existing attachment's chunk count: it could never complete. The
  conformance suite gains `aput-total-over-quota`, an advisory check that an `aput` declaring
  `2³¹−1` chunks is answered with an `err` carrying `q` on a connection that keeps working, and
  `attachment-get-truncated` skips itself on a spool whose quota cannot admit the `maxAget + 2`
  chunks it declares. Pinned by `AttachmentTotalTest` (each refused `total` answers `quota`, leaves
  presence absent and the store empty, and never reaches the guarded catch-all), the store contract
  tests on both backends, `HardLimitsTest` for the derivation, and a SQLite test that a header set
  to `Int.MAX_VALUE` is dropped on reopen.

## [0.2.0](https://github.com/getknit/knit-spool/releases/tag/v0.2.0) — 2026-09-04T19:49:37Z

> The operator release. A spool can now be reloaded, drained, credential-rotated and
> validated without dropping a connection or guessing, and it will tell you which version of
> itself is running. It also gains a commons — one optional shared scope per spool, relayed
> but unreadable. Pre-1.0, so every interface below is still subject to change; the wire is
> additive only, with no records or error codes removed or changed, so a 0.1.0 client talks to
> a 0.2.0 spool unchanged.

### Added

- **`SIGHUP` reloads the configuration** — quotas, rate limits and credentials change without
  restarting, so no client loses its connection to a settings change. `SPOOL_RELOAD_FILE` names a
  `KEY=value` file layered over the environment; the environment itself cannot be the source,
  because `System.getenv()` is fixed at exec and a container's variables cannot change without
  recreating it. What a reload can and cannot move, and when each takes effect, is in the README.
  Naming an unreloadable value is logged and ignored rather than fatal, and an unreadable or
  invalid file leaves the running configuration untouched.
- **`SPOOL_TOKEN_NEXT`, a second accepted credential** — rotation becomes add-new, migrate,
  promote, retire instead of a cutover that locks out every client until it updates. Both tokens
  are accepted for as long as both are set; anything that is neither is still refused. Compared in
  constant time with no early exit, so the timing does not say which credential was presented.

- **Drain mode, toggled by `SIGUSR1`** — new connections refused `503` with a `Retry-After` while
  the live ones keep being served. There was nowhere to stand between "serving" and "stopped":
  shutdown closes every session at once, so on a busy spool an upgrade sent every client back on
  the same second. Now you drain, watch the connection count fall, and then stop.

  `SIGUSR1` rather than a second `SIGTERM`, which is what `docker stop` sends before it `SIGKILL`s
  — a two-phase TERM would drain and then be killed mid-drain by the ordinary stop path. It reuses
  the same transport refusal `SPOOL_MAX_CONNS` already uses, and for the same reason: §7.1 has no
  close code that means "come back later".

  `/healthz` deliberately keeps answering `200` — the container HEALTHCHECK, both CI pipelines and
  compose's `service_healthy` gate all probe it, and a drain that failed it would restart the
  container mid-drain. Counted by `knit_spool_drain_refused_total`, kept apart from
  `knit_spool_conns_refused_total` so a planned drain never reads as a box out of room, and shown
  as `draining=yes` on the status line.
- **`knit-spool check`**, which validates the environment, prints every resolved value, and exits
  `0` valid or `1` invalid — without binding a port, opening a store, or creating a directory.
  Confirming a configuration previously meant starting a daemon and reading its logs, which is a
  poor fit for provisioning that wants to know a config is good *before* a container starts, and
  for testing a tier template in CI. Resolved config on stdout, warnings and errors on stderr, so
  one can be parsed without filtering the other; `SPOOL_DATA_DIR` is checked for a writable parent
  and deliberately not created. It checks configuration, not store state — a commons that will not
  fit because the store already holds `SPOOL_MAX_SCOPES` scopes still fails at boot.
- **The effective configuration is logged at boot**, one `k=v` line carrying every resolved value —
  defaults included, because the value an operator misremembers is always the one they never set,
  and across a fleet that is a support call rather than a shrug. `configFromEnv` validated all of
  it and then kept the answer to itself; the only startup line reported port, PoW bits, and whether
  a token was set.

  Tokens are reported as `set`/`unset` and never printed, and the commons appears as a truncated id
  for the same reason `hello` never carries it at all — publishing it would turn a room only invite
  holders can find into one anybody who connects could subscribe to.
- **A build stamp the daemon can report about itself**, and `GET /source` to serve it: the running
  version, the commit it was cut from, and a corresponding-source URL. Nothing in the running
  process knew any of that before — the jar carried no manifest attributes, and a fleet had no way
  to answer "what is actually deployed". `knit_spool_build_info{version,commit}` is the labelled
  gauge a dashboard joins against, and one startup log line says the same thing.

  `/source` is unauthenticated on purpose. AGPL §13 obliges anyone running a modified version to
  offer its source to the users whose clients connect, so an offer behind `SPOOL_TOKEN` would not
  be one; a fork sets **`SPOOL_SOURCE_URL`** and the offer points at their repository instead of
  upstream's. Both shipped proxy configs pass it through, and both now say why. `/healthz` is
  untouched.

  Version and commit arrive as `-PspoolVersion` / `-PspoolCommit`, so neither is stored in the
  tree and a build told neither honestly reports `unknown`. `Dockerfile` takes them as build args
  — `.dockerignore` excludes `.git`, so there is no repository in the image context to ask.
- **`SPOOL_METRICS_TOKEN`, a scrape credential separate from the connect credential** (default
  unset, which keeps today's behavior: `SPOOL_TOKEN` gates `/metrics` on a private spool). The two
  answer to different people. `/metrics` carries scope counts, live bytes and traffic shape — the
  operator's business, not the client's — and until now the only credential that opened it was the
  one every client already holds. It also ran the other way: a Prometheus scraping a fleet needed
  each spool's *connect* secret in its scrape config.

  Setting it **replaces** `SPOOL_TOKEN` on `/metrics` rather than joining it, which is the point —
  a client holding the connect token gets `403`. That does mean a scrape configured as
  `?k=$SPOOL_TOKEN` stops working the moment the new variable is set; nothing changes until it is.
  A public spool can set it on its own to gate metrics without becoming private.
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

### Fixed

- **The conformance runner's exit code `3` is documented.** `Report.summary()` has always returned
  it for a run where no MUST check failed but one or more could not be judged — the transport broke,
  or the tool itself hit a bug — and the README listed only `0`, `1` and `2`. A CI job written from
  that list treats an inconclusive run as a passing one, which is the opposite of what the code
  intends.

### Changed

- **Scope ids are truncated in the log.** The watermark's shed warning carried a full 64-character
  scope id; it now carries the first eight, and the whole id moved to `DEBUG`. A blinded spool
  ships its lines to an aggregator that is not blinded — it retains them, indexes them, and
  outlives the scope — so a full id at `WARN` was the one identifier this daemon holds that
  survived contact with the outside world. Eight hex characters still follow one scope across a
  single log stream. The store's self-heal line already truncated, to twelve; both now go through
  one helper so there is one convention rather than two. A malformed `SPOOL_COMMONS_ID` is no
  longer echoed back in full either: one mistyped character in a real id would otherwise have put
  a near-real scope id in an `ERROR` line.
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
