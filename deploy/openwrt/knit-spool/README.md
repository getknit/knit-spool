# knit-spool for OpenWrt

The package definition behind the `.apk` (OpenWrt 25.12) and `.ipk` (24.10) a release attaches
(ADR 2026-11.zmc3). It installs the prebuilt aarch64 musl binary from the release archive
(ADR 2026-11.bxce) and wraps it in a procd service; nothing is compiled. The OpenWrt One is the
first box it is for; any aarch64 OpenWrt with a disk to put the store on will do.

| Path on the box | What |
| --- | --- |
| `/usr/lib/knit-spool/knit-spool`, `libsqlitejdbc.so` | the binary and its SQLite library; `/usr/bin/knit-spool` links to it |
| `/etc/init.d/knit-spool` | procd: `start`, `stop`, `restart`, `reload` (a `SIGHUP`), `check` |
| `/etc/config/knit-spool` | UCI, the box side: `enabled`, `port`, `bind`, `data_dir`, `env_file`, `heap_mb`, `log_level`, `nofile` |
| `/etc/knit-spool/env` | the daemon's reload file: every other `SPOOL_*` setting, as `KEY=value` lines |
| `/etc/nginx/conf.d/knit-spool.conf.example` | TLS termination with `nginx-ssl` and an `acme` certificate |

One sentence decides which file a setting goes in: UCI is what the box decides and becomes the
daemon's environment; the env file is everything the daemon itself understands, and
`service knit-spool reload` re-reads it without dropping a connection. A UCI change needs `restart`.

Install (the SDK signs with a key the box does not know):

```sh
apk add --allow-untrusted ./knit-spool-<version>-r1.apk          # OpenWrt 25.12
opkg install ./knit-spool_<version>-1_aarch64_cortex-a53.ipk     # OpenWrt 24.10
```

Then a token in `/etc/knit-spool/env`, `data_dir` on a disk (or empty for the in-memory store),
`enabled '1'`, `service knit-spool check`, `service knit-spool start`, `logread -e knit-spool`.
HOSTING.md's "OpenWrt One" section has the whole recipe, nginx and acme included.

Build by hand from an OpenWrt SDK checkout as the Makefile's header says; the release workflow does
the same with the archive pre-seeded into `dl/`.
