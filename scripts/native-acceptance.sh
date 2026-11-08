#!/usr/bin/env bash
# Proves the aarch64 musl native archive on a musl system it was not built on (ADR 2026-11.bxce).
#
#   scripts/native-acceptance.sh <knit-spool-*-linux-aarch64-musl.tar.gz> <expected commit>
#
# Runs on an arm64 host with Docker, binutils, curl and a JDK 21 (for the conformance runner, which
# stays a JVM program). The daemon itself runs inside a plain Alpine container with nothing added to
# it: if the archive works there it works on OpenWrt, which ships the same musl generation. Every
# check is one the JVM dist already passes somewhere else; what is being proved is that the native
# build lost nothing on the way — the build stamp, run-time log levels, signal handling, the JNI
# store, the crypto providers and the whole conformance suite.
#
# Environment: SPOOL_PORT (default 9470), RUN_IMAGE (default alpine:3.21, pinned by digest below),
# CONFORMANCE (the runner's bin script, default the installDist path), ACCEPTANCE_OUT (a directory to
# copy the TAP, the daemon log and the RSS figures into, for a CI artifact), KEEP=1 to keep the work dir.
set -euo pipefail

tarball=${1:?usage: native-acceptance.sh <tarball> <expected-commit>}
commit=${2:?usage: native-acceptance.sh <tarball> <expected-commit>}
port=${SPOOL_PORT:-9470}
image=${RUN_IMAGE:-alpine:3.21@sha256:87a4361f6c819618a6bafa2ed5228714cb0bb22195d2b49c95eb26799990128a}
root=$(cd "$(dirname "$0")/.." && pwd)
conformance=${CONFORMANCE:-$root/conformance/build/install/knit-spool-conformance/bin/knit-spool-conformance}
container=knit-spool-acceptance-$$

for tool in docker readelf objdump curl; do
  command -v "$tool" >/dev/null || { echo "native-acceptance: $tool is required" >&2; exit 2; }
done
[ -x "$conformance" ] || { echo "native-acceptance: conformance runner not found at $conformance" >&2; exit 2; }

work=$(mktemp -d)
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  if [ -n "${ACCEPTANCE_OUT:-}" ] && [ -d "$work" ]; then
    mkdir -p "$ACCEPTANCE_OUT"
    cp -f "$work"/*.tap "$work"/*.log "$work"/rss.txt "$ACCEPTANCE_OUT"/ 2>/dev/null || true
  fi
  if [ -n "${KEEP:-}" ]; then echo "work dir kept: $work"; else rm -rf "$work"; fi
}
trap cleanup EXIT

step() { printf '\n### %s\n' "$*"; }
fail() {
  echo "FAIL: $*" >&2
  docker logs --tail 50 "$container" 2>/dev/null || true
  exit 1
}

tar -xzf "$tarball" -C "$work"
dist=$(echo "$work"/knit-spool-*)
bin="$dist/knit-spool"
[ -x "$bin" ] || fail "no executable knit-spool in $tarball"
[ -f "$dist/libsqlitejdbc.so" ] || fail "no libsqlitejdbc.so beside the binary in $tarball"
echo "archive: $(du -h "$tarball" | cut -f1), binary: $(du -h "$bin" | cut -f1), sqlite: $(du -h "$dist/libsqlitejdbc.so" | cut -f1)"

# ---- static facts about the binary ----------------------------------------------------------------

step "NEEDED: only musl's libc and zlib, which the OpenWrt package depends on"
needed=$(readelf -d "$bin" | awk '/\(NEEDED\)/ { gsub(/[][]/, "", $NF); print $NF }' | sort | xargs)
echo "$needed"
[ "$needed" = "libc.musl-aarch64.so.1 libz.so.1" ] || fail "unexpected NEEDED set: $needed"
readelf -h "$bin" | grep -q 'Machine:.*AArch64' || fail "not an AArch64 binary"

step "no LSE atomics outside GCC's outline-atomics helpers (the Cortex-A53 has none)"
# -march=armv8-a leaves the helpers (`__aarch64_ldadd4_acq_rel` and friends) in, because the JDK's
# static libraries were compiled with -moutline-atomics; those bodies pick LSE or LL/SC at run time
# behind a HWCAP check and are safe. Anything else using an LSE mnemonic would abort on the device.
lse=$(objdump -d --no-show-raw-insn "$bin" | awk '
  /^[0-9a-f]+ <.*>:$/ { skip = ($0 ~ /<__aarch64_/) ; next }
  !skip && $2 ~ /^(cas|ldadd|ldclr|ldeor|ldset|ldsmax|ldsmin|ldumax|ldumin|swp)[a-z]*$/ { print; n++ }
  END { print n + 0 > "/dev/stderr" }' 2>"$work/lse-count")
count=$(cat "$work/lse-count")
echo "LSE instructions outside the helpers: $count"
[ "$count" = 0 ] || { echo "$lse" | head -20; fail "the image was not built with -march=armv8-a"; }

# ---- the binary on a plain musl box -----------------------------------------------------------------

# Same uid as the caller, so nothing the container writes into the work dir is root-owned.
run() { docker run --rm --network host --user "$(id -u):$(id -g)" -v "$dist:/opt/ks:ro" -v "$work:/work" "$@"; }
ks() { run "$image" /opt/ks/knit-spool "$@"; }

step "check: the runtime option is consumed before main, and the build says native"
out=$(run -e SPOOL_PORT="$port" "$image" /opt/ks/knit-spool -Xmx64m check)
echo "$out"
[[ $out == *"runtime=native"* ]] || fail "check did not report runtime=native"
[[ $out == *"bind=0.0.0.0"* ]] || fail "check did not report the default bind"

step "check: refuses what it must"
if run -e SPOOL_MAX_BLOB=999999 "$image" /opt/ks/knit-spool check 2>/dev/null; then fail "SPOOL_MAX_BLOB=999999 was accepted"; fi
if run -e SPOOL_BIND=spool.example "$image" /opt/ks/knit-spool check 2>/dev/null; then fail "SPOOL_BIND=spool.example was accepted"; fi

step "the CLI tools run natively (Ed25519 keygen, the commons invite)"
ks ticket-keygen > "$work/keygen.txt"
cat "$work/keygen.txt"
awk 'NR == 1 { print $NF }' "$work/keygen.txt" > "$work/ticket.key"
ticket_public=$(sed -n 's/.*SPOOL_TICKET_KEY=//p' "$work/keygen.txt")
[ -n "$ticket_public" ] || fail "ticket-keygen printed no SPOOL_TICKET_KEY"
ks commons-invite > "$work/invite.txt"
cat "$work/invite.txt"
invite=$(sed -n 's/^invite (give this to members):[[:space:]]*//p' "$work/invite.txt")
commons_id=$(sed -n 's/.*SPOOL_COMMONS_ID=//p' "$work/invite.txt")
{ [ -n "$invite" ] && [ -n "$commons_id" ]; } || fail "commons-invite printed no invite or id"
head -c 48 /dev/urandom | base64 -w0 > "$work/turn.secret"
echo "acceptance-token-$RANDOM$RANDOM" > "$work/token"
token=$(cat "$work/token")

