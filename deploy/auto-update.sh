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

state_json() {
  if [ -n "$TOKEN" ]; then
    curl -fsS --max-time 10 -H "X-Auth-Token: $TOKEN" "http://127.0.0.1:${PORT}/api/state" 2>/dev/null
  else
    curl -fsS --max-time 10 "http://127.0.0.1:${PORT}/api/state" 2>/dev/null
  fi
}

# The server itself: it answers and the answer is the state document. The dashboard page is served
# straight from the jar and would answer even if the build were broken, so this asks the API.
server_ok() {
  state_json | python3 -c 'import sys,json; json.load(sys.stdin).get("link"); sys.exit(0)' 2>/dev/null
}

# The cloud link: connected, devices present, data recent.
link_ok() {
  state_json | python3 -c '
import sys, json, time
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
link = d.get("link") or {}
if not link.get("connected") or not d.get("batteries"):
    sys.exit(1)
last = link.get("lastDataMs") or 0
sys.exit(0 if (time.time() * 1000 - last) < 300000 else 1)
' 2>/dev/null
}

# Whether the cloud was reachable BEFORE the update decides whether it has to be reachable after.
# The Marstek cloud is not ours to control: while it was unreachable, every update failed its own
# health check and rolled itself back - including the update meant to deal with the unreachability.
# A link that was already down stays a warning; a link that was up and is now down is a regression.
LINK_WAS_OK=0
if link_ok; then LINK_WAS_OK=1; fi

wait_healthy() {
  local i
  for i in $(seq 1 20); do
    if server_ok; then
      if [ "$LINK_WAS_OK" = "0" ]; then
        log "Server antwortet. Cloud war schon vor dem Update nicht erreichbar - das zählt nicht als Rückschritt."
        return 0
      fi
      if link_ok; then return 0; fi
    fi
    sleep 3
  done
  if server_ok; then
    log "Server antwortet, aber die Cloud-Verbindung kam nach dem Update nicht zurück (vorher war sie da)."
  else
    log "Server antwortet nach dem Update nicht."
  fi
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
