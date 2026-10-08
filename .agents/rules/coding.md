# Coding rules

Invariants for any change to `:protocol`, `:daemon` or `:conformance`. The reasoning behind
each lives in [`CONTRIBUTING.md`](../../CONTRIBUTING.md); this file is the checklist.

## The spec wins

- The normative contract is `docs/SPOOL_PROTOCOL.md` in the Knit repo. If the daemon and the
  spec disagree, the daemon is wrong. Behavior the spec does not describe is out of scope.
- `SpecVectorTest` pins the §13 vectors byte for byte. A change that breaks them is a bug in
  the change: never re-record the vectors to make a diff pass.
- `:conformance` depends on `:protocol` only, never on `:daemon`. A check that can only pass
  against this server does not belong there.

## Blindness

- A Spool sees scope ids, blob ids, ciphertext, sizes and timing, and nothing else. Do not log,
  persist, export or derive node ids, plaintext, rosters or delivery facts.
- The bearer token rides in the query string. Keep it out of every log line.

## Dependencies and configuration

- The shipped graph is Kotlin, Ktor, kotlinx, SLF4J/Logback and sqlite-jdbc. A new shipped
  dependency needs an AGPL-compatible license and an entry in
  [`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md).
- New knobs are `SPOOL_*` environment variables only, refuse to start on an invalid value, and
  land in the README's configuration table in the same commit.

## Before calling it done

- `./gradlew check` passes (compile, ktlint, detekt, every suite). Commands are in
  [`../context/commands.md`](../context/commands.md).
- A wire-touching change also passes the packaged conformance run, the way the
  `conformance-selftest` CI job does it.
- Kover floors are a ratchet: raise one when coverage rises, never lower one to go green.
- Match the surrounding style: ktlint_official, 4-space indent.
