# Casambi Bridge v0.3.2

v0.3.2 erweitert v0.3.1 um einen ersten echten Setup-/Onboarding-Flow.

## Neu in v0.3.2

### SETUP-Reiter

Die Tab-Leiste ist jetzt:

```text
HOME   SETUP   CTRL   SET   ADV
```

Der bisherige `LOG`-Hinweis wurde in `ADV` verschoben.

### Casambi Discovery Ergebnisliste

Der SETUP-Reiter zeigt gefundene Casambi-Geräte nun als Liste:

```text
CASAMBI OK • Name • MAC • RSSI
manufacturer963=true casaUuid=true
[AUSWÄHLEN]
```

Der Scan sucht weiterhin nach:

```text
Manufacturer Data ID = 963
CASA UUID = c9ffde48-ca5a-0000-ab83-8f519b482f77
```

Zusätzlich bleiben vorhandene Fallbacks aktiv:

- bereits eingetragene MAC
- Name enthält `Casambi`

### Gerät auswählen

Wenn ein gefundenes Gerät ausgewählt wird:

- MAC wird in `Casambi BLE MAC` übernommen
- Status zeigt das ausgewählte Gerät
- Log schreibt Name, MAC, RSSI und Discovery-Kriterien

### ADD / API FETCH

Der SETUP-Reiter hat jetzt:

```text
ADD / API FETCH
```

Der Button startet den bestehenden API-Fetch-Flow. Das Netzwerkpasswort wird weiterhin im SET-Reiter im Feld `Casambi Passwort optional` eingetragen.

### RESET CASAMBI CONFIG

Für den Test als neuer Benutzer gibt es:

```text
RESET CASAMBI CONFIG
```

Der Button löscht lokal nur:

- Casambi MAC
- Netzwerkname
- Protocol Version
- Key ID
- HEX Key
- Szenen
- Gruppen

MQTT/SMB/Web-Konfiguration bleiben erhalten.

## Testablauf neuer Benutzer

```text
RESET CASAMBI CONFIG
SETUP öffnen
SCAN CASAMBI
Gerät auswählen
SET öffnen und Netzwerkpasswort eintragen
zurück zu SETUP
ADD / API FETCH
Bridge startet
Szenen erscheinen
Home Assistant Discovery bleibt aktiv
```

## Weiterhin enthalten

- v0.3.1 2-Spalten-Actions
- v0.2.38 Doppelstart-Fix
- Auto API Fetch
- API-managed KeyStore
- Home Assistant Szenenbuttons
- Home Assistant Status-Entities
- Home Assistant API Fetch / Restart Buttons
- App/Web/MQTT Lichtsteuerung
- App/Web/MQTT Szenensteuerung
- PS2-Style LED-Flicker

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Bitte nicht deinstallieren, wenn lokale Einstellungen erhalten bleiben sollen.
