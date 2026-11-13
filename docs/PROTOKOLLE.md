# Geräteprotokolle (Zusammenfassung)

Marstek dokumentiert keine dieser Schnittstellen. Diese Zusammenfassung stammt aus
Community-Reverse-Engineering. Die Umsetzung in `core/` ist eigenständig geschrieben, es wurde kein
fremder Code übernommen.

Quellen (nur als Protokollreferenz genutzt):
- B2500 Bluetooth: tomquist/hmjs (MIT), SolarPixelWerk/Android-Marstek-B2500-Reader (MIT),
  sonntam/marstek-relay-esp32 (MIT), Buschow2026/hame-b2500-local-poller (MIT),
  tomquist/esphome-b2500, noone2k/hm2500pub, Gadgetbridge (AGPL)
- CT002 UDP: tomquist/AstraMeter `docs/ct002-ct003-protocol.md` (GPL-3.0, nur Beschreibung genutzt)
- Marstek-Cloud HTTP: tomquist/hame-relay, marstek_cloud, loxberry-marstek-cloud, homey-marstek-connector

## 1. B2500 über Bluetooth LE

| | |
|---|---|
| Service | `0000ff00-0000-1000-8000-00805f9b34fb` |
| Schreiben | `ff01`, ohne Antwort (write without response) |
| Antworten | Notifications auf `ff02` (CCCD `2902` aktivieren) |
| Koppeln | keine Kopplung, keine PIN; MTU 517 anfordern |
| Verbindungen | nur **eine** gleichzeitig, deshalb kurze Zyklen: verbinden → lesen → trennen; Speicher nacheinander abfragen |

Rahmen: `0x73 | LEN (Gesamtlänge) | 0x23 | CMD | Nutzdaten | XOR aller vorherigen Bytes`.
Bei Laufzeitantworten passt das LEN-Byte nicht zur Länge der Notification (es steht dort `0x10`).
Für diese Antworten werden deshalb weder LEN noch XOR geprüft.

### Lesebefehle (ungefährlich)
| CMD | Bedeutung | Anfrage |
|---|---|---|
| 0x03 | Laufzeitdaten | `73 06 23 03 01 54` |
| 0x04 | Geräteinfo (`type=HMJ-2,id=…,mac=…`) | `73 06 23 04 01 53` |
| 0x0F | Zellspannungen (reiner ASCII-Text `SOC_T1_T2_c1_…`) | `73 06 23 0F 01 58` |
| 0x13 | Zeitpläne und adaptiver Modus | `73 06 23 13 00 45` |

Laufzeitdaten (0x03), Werte Little-Endian:

| Offset | Inhalt |
|---|---|
| 4, 5 | Zustand der PV-Eingänge 1 und 2 |
| 6, 8 | PV-Leistung 1 und 2 (u16, W) |
| 10 | Ladestand (u16, 0,1 %) |
| 12 | Firmware Hauptversion |
| 13 | Lademodus (0 = gleichzeitig laden/entladen, 1 = erst voll laden) |
| 14 | Ausgänge freigegeben (Bitmaske) |
| 15 | Bit 0 = WLAN, Bit 1 = Cloud |
| 16, 17 | Ausgang 1 und 2 aktiv |
| 18 | Entladetiefe (DoD) in % |
| 22 | Restenergie (u16, Wh) |
| 24, 26 | Ausgangsleistung 1 und 2 (u16, W) |
| 31, 32 | Uhrzeit des Geräts (Stunde, Minute) |
| 33, 35 | Temperatur min. und max. (i16, °C) |
| 39 | Firmware Unterversion (110.**9**) |
| 40, 44, 48, 52 | Tageswerte: Akku laden, Akku entladen, PV, Ausgang (u32, Wh) |

### Schreibbefehle (jeder Schreibvorgang landet im Flash)
| CMD | Bedeutung | Beispiel |
|---|---|---|
| 0x0B | DoD setzen (0–100) | `73 06 23 0b 5a 07` = 90 % |
| 0x0D | Lademodus | `73 06 23 0d 01 5a` = erst voll laden |
| 0x11 | adaptiven Modus (CT) einschalten | `73 06 23 11 00 47` |

**Gesperrt** (die App kann sie nicht senden): 0x02, 0x05 (WLAN), 0x20/0x21 (MQTT), 0x25 (Neustart),
0x26 (Werksreset), 0x2A, 0x60–0x62, außerdem jeder Schreibzugriff auf `ff06`.
Zeitpläne (0x12) schreibt die App nicht automatisch, weil das den adaptiven CT-Modus beendet.

## 2. CT002 über UDP (Port 12345)

Rahmen: `SOH STX LEN(ASCII, Gesamtlänge) |f1|f2…|fN ETX CHK`. CHK besteht aus 2 Hex-Zeichen
(XOR von SOH bis ETX). Der CT schickt manchmal ein Leerzeichen statt der ersten Hex-Ziffer.

**Passives Lesen**: Die App sendet `VNSE3-0 | <eigene ID> | HME-4 | 000000000000 | 0 | 0 | 0`,
also Phase 0, Leistung 0 und „nimmt nicht teil“. Damit wird sie in keiner Phase mitgezählt und
stört die Regelung der Speicher nicht. Die eigene ID wird zufällig erzeugt (`02a57e…`) und ist **nie**
die ID eines Speichers. Sonst würde der Speicher seine Zählerwerte verlieren.

Die Antwort hat 24 Felder (HME-4) bzw. 28 Felder (neuere Hardware mit kWh-Zählern):
Feld 5–7 = Leistung je Phase, Feld 8 = Summe (W). Positiv bedeutet Netzbezug, negativ Einspeisung.

Der CT beantwortet insgesamt nur etwa 2 Anfragen pro Sekunde, für alle Teilnehmer zusammen.
Die App fragt deshalb höchstens alle paar Sekunden über einen dauerhaft geöffneten Socket ab.

## 3. Marstek-Cloud (HTTP, nur lesen)

- Login: `GET https://eu.hamedata.com/app/Solar/v2_get_device.php?mailbox=<E-Mail>&pwd=<md5(Passwort)>`
  - `code` "2" (oder "1") mit `token` bedeutet erfolgreich angemeldet
  - "3" bedeutet unbekanntes Konto, "4" falsches Passwort
- Geräte: `GET /ems/api/v1/getDeviceList?token=…&mailbox=…`; bei Code 8 neu anmelden.
- Über **diese HTTP-API** aktualisiert die Cloud B2500-Daten nur selten, und der CT002-Wert kommt
  dort etwa alle 5 Minuten und ohne Vorzeichen. Deshalb nutzt die App die HTTP-API nur als Rückfall.
  **Achtung, das gilt nicht für MQTT:** auf dem Statustopic liefern die Geräte im Sekundentakt, und
  `pwr_t` ist dort vorzeichenbehaftet (negativ = Einspeisung). Der Server nutzt ausschließlich MQTT.
- Steuern über die Cloud ist nur per MQTT mit Zertifikaten aus der Marstek-App möglich.
  Das nutzt diese App bewusst **nicht**.
- Ein Login mit dem Hauptkonto kann die offizielle App abmelden. Abhilfe: ein zweites Konto anlegen
  und die Geräte damit teilen.