# ---- a real spool: SQLite store, every feature on, debug logging, behind nothing -------------------

step "boot: SQLite, tickets, TURN, STUN, a commons room, casts, moderation, debug logging"
mkdir -p "$work/data"
printf 'SPOOL_MAX_CONNS=500\n' > "$work/data/reload.env"
docker run -d --name "$container" --network host --user "$(id -u):$(id -g)" \
  -v "$dist:/opt/ks:ro" -v "$work/data:/data" \
  -e SPOOL_PORT="$port" -e SPOOL_BIND=127.0.0.1 -e SPOOL_DATA_DIR=/data -e SPOOL_RELOAD_FILE=/data/reload.env \
  -e SPOOL_LOG_LEVEL=DEBUG -e SPOOL_POW_BITS=8 -e SPOOL_RATE_NEW_SCOPES=10000 -e SPOOL_RATE_PUSHES=10 \
  -e SPOOL_TOKEN="$token" -e SPOOL_REQUIRE_MODERATION=true \
  -e SPOOL_TICKET_KEY="$ticket_public" -e SPOOL_TICKET_AUDIENCE=spool.test -e SPOOL_TICKET_AUDIENCE_ALIAS=old.spool.test \
  -e SPOOL_TURN_SECRET="$(cat "$work/turn.secret")" -e 'SPOOL_TURN_URLS=turn:turn.spool.test:3478?transport=udp' \
  -e SPOOL_STUN_URLS=stun:stun.spool.test:3478 \
  -e SPOOL_COMMONS_ID="$commons_id" -e SPOOL_COMMONS_NAME=Acceptance -e SPOOL_COMMONS_ATTACH=true \
  -e SPOOL_RATE_CASTS=4 \
  "$image" /opt/ks/knit-spool -Xmx96m -Dorg.sqlite.lib.path=/opt/ks -Dorg.sqlite.lib.name=libsqlitejdbc.so >/dev/null

for _ in $(seq 60); do
  if curl -fsS "http://127.0.0.1:$port/healthz" 2>/dev/null | grep -q '"ok"'; then break; fi
  docker inspect -f '{{.State.Running}}' "$container" | grep -q true || { docker logs "$container"; fail "the daemon exited during startup"; }
  sleep 1
done
curl -fsS "http://127.0.0.1:$port/healthz" | grep -q '"ok"' || { docker logs "$container"; fail "no healthy answer within 60 s"; }

