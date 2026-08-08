# Casambi Bridge v0.3.3

v0.3.3 ist ein Hotfix für v0.3.2.

## Fix

In v0.3.2 war in der SETUP-Geräteliste ein Kotlin-String über zwei physische Zeilen gebrochen. Dadurch brach der Build bei `MainActivity.kt` ab.

Gefixt:

```text
SETUP-Geräteliste kompiliert wieder
Discovery-Liste nutzt jetzt korrekt \n im String
```

## Enthalten aus v0.3.2

- SETUP-Reiter
- Casambi Discovery Ergebnisliste
- Gerät auswählen
- ADD / API FETCH
- RESET CASAMBI CONFIG
- HOME / SETUP / CTRL / SET / ADV Tabs

## Build

```powershell
gradle clean
gradle assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Bitte nicht deinstallieren, wenn lokale Einstellungen erhalten bleiben sollen.
