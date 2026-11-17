<#
  Fire TV Stick schneller machen (alles per ADB, alles rueckgaengig machbar)

  Start:       powershell -ExecutionPolicy Bypass -File .\tuning.ps1
  Rueckgaengig: powershell -ExecutionPolicy Bypass -File .\tuning.ps1 -Undo
#>
param([string]$Ip = "192.168.1.74", [switch]$Undo)

$adb = Join-Path $PSScriptRoot "adb.exe"
$T = "$($Ip):5555"
function Sh([string]$c) { & $adb -s $T shell $c 2>&1 | ForEach-Object { "$_" } }

# Amazon-Hintergrunddienste, die nur Werbung, Tracking oder Telemetrie machen.
# Absichtlich vorsichtig gewaehlt: nichts davon braucht man zum Streamen, fuer Alexa oder Updates.
$bloat = @(
    "com.amazon.device.crashmanager",        # Absturzberichte an Amazon
    "com.amazon.dp.logger",                  # Geraete-Protokolle an Amazon
    "com.amazon.wirelessmetrics.service",    # WLAN-Statistiken an Amazon
    "com.amazon.kso",                        # Werbe-/Angebotsdienst
    "com.amazon.hybridadidservice",          # Werbe-ID-Dienst
    "com.amazon.tv.acr",                     # Inhaltserkennung (was laeuft gerade)
    "com.amazon.client.metrics",             # Nutzungsstatistiken
    "com.amazon.client.metrics.api",
    "com.amazon.tv.livetv"                   # Live-TV-Leiste im Amazon-Home (wird nicht gebraucht)
)

& $adb connect $T | Out-Host

if ($Undo) {
    Write-Host "Mache alles rueckgaengig ..." -ForegroundColor Yellow
    foreach ($p in $bloat) { Sh "pm enable $p" | Out-Null }
    Sh "settings put global window_animation_scale 1.0"
    Sh "settings put global transition_animation_scale 1.0"
    Sh "settings put global animator_duration_scale 1.0"
    Write-Host "Fertig. Stick einmal neu starten." -ForegroundColor Green
    exit 0
}

Write-Host "`n[1] Animationen halb so lang (wirkt deutlich flotter) ..." -ForegroundColor Cyan
Sh "settings put global window_animation_scale 0.5"
Sh "settings put global transition_animation_scale 0.5"
Sh "settings put global animator_duration_scale 0.5"

Write-Host "`n[2] Werbe-/Tracking-Dienste von Amazon abschalten ..." -ForegroundColor Cyan
foreach ($p in $bloat) {
    $exists = Sh "pm list packages $p" | Select-String -SimpleMatch "package:$p"
    if (-not $exists) { continue }
    $r = (Sh "pm disable-user --user 0 $p") -join " "
    if ($r -match "disabled") { Write-Host "   aus:       $p" -ForegroundColor Green }
    else { Write-Host "   geschuetzt: $p (bleibt an)" -ForegroundColor DarkGray }
}

Write-Host "`n[3] Speicher aufraeumen (App-Caches leeren) ..." -ForegroundColor Cyan
Sh "pm trim-caches 999G" | Out-Null
Sh "am kill-all" | Out-Null

Write-Host "`n[4] CleanHome fuer Geschwindigkeit vorkompilieren (dauert ~30 s) ..." -ForegroundColor Cyan
Sh "cmd package compile -m speed -f de.cleanhome.launcher" | Out-Host

Write-Host "`n[5] Freier Arbeitsspeicher:" -ForegroundColor Cyan
Sh "cat /proc/meminfo" | Select-String "MemTotal|MemAvailable" | Out-Host

Write-Host "`nFertig! Stick einmal neu starten: Einstellungen > Mein Fire TV > Neu starten" -ForegroundColor Green
Write-Host "Rueckgaengig jederzeit mit:  .\tuning.ps1 -Undo"
