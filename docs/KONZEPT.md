# Konzept: Eigene Steuerung für 2× Marstek B2500 + CT002 + Hoymiles HMS-1600-4T

Stand: November 2026 – Entwurf, basiert auf Recherche öffentlicher Community-Projekte
(hm2mqtt, hame-relay, hmjs, AstraMeter, OpenDTU). Alle Geräteschnittstellen sind
**reverse-engineered**, es gibt keine offizielle lokale API für B2500/CT002.

## 1. Ausgangslage

| Gerät | Details |
|---|---|
| Speicher 1 | Marstek B2500 (vermutlich V2, Typ HMJ), FW V110.9, 192.168.178.23, 2 PV-Module |
| Speicher 2 | Marstek B2500 (vermutlich V2, Typ HMJ), FW V110.9, 192.168.178.24, 2 PV-Module |
| Zähler | Marstek CT002, FW 124 (vermutlich Hardware HME-4), 192.168.178.25 |
| Wechselrichter | Hoymiles HMS-1600-4T, 4 Eingänge: Speicher 1 → Eingang 1+2, Speicher 2 → Eingang 3+4 |

Topologie: `PV → B2500 (DC) → HMS-1600-4T (AC) → Hausnetz`. Der Wechselrichter ist der einzige
Punkt, an dem Energie ins Hausnetz geht.

## 2. Wichtige Erkenntnisse

