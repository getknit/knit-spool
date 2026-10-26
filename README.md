<div align="center">

<img src="assets/icon-256.png" width="128" height="128" alt="Knit Spool">

# Knit Spool

**The reference Spool — a scoped, blinded store-and-forward relay for
[Knit](https://github.com/getknit/knit)'s Internet plane.**

A Spool keeps a Knit conversation moving when its members are out of each other's range: sealed
frames wait there for whoever has not connected yet. It holds them for scope ids it cannot map to
anyone, and forgets everything else.

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-3.3.0%20CIO-087CFA?logo=ktor&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-orange?logo=openjdk&logoColor=white)
![Protocol](https://img.shields.io/badge/Spool%20protocol-v1%20(%C2%A713%20vectors%20pinned)-2EA043)
[![Coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fgetknit%2Fknit-spool%2Fbadges%2Fcoverage.json)](https://github.com/getknit/knit-spool/actions/workflows/ci.yml)
![Footprint](https://img.shields.io/badge/RSS-~128%E2%80%93256%20MB-00BCD4)
![License](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)
[![Knit Spool changelog on whatsnew.fyi](https://whatsnew.fyi/product/knit-spool/badge.svg)](https://whatsnew.fyi/product/knit-spool)

</div>

---

## What a Spool is

A Spool holds, per conversation **scope**, a bounded set of end-to-end-sealed frames and a digest
over them, streams new arrivals to connected subscribers, and heals divergence by digest
anti-entropy.

It never learns node ids, message content, rosters, or delivery facts — it stores ciphertext for
scope ids it cannot map to anyone. Spools never talk to each other: clients multi-home across
several Spools and union them, so **no Spool is load-bearing** and a wiped Spool is refilled by any
one conversation member.

> [!IMPORTANT]
> **The protocol spec is the product.** The normative spec lives in the Knit repo:
> [`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md).
> This daemon implements the spec — never the other way around — and `SpecVectorTest` pins this
> implementation to the spec's §13 vectors byte-for-byte. Third-party Spool implementations are
> first-class; this repo exists so nobody *has* to write one, and ships the conformance suite that
> validates any implementation.

### At a glance

| | |
|---|---|
| **What** | Store-and-forward relay daemon for Knit's optional Internet plane |
| **Wire** | CBOR records over one WebSocket, `wss://host/spool/v1` (`?k=` token on private Spools) |
| **Stack** | Kotlin 2.4.21 · Ktor 3.6.0 (CIO) · kotlinx-serialization CBOR · SQLite (WAL) · JDK 21 |
| **Sees** | Scope ids, blob ids, ciphertext, sizes, timing |
| **Never sees** | Node ids, plaintext, rosters, who read what, which Spools a client also uses |
| **Config** | Environment variables only; invalid values refuse to start |
| **Ops** | `GET /healthz`, `GET /source`, `GET /metrics` (Prometheus text) |
| **Footprint** | Idles in ~128–256 MB on the cheapest VPS tier (`-Xmx256m` default) |
| **Hosted** | [Knit Hosted](https://hosted.getknit.app) runs the same release for you — own hostname, token and dashboard |
| **License** | AGPL-3.0-or-later |

## Contents

- [How it works](#how-it-works)
- [Modules](#-modules)
- [Status](#-status)
- [Run](#-run)
- [Configuration](#-configuration)
- [The commons](#-the-commons)
- [Send-side moderation](#-send-side-moderation)
- [TURN (experimental)](#-turn-experimental)
- [Casts (experimental)](#-casts-experimental)
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
   │ Phone A │─────────►│  Spool 1   │──────────►│ Phone B │
   │  seals  │          └────────────┘           │ unions  │
   │  frame  │   push   ┌────────────┐   pull    │ + opens │
   │  once   │─────────►│  Spool 2   │◄──────────│         │
   └─────────┘          └────────────┘           └─────────┘
                  no Spool-to-Spool link, ever
```

The sender seals a frame once and pushes the same bytes to each Spool it knows. Every member
subscribes to the scope on the Spools *it* knows and unions what comes back, so overlap is the only
thing two members need — not agreement on a Spool list. Each Spool sees an opaque 32-byte scope id,
a blob id, ciphertext, and timing; a Spool that vanishes takes nothing with it that another member
can't re-push.

A client that has been away sends its scope digest instead of a full pull. Same digest, nothing to
do — an idle conversation costs one round trip.

## 📦 Modules

| Module | Artifact | What |
|---|---|---|
| `:protocol` | library | Records, PoW (verify + mine), digest — spec §2/§6.3/§7/§8, no server code |
| `:daemon` | `knit-spool` | The reference daemon: WSS server, in-memory + SQLite stores, rate limits, ops |
| `:conformance` | `knit-spool-conformance` | CLI that validates **any** live Spool over WebSocket (TAP output) |

`:conformance` depends only on `:protocol` — it tests the wire contract, not this repo's
internals.

## ✅ Status

Implements the full **v1** protocol:

- **Record layer** — CBOR `hello`/`sub`/`digest`/`list`/`pull`/`blob`/`push`/`event`/`ok`/`err`,
  with spec-vector conformance and forward-compatible tolerance of unknown records and fields.
- **Handshake** — version negotiation, advertised limits, bearer-token private Spools.
- **Fan-out** — live `event` delivery to every other subscriber of the scope, `q`-correlated
  replies, idempotent duplicate pushes.
- **Retention** — oldest-by-arrival eviction, count-bounded tombstones, per-scope digests with
  unsolicited re-anchors after eviction or expiry.
- **Attachments** (§6.5/§7.3) — `ahave`/`ahas`/`aget`/`achunk`/`aput`, chunk presence bitmaps,
  first-write-wins with `conflict` on mismatch, truncated (never refused) over-long `aget`, and a
  per-scope byte quota that charges every chunk at least 512 bytes, so a scope also holds at most
  `SPOOL_MAX_ATTACH_BYTES / 512` chunks. An `aput` declaring more chunks than the quota could hold
  at the structural 48 KiB each is refused `quota` before its first chunk is stored. Set
  `SPOOL_MAX_ATTACH_BYTES=0` and the family disappears from `hello`.
- **Abuse control** — stateless PoW on every path that creates a scope (a `sub` for one the Spool
  does not hold, including one the watermark has since shed, and the shed-scope `push`-recreate
  path) with the per-`(scope, day)` cache; per-connection and per-IP rate limits (`rate` +
  `retryMs`, escalating to close 4003), an IPv6 client keyed by its /64; and a global storage
  watermark that sheds the least-recently-active scope.
- **Commons** (§7.4) — up to eight optional shared rooms per Spool, off unless `SPOOL_COMMONS_ID` is
  set. Ordinary records on the data path; what is added is the policy a *shared* scope needs. See
  [the commons](#-the-commons).
- **Send-side moderation** (§7.5) — one optional `hello` bool, off unless
  `SPOOL_REQUIRE_MODERATION` is set, asking clients to run their on-device content screen before
  sending. A request the Spool cannot check, and nothing on the data path. See
  [send-side moderation](#-send-side-moderation).
- **Capacity** — an optional total-connection cap that refuses the upgrade with `503` and a
  `Retry-After` rather than letting the box degrade into GC thrash. Not a protocol limit: a full
  Spool is a property of the hardware, and a multi-homing client treats it as one more unreachable
  Spool.
- **Persistence** — SQLite (WAL, self-healing boot recompute) or in-memory, behind one store
  contract, plus a periodic sweeper.
- **Ops** — `/healthz`, `/source` (build stamp and the §13 source offer), `/metrics` (Prometheus
  text), a periodic status log line, graceful shutdown.
- **Operator controls** — `SIGHUP` reloads quotas and credentials from a file while connections
  stay up, `SIGUSR1` drains for a rolling upgrade, `SPOOL_TOKEN_NEXT` rotates the bearer token
  with nothing refused mid-rotation, and `knit-spool check` validates a configuration before a
  container starts. See [operating](#-operating).

## 🚀 Run

```sh
./gradlew :daemon:run                      # listens on :9470, PoW off, public, in-memory
SPOOL_TOKEN=s3cret ./gradlew :daemon:run   # private Spool: wss://host/spool/v1?k=s3cret
```

`knit-spool check` validates the environment and prints what it resolved to, without binding a port,
opening a store, or creating a directory — for confirming a configuration before a container starts:

```sh
$ knit-spool check
port=9470 maxBlob=65536 maxFrames=1000 … commons=off source=https://github.com/getknit/knit-spool \
token=unset tokenNext=unset metricsToken=unset pow=0 maxRecord=131072 … moderation=false store=memory
$ echo $?
0
```

(Wrapped here; in the terminal it is one line. The boot-only half prints first, then the half a
`SIGHUP` can move — the same split as the [reload table](#reloading-configuration).)

Exit **0** valid, **1** invalid (with the reason on stderr), **2** unknown command. The resolved
config goes to stdout and warnings to stderr, so one can be parsed without filtering the other. It
checks *configuration*, not store state: the one boot failure it cannot predict is a commons room
that will not fit because a persistent store already holds `SPOOL_MAX_SCOPES` scopes, none of them
empty.

## 🔧 Configuration

Environment variables only; invalid values refuse to start, and an unrecognized `SPOOL_*` name is
logged as a probable typo. Defaults follow the spec's §12 constants.

| Variable | Default | Meaning |
|---|---|---|
| `SPOOL_PORT` | `9470` | listen port |
| `SPOOL_TOKEN` | unset | bearer token; unset = public Spool |
| `SPOOL_TOKEN_NEXT` | unset | a second accepted token, for rotating without a cutover: set it, let clients migrate, promote it into `SPOOL_TOKEN`, unset it. Requires `SPOOL_TOKEN`, and must differ from it |
| `SPOOL_RELOAD_FILE` | unset | a `KEY=value` file `SIGHUP` re-reads, layered over the environment. Unset means `SIGHUP` has nothing to read and says so |
| `SPOOL_METRICS_TOKEN` | unset | `?k=` credential for `/metrics`; unset = `SPOOL_TOKEN` gates it. Setting it **replaces** the Spool token there, so a scrape that used `SPOOL_TOKEN` stops working |
| `SPOOL_DATA_DIR` | unset | unset = in-memory; set = SQLite at `$DIR/spool.db` |
| `SPOOL_SOURCE_URL` | upstream repo | corresponding-source URL served at `GET /source`; **set this if you run a modified build** (AGPL §13) |
| `SPOOL_POW_BITS` | `0` | PoW difficulty for unknown scopes (spec suggests 20; 0 = off) |
| `SPOOL_MAX_BLOB` | `65536` | max sealed-blob bytes |
| `SPOOL_MAX_SCOPES` | `64` | max scopes held; at the cap a new scope evicts the least-recently-active empty scope nobody is subscribed to (and the sweeper drops ones idle past their TTL), and is refused `quota` only when there is none; also the most scopes one `sub` may name — a longer one, or one naming a scope twice, is refused `malformed` whole |
| `SPOOL_MAX_FRAMES` | `1000` | per-scope frame-cap ceiling |
| `SPOOL_MAX_TTL_MS` | `604800000` | per-scope TTL ceiling (7 d) |
| `SPOOL_MAX_RECORD` | `131072` | max CBOR record bytes (must fit `SPOOL_MAX_BLOB` + 512) |
| `SPOOL_MAX_PULL` | `64` | max blob ids per `pull` |
| `SPOOL_MAX_ATTACH_BYTES` | `16777216` | per-scope attachment byte quota (§6.5); every chunk counts as at least 512 bytes, so a scope holds at most this ÷ 512 chunks; an `aput` declaring more than ⌈this ÷ 49152⌉ chunks (342 at the default) — an attachment that could never fit — is refused `quota` before a chunk is stored; **0 turns attachments off** — the three attachment limits then vanish from HELLO and a conforming client never sends `ahave`/`aget`/`aput` |
| `SPOOL_MAX_A_CHUNK` | `49221` | max sealed attachment-chunk bytes (the spec's structural 48 KiB plus framing) |
| `SPOOL_MAX_AGET` | `32` | max chunks per `aget`; an over-long request is truncated, never refused |
| `SPOOL_MAX_BYTES` | `268435456` | payload watermark (attachment chunks at their charged size, at least 512 B each); over it the least-active scope is shed; 0 = unlimited |
| `SPOOL_SWEEP_MS` | `60000` | sweeper cadence (expiry, cache pruning, watermark) |
| `SPOOL_STATUS_MS` | `300000` | status log line cadence (5 min); 0 = off |
| `SPOOL_LOG_LEVEL` | `INFO` | root log level |
| `SPOOL_TRUST_PROXY` | `false` | honor the proxy-appended `X-Forwarded-For` hop as the client address for the per-IP limits |
| `SPOOL_MAX_CONNS` | `0` | total live connections; over it the upgrade is refused **503 + `Retry-After`**, never a close code. 0 = unlimited |
| `SPOOL_MAX_CONNS_PER_IP` | `16` | connection cap per client address; an IPv6 client is its /64 |
| `SPOOL_RATE_RECORDS` | `50` | records/s per connection (burst 4×); a `sub` spends one token per scope it names, and once the bucket is dry the rest of its scopes answer `err rate` |
| `SPOOL_RATE_PUSHES` | `10` | pushes/s per connection (burst 4×) |
| `SPOOL_RATE_NEW_SCOPES` | `6` | new scopes/min per client address (IPv6: per /64; burst 4×); a `sub` naming more new scopes than the burst has its tail answered `err rate`, and a record refused for rate strikes the abuse window once, not once per scope |
| `SPOOL_REQUIRE_MODERATION` | `false` | ask clients to run their on-device content screen before sending and refuse what it flags; advertised as `moderation: true` in `hello`, omitted when off. A request the Spool cannot verify (§7.5) |
| `SPOOL_COMMONS_ID` | unset | the commons scope id, 64 hex chars from `knit-spool commons-invite`; **unset = no commons** |
| `SPOOL_COMMONS_NAME` | unset | display label, advertised in `hello` only when `SPOOL_TOKEN` is set |
| `SPOOL_COMMONS_MAX_FRAMES` | `500` | commons frame cap — pinned, not client-declared |
| `SPOOL_COMMONS_TTL_MS` | `86400000` | commons frame TTL (24 h) |
| `SPOOL_COMMONS_MAX_BLOB` | `SPOOL_MAX_BLOB` | commons per-blob cap |
| `SPOOL_COMMONS_ATTACH` | `false` | allow attachments in the commons |
| `SPOOL_COMMONS_RATE_PUSHES` | `20` | pushes/s into the room **as a whole**, not per connection (burst 4×) |
| `SPOOL_COMMONS_RATE_TOTAL` | `40`, or the largest room's `RATE_PUSHES` if higher | pushes/s into **every room together** (burst 4×) |
| `SPOOL_COMMONS_<n>_*` | unset | rooms 2 to 8: `_ID`, `_NAME`, `_MAX_FRAMES`, `_TTL_MS`, `_MAX_BLOB`, `_ATTACH`, `_RATE_PUSHES`, defaulting as above; each needs `SPOOL_COMMONS_ID` |
| `SPOOL_TURN_SECRET` | unset | *experimental:* the TURN shared secret, ≥ 32 characters; **unset = no TURN**, and the three below must stay unset |
| `SPOOL_TURN_URLS` | — | comma-separated `turn:`/`turns:` URIs clients are told to dial; required with the secret |
| `SPOOL_TURN_TTL_S` | `300` | how long a credential can *start* a relay, in seconds (60–3600). coturn checks it only at allocation, so a call outlives it. Every server's |
| `SPOOL_TURN_RATE` | `4` | credentials per connection per 10 minutes (one address gets 4×), whichever server they are for |
| `SPOOL_TURN_<n>_SECRET` | unset | *experimental:* TURN servers 2 to 4, each [a box of its own](#several-turn-boxes) with its own secret (≥ 32 characters). Numbered with no gap, and each needs `SPOOL_TURN_SECRET` before it |
| `SPOOL_TURN_<n>_URLS` | — | that server's `turn:`/`turns:` URIs; required with its secret. With two or more servers, each server's URLs name one host, no two servers share one, and each has a `turn:` URL over UDP |
| `SPOOL_STUN_URLS` | unset | *experimental:* up to 4 comma-separated `stun:`/`stuns:` URIs told to every client in `hello`, with no credential; needs no secret. Reloadable |
| `SPOOL_TICKET_KEY` | unset | *experimental:* the ticket issuer's Ed25519 **public** key, unpadded base64url (`knit-spool ticket-keygen`). Set = `hello` carries `ent`, and what `SPOOL_TICKET_GATES` names needs a ticket. Reloadable |
| `SPOOL_TICKET_KEY_NEXT` | unset | *experimental:* a second issuer public key, accepted beside the key during a rotation; a ticket's `kid` picks which one checks it. Must differ from the key, and needs it. Reloadable |
| `SPOOL_TICKET_AUDIENCE` | — | the lowercase host name tickets must name to be good here; required with the key |
| `SPOOL_TICKET_AUDIENCE_ALIAS` | unset | *experimental:* a second host name tickets may name, while the Spool is renamed and both names reach it. Must differ from the audience, and needs it and the key. Never announced. Reloadable |
| `SPOOL_TICKET_GATES` | `turn,cast` | *experimental:* which capabilities need a ticket, from `turn`, `files` and `cast`; `hello` says so in `entGates`. `files` gates **sending** attachments only. Needs the key. Reloadable |
| `SPOOL_TICKET_ATTACH_BYTES` | `33554432` | with `files` gated, the most one ticket holder may upload a day, whatever its ticket claims (32 MiB). Needs the key. Reloadable |
| `SPOOL_TICKET_TURN_RATE` | `16` | *experimental:* TURN credentials per ticket holder per 10 minutes (burst half that, at least 2), across all its connections and addresses, on top of `SPOOL_TURN_RATE`. **0 = off.** 0–240. Needs the key and `SPOOL_TURN_SECRET`. Reloadable |
| `SPOOL_RATE_CASTS` | `0` | *experimental:* casts per connection per second (burst 4×; one address gets 4× that). **0 = no casts**, and `hello` omits `cast`. With a ticket key, a cast needs a cast ticket on its scope. Reloadable; 0 stops casts at once |
| `SPOOL_MAX_CAST` | `2048` | the largest cast, in bytes; must fit `SPOOL_MAX_RECORD`. Reloadable |
| `SPOOL_RATE_CASTS_TOTAL` | `200` | **Spool-wide** casts/s accepted (burst 4×); each leaves once per other subscriber of its scope. Reloadable, immediately |

Each commons room's bounds must fit the Spool-wide caps and leave a `list` reply that fits
`SPOOL_MAX_RECORD`, and every room together must fit under `SPOOL_MAX_BYTES` — rooms are pinned
against the watermark, so rooms that cannot fit are refused at startup rather than discovered at 3am.

## 🏛 The commons

Set `SPOOL_COMMONS_ID` and a Spool also runs **a shared room** — a scope where everyone on that Spool
who holds the room's invite can talk to everyone else in it, still sealed end to end. A Spool can run
up to eight, each with its own invite. Without one a Spool is pure infrastructure: it relays between
clients that already paired out of band and never introduces anybody.

Mint an invite, which never touches disk or the log:

```sh
$ knit-spool commons-invite --url wss://spool.example.org/spool/v1 --token-file token --name Home
invite (give this to members):  knit-commons:v1:il03LpV71kiksIRvNBKlNln1t8k7NOBVJZtHW-QRr44
link (give this to members):    https://getknit.app/r#pGF2AWF1eC93c3M6Ly9zcG9vbC5leGFtcGxl…
spool config (put in env):      SPOOL_COMMONS_ID=bf92a00e…cf4df842
                                SPOOL_COMMONS_NAME=Home
```

The halves go to different places. The **invite** goes to your members, and so does the **link**,
which joins the Spool and the room in one tap and carries the room's name; it is printed only with
`--url`, and a private Spool's token goes into it from `--token-file`, never from the command line,
which every local user can read. The **id**, which is `SHA-256("knit/spool/v1/commons" ‖ secret)`,
goes in the Spool's environment. The Spool is never given the secret, so it relays a room it cannot
read — and this repo implements no content-key derivation at all, which makes that structural rather
than a promise.

A second room is the same again with `--room 2`, which prints `SPOOL_COMMONS_2_ID`; rooms run up to
`SPOOL_COMMONS_8_*`, and a numbered room needs the first. Give every room the first room's
`TTL_MS`: clients that predate rooms compare a room's lifetime with the first room's, and take a
room with a different one for a rotated room (the boot log warns).

On the wire a room is an ordinary scope: `sub`, `push`, `event`, `digest`, `list`, `pull` all behave
exactly as they do for a private conversation, and no new record types or error codes exist. What
the Spool adds is the policy a *shared* scope needs, room by room:

| | |
|---|---|
| **Bounds are pinned** | The store applies whatever the most recent subscriber declared. In a room shared with strangers that would let one member subscribe with `maxFrames = 1` and evict everyone's history, so a room's `sub` ignores what the client declares and answers with that room's bounds in its `digest`. |
| **No PoW to join** | Rooms are created at boot, so they are never unknown scopes and the §6.4 creation gates never fire — members join with a plain `sub` even at 20 bits. |
| **Never shed** | The storage watermark may not take a room away to make space for one client's conversation. |
| **Push budgets, not strikes** | `SPOOL_COMMONS_RATE_PUSHES` bounds each room as a whole; 200 members at the per-connection 10/s would be 2,000 pushes/s into one scope. `SPOOL_COMMONS_RATE_TOTAL` bounds every room together. Both throttle with `err rate` + `retryMs` but never strike the connection — congestion on a shared room is not evidence any one member misbehaved — and a push one refuses is not charged to the other. |
| **Attachments off by default** | A shared room is where a 16 MiB upload costs the operator most and is worth least. `SPOOL_COMMONS_ATTACH=true` (or `SPOOL_COMMONS_<n>_ATTACH`) for the rooms you want them in. |

`hello` lists every room in `rooms`, each with its bounds, whether attachments are on and a `tag`,
the first eight bytes of a hash of the room's id; `commons` repeats the first room for clients that
predate rooms. It **never carries a scope id**. A member who holds an invite computes its room's tag
and finds its entry; a client with no invite learns how many rooms there are and nothing it could
subscribe with. A room's **name** is in `hello` only when `SPOOL_TOKEN` is set: on an open Spool
anyone who connects reads `hello`, so there the name reaches members through the invite link alone.

> [!NOTE]
> Every member of a room shares one key, so a room is exactly as private as its invite, and there
> is no per-member identity or ban list. Removing someone means rotating their room: mint a new
> invite, set the new id, restart. Rooms are read at boot, so the restart reconnects every room's
> members, not just that one's. The old scope is no longer pinned and ages out on its TTL or under
> the watermark. The operator cannot moderate individual messages — that would need the key they do
> not have; the one lever is [send-side moderation](#-send-side-moderation), which covers the whole
> Spool and every room on it, and it is the members' clients that pull it. See
> [`SECURITY.md`](SECURITY.md#the-commons).

## 🛡 Send-side moderation

An operator who answers for the people on their Spool — a school, a workplace, a household — can
ask every client on it to screen what it sends. The Spool itself holds ciphertext, so it cannot
screen a message and never will; asking is the lever it has. Set `SPOOL_REQUIRE_MODERATION=true`
and `hello` carries one more field:

```
{ t: "hello", v: 1, min: 1, limits: {…}, powBits: 20, moderation: true }
```

A conforming client (spec §7.5) then runs its on-device content screen — the same text and image
classifier it already runs on everything it receives — on what its user *sends* into any scope this
Spool carries, and refuses what the screen flags, with no "send anyway". Off, or unset, the field is
omitted and nothing changes: clients still screen on receive and blur or collapse flagged content
behind tap-to-reveal, as their own setting says.

| | |
|---|---|
| **A request, not an enforcement** | The Spool sees sealed bytes and can neither check nor punish. A modified client can ignore the flag exactly as it can skip any sender-side check; what the flag buys is that every *conforming* client on the Spool refuses the same content the same way. Receive-side screening is what survives a hostile sender, and it stays the recipient's own — the flag never touches it. |
| **Strictest wins** | A sender seals a frame once and pushes identical bytes to every Spool it knows. If *any* Spool of a conversation asks for moderation, a conforming client withholds flagged content from all of them rather than fork the conversation by operator. |
| **Nothing on the data path** | No record, error code, close code or bound is added. An off Spool's `hello` is byte-identical to before this existed. |
| **Reloadable** | Announced in `hello`, so like `SPOOL_POW_BITS` a `SIGHUP` moves it for the next connection; a live client keeps the hello it negotiated. |

## 📞 TURN (experimental)

> [!WARNING]
> Experimental and off by default. Specified in knit-next `docs/SPOOL_PROTOCOL.md` §7.6; Knit's own
> client half is not in a shipped build yet.

Knit voice calls run peer to peer over WebRTC, and the 10–20% that cannot connect directly need a
relay. Set `SPOOL_TURN_SECRET` and the Spool hands short-lived relay credentials to clients that ask;
`docker-compose.turn.yml` runs [coturn](https://github.com/coturn/coturn) beside it, checking them
with the same secret. **The Spool never carries media**, and call setup is ordinary sealed traffic
in the conversation's own scope, so nothing else on the data path changes.

```
{ t: "hello", …, turn: true, turnHosts: ["us-west.turn.example:3478", "eu.turn.example:3478"] }
{ t: "turnreq", q: 7, host: "eu.turn.example:3478" }                          // client → Spool
{ t: "turncred", q: 7, urls: [...], user: "<exp>:<random>", pass: "…", exp: … } // Spool → client
```

`turnHosts` and `host` appear only with several TURN servers, or one with a UDP URL; `host` is
optional, and without it, or with one the Spool does not list, the answer is server 1's.

| | |
|---|---|
| **Names no conversation** | `turnreq` carries no scope, and the Spool does not ask. Holding *some* subscription is the whole gate, so the Spool never learns which conversation is calling. |
| **coturn's own scheme** | `pass = base64(HMAC-SHA1(secret, user))` — coturn's `use-auth-secret`. The suffix after `<exp>:` is random per issue, so a credential links to nothing. |
| **Budgeted, never struck** | `SPOOL_TURN_RATE` per connection, four times that per address; past it, `err rate` with `retryMs`. A refusal never counts toward the 4003 abuse close: redialling a dropped call is not abuse. |
| **Boot-only** | Each secret has to change in lockstep with its coturn's, so a `SIGHUP` reports every TURN setting as needing a restart. |
| **Never logged** | Neither half of a credential, nor the secret, reaches this daemon's log. |

Run it as the last overlay:

```sh
docker compose -f docker-compose.tls.yml -f docker-compose.tiny.yml -f docker-compose.turn.yml up -d
```

It needs **3478/udp, 5349/tcp** and the relay range **49160–49999/udp** open on the host; 443 stays
Caddy's. By default the Spool hands out one TURN URL per transport, `turn:…:3478?transport=udp` then
`turns:…:5349?transport=tcp` (knit-next `docs/SPOOL_PROTOCOL.md` S-7.6-25), because a phone opens an
allocation per network for every URL it tries; open 3478/tcp too only if you add a
`turn:…?transport=tcp` URL to `SPOOL_TURN_URLS`, which replaces the default as given. `turns:` on 5349 uses the certificate Caddy already holds for `SPOOL_DOMAIN`, and
a renewal is picked up in place (coturn's `SIGUSR2` reload) without dropping a call. coturn refuses to
relay to loopback, private, link-local and CGNAT addresses — without that list a credential holder
could reach the box's own network through it.

**On a public Spool, anyone can get a credential** — a subscription costs one proof-of-work stamp.
So the bound on what strangers can push through the box is coturn's quotas, not the credential:
`TURN_USER_QUOTA` (3 allocations per credential, or per ticket holder on a ticketed Spool — see
below), `TURN_TOTAL_QUOTA` (100), `TURN_MAX_BPS` (16000
bytes/s per session) and `TURN_BPS_CAPACITY` (1600000 bytes/s, 12.8 Mbps, for the whole relay). A
refreshed allocation lives on after its credential expires, so these caps are the real limit.
Prefer TURN on a tokened Spool.

### Several TURN boxes

One coturn box relays as many allocations as its `TURN_TOTAL_QUOTA` allows. To grow past that,
give the Spool up to three more boxes, `SPOOL_TURN_2_*` through `SPOOL_TURN_4_*`, each with its own secret and URLs (knit-next
`docs/SPOOL_PROTOCOL.md` S-7.6-48 to S-7.6-50; ADR 2026-10.u5f4). `hello`'s `turnHosts` names each
box by the `host:port` of its UDP URL, in server order. A phone measures every box from where it is
with a STUN Binding request and names the nearest in `turnreq`'s `host`. The Spool signs for that
box alone, with its secret, and answers with its URLs alone. A phone then allocates on one box per
call, so a second box adds capacity instead of mirroring the first. Budgets are shared: a credential
costs the same `SPOOL_TURN_RATE` whichever box it is for. `knit_spool_turn_issued_total{server="<n>"}`
and its ticketed twin count credentials per box, by number. The conformance check `turn-hosts-select`
asks for the last box and for one no Spool lists, and expects server 1's URLs for the second.
[`HOSTING.md`](HOSTING.md#several-turn-boxes) covers running them.

### Tickets and STUN

Two further switches, for an operator who sells TURN to some callers and not others (knit-next
`docs/SPOOL_PROTOCOL.md` §7.6; ADR 2026-10.bxsg), plus the choice of what else a ticket unlocks (ADR
2026-10.h6pt):

- **`SPOOL_STUN_URLS`** puts a credential-free `stun` list in every `hello`. A phone with no TURN of
  its own needs one: without it, it offers only private addresses, and a paying peer's relay —
  which admits peers by IP — cannot permit it. The TURN overlay points it at its own coturn.
- **`SPOOL_TICKET_KEY`** and **`SPOOL_TICKET_AUDIENCE`** make `hello` carry `ent`, and `turnreq` then
  needs an Ed25519 ticket presented once per connection with `{ t: "ent", q, tk, sig }`. With one,
  no subscription is needed, and the TURN username's suffix is derived from the ticket's daily
  pseudonym, so every credential one holder fetches shares it and `TURN_USER_QUOTA` counts the
  holder. A refusal is `err ticket` with a `msg` (`expired`, `audience`, …), never a strike.
- **`SPOOL_TICKET_GATES`** picks what a ticket unlocks on this one Spool, so paid and free callers
  share it instead of needing a Spool each. It defaults to `turn,cast`, what a key gated before the
  list existed. Adding `files` makes **sending** an attachment need the sender's own ticket minted
  with `--files`: each stored chunk is charged to that holder's daily budget,
  `min(ab, SPOOL_TICKET_ATTACH_BYTES)`, and a spent budget is refused `err ticket` with `msg`
  `budget`. A ticket's `am` lowers the largest attachment its holder may send, and a larger one is
  refused `size`. Another member's ticket never pays for an upload, and **receiving** never needs one.
- **`SPOOL_TICKET_TURN_RATE`** limits each holder's TURN credentials by its daily pseudonym, so
  one ticket copied to many addresses shares one budget. The per-connection and per-address
  limits still apply first, and a refusal is `err rate` with `retryMs`, never a strike.
- **`SPOOL_TICKET_KEY_NEXT`** rotates the issuer key without refusing anyone's ticket:
  1. set the new public key as `SPOOL_TICKET_KEY_NEXT` and `SIGHUP`, so both keys are accepted;
  2. once every Spool that takes these tickets has it, start signing with the new key;
  3. wait until the longest ticket signed by the old key has expired;
  4. move the new key into `SPOOL_TICKET_KEY`, unset `SPOOL_TICKET_KEY_NEXT`, and `SIGHUP`.

  No step touches a ticket a live connection already holds. Each step only changes which keys
  judge the next `ent`.
- **`SPOOL_TICKET_AUDIENCE_ALIAS`** renames a ticketed Spool without refusing anyone's ticket (ADR
  2026-10.zprp). A ticket names one host, and a client asks for one for the host it dialled, so
  while both names reach the Spool, set the new name as `SPOOL_TICKET_AUDIENCE` and the old one as
  the alias. Clear the alias once every client has moved, and before the old name is given to
  another Spool. Until then one account can hold a ticket for each name, and so two pseudonyms and twice the
  per-holder budgets.

The Spool holds only the public key. Until a storefront issues tickets, an operator can stand in:

```sh
knit-spool ticket-keygen                    # prints the signing key and SPOOL_TICKET_KEY=…
knit-spool ticket-mint --key-file ticket.key --aud spool.example.com --account alice
```

`ticket-mint` prints a `knit-ticket:v1:…` line for a test client; `--cast` adds the cast flag
([casts](#-casts-experimental)), `--files` the upload flag, `--ab BYTES` a smaller upload budget,
`--am BYTES` a smaller largest attachment, and `--no-turn` drops TURN. Conformance takes the same key:
`--ticket-key-file ticket.key --ticket-audience spool.example.com`, plus `--ticket-audience-alias` with
the old name on a Spool being renamed. `knit_spool_ticket_accepted_total`,
`knit_spool_ticket_refused_total{reason}`, `knit_spool_turn_issued_ticketed_total` and
`knit_spool_ticket_attach_bytes_charged_total` count what happened. The Spool learns each holder's
pseudonym and can link their connections within a day; it never logs a ticket or a pseudonym, and
the upload budgets live in memory only, so a restart resets them.

What a relay sees that a Spool does not: **both callers' IP addresses, and how long they talked**.
Never what was said — media is DTLS-SRTP end to end, keyed by fingerprints that ride in the sealed
call setup. Run by the same operator as the Spool, the two logs can be joined. See
[`HOSTING.md`](HOSTING.md#what-turn-costs) for the bandwidth.

## 📻 Casts (experimental)

> [!WARNING]
> Experimental and off by default. Specified in knit-next `docs/SPOOL_PROTOCOL.md` §7.7; Knit's own
> client half is not in a shipped build yet.

When two phones in a call have no radio path and no live one, the call becomes a walkie-talkie:
push to talk, a sealed chunk about every half second. Set `SPOOL_RATE_CASTS` and the Spool carries
those chunks. **A cast is fanned out to the scope's other live subscribers and never stored**. It
has no blob id, no digest entry, no tombstone, no listing and no row on disk. A phone that was not
connected when it went out never gets it, which is what a walkie means.

```
{ t: "hello", …, cast: { maxBytes: 2048, rate: 4 } }
{ t: "cast", scope, data }   // client → Spool: no q, never answered
{ t: "cast", scope, data }   // Spool → every other subscriber of the scope
```

| | |
|---|---|
| **Never answered** | A cast carries no `q` and draws no `ok` or `err`. A refused one is dropped and counted: not subscribed, over `maxBytes`, over budget, the commons, or casts off. `hello` states the bounds up front, so a client can keep to them unprompted. |
| **Never struck** | Past its budget a cast is just dropped. A flood still costs the record bucket every record does, and that bucket strikes. |
| **Never a slow consumer** | A subscriber whose outbound channel is full loses that cast and nothing else. It is not counted toward the 4003 slow-consumer close. |
| **Sold with tickets** | With `cast` in `SPOOL_TICKET_GATES` (the default once `SPOOL_TICKET_KEY` is set), a cast is carried only on a scope where some live subscriber holds a ticket with the cast flag (`ticket-mint --cast`). One payer unlocks the scope both ways, so a free friend can talk back. Without a ticket key, any subscriber may cast. |
| **Never logged** | No line names a cast or its scope. `knit_spool_casts_total`, `knit_spool_casts_fanned_total` and `knit_spool_casts_dropped_total{reason}` count them, and the status line shows `casts=+N`. |

What a Spool sees of a cast: that a conversation is in a walkie session, who talks when and for how
long, and both phones' addresses. It never sees what was said: each cast is sealed under the
conversation's key, and the chunk inside it is sealed again under the pair's ratchet. Client-side
padding hides chunk sizes. See [`HOSTING.md`](HOSTING.md#what-casts-cost) for the bandwidth.

## 🌐 Deploy

Picking a host first? [`HOSTING.md`](HOSTING.md) covers what a Spool needs from a box, which
providers fit, and which container platforms are the wrong shape for a long-lived WebSocket.

Would rather not run a box at all? [Knit Hosted](https://hosted.getknit.app) runs this same daemon
for you on hardware in the EU, pinned to a public release: your own hostname under
`spool.getknit.app` with its certificate renewed for you, and a token you can read, set or rotate
from a dashboard without dropping a device. Plans are priced by how many devices can connect at
once, paid a month at a time. The Spool is as blind there as it is on your box; what Knit Hosted
keeps about you is an email address, a billing reference and an instance id — no scope ids, no
connection logs, no client addresses. Knit works with either, or with several Spools at once.

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

Images go to two registries, and they are the same bytes: one multi-arch manifest, pushed to both.

| Registry | Image | Notes |
|---|---|---|
| GHCR | `ghcr.io/getknit/knit-spool` | Carries the build provenance attestation. No anonymous pull limit. |
| Docker Hub | `docker.io/getknit/knit-spool` | Shorter to type. Anonymous pulls are rate-limited. |

Both are `linux/amd64` and `linux/arm64`, so an Ampere or Graviton box, or a 64-bit Raspberry Pi,
pulls the same way an x86 VPS does.

```sh
docker pull ghcr.io/getknit/knit-spool:0.6.1
docker run --name knit-spool -p 9470:9470 -v spool-data:/data -e SPOOL_POW_BITS=20 \
    ghcr.io/getknit/knit-spool:0.6.1
```

Every release is tagged with its version, and a release that is not a prerelease also moves
`latest`. Pin the version in production, or a `@sha256:` digest for the strict form. `latest` moves
under you, and a restart on a moved tag brings back a daemon you never tested.

Every push to `main` publishes a development image too, once `check` and the conformance suite
have passed on it: `edge` follows the branch, and `sha-<short commit>` (`sha-e4159fd`) is that
commit's for good. Neither touches `latest`. An `edge` daemon reports its `-dev.<n>` development
version and the commit at `GET /source`, and it has cleared the same gates a release does — but
nobody has run it in production yet. Pull it to try a fix before it ships; pin a version to run a
Spool on.

The GHCR copy — release or `edge` — traces back to the workflow run and the commit that built it:

```sh
gh attestation verify oci://ghcr.io/getknit/knit-spool:0.6.1 --repo getknit/knit-spool
```

There is no equivalent command for the Docker Hub copy. The attestation travels over the OCI
referrers API, which Docker Hub supports unevenly, so it is pushed to GHCR alone. Verifying there
covers the Docker Hub image as well, since both names resolve to the same digest.

Building your own is the other route, and the one to take if you have modified the daemon:

```sh
docker build -t knit-spool .
docker run --name knit-spool -p 9470:9470 -v spool-data:/data -e SPOOL_POW_BITS=20 knit-spool
```

However the image arrives, it persists to the `/data` volume, runs as uid 65532, and carries a
`/healthz` HEALTHCHECK. [`Dockerfile`](Dockerfile) compiles from source;
[`Dockerfile.dist`](Dockerfile.dist) is what [the release workflow](.github/workflows/release.yml)
publishes: the same runtime stage over a distribution built ahead of time, which is how the arm64
image avoids an emulated compile.

## 📊 Operating

`GET /healthz` (liveness) and `GET /metrics` (Prometheus text; token-gated with `?k=` on private
Spools — the shipped proxy configs seal it off from the internet, so scrape it from inside your
network). The bearer token rides in the query string, so those configs also keep it out of proxy
access logs; do the same in any proxy of your own.

Exported: the build stamp (`knit_spool_build_info`, a labelled gauge carrying version and commit —
`count by (version) (knit_spool_build_info)` is what a fleet dashboard joins against), connections
(current + total) and the `SPOOL_MAX_CONNS` cap they are measured against, records, pushes, events,
PoW verifications, rate-limit hits, upgrades refused for capacity and for draining (counted apart),
sheds, bare scopes reclaimed, new scopes refused `quota` at `SPOOL_MAX_SCOPES`
(`knit_spool_new_scopes_quota_refused_total`: a conversation that could not start here, so size
the Spool up when it rises), attachment chunks stored, egress bytes, scopes held, live bytes, the
store file's size on disk (`knit_spool_store_file_bytes`, SQLite only), and `err` counts by code.
A Spool running a [commons](#-the-commons) also exports each room's subscriber count, its pushes,
and how often a push budget throttled it, labelled `room="1"` to `room="8"` by the room's number,
never by its id or name; the throttle count also says which budget, `budget="room"` or
`budget="spool"`.

> [!NOTE]
> **On a metered link, watch `knit_spool_egress_bytes_total`.** Fan-out means one push leaves as
> (subscribers − 1) copies, so egress is a multiple of ingest that the record and push counters
> cannot tell you the size of — and on the cheap VPS tiers the monthly transfer allowance binds
> long before CPU or memory does. It counts CBOR record payload, excluding WebSocket and TLS
> framing, so it runs a few percent under the figure your provider bills.

### Rotating the token

`SPOOL_TOKEN` alone makes rotation a cutover: change it and every client is locked out until it
updates. `SPOOL_TOKEN_NEXT` is a second credential the Spool accepts alongside the first, so the
rotation becomes four steps with nothing refused at any point:

```sh
SPOOL_TOKEN=old SPOOL_TOKEN_NEXT=new   # 1. both work; hand `new` out
                                       # 2. wait for clients to migrate
SPOOL_TOKEN=new                        # 3. promote; `old` stops working here
                                       # 4. issue the next one as NEXT when it is time
```

Both are accepted for as long as both are set, and anything that is neither is still refused — a
Spool mid-rotation accepts two secrets, not any secret. Step 3 is the one that ends the rotation,
so do not leave `SPOOL_TOKEN_NEXT` set indefinitely: a credential you have stopped tracking is
still a credential that works.

Each step is a `SIGHUP` away — see below — so a rotation costs no client a reconnect.

### Reloading configuration

`SIGHUP` re-reads the file named by `SPOOL_RELOAD_FILE` and installs it without restarting, so a
quota or a credential can change while connections stay up.

The file is `KEY=value` lines in the environment's own syntax, `#` comments and blanks skipped, and
it is layered **over** the environment: what the file names wins, what it omits falls back to what
the process booted with. Unsetting a value therefore means deleting its line, not writing an empty
one.

```sh
printf 'SPOOL_MAX_CONNS=500\nSPOOL_TOKEN=old\nSPOOL_TOKEN_NEXT=new\n' > /data/reload.env
docker exec knit-spool kill -HUP 1     # compose: docker compose exec -T spool kill -HUP 1
```

**Not `docker kill --signal=HUP`.** It delivers the same signal, and one thing more: *any*
`docker kill` marks the container manually stopped, whatever signal it carries and even when the
process keeps running. Under `restart: unless-stopped` — which every compose file here ships —
Docker then declines to start it after a reboot, silently, with `RestartCount=0` to show it never
tried. The Spool that reloaded perfectly is simply missing the next morning. `restart: always` is
not a fix either: it ignores that flag at boot, but a `docker kill` still suppresses the ordinary
restart-on-exit, so a signal that did stop the process would leave the Spool down until the next
reboot rather than back in seconds.

`docker exec … kill` sends the signal and touches none of that bookkeeping. It reaches PID 1
because this daemon installs handlers for `HUP`, `USR1` and `TERM` — a namespace init ignores a
signal sent from inside it unless there is a handler, which is also why `kill -KILL 1` from in
there does nothing.

The environment cannot be the source, which is why the file exists: `System.getenv()` is fixed when
the process starts, so a container's variables cannot change without recreating it — the reconnect
this feature exists to avoid.

**What a reload can move**, and when it bites:

| | Takes effect |
|---|---|
| `SPOOL_TOKEN`, `SPOOL_TOKEN_NEXT`, `SPOOL_METRICS_TOKEN`, `SPOOL_MAX_CONNS`, `SPOOL_MAX_BYTES` | immediately — read on every use |
| `SPOOL_POW_BITS`, `SPOOL_MAX_RECORD`, `SPOOL_MAX_PULL`, `SPOOL_MAX_AGET`, `SPOOL_REQUIRE_MODERATION`, `SPOOL_STUN_URLS`, `SPOOL_TICKET_KEY`, `SPOOL_TICKET_AUDIENCE`, `SPOOL_TICKET_GATES` | on the next connection — they are announced in `hello`, and a live client keeps the contract it negotiated |
| `SPOOL_TICKET_ATTACH_BYTES` | immediately — read on every gated `aput`; what holders already spent stays spent |
| `SPOOL_TICKET_KEY_NEXT`, `SPOOL_TICKET_AUDIENCE_ALIAS` | immediately — read by every `ent`; a ticket a connection already holds stays held |
| `SPOOL_TICKET_TURN_RATE` | immediately — read on every ticketed `turnreq`; a changed rate refills each holder's bucket |
| `SPOOL_RATE_RECORDS`, `SPOOL_RATE_PUSHES` | on the next connection — the bucket is per connection |
| `SPOOL_RATE_CASTS`, `SPOOL_MAX_CAST` | on the next connection, which is told them in `hello`; but `SPOOL_RATE_CASTS=0` stops every cast at once |
| `SPOOL_RATE_CASTS_TOTAL` | immediately — the Spool-wide bucket is rebuilt |
| `SPOOL_RATE_NEW_SCOPES`, `SPOOL_MAX_CONNS_PER_IP` | on the next connection from an address (IPv6: a /64) the Spool has not seen recently |

**What it cannot**, because something is already built from it — the store from the blob and scope
limits, the sweeper and status line from their cadences, the commons rooms from their ids, the
listener from the port: `SPOOL_PORT`, `SPOOL_MAX_BLOB`, `SPOOL_MAX_FRAMES`, `SPOOL_MAX_TTL_MS`,
`SPOOL_MAX_SCOPES`, `SPOOL_MAX_ATTACH_BYTES`, `SPOOL_MAX_A_CHUNK`, `SPOOL_SWEEP_MS`,
`SPOOL_STATUS_MS`, `SPOOL_TRUST_PROXY`, `SPOOL_COMMONS_*`, every `SPOOL_TURN_*` setting (server 1's
and `SPOOL_TURN_<n>_*` alike), `SPOOL_SOURCE_URL`, `SPOOL_DATA_DIR`.

Naming one of those in the file is not fatal — it is logged as ignored and everything else still
applies, so a stale line cannot block a later change. A file that is missing, unreadable or invalid
leaves the running configuration untouched and logs why; reloads are retryable, and a Spool that
dropped its quotas to a half-written file would not be.

### Draining

`SIGUSR1` closes the door to new connections and leaves the live ones alone — what a rolling
upgrade needs between "serving" and "stopped", since a plain stop closes every session at once
and sends every client back on the same second:

```sh
docker exec knit-spool kill -USR1 1   # drain: new upgrades get 503 + Retry-After, live conns served
docker exec knit-spool kill -USR1 1   # again to lift it
```

`docker exec`, not `docker kill --signal=USR1`, for the reason under reloading above — a
`docker kill` of any kind is what stops the container coming back after a reboot, and drain is
exactly the thing you reach for right before one.

Shift traffic to a sibling Spool, wait for the connection count to fall, then stop. `/healthz`
deliberately keeps answering `200` throughout — the container HEALTHCHECK and compose's
`service_healthy` gate both probe it, and a drain that failed it would restart the container in
the middle of the drain. Watch `knit_spool_drain_refused_total`, which is counted apart from
`knit_spool_conns_refused_total` so a planned drain never looks like a box out of room, and
`draining=yes` on the status line.

### The status line

Every `SPOOL_STATUS_MS` (5 min by default; `0` switches it off) the daemon logs one line — the
`docker logs -f` view of a Spool with no Prometheus in front of it:

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

Routine socket churn — a client that sleeps, loses its network, or dies without closing — is a
`ping timeout` drop, and those log at `DEBUG`, so the default log stays quiet under a fleet of
mobile clients. The `conns` gauge above is where churn shows up instead. To watch the individual
drops, level up that one logger rather than the root:

```xml
<logger name="app.getknit.spool.server.SpoolServer" level="DEBUG"/>
```

Ktor's own loggers are pinned at `INFO` in the shipped `logback.xml` and do not follow
`SPOOL_LOG_LEVEL`: below that, Ktor traces the request URI at the start of every WebSocket session,
and on a private Spool the URI carries the bearer token in `?k=`. Nothing Ktor says below `INFO` is
about the Spool. If you override the logging config, keep that pin.

## 🧪 Conformance

Validate any Spool implementation — this one or a third party's — over a live connection:

```sh
./gradlew :conformance:installDist
conformance/build/install/knit-spool-conformance/bin/knit-spool-conformance \
    wss://spool.example.com/spool/v1 \
    [--token T | --token-file PATH] [--timeout-ms 10000] [--pow-limit 24] [--destructive] \
    [--commons-invite knit-commons:v1:… (once per room)]
```

TAP on stdout, a MUST tally on stderr. Exit **0** = every MUST check passed (skips and advisory
shortfalls don't fail the run), **1** = a MUST check failed, **2** = bad arguments or no handshake
at all, **3** = nothing failed but something could not be judged, because the transport broke or
this tool hit a bug. An inconclusive run is not a passing one, so treat **3** the way you treat
**1** in CI. The attachment checks skip themselves against a Spool that advertised no §7.3 limits —
which is exactly the client behaviour the spec requires. `--destructive` enables the quota and
rate-limit checks; they fill real capacity, so run them against Spools you operate.
`--commons-invite` takes a room's invite, once per room, and enables the two checks that need to be
in it — that each room's bounds are pinned to its own advertised entry and that a push fans out —
which otherwise skip; the advertisement check needs no invite and always runs. CI runs the whole suite against the freshly
built daemon on every pipeline (`conformance-selftest`).

> [!WARNING]
> Prefer `--token-file` against a Spool you care about. `--token` puts the bearer token in argv,
> where every local user can read it out of `ps` for the life of the run, and most shells record it
> in history. The file is read once and may be mode 0600.

## 🔨 Build and test

JDK 21 — the Gradle wrapper pins Gradle 9.8.2.

```sh
./gradlew check                 # compile + ktlint + detekt + every suite
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

The merged report is the one to read. `:conformance`'s checks execute inside `:daemon`'s test
suite — `ConformanceSelfTest` runs the whole suite against an in-process server — so they are
credited only when the three modules are reported together, and `:conformance:koverHtmlReport` on
its own reads a few percent by construction. The `conformance-selftest` CI job runs the same suite
against the packaged binaries; it proves the shipped artefacts rather than adding to the number.

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
for security problems. That file also documents what a Spool is trusted with, and which properties
are intentional trade-offs (visible traffic metadata, TLS terminating at a proxy, PoW off by
default) rather than findings.

## 💛 Support

Knit Spool is free and open source, with no ads, no tracking, and no paid edition — the daemon here
is the same one [Knit Hosted](https://hosted.getknit.app) runs. If you run a Spool yourself and it's
been useful, you can leave a one-off tip on Ko-fi or set up a recurring one on Liberapay:

[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-leave%20a%20tip-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/knit)
[![Support on Liberapay](https://img.shields.io/badge/Liberapay-give%20recurring-F6C915?logo=liberapay&logoColor=black)](https://liberapay.com/zaventh/)

Tips are optional and buy no special treatment — Knit Spool is AGPLv3 and stays that way. Reporting
bugs, running a public Spool, and telling people it exists help just as much.

## 📄 License

Knit Spool is free software, licensed under the **GNU Affero General Public License v3.0 or later**
([`LICENSE`](LICENSE)).

AGPL rather than GPL because a Spool is a network service handling other people's ciphertext:
§13 obliges anyone running a **modified** version that other people's clients connect to to offer
those users the source of *their* version. The daemon serves that offer itself, so it cannot go
stale: `GET /source` returns the running version, its commit, and a source URL. Run a fork and you
set [`SPOOL_SOURCE_URL`](#-configuration) to your own repository — the endpoint is unauthenticated
on purpose, because an offer nobody can read is not an offer. Publish your fork and say where it is.

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

Knit Spool depends on third-party open-source libraries, all under AGPL-compatible licenses; see
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) for the component list and their licenses.

The Knit app is a separate GPL-3.0-or-later codebase; the two share a protocol spec and no code.

---

<div align="center">
<sub>AGPL-3.0-or-later — <code>app.getknit.spool</code></sub>
</div>
