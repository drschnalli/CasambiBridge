# Casambi Bridge v0.3.4

v0.3.4 erweitert v0.3.3 um kleinere, aber wichtige UI-Verbesserungen nach dem ersten realen Setup-Test.

## Neu in v0.3.4

### MQTT-Verbindungs-LED im Signal Canopy

Im HOME-Tab unter `Signal Canopy` gibt es jetzt eine dauerhafte LED:

```text
MQTT verbunden
```

Die LED wird grün, sobald im Log `MQTT verbunden ...` erscheint. Bei MQTT-Fehlern oder Verbindungsverlust wird die LED wieder dunkel.

### Speichern-Button im SET-Reiter

Im `SET`-Reiter gibt es jetzt:

```text
EINSTELLUNGEN SPEICHERN
```

Der Button ist standardmäßig ausgeblendet und erscheint erst, wenn ein Einstellungsfeld oder Toggle geändert wurde.

Nach dem Speichern verschwindet der Button wieder.

### Onboarding-Flow bestätigt

Der v0.3.3/v0.3.4 Setup-Flow wurde real getestet:

```text
RESET CASAMBI CONFIG
SCAN CASAMBI
Gerät gefunden
Gerät ausgewählt
Netzwerkpasswort manuell hinterlegt
API Fetch
Netzwerkname Kalli erkannt
Unit 1 gefunden und steuerbar
```

## Weiterhin enthalten

- SETUP-Reiter
- Casambi Discovery Ergebnisliste
- Gerät auswählen
- ADD / API FETCH
- RESET CASAMBI CONFIG
- Home Assistant Discovery für Szenen, Status und Buttons
- App/Web/MQTT Lichtsteuerung
- App/Web/MQTT Szenensteuerung
- v0.2.38 Doppelstart-Fix

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Bitte nicht deinstallieren, wenn lokale Einstellungen erhalten bleiben sollen.
