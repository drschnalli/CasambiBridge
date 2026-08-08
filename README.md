# Casambi Bridge v0.4.1

v0.4.1 erweitert die Home-Assistant-Anbindung um sinnvolle Bridge-Einstellungen als MQTT-Schalter.

## Neu in v0.4.1

### Home Assistant Bridge Settings

Home Assistant bekommt zusätzliche Switch-Entities:

```text
Casambi Web Interface
Casambi SMB Logging
Casambi TCP Logstream
Casambi Auto API Fetch
```

Damit können wichtige Bridge-Funktionen direkt aus Home Assistant ein- und ausgeschaltet werden.

### MQTT Topics

```text
casambi_bridge/settings/webinterface/set
casambi_bridge/settings/webinterface/state

casambi_bridge/settings/smb_logging/set
casambi_bridge/settings/smb_logging/state

casambi_bridge/settings/tcp_logstream/set
casambi_bridge/settings/tcp_logstream/state

casambi_bridge/settings/auto_api_fetch/set
casambi_bridge/settings/auto_api_fetch/state
```

Payloads:

```text
ON
OFF
```

### Verhalten

Wenn Home Assistant einen Schalter betätigt:

- Konfiguration wird gespeichert
- Webinterface/SMB/TCP/Auto-API-Fetch wird direkt aktualisiert
- MQTT State Topic wird aktualisiert
- Signal-Canopy-LEDs reagieren über die bestehende App-Logik

## Weiterhin enthalten

- 🌴 Casambi Jungle Header
- powered by Sambesi
- Jungle Tabs
- SETUP-Reiter mit Passwortfeld
- MQTT verbunden LED
- Home Assistant Discovery für Licht, Szenen, Status und Buttons

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```
