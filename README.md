# Casambi Bridge v0.3.0

v0.3.0 ist der erste UI-Release auf Basis des stabilen v0.2.42/v0.2.45 Funktionsstands.

## Wichtigste Änderung

Die App ist jetzt in Reiter aufgeteilt:

```text
HOME   CTRL   SET   ADV   LOG
```

Damit ist die Hauptansicht auf kleinen Displays deutlich schlanker.

## Reiter

### HOME

Für den Alltag:

- Signal/Status LEDs
- Unit 1 Steuerung
- Szenen
- System Status

### CTRL

Aktionen:

- START
- STOP
- MQTT Test
- SAVE
- BLE Test
- BACKUP SMB
- RESTORE SMB
- FETCH API
- SCAN CASAMBI

### SET

Normale Einstellungen:

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

Advanced/Fallback:

- Protocol Version
- Key ID
- HEX Key

Diese Felder sind nur Fallback. Normalerweise holt die App den Key per API Fetch.

### LOG

Kurzer Hinweis zum Log-Status. Die ausführlichen Logs laufen weiterhin über SMB/TCP.

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

## Home Assistant Entities

Diese Entities sollten weiterhin bzw. zusätzlich erscheinen:

```text
Casambi Light 1
Casambi Scene An
Casambi Scene Aus
Casambi Bridge Status
Casambi BLE Status
Casambi Unit 1 Online
Casambi API Fetch
Casambi Restart Bridge
```

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Bitte nicht deinstallieren, wenn lokale Einstellungen erhalten bleiben sollen.
