#!/usr/bin/env bash
# Builds the aarch64 musl native binary inside the Liberica NIK musl container (ADR 2026-11.bxce).
#
# native-image cannot cross-compile and no musl toolchain for it exists outside BellSoft's, so the
# archive comes only from an arm64 host with Docker: a GitHub `ubuntu-24.04-arm` runner, or a Hetzner
# CAX box for the dev loop. Not a job-level `container:` in CI, because the runner's JavaScript
# actions do not run inside a musl container; the build is the only thing that happens in there.
#
# On an x86_64 box the same container compiles the same image for that box (`:daemon:nativeCompile`,
# no archive): everything but the instruction set breaks there first, in about a minute. Tar
# daemon/build/native/nativeCompile/{knit-spool,libsqlitejdbc.so} plus LICENSE and
# THIRD-PARTY-NOTICES.md under knit-spool-<version>/ and scripts/native-acceptance.sh takes it.
#
# The container runs as the invoking user, so the workspace does not come back root-owned, and keeps
# Gradle's home inside the workspace (`.gradle-home/`, gitignored) so CI can cache it.
#
#   scripts/native/build.sh [extra gradle args]
#   scripts/native/build.sh -PspoolVersion=0.9.0 -PspoolCommit="$(git rev-parse HEAD)"
#   scripts/native/build.sh "-PspoolNativeArgs=--trace-class-initialization=a.b.C"   # one diagnostic build
#
# Output: daemon/build/distributions/knit-spool-<version>-linux-aarch64-musl.tar.gz, and
# conformance/build/install/knit-spool-conformance/ (the JVM runner scripts/native-acceptance.sh drives).
set -euo pipefail

case "$(uname -m)" in
  aarch64 | arm64) targets=(:daemon:nativeDistTar :conformance:installDist) ;;
  x86_64)
    echo "scripts/native/build.sh: x86_64 host, so :daemon:nativeCompile for this box and no aarch64 archive" >&2
    targets=(:daemon:nativeCompile :conformance:installDist)
    ;;
  *)
    echo "scripts/native/build.sh: no musl native-image toolchain exists for $(uname -m)" >&2
    exit 2
    ;;
esac

root=$(cd "$(dirname "$0")/../.." && pwd)
image=${NIK_IMAGE:-knit-spool-nik-musl:local}

if [ -z "${NIK_IMAGE:-}" ]; then
  docker build --quiet --tag "$image" --file "$root/scripts/native/Dockerfile" "$root/scripts/native" >/dev/null
fi

mkdir -p "$root/.gradle-home"

# --no-daemon --no-watch-fs: nothing outlives the container, and Gradle's file-system watching has no
# native library for musl. In-process Kotlin compilation keeps the compiler off a forked daemon too,
# as the Dockerfile already does.
exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home \
  -e GRADLE_USER_HOME=/w/.gradle-home \
  -v "$root:/w" -w /w \
  "$image" \
  ./gradlew --no-daemon --no-watch-fs -Pkotlin.compiler.execution.strategy=in-process \
  "${targets[@]}" "$@"