step "the build stamp survived: /source, /metrics, the first log line"
source_json=$(curl -fsS "http://127.0.0.1:$port/source")
echo "$source_json"
[[ $source_json == *"\"commit\":\"$commit\""* ]] || fail "/source does not carry commit $commit"
[[ $source_json != *'"version":"unknown"'* ]] || fail "/source reports an unknown version: the build-info resource is missing"
curl -fsS "http://127.0.0.1:$port/metrics?k=$token" | grep -q "knit_spool_build_info{version=\"[^\"]*\",commit=\"$commit\"} 1" || fail "build_info metric is wrong"
docker logs "$container" 2>&1 | grep -q 'knit-spool .* (commit .*, native)' || fail "the first log line does not say native"
docker logs "$container" 2>&1 | grep -q "listening on 127.0.0.1:$port" || fail "the listener did not bind SPOOL_BIND"

step "conformance, destructive, every feature"
"$conformance" "ws://127.0.0.1:$port/spool/v1" --destructive --token-file "$work/token" \
  --commons-invite "$invite" --turn-secret-file "$work/turn.secret" \
  --ticket-key-file "$work/ticket.key" --ticket-audience spool.test --ticket-audience-alias old.spool.test \
  2>&1 | tee "$work/conformance.tap"
[ "${PIPESTATUS[0]}" = 0 ] || fail "the conformance suite failed (exit ${PIPESTATUS[0]})"

step "logging: SPOOL_LOG_LEVEL was read at run time, and the io.ktor pin kept the token out"
docker logs "$container" > "$work/daemon.log" 2>&1
grep -q ' DEBUG ' "$work/daemon.log" || fail "SPOOL_LOG_LEVEL=DEBUG produced no DEBUG line: the level was frozen at build time"
if grep -Eq ' (DEBUG|TRACE) i\.k\.' "$work/daemon.log"; then fail "io.ktor logged below INFO: the pin in logback.xml is gone"; fi
if grep -q "$token" "$work/daemon.log"; then fail "the bearer token appears in the log"; fi

step "SIGHUP: the reload file is re-read, and the process stays up"
docker kill --signal=HUP "$container" >/dev/null
sleep 2
docker logs "$container" 2>&1 | grep -q 'reloaded configuration from /data/reload.env' || fail "no reload log line after SIGHUP"
docker inspect -f '{{.State.Running}}' "$container" | grep -q true || fail "the daemon exited on SIGHUP"
curl -fsS "http://127.0.0.1:$port/healthz" | grep -q '"ok"' || fail "unhealthy after reload"

step "footprint"
docker exec "$container" awk '/VmRSS|VmHWM/ { print }' /proc/1/status | tee "$work/rss.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo '### knit-spool native acceptance'; echo; echo '```'; cat "$work/rss.txt"; echo "binary: $(du -h "$bin" | cut -f1)"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi

step "SIGTERM: exits within 10 s and closes the store (no WAL left behind)"
docker kill --signal=TERM "$container" >/dev/null
if ! timeout 10 docker wait "$container" >/dev/null; then docker logs --tail 30 "$container"; fail "still running 10 s after SIGTERM: --install-exit-handlers is missing"; fi
[ ! -e "$work/data/spool.db-wal" ] || fail "spool.db-wal left behind: the shutdown hook did not close the store"
[ ! -e "$work/data/spool.db-shm" ] || fail "spool.db-shm left behind: the shutdown hook did not close the store"
docker rm -f "$container" >/dev/null

# ---- the package's other shape: a symlink in PATH, no explicit library path, a quiet log -----------

step "symlink: /usr/bin/knit-spool -> /usr/lib/knit-spool/knit-spool finds its sqlite library"
mkdir -p "$work/bin" "$work/data2"
ln -sf /opt/ks/knit-spool "$work/bin/knit-spool"
docker run -d --name "$container" --network host --user "$(id -u):$(id -g)" \
  -v "$dist:/opt/ks:ro" -v "$work/bin:/usr/local/bin:ro" -v "$work/data2:/data" \
  -e SPOOL_PORT="$port" -e SPOOL_DATA_DIR=/data -e SPOOL_LOG_LEVEL=WARN \
  -w / "$image" knit-spool >/dev/null
for _ in $(seq 30); do
  if curl -fsS "http://127.0.0.1:$port/healthz" 2>/dev/null | grep -q '"ok"'; then break; fi
  docker inspect -f '{{.State.Running}}' "$container" | grep -q true || { docker logs "$container"; fail "the symlinked daemon exited: the sqlite library was not found next to the executable"; }
  sleep 1
done
curl -fsS "http://127.0.0.1:$port/healthz" | grep -q '"ok"' || { docker logs "$container"; fail "the symlinked daemon never became healthy"; }
# io.ktor is pinned at INFO whatever the root level says (logback.xml), so Ktor's three startup
# lines are expected here; SPOOL_LOG_LEVEL governs the daemon's own loggers.
if docker logs "$container" 2>&1 | grep -Eq ' INFO +(a\.g\.s\.|app\.getknit\.)'; then fail "SPOOL_LOG_LEVEL=WARN still printed the daemon's INFO lines: the level was frozen at build time"; fi
docker kill --signal=TERM "$container" >/dev/null
timeout 10 docker wait "$container" >/dev/null || fail "the symlinked daemon did not stop on SIGTERM"

echo
echo "native acceptance: all checks passed"
