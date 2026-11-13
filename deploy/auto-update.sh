#!/usr/bin/env bash
# Safe auto-update for the Rasier/Heim-PV server.
# Pulls new commits, tests and builds them, restarts the service only on success, and rolls
# back to the previous commit if the build or the health check fails. Designed to run as the
# "ubuntu" user from a systemd timer. Logs to the journal (stdout/stderr).
set -euo pipefail

REPO_DIR="${RASIER_REPO_DIR:-$HOME/rasier/src}"
CONFIG="${RASIER_CONFIG:-$HOME/rasier/config.properties}"
SERVICE="${RASIER_SERVICE:-rasier}"
BRANCH="${RASIER_BRANCH:-claude/marstek-app-f4a5gl}"
LOCK="/tmp/rasier-auto-update.lock"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# Only one update at a time.
exec 9>"$LOCK"
if ! flock -n 9; then
  log "Ein anderer Update-Lauf ist aktiv, überspringe."
  exit 0
fi

cfg() { grep -E "^$1=" "$CONFIG" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]' || true; }
PORT="$(cfg web_port)"; PORT="${PORT:-8080}"
TOKEN="$(cfg auth_token)"

# Healthy means the server really works, not just that it answers. The dashboard page is served
# straight from the jar and would answer even if the update broke the cloud link or the parsing,
# so an update that blinds the system would otherwise count as a success.
health_ok() {
  local json
  if [ -n "$TOKEN" ]; then
    json="$(curl -fsS --max-time 10 -H "X-Auth-Token: $TOKEN" "http://127.0.0.1:${PORT}/api/state" 2>/dev/null)" || return 1
  else
    json="$(curl -fsS --max-time 10 "http://127.0.0.1:${PORT}/api/state" 2>/dev/null)" || return 1
  fi
  printf '%s' "$json" | python3 -c '
import sys, json, time
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
link = d.get("link") or {}
if not link.get("connected"):
    sys.exit(1)
# Devices must actually be there, and their data must be recent.
if not d.get("batteries"):
    sys.exit(1)
last = link.get("lastDataMs") or 0
sys.exit(0 if (time.time() * 1000 - last) < 300000 else 1)
' 2>/dev/null
}

wait_healthy() {
  for _ in $(seq 1 20); do
    if health_ok; then return 0; fi
    sleep 3
  done
  return 1
}

build() { ( cd "$REPO_DIR" && ./gradlew --no-daemon :server:test :server:installDist ); }

restart() { sudo systemctl restart "$SERVICE"; }

cd "$REPO_DIR"
git config --global --add safe.directory "$REPO_DIR" 2>/dev/null || true

PREV="$(git rev-parse HEAD)"
log "Aktueller Stand: $PREV"

git fetch --quiet origin "$BRANCH"
REMOTE="$(git rev-parse "origin/$BRANCH")"
if [ "$PREV" = "$REMOTE" ]; then
  log "Keine Änderungen."
  exit 0
fi

log "Neue Version gefunden: $REMOTE. Hole und baue…"
if ! git merge --ff-only "origin/$BRANCH"; then
  log "FEHLER: Fast-Forward nicht möglich (lokale Änderungen?). Breche ab, lasse laufende Version unangetastet."
  exit 1
fi

rollback() {
  log "ROLLBACK auf $PREV."
  git reset --hard "$PREV"
  if build; then
    restart
    if wait_healthy; then log "Rollback erfolgreich, alte Version läuft wieder."; else log "WARNUNG: Rollback gebaut, aber Health-Check fehlgeschlagen."; fi
  else
    log "KRITISCH: Rollback-Build fehlgeschlagen. Bitte manuell prüfen."
  fi
}

if ! build; then
  log "FEHLER: Test/Build der neuen Version fehlgeschlagen."
  rollback
  exit 1
fi

log "Build ok, starte Dienst neu…"
restart
if wait_healthy; then
  log "Update erfolgreich auf $(git rev-parse HEAD)."
else
  log "FEHLER: Health-Check nach Update fehlgeschlagen."
  rollback
  exit 1
fi
