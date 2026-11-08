---
id: "2026-11.zmc3"
slug: openwrt-gets-a-procd-service
title: "OpenWrt gets a procd service around the prebuilt binary, bound to loopback behind nginx"
date: 2026-11-07
topics: [deploy, openwrt, config]
---

# ADR 2026-11.zmc3 — OpenWrt gets a procd service around the prebuilt binary, bound to loopback behind nginx

Status: Proposed (2026-11-07)

**Context:** ADR 2026-11.bxce gives OpenWrt a binary it can run. It does not give it a service.
OpenWrt's shape is procd and UCI: an `/etc/init.d/<name>` script that reads `/etc/config/<name>`,
runs the program as a dedicated user, respawns it, sends its stdout to logd, and reloads it on
`service <name> reload`. The daemon's shape is sixty-odd `SPOOL_*` environment variables (the
README's table), a reload file that `SIGHUP` re-reads and that boot layers over the environment
(ADR 2026-10.hnqs), and a listener that until now always bound every interface. Three things about
the device bear on how those meet. The listener is plaintext and the token rides the URL, so on a
router that also serves the LAN, `0.0.0.0` puts a credential on the wire for anyone on the LAN who
tries `ws://`. The root filesystem is 256 MB of SPI NAND under an overlay, and SQLite's WAL churn on
every push is the wrong workload for it; the M.2 slot is where a store belongs. And the current
stable release, 25.12, installs `.apk` packages while the previous one, 24.10, installs `.ipk`,
with apk's version grammar refusing `0.9.0-rc.1`.

The obvious design is a UCI option per `SPOOL_*` variable. That is a second parser over the same
contract, in shell, which drifts from `KNOWN_VARS` the first time either side moves.

**Decision:** An in-repo package under `deploy/openwrt/knit-spool/`, consumable as a one-package
feed, installs the native archive and wraps it in procd. The daemon gains `SPOOL_BIND`.

- **UCI for the box, the env file for the daemon.** `/etc/config/knit-spool` holds what the box
  decides: `enabled`, `port`, `bind` (default `127.0.0.1`), `data_dir`, `env_file`, `heap_mb`,
  `log_level`, `nofile`. The init script turns those into the environment. Every other `SPOOL_*`
  setting lives in `/etc/knit-spool/env`, which the script hands to the daemon as
  `SPOOL_RELOAD_FILE`: boot layers it over the environment, and `service knit-spool reload` is a
  `SIGHUP` that re-reads it. The script never parses a `SPOOL_*` line itself. A UCI change needs
  `restart`; there is deliberately no UCI reload trigger, because a HUP would re-read the file,
  not UCI.
- **`SPOOL_BIND`**, a literal IPv4 or IPv6 address, default `0.0.0.0` (nothing changes for any
  existing deployment), boot-only. The package defaults it to `127.0.0.1` and puts nginx-ssl with
  an acme certificate in front, the shape the compose files have with Caddy; Caddy is not in
  OpenWrt's feeds and uhttpd cannot proxy. A name is refused, not resolved.
- **Data on an external disk, or nowhere.** An empty `data_dir` means the in-memory store, with
  `SPOOL_MAX_BYTES` defaulted by the script to 48 MiB so frames cannot fill a 128 MB heap before the
  watermark acts; the env file can override it. A set `data_dir` is created and owned by the
  service user, and a `logger` warning fires when it sits on the NAND overlay.
- **procd:** a dedicated `knit-spool` user and group (`USERID`), `limits nofile`, `respawn`,
  `stdout` and `stderr` to logd, `term_timeout 15`, `-Xmx${heap_mb}m` and the sqlite library path
  as the first arguments (the native executable consumes them before `main`), and an extra `check`
  command that runs `knit-spool check` in the service's exact environment.
- **Release assets, not a feed yet.** The release workflow builds the `.apk` (OpenWrt 25.12 SDK) and
  the `.ipk` (24.10 SDK) from the archive and attaches them, for stable versions only. The SDK signs
  with an ephemeral key, so the install is `apk add --allow-untrusted`.

The alternatives: a UCI option per variable (the drift above); a wrapper shell script instead of the
`/usr/bin/knit-spool` symlink (one more place for the environment to be wrong); `ujail` now (worth
doing, once the service has run on a device for a while); publishing to `openwrt/packages` (that
feed builds from source, and this installs a prebuilt binary).

**Consequences / limits:** An operator reads two files, and the rule for which is which is one
sentence. `apk add --allow-untrusted` is a line in the release notes until a signed project feed
exists. The in-memory default forgets every frame on a restart or respawn, by design: the
alternative was a store on the NAND by default. Lines in `logread` carry two timestamps, logback's
and logd's. `term_timeout 15` fits the sequential 1001 closes of a home spool: `stop()` closes
sessions one at a time with a per-session timeout before the engine's grace period, so a router
holding thousands of connections needs more, raised with `SPOOL_MAX_CONNS`. Prereleases ship the
archive and no package. The device pass on 2026-11-08 (an OpenWrt One on 25.12.5) installed the
`.apk`, ran the service as its user, reloaded a token live and passed the conformance suite over
loopback, and found two things. OpenWrt's packaging check compares every `NEEDED` entry with what
the package and its dependencies install, and the binary's libc is `libc.musl-aarch64.so.1`,
Alpaquita's SONAME, where OpenWrt writes `libc.so`; musl's loader answers any `libc.*` name with
itself, so the Makefile declares that one name in `extra_provides` and nothing else changes. And
procd's `no_new_privs` is a flag it passes to ujail, inert without a jail, so the script no longer
claims it; confinement is the ujail follow-up above. The exact nginx, acme and firewall steps are
still to be written into HOSTING.md from a device with a public name, and this ADR moves to
Accepted with them.

The trap is a `SPOOL_*` name in the init script or the example env file that the daemon does not
read: the daemon warns about it on every boot and the setting silently never happens. Guards:

- `OpenWrtPackageTest`: every `SPOOL_*` name in `knit-spool.init` and `env.example` is in
  `KNOWN_VARS`; the script sets `SPOOL_RELOAD_FILE`; the UCI default for `bind` is `127.0.0.1`.
- `ProxyConfigTest` lists the shipped nginx snippet among the configs that seal `/metrics`.
- `ConfigTest.bindDefaultsToEveryInterfaceAndAcceptsOnlyLiterals`, and `EnvContractTest`, which
  moved to `0.9.0-dev.3` with the variable (ADR 2026-10.8ymn).
- The `openwrt-package` release job builds both package formats from the archive.
