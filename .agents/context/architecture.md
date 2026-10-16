# Architecture

A Spool is a blind store-and-forward relay for Knit: phones push sealed frames to every Spool
they know, and members subscribe and union what comes back. There is no Spool-to-Spool link.
The README's "How it works" section has the diagram.

## Modules

| Module | Artifact | What |
| --- | --- | --- |
| `:protocol` | library | Records, PoW, digest (spec §2, §6.3, §7, §8). No server code |
| `:daemon` | `knit-spool` | The reference daemon: WSS server, in-memory and SQLite stores, rate limits, ops |
| `:conformance` | `knit-spool-conformance` | CLI that validates any live Spool over WebSocket (TAP) |

Dependency arrows: `:daemon` and `:conformance` both depend on `:protocol`. `:daemon`'s tests
run the conformance suite in process (`ConformanceSelfTest`); the main source sets never
cross.

## Elsewhere in the tree

- `deploy/`: Compose files (tiny, TLS, TURN overlays), Caddy and nginx configs, coturn.
- `config/detekt/`: the detekt overlay; every departure from a default carries its reason.
- `.githooks/`: DCO trailer and ADR router check.
- `bench/`: local load-test scratch, gitignored.

## Operator and security docs

- [`HOSTING.md`](../../HOSTING.md): sizing, providers, upgrade without a reconnect storm.
- [`SECURITY.md`](../../SECURITY.md): what a Spool is trusted with, known limits, hardening.
- README sections: configuration table, the commons, send-side moderation, TURN, casts,
  operating.
