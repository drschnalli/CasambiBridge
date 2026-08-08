# Casambi Bridge v0.3.8

v0.3.8 ist ein HOME-Dashboard-Polish auf Basis von v0.3.7.

## Neu in v0.3.8

### HOME Dashboard

Der HOME-Reiter hat jetzt oben eine kompakte Übersicht:

```text
Netzwerk: <Name>
Unit: 1 • Szenen: <Anzahl>
MQTT: <Host>:<Port>
```

Damit sieht man direkt, ob das Setup grundsätzlich vollständig ist.

### Kürzere Signal-Labels

Die Signal-LEDs sind auf kleinen Displays besser lesbar:

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

### Klarere HOME-Bereiche

- `Unit 1 Control` wurde zu `Light Control`
- `System Status` wurde zu `Bridge Status`
- Szenenbuttons im HOME-Reiter sind kürzer und größer lesbar
- Status-Texte sind weniger nach Roadmap und mehr nach echtem Betriebsstatus formuliert

## Weiterhin enthalten

- Aktiver Tab wird cyan hervorgehoben
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
