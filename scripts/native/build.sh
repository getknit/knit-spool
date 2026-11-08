#!/usr/bin/env bash
# Builds the aarch64 musl native binary inside the Liberica NIK musl container (ADR 2026-11.bxce).
#
# native-image cannot cross-compile and no musl toolchain for it exists outside BellSoft's, so this
# runs only on an arm64 host with Docker: a GitHub `ubuntu-24.04-arm` runner, or a Hetzner CAX box
# for the dev loop. Not a job-level `container:` in CI, because the runner's JavaScript actions do
# not run inside a musl container; the build is the only thing that happens in there.
#
# The container runs as the invoking user, so the workspace does not come back root-owned, and keeps
# Gradle's home inside the workspace (`.gradle-home/`, gitignored) so CI can cache it.
#
#   scripts/native/build.sh [extra gradle args]
#   scripts/native/build.sh -PspoolVersion=0.9.0 -PspoolCommit="$(git rev-parse HEAD)"
#
# Output: daemon/build/distributions/knit-spool-<version>-linux-aarch64-musl.tar.gz, and
# conformance/build/install/knit-spool-conformance/ (the JVM runner scripts/native-acceptance.sh drives).
set -euo pipefail

case "$(uname -m)" in
  aarch64 | arm64) ;;
  *)
    echo "scripts/native/build.sh: builds the aarch64 archive, so it must run on an arm64 host, not $(uname -m)" >&2
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
  :daemon:nativeDistTar :conformance:installDist "$@"
