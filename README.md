# Casambi Bridge v0.4.2

v0.4.2 ist ein Home-Assistant-Hotfix für `Casambi Unit 1 Online`.

## Fix in v0.4.2

### Casambi Unit 1 Online nicht mehr `Unbekannt`

Home Assistant zeigte den Binary Sensor `Casambi Unit 1 Online` als `Unbekannt`, obwohl die App selbst `Unit 1 Connected / Online` korrekt erkannte.

Ursache:

```text
value_template = {{ value_json.online }}
```

Das lieferte `true`/`false`, während der MQTT Binary Sensor ohne weitere Payload-Angaben standardmäßig `ON`/`OFF` erwartet.

Gefixt auf:

```text
{% if value_json.online %}ON{% else %}OFF{% endif %}
```

Zusätzlich wird der Initial-State nicht mehr mit `online=true` veröffentlicht, sondern erst einmal sauber mit `online=false`, bis der echte BLE-UnitState kommt.

## Nach dem Update

Nach Installation und Start sollte Home Assistant das retained Discovery-Config-Payload aktualisieren.

Falls `Casambi Unit 1 Online` weiterhin kurz unbekannt bleibt:

1. Bridge einmal neu starten
2. Warten, bis `UnitState parsing fertig count=1` im Log erscheint
3. In Home Assistant MQTT-Entity neu laden oder kurz warten

## Weiterhin enthalten

- Home Assistant Bridge Settings Switches
- Casambi Web Interface Switch
- SMB Logging Switch
- TCP Logstream Switch
- Auto API Fetch Switch
- 🌴 Casambi Jungle Header
- powered by Sambesi
- Jungle Tabs

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```