- **B2500 hat keine „Open API“** (die gibt es nur bei der Venus-Serie). Lokal geht nur **MQTT**:
  Der B2500 kann auf einen eigenen MQTT-Broker umgestellt werden (per Bluetooth mit
  [hmjs](https://tomquist.github.io/hmjs/) oder über den Marstek-Support). Protokoll ist
  dokumentiert (hm2mqtt). Alternativ: Cloud bleibt aktiv und hame-relay spiegelt die Daten lokal.
- **CT002** spricht per **UDP (Port 12345, Broadcast)** mit den Speichern. Er liefert nur Leistung,
  **keine kWh-Zähler** (HME-4) → Verbrauch/Einspeisung müssen wir selbst aufintegrieren.
  Verbindungsprobleme entstehen häufig durch Mesh-Repeater/Gast-WLAN/Client-Isolation, die
  UDP-Broadcasts verschlucken, oder zu viele Abfrager am CT002.
- **Marstek-App-Gruppierung** („Combination“) schaltet Ausgang 2 ab und halbiert die Leistung →
  die Gruppe gehört in unsere Software.
- **HMS-1600-4T hat kein WLAN** (Sub-1-GHz-Funk). Lokaler Zugriff nur über eine DTU:
  **OpenDTU** (ESP32 + CMT2300A, ~40–60 €, vollständig lokal) oder Hoymiles DTU (Cloud).
- **Inverter-Firmware** ist entscheidend: ab 01.01.12 verteilt der HMS ein 800-W-Limit
  über alle Eingänge („Power Distribution Logic“). Ältere FW → max. ~200 W pro Eingang, d. h. ein
  einzelner Speicher schafft nur ~400 W. FW 2.0.4 hat Probleme mit temporären Limits.
- **Bekanntes Problem**: Hat ein Speicher seine Entladegrenze erreicht, kann der HMS abschalten,
  obwohl der andere noch Energie hat → **SOC-Ausgleich** zwischen beiden Speichern ist Pflicht.
- **Rechtliches (DE, keine Rechtsberatung)**: Steckersolargerät ≤ 800 VA Wechselrichter,
  ≤ 2000 Wp Module, Anmeldung im Marktstammdatenregister (Speicher separat). Ein per Software
  auf 800 W gedrosselter 1600-VA-Wechselrichter mit Speicher ist eine Grauzone → mit
  Netzbetreiber klären. Unsere Software setzt **niemals** ein Limit > 800 W.

## 3. Architektur

```
                        ┌─────────────── Raspberry Pi (Docker, LAN-Kabel) ───────────────┐
 B2500 #1 ──MQTT──┐     │  Mosquitto ◄── hm2mqtt (Telemetrie, Einstellungen)             │
 B2500 #2 ──MQTT──┤────►│                                                                 │
                  │     │  AstraMeter (virtueller CT002, 1-s-Nulleinspeiseregelung,       │
 B2500 #1/#2 ◄─UDP┘     │              liest den echten CT002 passiv)                     │
 CT002 ◄────────UDP────►│                                                                 │
 HMS-1600-4T ◄─868MHz─► OpenDTU (ESP32) ──MQTT/REST──►                                      │
                        │  rasier-core (Python/FastAPI)                                   │
                        │   • virtuelle Gruppe, SOC-Ausgleich, Prioritäten                │
                        │   • automatische Kalibrierung (Zustandsautomat)                 │
                        │   • Statistik: Verbrauch, Einspeisung, Eigenverbrauch, Ersparnis│
                        │   • Watchdog & Alarme, 800-W-Schutz                              │
                        │   • REST + WebSocket API, SQLite (Zeitreihen + Rollups)        │
                        └───────────────────────────────┬─────────────────────────────────┘
                                                        │ LAN / Tailscale
                                               Android-App (Kotlin, Compose)
```

**Grundsatz:** genau *ein* Regelkreis für die Nulleinspeisung (AstraMeter steuert die
B2500-Ausgänge). Der Wechselrichter wird nur als Messgerät, gesetzliche 800-W-Kappe und
Notfall-Bremse genutzt.

### Funktionen (deine Kritikpunkte → Lösung)

| Problem | Lösung |
|---|---|
| Kalibrierung manuell | Zustandsautomat: ein Speicher nach dem anderen, Vollladung aus PV (anderer Speicher übernimmt das Haus), optional Entladung bis Untergrenze, Erfolgsprüfung über Kalibrier-Events, Einstellungen wiederherstellen. Plan alle 30–60 Tage, abbrechbar, Benachrichtigung. |
| Keine Auswertung | Messwerte alle 5–10 s, kWh-Integration für Netzbezug/Einspeisung, Hausverbrauch = Netz + Wechselrichter-AC, Autarkie, Eigenverbrauch, Ersparnis in € nach Tarif. Tag/Woche/Monat/Jahr. |
| Verbindungsprobleme CT002 ↔ Speicher | Pi als einziger Abfrager des CT002 (kabelgebunden), Speicher hängen am virtuellen CT des Pi. Netzwerk-Check, Watchdog, dokumentierter Rückfall auf den echten CT002. |
| Keine Gruppe | Virtuelle Gruppe in der Software: gemeinsamer SOC, faire Lastverteilung, SOC-Ausgleich, Effizienzmodus (bei wenig Last nur ein Speicher, rotierend). |
| Wechselrichter | OpenDTU: echte AC-Leistung, Leistung pro Eingang (= pro Speicherausgang, Plausibilitätsprüfung), Wirkungsgrad, 800-W-Limit, Alarm bei Abschaltung. |

### Android-App
Dashboard mit Energiefluss (PV → 2 Speicher → Wechselrichter → Haus/Netz), Gruppenansicht mit
Detail je Speicher, Verlauf/Statistik mit Ersparnis, Kalibrierung starten/verfolgen,
Einstellungen (Tarif, Reserve-SOC, Limits), Gerätezustand, Ereignislog, Push via ntfy.
Kein Cloud-Zwang; Zugriff von unterwegs über Tailscale/WireGuard.

## 4. Phasen

| Phase | Inhalt | Eingriff in Geräte |
|---|---|---|
| P0 | Repo-Gerüst (backend/, android/, sim/, deploy/), **Simulator** für B2500/CT002/OpenDTU, CI | nein |
| P1 | Nur lesen: Live-Daten, Statistik, Ersparnis, App-Dashboard | nein |
| P2 | Regelung übernehmen: Speicher an virtuellen CT koppeln, Gruppe, Watchdog | ja (umkehrbar) |
| P3 | Automatische Kalibrierung | ja |
| P4 | Hoymiles/OpenDTU: Messung, 800-W-Kappe, Notfall-Bremse | ja |
| P5 | App-Feinschliff, Benachrichtigungen, Fernzugriff | nein |

## 5. Risiken
- Firmware-Updates von Marstek können Protokolle ändern → Firmware nicht unkontrolliert updaten.
- Lokales MQTT trennt die Speicher von der Marstek-Cloud (App nur noch per Bluetooth),
  außer hame-relay wird genutzt.
- Virtuellen CT koppeln braucht einmalig Marstek-Zugangsdaten (Cloud-Registrierung).
- Fällt der Pi aus, haben die Speicher keinen Zähler → speisen ~0 W ein (sicher), Rückfall
  per App auf echten CT002.
- Ungeprüft auf deiner FW 110.9: Client-ID-Konflikt bei 2 Speichern, „No-Flash“-Befehle,
  Entladung auf 0 %.

## 6. Offene Fragen an den Nutzer
Siehe Chat; Antworten werden hier nachgetragen.
