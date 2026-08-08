# Casambi Bridge v0.3.5

v0.3.5 poliert den SETUP-Flow weiter und baut auf dem bestätigten v0.3.4-Stand auf.

## Neu in v0.3.5

### Passwortfeld direkt im SETUP-Reiter

Der SETUP-Reiter enthält jetzt direkt ein eigenes Feld:

```text
Netzwerkpasswort
```

Das Feld synchronisiert sich mit dem bisherigen Feld `Casambi Passwort optional` im SET-Reiter. Damit muss man beim Onboarding nicht mehr zwischen SETUP und SET wechseln.

### SETUP-Statusanzeige

Im SETUP-Reiter gibt es jetzt eine kompakte Statuszeile:

```text
Gerät: OK/fehlt • Passwort: OK/fehlt • API Key: OK/fehlt • Szenen: n
```

Damit ist sofort sichtbar, ob die wichtigsten Setup-Schritte erledigt sind.

### ADD / API FETCH wird erst sinnvoll aktiv

Der Button `ADD / API FETCH` wird optisch abgeschwächt und deaktiviert, solange MAC oder Netzwerkpasswort fehlen. Sobald ein Gerät ausgewählt und ein Passwort eingetragen ist, ist der Button aktiv.

### MQTT-LED und Einstellungen-Speichern bleiben aus v0.3.4

Weiterhin enthalten:

- `MQTT verbunden` LED im HOME-Tab
- `EINSTELLUNGEN SPEICHERN` im SET-Reiter, nur sichtbar bei Änderungen

## Empfohlener Test

```text
RESET CASAMBI CONFIG
SCAN CASAMBI
AUSWÄHLEN
Netzwerkpasswort direkt im SETUP-Reiter eintragen
ADD / API FETCH
Bridge startet
Szenen erscheinen
Unit 1 steuerbar
```

## Weiterhin enthalten

- SETUP-Reiter
- Casambi Discovery Ergebnisliste
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
