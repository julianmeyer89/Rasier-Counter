#!/usr/bin/env bash
# One-time setup of the maintenance automation on the VM. Run with sudo:
#   sudo bash deploy/install-automation.sh
# Idempotent: safe to run again. Sets up automatic OS updates + reboot, grants the service user the
# few rights the scripts need, and installs the auto-update and cert-watch systemd timers.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then echo "Bitte mit sudo ausführen: sudo bash deploy/install-automation.sh"; exit 1; fi

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_USER="$(stat -c '%U' "$REPO_DIR")"
USER_HOME="$(getent passwd "$RUN_USER" | cut -d: -f6)"
CONFIG="$USER_HOME/rasier/config.properties"
SERVICE="rasier"

echo "Repository : $REPO_DIR"
echo "Dienst-User: $RUN_USER ($USER_HOME)"
echo "Konfig     : $CONFIG"
[ -f "$CONFIG" ] || { echo "FEHLER: $CONFIG nicht gefunden. Erst den Server einrichten."; exit 1; }
systemctl list-unit-files | grep -q "^${SERVICE}.service" || { echo "FEHLER: Dienst '$SERVICE' nicht installiert."; exit 1; }

echo "== Pakete sicherstellen =="
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq unattended-upgrades curl python3 docker.io >/dev/null

echo "== Skripte ausführbar machen =="
chmod +x "$REPO_DIR/deploy/auto-update.sh" "$REPO_DIR/deploy/cert-watch.sh"

echo "== Docker-Zugriff für $RUN_USER (für den Zertifikat-Wächter) =="
usermod -aG docker "$RUN_USER"

echo "== Passwortloses Neustarten des Dienstes erlauben =="
cat > /etc/sudoers.d/rasier <<EOF
$RUN_USER ALL=(root) NOPASSWD: /usr/bin/systemctl restart $SERVICE, /usr/bin/systemctl start $SERVICE, /usr/bin/systemctl stop $SERVICE
EOF
chmod 440 /etc/sudoers.d/rasier
visudo -cf /etc/sudoers.d/rasier >/dev/null

echo "== Automatische OS-Updates + nächtlicher Neustart =="
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/52rasier-unattended <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:00";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
EOF
systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true

echo "== systemd-Timer installieren =="
write_unit() { cat > "/etc/systemd/system/$1"; echo "  /etc/systemd/system/$1"; }

write_unit rasier-update.service <<EOF
[Unit]
Description=Rasier/Heim-PV: sichere Auto-Updates
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$RUN_USER
Environment=HOME=$USER_HOME
Environment=RASIER_REPO_DIR=$REPO_DIR
Environment=RASIER_CONFIG=$CONFIG
ExecStart=$REPO_DIR/deploy/auto-update.sh
EOF

write_unit rasier-update.timer <<'EOF'
[Unit]
Description=Täglicher Auto-Update-Lauf

[Timer]
OnCalendar=*-*-* 03:30
Persistent=true
RandomizedDelaySec=300

[Install]
WantedBy=timers.target
EOF

write_unit rasier-certwatch.service <<EOF
[Unit]
Description=Rasier/Heim-PV: Marstek-Zertifikat-Wächter
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$RUN_USER
Environment=HOME=$USER_HOME
Environment=RASIER_CONFIG=$CONFIG
ExecStart=$REPO_DIR/deploy/cert-watch.sh
EOF

write_unit rasier-certwatch.timer <<'EOF'
[Unit]
Description=Zertifikat-Wächter alle 30 Minuten

[Timer]
OnCalendar=*:0/30
Persistent=true
RandomizedDelaySec=120

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now rasier-update.timer rasier-certwatch.timer

echo
echo "== Fertig. Aktive Timer: =="
systemctl list-timers --no-pager | grep -E 'rasier-(update|certwatch)' || true
echo
echo "Testläufe von Hand (optional):"
echo "  sudo systemctl start rasier-update.service   && journalctl -u rasier-update -n 30 --no-pager"
echo "  sudo systemctl start rasier-certwatch.service && journalctl -u rasier-certwatch -n 30 --no-pager"
