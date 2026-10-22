#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# coturn beside a Knit Spool (experimental; SPOOL_PROTOCOL.md §7.6). The Spool issues credentials under
# SPOOL_TURN_SECRET and this relays the calls they authorise. Run by docker-compose.turn.yml.
#
# Starts as root for one reason: Caddy writes its certificates 0600 root, so only root can read
# them. This copies them to a tmpfs readable by turnserver's group, writes the config there, and
# turnserver drops to nobody:nogroup (proc-user/proc-group) once its listeners are up.
#
# It then watches Caddy's copy. A renewal is re-copied and turnserver is sent SIGUSR2, which 4.18
# answers with reload_ssl_certs (mainrelay.c) — no restart, so no relayed call drops. The signal
# is sent from in here, never with `docker kill -s`, which would mark the container
# manually-stopped and leave it down after the next reboot. A renewal writes the certificate and
# the key one after the other, so a change is looked at again 5 s later and installed only if
# neither has moved; a pair still moving waits for the next poll.
#
# With no certificate yet (Caddy's first boot) it starts plain turn: on 3478 and restarts with TLS
# once one appears, polling every minute until then.
#
# On a TURN box of its own, with no Caddy, it runs unchanged given:
#   TURN_CERT_FILE, TURN_KEY_FILE  both set: read these instead of searching Caddy's directory, e.g.
#       /etc/letsencrypt mounted read-only at /letsencrypt and certbot's live/<domain>/fullchain.pem
#       and privkey.pem. certbot repoints those symlinks on renewal, and cp and sha256sum follow
#       them. Its files are 0600 root too, under 0700 directories, which root reads as their owner.
#       Until both exist it runs plain, as above.
#   TURN_TLS_PORT  where turns: listens, 5349 by default. A port below 1024 needs only
#       NET_BIND_SERVICE, which docker-compose.turn.yml already grants: turnserver binds its
#       listeners as root, before the drop.
set -eu

: "${SPOOL_TURN_SECRET:?set SPOOL_TURN_SECRET in deploy/.env}"
: "${SPOOL_DOMAIN:?set SPOOL_DOMAIN in deploy/.env}"
MIN_PORT="${TURN_MIN_PORT:-49160}"
MAX_PORT="${TURN_MAX_PORT:-49999}"
TLS_PORT="${TURN_TLS_PORT:-5349}"
POLL_S="${TURN_CERT_POLL_S:-21600}"
CERT_FILE="${TURN_CERT_FILE:-}"
KEY_FILE="${TURN_KEY_FILE:-}"
RUN=/run/turn
CONF="$RUN/turnserver.conf"
PID=

log() { echo "knit-turn: $*"; }

if [ -n "$CERT_FILE" ] && [ -n "$KEY_FILE" ]; then
  where="at $CERT_FILE"
elif [ -n "$CERT_FILE$KEY_FILE" ]; then
  log "set both TURN_CERT_FILE and TURN_KEY_FILE, or neither"
  exit 1
else
  where="for $SPOOL_DOMAIN under /caddy"
fi

