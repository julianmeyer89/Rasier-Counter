# Kalibrierung (umgesetzt)

Der Ablauf ist in `core/src/main/kotlin/de/rasier/speicher/control/Calibration.kt` umgesetzt und
läuft über die Marstek-Cloud. Diese Datei beschreibt, was der Zustandsautomat tut und warum.

## Warum überhaupt

Marstek kennt keinen Kalibrierbefehl. Der Speicher **schätzt** seinen Ladestand, und diese Schätzung
driftet. Ein voller Zyklus (ganz voll, ganz leer, wieder voll) lässt den BMS beide Endpunkte neu
sehen. Es wird immer nur **ein** Speicher kalibriert, der andere versorgt weiter das Haus.

## Ablauf je Speicher

1. **Laden** – Lademodus „erst laden", Entladetiefe 0 %, bis 100 %. Darf mehrere Tage dauern.
2. **Halten** – Haltezeit bei 100 % (Standard 30 min), damit der obere Punkt sitzt.
3. **Auf Abend warten** – bis die PV lange genug unter der Schwelle liegt. Bis dahin darf der
   Ladestand durch Selbstentladung bis zur eingestellten Toleranz sinken, ohne nachzuladen.
4. **Abend-Entladung** – im Automodus, damit das Haus die Energie nutzt.
5. **Rest leeren** – ab der eingestellten Uhrzeit im Manuell-Modus über den in der Marstek-App
   hinterlegten Zeitplan (800 W, 00:00–23:59) bis ~0 %.
6. **Erneut laden** – Entladetiefe 0 %, zurück auf 100 %, nochmal halten.
7. **Freigeben und prüfen** – zurück auf „gleichzeitig laden/entladen" im Automodus mit der
   ursprünglichen Entladetiefe, dann Nulleinspeisung prüfen.

Schlägt die Prüfung fehl: Manuell → Neustart → warten, bis das Gerät wieder meldet → Auto, bis zu
den eingestellten Versuchen. Danach Stopp mit Push.

## Was abgesichert ist

- **Vorab-Prüfung:** Schreibrecht, Entladetiefe, hinterlegter Manuell-Zeitplan und die Machbarkeit
  aus dem PV-Ertrag der letzten Tage. Ohne `force` startet der Lauf sonst nicht.
- **Befehle werden gegengeprüft.** Cloud-Befehle gehen mit QoS 0 raus und können verloren gehen;
  jede Einstellung wird mit dem verglichen, was das Gerät meldet (`do`, `cs`, `md`), und erneut
  gesendet, bis sie passt.
- **Die Prüf-Phase verlangt die bestätigte Freigabe.** Ein Speicher, der noch im Manuell-Modus mit
  800 W läuft, gilt nicht als Erfolg, auch wenn er Leistung abgibt.
- **Nach dem Neustart** zählt das Gerät erst als zurück, wenn ein Datensatz von *nach* dem Neustart
  ankommt. Kommt es gar nicht zurück, wird gestoppt und wiederhergestellt.

## Neustart über die Cloud

`cd=10` startet den Speicher neu (am Gerät gemessen: rund 35 s). Er wird **ausschließlich** intern
von der Recovery ausgelöst, es gibt keinen Knopf und keine API dafür. Lokal über Bluetooth ist der
Neustart gesperrt (`B2500Protocol.FORBIDDEN`). `cd=11` wäre ein Werksreset und wird nie erzeugt.
