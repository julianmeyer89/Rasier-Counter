# Kalibrierung – Arbeitsstand / offene Umsetzung (WIP)

> Fortsetzen mit dem Stichwort **„fortsetzen mit Kalibrierung"**.
> Diese Datei hält den abgestimmten Entwurf fest. Der Ablauf ist **noch nicht** in
> `core/.../control/Calibration.kt` umgesetzt – es fehlen die unten offenen Antworten 1–4.

## Was bereits umgesetzt & am echten Gerät bestätigt ist
- **Umschalten Auto ↔ Manuell über die Cloud funktioniert.**
  - `CloudPayloads.encode(SetAdaptiveMode(enabled))` → `cd=4,md=1` (Automatik) bzw. `cd=4,md=0` (Manuell).
  - UI: Buttons „Auf Manuell (Zeitplan)" / „Auf Automatik" auf der Speicher-Seite.
  - Der Nutzer hat in der Marstek-App einen Zeitplan **00:00–23:59 / 800 W** gesetzt → im
    Manuell-Modus entlädt sich der Speicher damit zuverlässig (auch wenn morgens wieder PV kcommt).

## Harte Randbedingung
- **Kein Geräte-Neustart möglich**: Der Reboot-Befehl ist bewusst gesperrt
  (`B2500Protocol.FORBIDDEN`) und existiert über die Cloud nicht. Der manuelle Fix des Nutzers
  enthält aber einen Neustart → nicht 1:1 automatisierbar. Automatisieren, was geht; sonst den
  Nutzer gezielt zum kurzen Neustart auffordern (Alarm).

## Abgestimmter Ablauf (je Speicher, nacheinander)
1. **Vorbereiten**: sichern von **DoD, Lademodus, Auto/Manuell** · Lademodus „erst voll laden" · **DoD = 0 %**.
2. **Laden**: warten bis **SOC ≥ 100 %** (DoD 0 % verhindert vorzeitiges Entladen; mehrtägig möglich).
3. **100 % halten**: **30 min** (runter von 60).
4. **Auf Abend warten**: bis **PV < 10 W seit 20 min** am Stück.
5. **Entladen**: **auf Manuell schalten** (800-W-Zeitplan leert ihn), warten bis **SOC ~0 %**.
6. **Untergrenze halten**: 30 min.
7. **Zurück + CT002-Verbindung sichern**:
   - Lademodus/DoD wiederherstellen.
   - **Unsere CT002-Abfrage ~60–90 s pausieren** (Hypothese: unser Sekundentakt nimmt dem Speicher
     einen CT002-Slot; Pause gibt ihm Luft zum Andocken) → **auf Automatik** → Pause beenden.
   - **Reconnect-Prüfung** (laufender Wächter, nicht sofort, da Speicher nach Entladen leer ist):
     Fehlzustand = Speicher geladen **und** CT002 meldet Netzbezug, aber Abgabe mehrere Minuten ~0 W.
   - **Auto-Erholung** (ohne Neustart): CT002-Abfrage pausieren → Manuell → kurz warten → Automatik →
     Pause beenden → erneut prüfen. Bis zu **N Versuche**.
   - Klappt es nicht → **Dashboard-Alarm**: „Speicher X: CT002 nach Kalibrierung nicht verbunden –
     bitte Gerät kurz neustarten." Wächter prüft weiter, löscht Alarm bei wieder normaler Abgabe.

## Feste Werte (nicht im Dashboard einstellbar)
- 100 % halten: 30 min · Entlade-DoD: über Manuell-Modus (Zeitplan) · Untergrenze halten: 30 min ·
  Zeitlimit pro Speicher: 120 h · DoD beim Laden: 0 %.

## Offen – vor der Umsetzung vom Nutzer zu klären
1. CT002-Polling-Pause beim Umschalten einbauen? (Empfehlung: **ja**)
2. Anzahl automatischer Erholungsversuche vor Alarm? (Vorschlag: **3**)
3. Bei hängendem Speicher: nächsten trotzdem kalibrieren (Empfehlung) oder erst stoppen?
4. Reicht Dashboard-Alarm, oder zusätzlich echte Push-Benachrichtigung aufs Handy?

## Betroffene Dateien bei der Umsetzung
- `core/src/main/kotlin/de/rasier/speicher/control/Calibration.kt` (Phasen-Logik, neue Schritte 1/5/7)
- `core/.../control/CalibrationSettings` (Haltezeit 100 % auf 30 min)
- `core/.../control/AutomationEngine.kt` (Einbindung, Wächter, Alarm) ggf. `Monitor`
- `server/.../CloudDeviceLink.kt` / `ServerHub.kt` (CT002-Abfrage pausieren können)
- Evtl. UI-Hinweis im Tab „Kalibrierung".
