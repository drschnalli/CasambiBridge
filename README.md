# Casambi Bridge v0.3.9

v0.3.9 ist ein HOME/Jungle-Polish auf Basis von v0.3.7.

## Neu in v0.3.9

### Casambi Jungle ist zurück

Der Header zeigt wieder:

```text
CASAMBI JUNGLE // v0.3.9
NEON CANOPY CONTROL CENTER
```

### Kompakter Jungle-Block oben im HOME-Reiter

Der HOME-Reiter startet jetzt mit:

```text
CASAMBI JUNGLE
Network: Kalli
Units: 1  |  Scenes: 2
MQTT mqtt.kallii.net:1883
```

Der technische `HOME DASHBOARD`-Block wurde dadurch wieder mehr in den Neon/Jungle-Stil gebracht.

### Light Control wichtiger und größer

Die Lichtsteuerung ist jetzt stärker hervorgehoben:

- `Unit 1 Control` wurde zu `Light Control`
- ON/OFF/40%-Buttons sind größer
- Szenenbuttons sind kompakter und besser lesbar

### Signal Canopy bleibt erhalten

Die Signal-Canopy-Karte bleibt erhalten und nutzt die kurzen Labels:

```text
BLE RX
BLE TX
MQTT verbunden
MQTT IN
MQTT OUT
SMB Logging
Webserver
TCP Logstream
```

## Weiterhin enthalten

- aktiver Tab wird cyan hervorgehoben
- SETUP-Reiter mit Passwortfeld
- Setup-Statusanzeige
- MQTT verbunden LED
- Einstellungen speichern Button bei Änderungen
- Casambi Discovery Ergebnisliste
- Home Assistant Discovery

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```
