# Casambi Bridge v0.3.1

v0.3.1 ist ein UI-Fix auf Basis von v0.3.0.

## Fix in v0.3.1

Der `CTRL`-Reiter hatte noch horizontale Button-Reihen. Auf kleinen Displays wurden dadurch `FETCH API` und besonders `SCAN CASAMBI` abgeschnitten oder gar nicht sichtbar.

In v0.3.1 ist der `CTRL`-Reiter jetzt als 2-Spalten-Grid aufgebaut:

```text
START          STOP
FETCH API      SCAN CASAMBI
SAVE           MQTT
BLE            BACKUP SMB
RESTORE SMB
```

Dadurch bleiben alle Aktionen sichtbar und nichts läuft rechts aus dem Bildschirm.

## Reiter

```text
HOME   CTRL   SET   ADV   LOG
```

### HOME

- Signal/Status LEDs
- Unit 1 Steuerung
- Szenen
- System Status

### CTRL

- START
- STOP
- FETCH API
- SCAN CASAMBI
- SAVE
- MQTT
- BLE
- BACKUP SMB
- RESTORE SMB

### SET

- Casambi MAC
- Casambi Netzwerkname
- Casambi Passwort
- MQTT
- Home Assistant Discovery Prefix
- SMB
- Webinterface
- TCP Logstream
- Auto API Fetch

### ADV

- Protocol Version
- Key ID
- HEX Key

### LOG

- Hinweis auf SMB/TCP-Diagnose

## Weiterhin enthalten

- Auto API Fetch
- API-managed KeyStore
- Casambi BLE Discovery per Manufacturer `963` + CASA UUID
- App/Web/MQTT Lichtsteuerung
- App/Web/MQTT Szenensteuerung
- Home Assistant Szenenbuttons
- Home Assistant Status-Entities
- Home Assistant Buttons:
  - Casambi API Fetch
  - Casambi Restart Bridge
- v0.2.38 Doppelstart-Fix
- Unit 1 Connected/Online LED
- PS2-Style LED-Flicker
- Force Stop Button

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Bitte nicht deinstallieren, wenn lokale Einstellungen erhalten bleiben sollen.
