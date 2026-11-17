# CleanHome – eigener Home Screen für den Fire TV Stick

Vercel-Look: tiefschwarz, feines Raster, 1px-Rahmen, Inter + Monospace. Ohne Werbung, ohne Tracking.
Schriften: Inter und DejaVu Sans Mono (freie Lizenzen, siehe app/src/main/assets/fonts).

- Große Uhr, Datum und Begrüßung oben
- **Favoriten**-Reihe mit großen Kacheln, beim ersten Start automatisch mit Netflix, Prime Video, YouTube, Disney+ usw. gefüllt (falls installiert)
- **Alle Apps** alphabetisch im Raster
- Die markierte Kachel wird größer und bekommt einen blauen Rahmen
- **☰ Menü-Taste** oder **OK lange drücken** = App-Menü:
  Öffnen · Verschieben (◀ ▶, OK = fertig) · Zu Favoriten · Zu Reihe hinzufügen (Streaming, Musik, Tools …) · Aus Reihe entfernen · App ausblenden
- **Einstellungen**-Knopf: Fire-TV-Einstellungen, ausgeblendete Apps zurückholen, Bildschirmschoner starten
- **Bildschirmschoner** nach 4 Min.: große Uhr, Datum, Wetter – wandert langsam (kein Einbrennen), jede Taste weckt auf
- **Netzwerk-Status** oben: IP · WLAN/LAN · Ping in ms (grün/gelb/rot)
- **Wetter** für Beckingen (Open-Meteo, alle 30 Min.)
- **Jetzt läuft**: Cover, Titel, Fortschritt, ◀◀ ❚❚ ▶▶ (Spotify, YouTube …)
- **PC-Gaming**: Kachel weckt den PC per Wake-on-LAN, wartet bis Sunshine erreichbar ist (Port 47989) und startet Moonlight.
  Einrichten: Kachel ☰ → „Gaming-PC einrichten“ → suchen (PC muss an sein) – oder `install.ps1 -PcIp 192.168.1.20 -PcMac AA:BB:CC:DD:EE:FF`
  PC: Sunshine installieren + einmal mit Moonlight koppeln, Wake-on-LAN im BIOS und in der Netzwerkkarte aktivieren (am besten per LAN-Kabel)
- **WiZ-Licht (automatisch)**: Einstellungen → WiZ-Licht → Lampen suchen – danach läuft alles von selbst:
  Home Screen = App-Farbe · Musik = Cover-Farbe · Film läuft = warm 10 % · Film pausiert = warm 35 % · Streaming-Menü = warm 20 %
  PC-Gaming = lila · Bildschirmschoner = warm 10 % · TV aus = normales Licht 50 % · 23–6 Uhr höchstens 35 %
  Voraussetzung: WiZ-App → Einstellungen → Sicherheit → „Lokale Kommunikation zulassen“ an
- Knopf **Einstellungen** öffnet die Fire-TV-Einstellungen

## 1. APK bauen (ohne Android Studio)
1. Ordner als neues GitHub-Repo hochladen
2. Reiter **Actions** → „APK bauen“ läuft automatisch (ca. 3–5 Min.)
3. Unten bei **Artifacts** → `CleanHome-apk` herunterladen und entpacken → `app-debug.apk`

Alternative: Ordner in Android Studio öffnen → *Build → Build APK(s)*.

## 2. Installieren
`app-debug.apk` und `install.ps1` in denselben Ordner legen, dann in PowerShell:

```
powershell -ExecutionPolicy Bypass -File .\install.ps1 -AdBlock
```

`-AdBlock` blockt Werbung und Tracker über AdGuard DNS (weglassen, falls nicht gewünscht).
Rückgängig: `-Undo`

## Wie es funktioniert
Fire OS erlaubt keinen fremden Standard-Launcher. CleanHome nutzt deshalb eine
Bedienungshilfe: Sie fängt die Home-Taste ab und ersetzt die Amazon-Startseite
sofort, sobald sie auftaucht.
Hinweis: Langes Drücken auf Home (Schnellmenü) wird dabei mit abgefangen.
Schlafen legen geht über *Einstellungen*.

Falls die Home-Taste nach einem Amazon-Update wieder auf die Amazon-Startseite führt:
`install.ps1` nochmal laufen lassen.
