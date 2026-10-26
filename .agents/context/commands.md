# Commands

Build, test and lint invocations. JDK 21; the Gradle wrapper pins Gradle 9.8.2. Which ones a
change must pass is in [`../rules/coding.md`](../rules/coding.md).

```sh
./gradlew check                 # compile + ktlint + detekt + every suite
./gradlew ktlintFormat          # autoformat
./gradlew detektMain detektTest # static analysis with type resolution
./gradlew :daemon:installDist   # runnable dist at daemon/build/install/knit-spool/
./gradlew :daemon:run           # listens on :9470, PoW off, public, in-memory
./gradlew koverHtmlReport       # merged coverage, build/reports/kover/html/index.html
./gradlew koverVerify           # enforce the coverage floors
```

The conformance suite against the packaged binaries, as CI's `conformance-selftest` runs it:

```sh
./gradlew :daemon:installDist :conformance:installDist
daemon/build/install/knit-spool/bin/knit-spool &
conformance/build/install/knit-spool-conformance/bin/knit-spool-conformance \
    ws://127.0.0.1:9470/spool/v1 --destructive
```

Ports 9470 and 9471 may already be in use on a dev box; set `SPOOL_PORT` for a local run.

Architecture decision records (see [`../rules/decisions.md`](../rules/decisions.md)):

```sh
python3 scripts/adr.py new "<title>" --topics a,b  # scaffold an ADR; never pick a number
python3 scripts/adr.py index                       # regenerate .agents/memory/decisions.md
python3 scripts/adr.py index --check               # what CI and the pre-commit hook run
python3 scripts/adr.py lint                        # markdownlint over the ADRs
```

Repo hooks (DCO trailer, ADR router check) need `git config core.hooksPath .githooks` once
per clone.
