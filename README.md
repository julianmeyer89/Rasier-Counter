# Rasier Speicher

Eigene Android-App für **2× Marstek B2500-D**, den **CT002** und einen **Hoymiles HMS-1600-4T**.
Ersetzt die Marstek-App nicht vollständig, sondern ergänzt das, was dort fehlt:

| Problem mit der Marstek-App | Lösung in dieser App |
|---|---|
| Kalibrierung nur von Hand | **Automatische Kalibrierung** (Vollzyklus, ein Speicher nach dem anderen, Einstellungen werden wiederhergestellt) |
| Keine Auswertung von Verbrauch, Einspeisung, Ersparnis | **Statistik** pro Tag/7 Tage/30 Tage/Jahr mit Ersparnis in €, Autarkie, Bezug, Einspeisung |
| Verbindungsprobleme CT002 ↔ Speicher | **Überwachung mit Warnung**, wenn die Speicher nicht auf den CT002 reagieren |
| Keine passende Gruppe für 2 Speicher an einem Wechselrichter | **Gruppenansicht** und **Ladestand-Ausgleich** zwischen beiden Speichern |

Alles kostenlos, ohne Zusatzhardware, ohne eigenen Server. Die Marstek-Cloud bleibt aktiv.

## So funktioniert es

```
 Handy (diese App)
  ├─ Bluetooth ──► B2500 #1, B2500 #2   (lesen + Einstellungen ändern, wenn das Handy in der Nähe ist)
  ├─ WLAN/UDP ──► CT002                 (nur lesen, passiv – stört die Regelung nicht)
  └─ Internet ──► Marstek-Cloud         (nur lesen, Rückfall von unterwegs)

 CT002 ──WLAN──► B2500 ──DC──► HMS-1600-4T ──► Hausnetz     (Nulleinspeisung regelt weiterhin der CT002)
```

- Die sekundenschnelle **Nulleinspeisung bleibt beim CT002**. Ohne ein Gerät, das dauerhaft im Heimnetz
  läuft, kann sie keine App zuverlässig übernehmen.
- Die App macht die **langsame Steuerung**: Kalibrierung, Ladestand-Ausgleich, Warnungen und Statistik.
- **Hoymiles:** Der HMS-1600-4T hat kein WLAN. Ohne DTU ist kein Zugriff möglich, deshalb wird seine
  Leistung aus der Abgabe der Speicher geschätzt (Wirkungsgrad einstellbar).

## Installation

1. Unter GitHub → **Actions** → letzter Lauf von „Build“ → Artefakt **rasier-speicher-debug-apk** herunterladen.
2. Die ZIP-Datei entpacken und die APK auf dem Handy öffnen. Dafür muss „Installation aus unbekannten Quellen“ erlaubt sein.
3. In der App unter **Einstellungen**:
   - „Bluetooth-Berechtigung erteilen“
   - bei beiden Speichern auf **Suchen** tippen und den jeweiligen Speicher auswählen (die Marstek-App vorher schließen)
   - die CT002-IP prüfen (192.168.178.25)
   - optional das Marstek-Konto eintragen und **Anmeldung testen**
   - den Tarif eintragen
   - **Echte Geräte** wählen und **Speichern**
4. Optional „Im Hintergrund weiterlaufen“ einschalten und die Akku-Optimierung abschalten.
   Nur so laufen Statistik und Automatik auch bei geschlossener App.

Zum Ausprobieren ohne Geräte startet die App im **Demo-Modus** mit simulierten Speichern.

## Sicherheit

- „Automatik darf Einstellungen ändern“ ist zu Beginn **aus**. Bis du es einschaltest, macht die Automatik nur Vorschläge.
- Jede Einstellungsänderung wird im Flash des Speichers gespeichert. Deshalb gilt für die Automatik ein Limit
  von höchstens 8 Änderungen pro Speicher und Tag.
- Gefährliche Bluetooth-Befehle (WLAN, MQTT, Neustart, Werksreset) sind im Code gesperrt.
- Vom Marstek-Passwort wird nur ein Hash gespeichert, verschlüsselt im Android-Keystore. Es landet nie im Repository.
- Ein Login mit dem Hauptkonto kann die offizielle Marstek-App abmelden. Besser ist ein zweites Konto,
  für das die Geräte freigegeben werden.

## Projektstruktur

| Ordner | Inhalt |
|---|---|
| `core/` | Reine Kotlin-Logik: Protokolle (Bluetooth, CT002, Cloud), Statistik, Kalibrierung, Ausgleich, Überwachung, Simulator. Getestet mit `./gradlew :core:test` |
| `app/` | Android-App (Jetpack Compose), Bluetooth- und WLAN-Zugriff, Hintergrunddienst |
| `docs/` | Konzept und Protokollbeschreibungen |

## Noch ungeprüft am echten Gerät

Alle Protokolle stammen aus Community-Reverse-Engineering und wurden mit Testdaten geprüft, aber noch nicht an
deinen Geräten (Firmware V110.9 / CT002 FW 124). Beim ersten Test bitte auf Folgendes achten:
- Werden SOC, PV und Ausgang der Speicher plausibel angezeigt?
- Kommen Werte vom CT002 („Verbindungen“ in der Übersicht)?
- Wird eine DoD-Änderung von Hand übernommen (Reiter Speicher)?

Fehlermeldungen aus „Verbindungen“ bzw. dem Protokoll im Reiter Kalibrierung helfen bei der Anpassung.
