# Casambi Bridge v0.4.0

v0.4.0 ist die erste **Casambi Jungle Identity** Version.

## Neu in v0.4.0

### Header mit Jungle-Charakter

Der Header zeigt jetzt:

```text
🌴 CASAMBI JUNGLE // v0.4.0
powered by Sambesi
NEON CANOPY CONTROL CENTER
```

### Jungle Tabs

Die Reiter wirken jetzt dschungelmäßiger:

```text
🌴 HOME     aktiver Reiter
🌿 SETUP    inaktiver Reiter
🌿 CTRL
🌿 SET
🌿 ADV
```

Der aktive Reiter bleibt cyan hervorgehoben, die inaktiven Reiter bleiben grün.

### Casambi Jungle Karte mit Icons

Der HOME-Reiter startet jetzt mit einer kompakteren Jungle-Karte:

```text
🌴 Kalli
💡 Units: 1  |  🎭 Scenes: 2
📡 MQTT mqtt.kallii.net:1883
```

### Vorhandene UI-Verbesserungen bleiben erhalten

- Signal Canopy bleibt erhalten
- Light Control bleibt größer hervorgehoben
- Aktive Tabs bleiben erhalten
- SETUP-Reiter mit Passwortfeld bleibt erhalten
- MQTT verbunden LED bleibt erhalten
- Einstellungen speichern Button bei Änderungen bleibt erhalten

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```
