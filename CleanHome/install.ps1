<#
  CleanHome auf den Fire TV Stick bringen

  Vorbereitung:
    - Fire TV: Einstellungen > Mein Fire TV > Entwickleroptionen > ADB-Debugging = AN
    - app-debug.apk (aus GitHub Actions oder Android Studio) in diesen Ordner legen
    - adb.exe im PATH oder in diesem Ordner

  Start:   powershell -ExecutionPolicy Bypass -File .\install.ps1
  Optional zusaetzlich Werbe-/Tracking-Blocker per DNS:   ... -AdBlock
  Alles rueckgaengig:   ... -Undo
#>
param(
    [string]$Ip = "192.168.1.74",
    [switch]$AdBlock,
    [string]$PcIp = "",      # Gaming-PC (optional), z. B. 192.168.1.20
    [string]$PcMac = "",     # MAC der Netzwerkkarte, z. B. AA:BB:CC:DD:EE:FF
    [switch]$Undo
)
$ErrorActionPreference = "Stop"
$Pkg = "de.cleanhome.launcher"
$T   = "$($Ip):5555"

$adb = Join-Path $PSScriptRoot "adb.exe"
if (-not (Test-Path $adb)) {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { $adb = $c.Source } else { Write-Host "adb.exe nicht gefunden" -ForegroundColor Red; exit 1 }
}
function Sh([string]$c) { & $adb -s $T shell $c }

& $adb connect $T | Out-Host
Write-Host "Falls am TV 'Debugging zulassen?' kommt: 'Immer erlauben' + OK. Dann Enter." ; Read-Host | Out-Null
if ((& $adb -s $T get-state 2>$null) -ne "device") { Write-Host "Keine Verbindung." -ForegroundColor Red; exit 1 }

if ($Undo) {
    $acc = "$(Sh "settings get secure enabled_accessibility_services")".Trim()
    if ($acc -match "cleanhome") { Sh "settings put secure enabled_accessibility_services ''"; Sh "settings put secure accessibility_enabled 0" }
    Sh "settings put global private_dns_mode off"
    Sh "settings put secure screensaver_enabled 1"
    Sh "settings delete secure screensaver_components"
    Sh "settings put secure sleep_timeout 1200000"
    Sh "cmd notification disallow_listener de.cleanhome.launcher/de.cleanhome.launcher.MediaListener"
    & $adb -s $T uninstall $Pkg | Out-Host
    Write-Host "Zurueckgesetzt - Amazon-Startseite ist wieder da." -ForegroundColor Green
    exit 0
}

$apk = Join-Path $PSScriptRoot "app-debug.apk"
if (-not (Test-Path $apk)) { Write-Host "app-debug.apk fehlt in diesem Ordner." -ForegroundColor Red; exit 1 }

Write-Host "`n[1] Installiere CleanHome ..." -ForegroundColor Cyan
$ErrorActionPreference = "Continue"   # adb schreibt Fehler auf stderr - nicht abbrechen, sondern auswerten
$res = (& $adb -s $T install -r $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
Write-Host $res
if ($res -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
    Write-Host "Alte Version mit anderer Signatur gefunden -> wird einmalig entfernt und neu installiert" -ForegroundColor Yellow
    & $adb -s $T uninstall $Pkg | Out-Host
    $res = (& $adb -s $T install $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
    Write-Host $res
}
if ($res -notmatch "Success") { Write-Host "Installation fehlgeschlagen - Ausgabe oben an Claude schicken." -ForegroundColor Red; exit 1 }
$ErrorActionPreference = "Stop"

Write-Host "`n[2] Home-Taste umleiten (Protokoll-Waechter) ..." -ForegroundColor Cyan
Sh "pm grant $Pkg android.permission.READ_LOGS"
Sh "dumpsys deviceidle whitelist +$Pkg" | Out-Null        # nicht im Hintergrund einschlaefern
Sh "cmd appops set $Pkg RUN_IN_BACKGROUND allow" | Out-Null
Sh "appops set $Pkg SYSTEM_ALERT_WINDOW allow"   # Sofort-Home: Standbild ueber Amazon legen
if ((Sh "pm list packages com.spocky.projengmenu") -match "projengmenu") {
    Write-Host "   Projectivy gefunden -> wird entfernt"
    Sh "am force-stop com.spocky.projengmenu"
    Sh "pm uninstall com.spocky.projengmenu" | Out-Null
}
# alte CleanHome-Bedienungshilfe austragen (wirkte auf Fire OS nie), andere Bedienungshilfen bleiben
$acc = "$(Sh "settings get secure enabled_accessibility_services")".Trim()
if ($acc -match "cleanhome") {
    $rest = (($acc -split ":") | Where-Object { $_ -and $_ -notmatch "cleanhome" }) -join ":"
    if ($rest) { Sh "settings put secure enabled_accessibility_services $rest" }
    else { Sh "settings put secure enabled_accessibility_services ''"; Sh "settings put secure accessibility_enabled 0" }
    Write-Host "   alte Bedienungshilfe entfernt"
}

Write-Host "`n[3] Ruhebildschirm: CleanHome statt Amazon, kein schwarzer Ruhezustand mehr ..." -ForegroundColor Cyan
Sh "settings put secure screensaver_enabled 1"
Sh "settings put secure screensaver_components de.cleanhome.launcher/de.cleanhome.launcher.SaverDream"
Sh "settings put secure screensaver_activate_on_sleep 1"
Sh "settings put secure screensaver_activate_on_dock 1"
Sh "settings put secure sleep_timeout 0"

Write-Host "`n[3a] 'Jetzt laeuft'-Karte freigeben (Musik/Video-Infos) ..." -ForegroundColor Cyan
Sh "cmd notification allow_listener de.cleanhome.launcher/de.cleanhome.launcher.MediaListener"

Write-Host "`n[3b] Werbe-ID begrenzen ..." -ForegroundColor Cyan
Sh "settings put secure limit_ad_tracking 1"

if ($AdBlock) {
    Write-Host "`n[4] Werbe-/Tracking-Blocker (AdGuard DNS) ..." -ForegroundColor Cyan
    Sh "settings put global private_dns_mode hostname"
    Sh "settings put global private_dns_specifier dns.adguard-dns.com"
}

Sh "am force-stop $Pkg"     # Neustart, damit die Protokoll-Berechtigung greift

if ($PcIp -or $PcMac) {
    Write-Host "`n[5] Gaming-PC eintragen ($PcIp / $PcMac) ..." -ForegroundColor Cyan
    $extra = ""
    if ($PcIp)  { $extra += " --es pc_ip $PcIp" }
    if ($PcMac) { $extra += " --es pc_mac $PcMac" }
    Sh "am start -n $Pkg/.MainActivity$extra" | Out-Null
} else {
    Sh "am start -n $Pkg/.MainActivity" | Out-Null
}
Write-Host "`nFertig! Druecke jetzt die Home-Taste auf der Fernbedienung." -ForegroundColor Green