# The certificate to serve, or nothing yet. Never fails, so `set -e` cannot take a missing file for
# an error.
find_cert() {
  if [ -n "$CERT_FILE" ]; then
    if [ -r "$CERT_FILE" ] && [ -r "$KEY_FILE" ]; then echo "$CERT_FILE"; fi
  else
    # The newest certificate Caddy holds for the domain, whichever ACME issuer it came from.
    # shellcheck disable=SC2012 # names are Caddy's own: <issuer>/<domain>/<domain>.crt
    ls -t /caddy/caddy/certificates/*/"$SPOOL_DOMAIN/$SPOOL_DOMAIN.crt" 2>/dev/null | head -n 1
  fi
}

key_for() {
  if [ -n "$KEY_FILE" ]; then echo "$KEY_FILE"; else echo "${1%.crt}.key"; fi
}

# Both files: the key can change without the certificate.
fingerprint() { cat "$1" "$(key_for "$1")" | sha256sum | cut -d ' ' -f 1; }

install_cert() {
  cp "$1" "$RUN/cert.pem"
  cp "$(key_for "$1")" "$RUN/key.pem"
  chown root:nogroup "$RUN/cert.pem" "$RUN/key.pem"
  chmod 0640 "$RUN/cert.pem" "$RUN/key.pem"
}

# $1 = "tls" or "plain". The secret goes in this file, never on a command line, where it would sit
# in `ps` for the life of the process.
write_conf() {
  umask 027
  {
    echo "use-auth-secret"
    echo "static-auth-secret=$SPOOL_TURN_SECRET"
    echo "realm=$SPOOL_DOMAIN"
    echo "listening-port=3478"
    if [ "$1" = tls ]; then
      echo "tls-listening-port=$TLS_PORT"
      echo "cert=$RUN/cert.pem"
      echo "pkey=$RUN/key.pem"
    else
      echo "no-tls"
    fi
    echo "min-port=$MIN_PORT"
    echo "max-port=$MAX_PORT"
    [ -n "${TURN_EXTERNAL_IP:-}" ] && echo "external-ip=$TURN_EXTERNAL_IP"
    echo "fingerprint"
    echo "no-multicast-peers"
    echo "no-tcp-relay"
    # The relay's real abuse bound. A credential is checked only when a session first allocates,
    # and a refreshed allocation then lives on, so what a leaked or farmed credential is worth is
    # capped here, not by its lifetime. max-bps and bps-capacity are BYTES per second: one Opus
    # call leg is ~4 kB/s of audio and ~7 kB/s with headers, so 16000 per session is 2x headroom, and
    # bps-capacity caps the whole box. coturn reserves max-bps from bps-capacity for every allocation,
    # used or not, so the default pool is total-quota x max-bps and the two run out together.
    echo "user-quota=${TURN_USER_QUOTA:-3}"
    echo "total-quota=${TURN_TOTAL_QUOTA:-100}"
    echo "max-bps=${TURN_MAX_BPS:-16000}"
    echo "bps-capacity=${TURN_BPS_CAPACITY:-1600000}"
    echo "stale-nonce=600"
    echo "proc-user=nobody"
    echo "proc-group=nogroup"
    echo "log-file=stdout"
    # Written as root before the privilege drop, and root here holds no DAC_OVERRIDE: it can write
    # only where it is the owner. The image's default, /var/run, is on the read-only root.
    echo "pidfile=$RUN/turnserver.pid"
    # Not optional. Without these, any credential holder can use the relay to reach this host's
    # loopback, its private network or a cloud metadata endpoint: the standard TURN SSRF.
    for range in \
      0.0.0.0-0.255.255.255 \
      10.0.0.0-10.255.255.255 \
      100.64.0.0-100.127.255.255 \
      127.0.0.0-127.255.255.255 \
      169.254.0.0-169.254.255.255 \
      172.16.0.0-172.31.255.255 \
      192.0.0.0-192.0.0.255 \
      192.168.0.0-192.168.255.255 \
      198.18.0.0-198.19.255.255 \
      ::1 \
      64:ff9b::-64:ff9b::ffff:ffff \
      fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff \
      fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff; do
      echo "denied-peer-ip=$range"
    done
  } >"$CONF"
  chown root:nogroup "$CONF"
}

start() {
  turnserver -c "$CONF" &
  PID=$!
}

stop() {
  [ -n "$PID" ] || return 0
  kill -TERM "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
  PID=
}

# In the background and waited on, so TERM interrupts the sleep instead of waiting it out.
pause() {
  sleep "$1" &
  wait $! || true
}

trap 'stop; exit 0' TERM INT

chown root:nogroup "$RUN"
chmod 0750 "$RUN"

source_cert=$(find_cert)
if [ -n "$source_cert" ]; then
  install_cert "$source_cert"
  seen=$(fingerprint "$source_cert")
  mode=tls
else
  log "no certificate $where yet; starting without turns: and polling for one"
  seen=
  mode=plain
fi
write_conf "$mode"
start
log "turnserver started ($mode), relay ports $MIN_PORT-$MAX_PORT"

while :; do
  if [ "$mode" = tls ]; then pause "$POLL_S"; else pause 60; fi
  if ! kill -0 "$PID" 2>/dev/null; then
    log "turnserver exited; leaving it to the restart policy"
    exit 1
  fi
  source_cert=$(find_cert)
  [ -n "$source_cert" ] || continue
  current=$(fingerprint "$source_cert")
  [ "$current" != "$seen" ] || continue
  pause 5
  if [ "$(fingerprint "$source_cert")" != "$current" ]; then
    log "certificate still changing; looking again at the next poll"
    continue
  fi
  install_cert "$source_cert"
  seen=$current
  if [ "$mode" = plain ]; then
    mode=tls
    write_conf tls
    stop
    start
    log "certificate found; restarted with turns: on $TLS_PORT"
  else
    kill -USR2 "$PID"
    log "certificate renewed; asked turnserver to reload it"
  fi
done
