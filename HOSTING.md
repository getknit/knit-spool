# Hosting a spool

Where to run one. This is the host-shopping half: what a spool needs from a box, what your bill
actually depends on, and which providers fit. The commands are in [`README.md`](README.md#-deploy).

A spool is a small favour you do for a handful of people. It holds sealed frames it can't read, for
scope ids it can't map to anyone, and forgets them on a timer. Clients multi-home across several
spools and union what comes back, so nothing is riding on yours in particular: if it dies, any
member who still has the frames re-pushes them somewhere else. Buy accordingly. The cheapest tier
that stays up is usually the right one, and if you want redundancy the answer is a second spool
somewhere else, not a bigger box.

## Contents

- [What a spool needs from a host](#what-a-spool-needs-from-a-host)
- [What a 1 GB box actually holds](#what-a-1-gb-box-actually-holds)
- [What your bill depends on](#what-your-bill-depends-on)
- [Providers](#providers)
- [Linode, where the canonical instance runs](#linode-where-the-canonical-instance-runs)
- [Poor fits](#poor-fits)
- [Home and self-hosted](#home-and-self-hosted)
- [Architecture](#architecture)
- [Before you commit](#before-you-commit)
- [If you modify it](#if-you-modify-it)

## What a spool needs from a host

| Need | Figure | Why |
|---|---|---|
| RAM | 1 GB | The JVM is pinned to `-Xmx256m`; ~450 MB RSS once metaspace, threads, and CIO's direct buffers are counted. A 512 MB box leaves no headroom for a spike, and on a box that size the OOM killer's next pick is often sshd. |
| CPU | 1 shared vCPU | The store runs on one thread and sustains order-1000 pushes/s on a $5 tier. TLS and PoW verification are the only other work. CPU won't be your constraint. |
| Disk | 10–25 GB | Payload is bounded by `SPOOL_MAX_BYTES` (256 MB default) and the image is ~150 MB. The rest is logs, so cap them. |
| Transfer | 1 TB/month, minimum | The one to shop on. See below. |
| Network | Public IPv4, a DNS name, inbound :80 and :443 | ACME needs :80 to issue, clients need :443. IPv6 is good to have but not enough on its own; plenty of mobile networks still want the A record. |
| Uptime | Whatever the tier gives you | Clients treat a missing spool as a missing spool. Don't buy an SLA for this. |

Anything sold as a 1 GB VPS for $4–6/month clears that. So does a spare machine at home, with the
caveats [below](#home-and-self-hosted).

## What a 1 GB box actually holds

**About 2,400 concurrent clients, and the thing that runs out is memory in the reverse proxy.**
These are measurements off a JVM pinned to one core with `-XX:ActiveProcessorCount=1` and a real
`caddy:2-alpine` terminating TLS in front, scaled to the tier by the ~1.8× single-core gap between
the test machine and a Nanode:

| Per client | Cost | Notes |
|---|---|---|
| Daemon | 41 KB of JVM heap | An idle WebSocket: two Ktor CIO byte channels, the frame channels, and the `Conn` bookkeeping. Off-heap is noise — NMT puts direct buffers under 1 MB in total. |
| Caddy | ~110 KB while connections are arriving, ~41 KB settled | Two 32 KB copy buffers plus TLS state per proxied socket, and Go lets the burst's garbage pile on top unless `GOMEMLIMIT` says otherwise. |

Everything else has an order of magnitude in hand at that number:

| Resource | Measured | Where 2,000 clients land |
|---|---|---|
| Store thread | ~4,000 pushes/s | 2,000 clients at a message a minute is 33/s |
| Sweeper | ~26 µs per scope, on the store thread | 4096 scopes is a ~110 ms stall every 5 min |
| Transfer | 1 TB/month = 386 KB/s | ~14 GB/month of sealed text; ~230 GB even at 16 KiB a frame |
| Disk | ~1.2× payload, ~160 B per scope row | 8 GiB of payload is ~10 GB of the 18 GB free |

Two failure modes are worth knowing before you meet them. Caddy at the old 128 MB ceiling was
OOM-killed at ~1,100 connections, and `restart: unless-stopped` then walks every client back into
the same wall. And the daemon does not OOM when its heap fills — it full-GCs every 700 ms for 190 ms
at a time, which looks like a network problem and is not one. Both are why
[`deploy/docker-compose.tiny.yml`](deploy/docker-compose.tiny.yml) splits the box's ~640 MB of
container budget 352/288 and targets ~2,000 rather than the ~2,400 the hardware will technically do.

Reach for a second spool before a bigger box. Two $5 boxes in different regions are worth more to
the people using them than one box with twice the RAM, because the failure they protect against is
the box being gone, not the box being full.

## What your bill depends on

Egress. Every push fans out to (subscribers − 1) copies, so what leaves the box is a multiple of
what arrives, and the multiplier is the size of the conversation. Metered transfer binds long before
CPU does, and — once attachments are in play — before memory does too.

The arithmetic is easy: 1 TB/month is about 386 KB/s sustained outbound. That's an enormous number
of sealed text frames and a very ordinary number of attachments, which is why the blob and
attachment ceilings — not the box — are the knobs that set the bill.

Compare providers on three things, in this order:

1. Included transfer. 1 TB is the common floor; some hosts include 20 TB at the same price.
2. The overage rate, which is where a cheap tier gets expensive.
3. What happens when you hit the cap: billed overage, throttling, or a suspended instance. Worth
   knowing which one before it happens.

> [!TIP]
> Watch `knit_spool_egress_bytes_total` in `/metrics` from the first week. It counts CBOR record
> payload and excludes WebSocket and TLS framing, so it reads a few percent under what your provider
> bills — close enough to spot a trend before the invoice does.

## Providers

All of these run the container fine. Prices and allowances are what they were in August 2026 and
providers change them without asking, so treat the table as a shortlist to check rather than a
quote.

| Provider | Entry tier | Transfer | Notes |
|---|---|---|---|
| [Linode (Akamai)](https://www.linode.com/lp/refer/?r=9ff78b194fe5bdf1caebb29664229d5cdbe821af) | Nanode 1 GB, ~$5/mo | 1 TB | Where the canonical spool runs; see [below](#linode-where-the-canonical-instance-runs). |
| Hetzner Cloud | CX22, ~€4/mo | 20 TB | Far more transfer per euro than anything else here, and 4 GB of RAM at the entry price. EU and US locations; the US regions bill IPv4 separately. |
| DigitalOcean | 1 GB droplet, ~$6/mo | 1 TB | Good docs, no surprises. The $4 512 MB tier is under spec, so skip it. |
| Vultr | 1 GB, ~$5/mo | 1–2 TB | The widest region list of the group, including places the others don't sell. |
| OVHcloud / Scaleway | ~€4/mo | Unmetered or generous | Cheap EU capacity. Unmetered here means a bandwidth ceiling rather than a byte cap, which suits this workload. |
| Oracle Cloud Free Tier | Ampere ARM, free | 10 TB | Free, and the allowance is real. ARM capacity is frequently unavailable to new accounts, idle instances can be reclaimed, and signup verification fails for a lot of people. Worth trying, not worth planning around. See [Architecture](#architecture) for the ARM caveat. |

All of them work. If you'd rather have it decided for you: Hetzner for the most headroom per euro,
Linode if you want to run what the reference deployment runs.

Region matters more than which company you pick. A spool exists to be reachable when two phones
can't reach each other directly, so put it somewhere with a decent path to the people using it — and
if the group already has one spool, put yours on a different provider or in a different region.
Overlap without agreement is the whole redundancy story.

## Linode, where the canonical instance runs

The reference spool, `wss://lax.spool.getknit.app/spool/v1`, is a $5 Linode Nanode (1 GB, 1 TB
transfer) in `us-lax`, behind Caddy with an automatic Let's Encrypt certificate. Every sizing figure
above came off that box, as did
[`deploy/docker-compose.tiny.yml`](deploy/docker-compose.tiny.yml), which exists because the box has
1 GB of RAM and nothing else to spend it on.

If you're signing up for Linode anyway, this project has a referral link:

<https://www.linode.com/lp/refer/?r=9ff78b194fe5bdf1caebb29664229d5cdbe821af>

New accounts get $100 in credit through it, and once an account has been active for a while the same
program credits the one that pays for the canonical spool's hosting. Both halves are Linode's
program rather than something this project negotiated, and the amounts and time windows are theirs
to change, so the linked page is the authority on the current terms.

Using it costs you nothing over the normal price and it covers a $5/month box that anyone can point
a client at. Nothing in knit-spool works better on Linode, though, and a spool you run somewhere
else is worth more to this project than a referral is.

## Poor fits

Not everything that runs containers runs a spool. The protocol wants one long-lived WebSocket per
client, held open for hours, plus a durable disk. That rules out a fair amount of the modern hosting
market:

- Serverless and edge functions (Lambda, Cloud Run's request model, Workers, Vercel Functions). No
  long-lived inbound WebSocket, no local disk that survives the request, and per-request billing
  against connections measured in hours.
- Scale-to-zero PaaS. Suspending an idle instance drops every subscriber's connection, and on the
  platforms that also discard the filesystem it takes the SQLite database with it. If your host has
  a scale-to-zero setting, turn it off.
- Free tiers that sleep, for the same reason.
- Container hosts with ephemeral disk. `SPOOL_DATA_DIR` wants a real volume. Without one the daemon
  still runs — in memory — but every restart drops every frame, which is legal per the spec and
  annoying in practice.
- Cloudflare's proxy in front of it. The orange cloud terminates WebSockets on its own idle timeouts
  and isn't what the shipped proxy configs expect. Cloudflare DNS is fine; Cloudflare proxying is a
  support burden you don't need. Tunnels carry the same caveat, plus WebSocket-proxy terms worth
  reading first.

## Home and self-hosted

A spare mini PC, an old laptop, or a NAS running Docker will do this happily; a 1 GB VM's worth of
resources isn't hard to find at home. What you take on:

- Residential ISP terms often forbid inbound servers, and CGNAT means no inbound at all on a lot of
  connections. Check both before planning around it.
- A dynamic IP needs dynamic DNS, and clients holding the old address reconnect the slow way.
- Uptime is yours: power cuts, the reboot you forgot, the router firmware update. That's survivable,
  since clients treat a missing spool as a missing spool, but be straight about it with whoever else
  is relying on the box.
- Exposing :443 from your home network deserves some thought. The daemon serves plain WebSocket and
  expects TLS at a reverse proxy, so put the proxy on the box rather than forwarding the port
  straight through.

Worth weighing first: the usual reason to self-host is keeping your data off someone else's disk,
and a spool doesn't hold your data. It holds ciphertext it can't open, for scope ids it can't map to
anyone, and drops it on a timer. The design already assumes the spool is untrusted, so hosting it
yourself buys less privacy here than it would for nearly anything else you'd run at home.

## Architecture

Published images are multi-arch — `linux/amd64` and `linux/arm64` — so Oracle's Ampere, Graviton,
and a 64-bit Raspberry Pi pull and run without building anything. knit-spool is pure JVM, so both
architectures carry the same bytecode over the matching `eclipse-temurin` JRE base.

If you build from source instead, the Gradle build stage wants more memory than a 1 GB box has:
build on a machine with room and move the result over. The README covers how.

## Before you commit

- [ ] 1 GB of RAM or more, on a plan that isn't oversubscribed into uselessness.
- [ ] Included transfer you're comfortable with, and you've read the overage rate.
- [ ] A public IPv4 with inbound :80 and :443, and a DNS name already pointing at it. ACME issuance
      fails otherwise.
- [ ] A persistent volume for `SPOOL_DATA_DIR`, or a deliberate choice to run in memory.
- [ ] A region with a sane path to the people who'll use it, and ideally not the region their other
      spool is in.
- [ ] Log rotation capped, so an access log can't fill a 25 GB disk.

## If you modify it

knit-spool is AGPL-3.0-or-later. Running it unmodified imposes nothing on you. Running a modified
version that other people's clients connect to obliges you to offer those users the source of your
version, so publish the fork and say where it is. The [license section](README.md#-license) has the
reasoning.
