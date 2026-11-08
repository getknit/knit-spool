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

The native aarch64 musl binary (ADR 2026-11.bxce) builds only on an arm64 host, inside the Liberica
NIK musl container, and is proved against a plain Alpine container:

```sh
scripts/native/build.sh -PspoolCommit="$(git rev-parse HEAD)"   # -> daemon/build/distributions/*-linux-aarch64-musl.tar.gz
scripts/native-acceptance.sh daemon/build/distributions/knit-spool-*-linux-aarch64-musl.tar.gz "$(git rev-parse HEAD)"
```

On an x86_64 box `build.sh` compiles the same image for that box instead (no archive), and the
acceptance script takes it once tarred up in the archive's layout: everything but the instruction
set breaks here first, in about a minute. `-PspoolNativeArgs` passes extra native-image options to
one diagnostic build.

```sh
scripts/native/build.sh                            # x86_64: -> daemon/build/native/nativeCompile/{knit-spool,libsqlitejdbc.so}
d=/tmp/ks/knit-spool-local; mkdir -p "$d" && cp daemon/build/native/nativeCompile/{knit-spool,libsqlitejdbc.so} LICENSE THIRD-PARTY-NOTICES.md "$d"
tar -czf /tmp/ks/local.tar.gz -C /tmp/ks knit-spool-local && SPOOL_PORT=9477 scripts/native-acceptance.sh /tmp/ks/local.tar.gz unknown
scripts/native/build.sh "-PspoolNativeArgs=--trace-class-initialization=kotlin.DeprecationLevel"
```

Architecture decision records (see [`../rules/decisions.md`](../rules/decisions.md)):

```sh
python3 scripts/adr.py new "<title>" --topics a,b  # scaffold an ADR; never pick a number
python3 scripts/adr.py index                       # regenerate .agents/memory/decisions.md
python3 scripts/adr.py index --check               # what CI and the pre-commit hook run
python3 scripts/adr.py lint                        # markdownlint over the ADRs
```

Repo hooks (DCO trailer, ADR router check) need `git config core.hooksPath .githooks` once
per clone.
