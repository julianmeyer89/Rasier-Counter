# Konzept

Stand: November 2026. Die erste Fassung ging von einem Raspberry Pi und einer OpenDTU aus.
Nach den Vorgaben (keine Kosten, keine Zusatzhardware, Marstek-Cloud bleibt aktiv,
Wechselrichter bleibt auf 1600 W) sieht der Aufbau jetzt so aus.

## Anlage

| Gerät | Details |
|---|---|
| Speicher 1 | B2500-D (HMJ-2), FW V110.9, 192.168.178.23, 2 PV-Module |
| Speicher 2 | B2500-D (HMJ-2), FW V110.9, 192.168.178.24, 2 PV-Module |
| Zähler | CT002 (HME-4), FW 124, 192.168.178.25, dreiphasig |
| Wechselrichter | Hoymiles HMS-1600-4T, 1600 W, ohne DTU. Speicher 1 an Eingang 1+2, Speicher 2 an Eingang 3+4 |
| Module | 2000 Wp gesamt |

## Entscheidungen

1. **Alles in der Android-App.** Es gibt kein Gerät, das dauerhaft im Heimnetz läuft. Die App bringt deshalb einen
   Hintergrunddienst mit, der den „Webdienst“ ersetzt. Die Logik steckt in `core/` (reines Kotlin), damit sie
   später auch auf einem PC laufen kann.
2. **Nulleinspeisung bleibt beim CT002.** Sie über einen virtuellen CT selbst zu übernehmen, bräuchte ein Gerät im
   Heimnetz, das rund um die Uhr läuft.
3. **Datenwege:**
   - Bluetooth zu den Speichern: lesen und schreiben, kurze Verbindungszyklen
   - UDP zum CT002: passiv lesen
   - Marstek-Cloud: nur HTTP, nur lesen, als Rückfall
4. **Keine Cloud-Steuerung über MQTT.** Dafür wären Client-Zertifikate aus der Marstek-App nötig. Diese App nutzt
   sie bewusst nicht.
5. **Hoymiles:** Ohne DTU gibt es keinen Zugriff. Die Wechselrichterleistung wird aus der Speicherabgabe geschätzt.
   Eine OpenDTU (ESP32 + CMT2300A) ließe sich später ergänzen.

## Funktionen

- **Gruppe:** gemeinsamer Ladestand (gewichtet nach Kapazität), Gesamtleistung, Ladestand-Ausgleich. Liegen die
  Ladestände um mindestens 15 % auseinander, wird der leerere Speicher beim Entladen über die DoD pausiert, bis
  beide wieder gleich sind. Das verhindert, dass ein leerer Speicher den Wechselrichter abschaltet.
- **Kalibrierung:** Laden auf 100 % mit „erst voll laden“, 100 % halten, entladen bis DoD-Grenze, Untergrenze
  halten, danach die Einstellungen wiederherstellen. Immer ein Speicher nach dem anderen, mit Zeitlimit,
  abbrechbar und über Neustarts hinweg gespeichert. Läuft automatisch alle 45 Tage (einstellbar).
- **Statistik:** Leistungswerte werden zu kWh integriert:
  - Netzbezug und Einspeisung getrennt
  - Hausverbrauch = Netz + Wechselrichter-AC
  - Eigenverbrauch = AC − Einspeisung
  - Ersparnis = Eigenverbrauch × Bezugspreis + Einspeisung × Vergütung
- **Überwachung:**
  - Speicher nicht erreichbar
  - Netzbezug, obwohl die Speicher geladen sind und nichts abgeben (CT002-Verbindung gestört)
  - Einspeisung, obwohl die Speicher abgeben
  - starke Abweichung der Ladestände
- **Sicherheit:** Die Automatik schreibt nur, wenn das eingeschaltet ist, und höchstens 8-mal pro Speicher und Tag.
  Gefährliche Befehle sind gesperrt. Zeitpläne werden nie automatisch geschrieben, weil das den adaptiven Modus beendet.

## Grenzen

- Statistik und Automatik laufen nur, solange die App oder der Hintergrunddienst läuft. Bluetooth und CT002
  funktionieren nur zu Hause.
- Die Cloud liefert für den B2500 möglicherweise nur selten Daten. Unterwegs zeigt die App daher nur ungefähre Werte.
- Die Protokolle stammen aus Reverse-Engineering und sind auf FW V110.9 noch nicht getestet.

## Rechtlicher Hinweis (keine Rechtsberatung)

Mit 1600 W Wechselrichterleistung ist die Anlage kein Steckersolargerät mehr (Grenze 800 VA). Sie braucht dann
die Anmeldung beim Netzbetreiber, die Inbetriebnahme durch eine Elektrofachkraft und einen Eintrag im
Marktstammdatenregister. Der Speicher wird dort separat eingetragen.
