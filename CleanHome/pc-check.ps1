<#
  PC-Gaming pruefen und einrichten - auf dem GAMING-PC ausfuehren.
  Prueft alles, was fuer "PC-Gaming" in CleanHome noetig ist, und traegt den PC
  automatisch auf dem Fire TV Stick ein.

  Start (am besten als Administrator):
    powershell -ExecutionPolicy Bypass -File .\pc-check.ps1
#>
param([string]$Ip = "192.168.1.74")

$adb = Join-Path $PSScriptRoot "adb.exe"
$T = "$($Ip):5555"
$ok = $true
function Good($t) { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Bad($t, $fix) { Write-Host "  [FEHLT] $t" -ForegroundColor Red; Write-Host "         -> $fix" -ForegroundColor Yellow; $script:ok = $false }
function Warn($t, $fix) { Write-Host "  [HINWEIS] $t" -ForegroundColor DarkYellow; if ($fix) { Write-Host "         -> $fix" -ForegroundColor Yellow } }

Write-Host "`n=== PC-Gaming Check ===`n" -ForegroundColor Cyan

# 1) Netzwerk: IP + MAC des aktiven Adapters
$cfg = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq "Up" } | Select-Object -First 1
if (-not $cfg) { Bad "Kein aktives Netzwerk gefunden" "PC mit dem Heimnetz verbinden"; exit 1 }
$pcIp = $cfg.IPv4Address.IPAddress
$adapter = $cfg.NetAdapter
$pcMac = $adapter.MacAddress
$isWlan = ($adapter.PhysicalMediaType -match "802.11") -or ($adapter.InterfaceDescription -match "Wi-?Fi|Wireless|WLAN")
Good "PC im Netz: $pcIp  (Adapter: $($adapter.Name), MAC $pcMac)"
if ($isWlan) { Warn "Der PC ist per WLAN verbunden." "Wake-on-LAN klappt per WLAN meist NICHT. Fuer das Aufwecken den PC per LAN-Kabel anschliessen. Streaming selbst geht auch per WLAN." }

# 2) Sunshine laeuft?
$sun = Get-NetTCPConnection -LocalPort 47989 -State Listen -ErrorAction SilentlyContinue
if ($sun) { Good "Sunshine laeuft (Port 47989)" }
else { Bad "Sunshine laeuft nicht" "Sunshine installieren: https://github.com/LizardByte/Sunshine/releases (sunshine-windows-installer.exe). Danach im Browser https://localhost:47990 oeffnen und Benutzername/Passwort festlegen." }

# 3) Wake-on-LAN an der Netzwerkkarte
try {
    $pm = Get-NetAdapterPowerManagement -Name $adapter.Name -ErrorAction Stop
    if ($pm.WakeOnMagicPacket -eq "Enabled") { Good "Wake-on-LAN (Magic Packet) an der Netzwerkkarte aktiv" }
    else { Bad "Wake-on-LAN an der Netzwerkkarte aus" "Geraete-Manager > Netzwerkadapter > $($adapter.InterfaceDescription) > Eigenschaften > Energieverwaltung: alle 3 Haken setzen. Unter 'Erweitert': 'Wake on Magic Packet' = Aktiviert." }
} catch { Warn "Wake-on-LAN-Status nicht lesbar" "Skript als Administrator starten, um das zu pruefen." }

# 4) Windows-Schnellstart verhindert WOL aus dem ausgeschalteten Zustand
$hb = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power" -ErrorAction SilentlyContinue).HiberbootEnabled
if ($hb -eq 1) { Warn "Windows-Schnellstart ist an" "Systemsteuerung > Energieoptionen > 'Auswaehlen, was beim Druecken des Netzschalters geschehen soll' > 'Schnellstart aktivieren' ausschalten. Sonst wacht der PC aus 'Herunterfahren' oft nicht auf (aus dem Energiesparmodus klappt es trotzdem)." }
else { Good "Windows-Schnellstart ist aus" }
Warn "Im BIOS muss Wake-on-LAN erlaubt sein" "Je nach Board heisst das 'Wake on LAN', 'Power On By PCI-E' oder 'Resume by PCI-E Device' -> Enabled. Kann ich von hier nicht pruefen."

# 5) Fire TV: Moonlight installiert?
& $adb connect $T | Out-Null
$ml = & $adb -s $T shell pm list packages com.limelight 2>$null
if ($ml -match "com.limelight") { Good "Moonlight ist auf dem Fire TV installiert" }
else { Bad "Moonlight fehlt auf dem Fire TV" "Am Fire TV im Amazon Appstore nach 'Moonlight' suchen und installieren - oder die APK von https://github.com/moonlight-stream/moonlight-android/releases per 'adb install' aufspielen." }

# 6) PC in CleanHome eintragen
& $adb -s $T shell am start -n de.cleanhome.launcher/.MainActivity --es pc_ip $pcIp --es pc_mac $pcMac | Out-Null
Good "PC in CleanHome eingetragen ($pcIp / $pcMac)"
Warn "Feste IP empfohlen" "In der FritzBox: Heimnetz > Netzwerk > PC > 'Diesem Netzwerkgeraet immer die gleiche IPv4-Adresse zuweisen'. Sonst findet CleanHome den PC nach einem Neustart evtl. nicht."

Write-Host ""
if ($ok) {
    Write-Host "Alles bereit! Letzter Schritt (nur einmal):" -ForegroundColor Green
    Write-Host "  1. Am Fire TV Moonlight oeffnen -> dein PC erscheint -> auswaehlen" -ForegroundColor Green
    Write-Host "  2. Es kommt eine 4-stellige PIN -> am PC unter https://localhost:47990 > PIN eingeben" -ForegroundColor Green
    Write-Host "  3. Danach geht's in CleanHome mit der Kachel 'PC-Gaming' in einem Rutsch." -ForegroundColor Green
} else {
    Write-Host "Erst die roten Punkte erledigen, dann das Skript nochmal starten." -ForegroundColor Yellow
}
