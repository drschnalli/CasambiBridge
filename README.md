# Casambi Bridge v0.10.2

v0.10.2 ist der erste grosse Schritt Richtung Discovery 2.0, Diagnose-Dashboard und spaeterer HACS-Faehigkeit.

## Neu in v0.10.2

### Diagnostics & Discovery 2.0

Neue MQTT Discovery Entitaeten fuer Home Assistant:

```text
Casambi Network Name
Casambi Unit Count
Casambi Group Count
Casambi Scene Count
Casambi Bridge Version
Casambi Bridge Uptime
Casambi Last API Sync
Casambi Last Unit Update
Casambi MQTT Connected
Casambi BLE Connected
Casambi Cloud Connected
Casambi Unit 1 Online Diagnostic
```

### MQTT Topics

```text
casambi_bridge/diagnostics/network_name
casambi_bridge/diagnostics/unit_count
casambi_bridge/diagnostics/group_count
casambi_bridge/diagnostics/scene_count
casambi_bridge/diagnostics/bridge_version
casambi_bridge/diagnostics/bridge_uptime
casambi_bridge/diagnostics/last_sync
casambi_bridge/diagnostics/last_unit_update
casambi_bridge/diagnostics/mqtt_connected
casambi_bridge/diagnostics/ble_connected
casambi_bridge/diagnostics/cloud_connected
casambi_bridge/diagnostics/unit_1_online_diag
```

### Unit Cache

Der API Fetch speichert jetzt auch gefundene Units lokal. Dadurch kann die App Counts, Backup und Dashboard besser erzeugen.

### Dashboard Generator

Neu im CTRL-Tab:

```text
DASHBOARD
```

Der Button erzeugt per SMB:

```text
casambi_jungle_dashboard.yaml
```

Das YAML ist fuer Mushroom Cards und Bubble Cards vorbereitet.

Auch im Webinterface gibt es neue Routen:

```text
/dashboard-yaml
/dashboard-export
```

### Full Backup / Restore

Backup/Restore speichert nun nicht mehr nur die Config, sondern auch:

```text
config
scenes
groups
units
```

Datei:

```text
casambi_bridge_full_backup.json
```

## Weiterhin enthalten

- Home Assistant Bridge Settings Switches
- Web Interface Switch
- SMB Logging Switch
- TCP Logstream Switch
- Auto API Fetch Switch
- Casambi Unit 1 Online Fix
- Jungle UI
- Webinterface
- Szenensteuerung
- Lichtsteuerung und Dimmen

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```


## Neu in v0.10.2 Scanner Pro

- History Pro: einzelne Eintraege loeschen, Copy, TXT Export, Schnellfilter, Karten-/Baumansicht und bessere Summary.
- WiFi Pro: Channel Usage Chart, Access-Point/BSSID Gruppierung, Security Badges und Live-Karten.
- Network Pro: Kandidaten-Badges fuer HTTP, MQTT, SMB und Home Assistant sowie Port-Badges.
- BLE Pro: Casambi Watch Filter, Seen Count, Last Seen, Best Signal und Casambi-Kartenmarkierung.
