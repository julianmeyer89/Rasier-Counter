#!/usr/bin/env bash
# Watches the Marstek cloud connection. If it is down (e.g. Marstek rotated the client certificate),
# it re-pulls the public hame-relay image, extracts the current certificate into the server's cert
# directory and restarts the service. A cooldown prevents repeated pulls when the fix is not yet
# available upstream. Runs as the "ubuntu" user from a systemd timer. Needs docker access (ubuntu in
# the docker group) and passwordless `systemctl restart`.
set -euo pipefail

CONFIG="${RASIER_CONFIG:-$HOME/rasier/config.properties}"
SERVICE="${RASIER_SERVICE:-rasier}"
IMAGE="${RASIER_HAME_IMAGE:-ghcr.io/tomquist/hame-relay:latest}"
STATE="${RASIER_CERTWATCH_STATE:-$HOME/rasier/.certwatch_last}"
COOLDOWN_S="${RASIER_CERTWATCH_COOLDOWN:-21600}"   # 6 h between refresh attempts
STALE_S="${RASIER_CERTWATCH_STALE:-1200}"          # data older than 20 min = unhealthy
LOCK="/tmp/rasier-cert-watch.lock"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

exec 9>"$LOCK"
if ! flock -n 9; then exit 0; fi

cfg() { grep -E "^$1=" "$CONFIG" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]' || true; }

PORT="$(cfg web_port)"; PORT="${PORT:-8080}"
TOKEN="$(cfg auth_token)"
CERT_DIR_RAW="$(cfg cert_dir)"; CERT_DIR_RAW="${CERT_DIR_RAW:-certs}"
case "$CERT_DIR_RAW" in
  /*) CERT_DIR="$CERT_DIR_RAW" ;;
  *)  CERT_DIR="$HOME/rasier/$CERT_DIR_RAW" ;;
esac

# Query the local API for the cloud link health.
state_json() {
  if [ -n "$TOKEN" ]; then
    curl -fsS --max-time 10 -H "X-Auth-Token: $TOKEN" "http://127.0.0.1:${PORT}/api/state" 2>/dev/null
  else
    curl -fsS --max-time 10 "http://127.0.0.1:${PORT}/api/state" 2>/dev/null
  fi
}

JSON="$(state_json || true)"
if [ -z "$JSON" ]; then
  log "API nicht erreichbar (Dienst neu gestartet?). Kein Eingriff."
  exit 0
fi

read -r CONNECTED LASTDATA <<EOF2
$(printf '%s' "$JSON" | python3 -c 'import sys,json; d=json.load(sys.stdin); l=d.get("link",{}); print(1 if l.get("connected") else 0, int(l.get("lastDataMs") or 0))')
EOF2

NOW_MS=$(( $(date +%s) * 1000 ))
AGE_S=$(( (NOW_MS - LASTDATA) / 1000 ))

if [ "$CONNECTED" = "1" ] && [ "$LASTDATA" -gt 0 ] && [ "$AGE_S" -lt "$STALE_S" ]; then
  # Healthy.
  exit 0
fi

log "Cloud-Verbindung ungesund (verbunden=$CONNECTED, Daten vor ${AGE_S}s)."

# Cooldown so we do not pull the image repeatedly while upstream has not updated.
LAST=0; [ -f "$STATE" ] && LAST="$(cat "$STATE" 2>/dev/null || echo 0)"
NOW_S=$(date +%s)
if [ $(( NOW_S - LAST )) -lt "$COOLDOWN_S" ]; then
  log "Letzter Zertifikatsversuch < Cooldown. Warte."
  exit 0
fi
echo "$NOW_S" > "$STATE"

log "Hole aktuelles hame-relay-Image und erneuere das Zertifikat…"
TMP="$(mktemp -d)"
cleanup() { docker rm -f rasier-cert-tmp >/dev/null 2>&1 || true; rm -rf "$TMP"; }
trap cleanup EXIT

docker pull "$IMAGE"
docker create --name rasier-cert-tmp "$IMAGE" >/dev/null
docker cp rasier-cert-tmp:/app/certs/ca.crt        "$TMP/ca.crt"
docker cp rasier-cert-tmp:/app/certs/hame-2025.crt "$TMP/client.crt"
docker cp rasier-cert-tmp:/app/certs/hame-2025.key "$TMP/client.key"
docker cp rasier-cert-tmp:/app/certs/hame-2025-url "$TMP/broker-url"

# Only replace if the new client cert differs from the current one.
if [ -f "$CERT_DIR/client.crt" ] && cmp -s "$TMP/client.crt" "$CERT_DIR/client.crt"; then
  log "Zertifikat unverändert – upstream hat noch nicht aktualisiert. Kein Neustart."
  exit 0
fi

mkdir -p "$CERT_DIR"
cp -f "$TMP/ca.crt" "$TMP/client.crt" "$TMP/client.key" "$TMP/broker-url" "$CERT_DIR/"
log "Neues Zertifikat installiert, starte Dienst neu…"
sudo systemctl restart "$SERVICE"
log "Fertig. Prüfe beim nächsten Lauf, ob die Verbindung wieder steht."
