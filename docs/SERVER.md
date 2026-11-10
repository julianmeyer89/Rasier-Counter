# Server auf der kostenlosen VM einrichten

Der Server (`server/`) läuft rund um die Uhr auf einer kleinen Gratis-VM, spricht über die
Marstek-Cloud mit den Geräten und stellt eine Weboberfläche bereit. Du rufst sie im Browser auf,
ganz ohne App und ohne etwas bei dir zu Hause laufen zu lassen.

> **Grauzone:** Der Server nutzt das Client-Zertifikat aus der Marstek-App (über das hame-relay-Image).
> Marstek kann es jederzeit ändern, dann steht die Verbindung still, bis das neue Zertifikat da ist.
> Nichts davon gehört ins Repository.

## Überblick

1. Gratis-VM bei Oracle Cloud anlegen.
2. Tailscale installieren, damit nur deine Geräte die Oberfläche erreichen.
3. Zertifikat aus dem hame-relay-Image holen.
4. Zweites Marstek-Konto anlegen und Geräte freigeben.
5. Server bauen, konfigurieren und als Dienst starten.

## 1. Oracle-VM

- Konto unter <https://www.oracle.com/cloud/free/> anlegen (Debitkarte zur Identitätsprüfung, es wird nichts abgebucht).
- Eine „Always Free“-VM erstellen: Shape `VM.Standard.E2.1.Micro` (AMD, immer kostenlos) oder eine Ampere-ARM-VM.
  Betriebssystem Ubuntu. Falls in der Region keine Kapazität frei ist, später erneut versuchen oder eine andere Region wählen.
- Java 21 installieren:
  ```bash
  sudo apt update && sudo apt install -y openjdk-21-jre-headless git
  ```

## 2. Tailscale (empfohlen)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```
Danach die Tailscale-App auf dem Handy installieren und mit demselben Konto anmelden.
Die Oberfläche erreichst du dann über die Tailscale-Adresse der VM, z. B. `http://100.x.y.z:8080`,
und sonst niemand. Der Server muss nicht ins öffentliche Internet.

## 3. Zertifikat holen

Die vier Dateien stecken im öffentlichen hame-relay-Image. Mit Docker auf der VM:
```bash
sudo apt install -y docker.io
sudo docker create --name hr ghcr.io/tomquist/hame-relay:latest
mkdir -p ~/rasier/certs
sudo docker cp hr:/app/certs/ca.crt            ~/rasier/certs/ca.crt
sudo docker cp hr:/app/certs/hame-2025.crt     ~/rasier/certs/client.crt
sudo docker cp hr:/app/certs/hame-2025.key     ~/rasier/certs/client.key
sudo docker cp hr:/app/certs/hame-2025-url     ~/rasier/certs/broker-url
sudo docker rm hr
sudo chown -R $USER ~/rasier/certs
```
In `~/rasier/certs/` liegen nun `ca.crt`, `client.crt`, `client.key` und `broker-url`.

## 4. Zweites Marstek-Konto

Ein Login mit dem Hauptkonto kann die offizielle App abmelden. Deshalb:
- In der Marstek-App ein zweites Konto anlegen (andere E-Mail).
- Die Geräte vom Hauptkonto aus für das zweite Konto freigeben.
- Im Server das zweite Konto eintragen.

## 5. Server bauen und starten

```bash
git clone https://github.com/julianmeyer89/Rasier-Counter.git ~/rasier/src
cd ~/rasier/src
./gradlew :server:installDist          # baut den Server
cp server/config.properties.example ~/rasier/config.properties
nano ~/rasier/config.properties        # email, password, cert_dir=/home/<user>/rasier/certs, auth_token ...
```

Testlauf:
```bash
./server/build/install/server/bin/server ~/rasier/config.properties
```
Dann im Browser `http://<tailscale-adresse>:8080` öffnen. Zum Ausprobieren ohne Geräte
`demo=true` setzen.

### Als Dauerdienst (systemd)

`/etc/systemd/system/rasier.service`:
```ini
[Unit]
Description=Rasier Speicher Server
After=network-online.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/rasier
ExecStart=/home/ubuntu/rasier/src/server/build/install/server/bin/server /home/ubuntu/rasier/config.properties
Restart=always
RestartSec=30

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl enable --now rasier
sudo journalctl -u rasier -f     # Log ansehen
```

## Bedienung

- **Übersicht:** Live-Werte, Ersparnis heute, Verlauf der letzten 24 Stunden.
- **Speicher:** beide Speicher einzeln, DoD und Lademodus von Hand setzen.
- **Statistik:** Woche/Monat/Jahr, und Eingabe der Zählerstände (1.8.0/2.8.0) vom Iskra-Zähler
  für exakte Werte.
- **Kalibrierung:** starten/abbrechen, Protokoll.
- **Einstellungen:** Tarif, Anlage, und der Schalter „Automatik darf Einstellungen ändern“
  (zu Beginn aus; erst einschalten, wenn die Werte stimmen).

## Wenn etwas nicht geht

- **Verbindung bricht ab / „Broker-URL fehlt“:** Zertifikatsdateien prüfen (Schritt 3), `cert_dir` korrekt?
- **„Falsches Passwort“:** zweites Konto, Passwort stimmt?
- **Keine Gerätedaten:** Stehen die Geräte in der Marstek-App online? Firmware-Stände wie erwartet?
- **Zertifikat getauscht:** Schritt 3 mit aktuellem Image wiederholen.

Der Server ist noch nicht an echten Geräten getestet (Firmware B2500 V110.9, CT002 124). Der erste
Lauf zeigt, ob die Cloud die erwarteten Werte liefert; Abweichungen bitte melden.

## Wartung automatisieren (optional, empfohlen)

Damit du dich um nichts mehr kümmern musst, richtet ein Skript automatische OS-Updates (inkl.
nächtlichem Neustart), automatische App-Updates mit Gesundheitscheck und Rollback, sowie einen
Marstek-Zertifikat-Wächter ein. Einmalig auf der VM:

```
cd ~/rasier/src && git pull
sudo bash deploy/install-automation.sh
```

Das richtet ein:
- **Automatische Sicherheitsupdates** von Ubuntu, bei Bedarf Neustart um 04:00 (Dienst startet danach von allein).
- **rasier-update.timer** (täglich 03:30): holt neue Versionen, testet und baut sie, startet nur bei
  erfolgreichem Bau neu und macht bei Fehlern automatisch einen Rollback auf die vorige Version.
- **rasier-certwatch.timer** (alle 30 min): erkennt, wenn die Cloud-Verbindung wegen eines
  getauschten Marstek-Zertifikats abbricht, holt automatisch das aktuelle hame-relay-Image, zieht das
  neue Zertifikat und startet neu (mit 6-Stunden-Cooldown gegen wiederholte Versuche).

Prüfen/ansehen:
```
systemctl list-timers | grep rasier
journalctl -u rasier-update -n 30 --no-pager
journalctl -u rasier-certwatch -n 30 --no-pager
```

Rückgängig machen:
```
sudo systemctl disable --now rasier-update.timer rasier-certwatch.timer
```
