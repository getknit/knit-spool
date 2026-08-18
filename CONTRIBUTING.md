# Contributing to knit-spool

Thanks for your interest in knit-spool — the reference *spool*, a scoped, blinded store-and-forward
relay for [Knit](https://github.com/getknit/knit)'s Internet plane. Contributions are welcome, within
the expectations below.

## Support expectations (read this first)

knit-spool is released **as-is** under the [GNU AGPL v3.0-or-later](LICENSE). It is developed on a
**best-effort, hobby basis**, with **no support, warranty, or response-time guarantee** of any kind —
this is the "NO WARRANTY" clause of the AGPL, stated plainly:

- Issues and merge requests are welcome, but may not be triaged, answered, or accepted.
- There is **no commitment** to fix bugs, review contributions on any timeline, or keep any
  interface stable beyond the wire protocol's own compatibility rules.
- Do not depend on any spool where failure matters. By design no spool is load-bearing: clients
  multi-home across several and union them, and a wiped spool is refilled by any one conversation
  member.

If that works for you, read on.

## The spec is the product

The normative wire contract is **[`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md)**
in the Knit repo. This daemon implements the spec — never the other way around. That has two
consequences for contributions:

- **Behavior changes that the spec does not describe are out of scope here.** If the daemon and the
  spec disagree, the daemon is wrong; open an issue quoting the section. If the *spec* is what needs
  changing, that discussion belongs in the Knit repo, and this repo follows once it lands.
- **`SpecVectorTest` pins this implementation to the spec's §13 vectors byte-for-byte.** A change
  that makes those vectors fail is a bug in the change, not in the test — do not re-record them to
  make a diff pass.

Third-party spool implementations are first-class. `:conformance` deliberately depends only on
`:protocol`, never on `:daemon`, so it tests the wire contract rather than this repo's internals; a
check that can only pass against this server does not belong in it.

## Ground rules

- **Be excellent to each other.** This project has a [Code of Conduct](CODE_OF_CONDUCT.md); by
  participating you agree to uphold it. Harassment or abuse is not welcome in issues, merge
  requests, or discussions.
- **License:** by contributing, you agree your contribution is licensed under
  **AGPL-3.0-or-later**, the same as the project. (The Knit app is a separate GPL-3.0-or-later
  codebase; the two share a protocol spec and no code.)
- **Sign your commits (DCO):** add a `Signed-off-by: Your Name <you@example.com>` trailer to each
  commit (`git commit -s`), certifying the [Developer Certificate of Origin](https://developercertificate.org/).
  Only submit code you have the right to license under the AGPL.
- **Third-party dependencies:** the shipped graph is small and deliberately so — Kotlin, Ktor,
  kotlinx, SLF4J/Logback, sqlite-jdbc, and nothing else. Do not add a dependency whose license is
  AGPL-incompatible or unverified, and update
  [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) when you add or remove a shipped one.
- **Keep the blindness property.** A spool sees scope ids, blob ids, ciphertext, sizes, and timing —
  and must never be able to learn node ids, plaintext, rosters, or delivery facts. A change that
  logs, persists, exports, or derives anything outside that set needs a very good reason and a note
  in the merge request. The same goes for the bearer token: it rides in the query string, so it must
  stay out of logs.

## Development

JDK 21 — the Gradle wrapper pins Gradle 9.5.0. There is no detekt yet (deliberately deferred until
the Knit app's config settles off its alpha); ktlint runs as part of `check`.

```sh
./gradlew check                 # compile + ktlint + every suite
./gradlew ktlintFormat          # autoformat
./gradlew :daemon:installDist   # runnable dist at daemon/build/install/knit-spool/
./gradlew :daemon:run           # listens on :9470, PoW off, public, in-memory
```

`check` runs the §13 spec-vector pins, the store contract against **both** backends (in-memory and
SQLite), and the full server integration tests. Please run it before opening a merge request, and
match the surrounding code style.

If your change touches the wire, run the conformance suite against your own build the way CI does —
that job (`conformance-selftest`) is the one that catches a server that passes its unit tests and
still talks the wrong protocol:

```sh
./gradlew :daemon:installDist :conformance:installDist
daemon/build/install/knit-spool/bin/knit-spool &
conformance/build/install/knit-spool-conformance/bin/knit-spool-conformance \
    ws://127.0.0.1:9470/spool/v1 --destructive
```

`--destructive` enables the quota and rate-limit checks; they fill real capacity, so run them only
against spools you operate.

### Coverage

Coverage is measured with [Kover](https://github.com/Kotlin/kotlinx-kover) and merged across all
three modules. It is not part of `check` — ask for it:

```sh
./gradlew koverHtmlReport          # build/reports/kover/html/index.html
./gradlew koverVerify              # enforce the line/branch floors
./gradlew :daemon:koverHtmlReport  # one module on its own
```

CI runs the same reports in the `test` job, publishes the XML as a GitLab coverage report so the
merge-request diff is annotated line by line, and gates on `koverVerify`.

The floors in the root `build.gradle.kts` are a **ratchet**: they sit a few points under today's
numbers so that ordinary refactoring passes and deleting tests does not. If a change raises
coverage, raise the floor with it. Do not lower a floor to turn a red build green — that is the one
edit the ratchet exists to prevent, and it will be asked about in review.

Two things are excluded from the report, both in `build.gradle.kts` with a comment: the
kotlinx-serialization-generated `$serializer` classes, and the two `main` entry points, which no
unit test can reach and which `conformance-selftest` covers for real, out of process. `:conformance`
itself scores low for the same out-of-process reason — its checks run against a live server — so
judge a conformance change by the self-test, not by the number.

New configuration knobs are **environment variables only** (`SPOOL_*`), must refuse to start on an
invalid value, and belong in the README's configuration table in the same commit.

## Where to submit

Development happens on the self-hosted GitLab at
<https://github.com/getknit/knit-spool>. Open issues and merge requests there; the issue and
merge-request templates will guide you through what to include. Keep each merge request focused on a
single change with a clear description of what and why.

CI runs the tests with coverage, the conformance self-test, a kaniko image build, and advisory Trivy
and markdownlint scans. The advisory jobs report without gating; the test jobs gate.

## Security

**Do not** report security vulnerabilities through public issues or merge requests. See
[`SECURITY.md`](SECURITY.md) for private disclosure.

## Running a modified spool (AGPL §13)

If you run a modified version of this daemon and let anyone else's client talk to it over a network,
the AGPL's §13 obliges you to offer those users the corresponding source of *your* version. Publish
your fork and say where it is — that is the whole obligation, and it is the reason a relay that
handles other people's ciphertext is AGPL rather than GPL.
