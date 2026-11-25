<#
  Fire TV Stick schneller machen (alles per ADB, alles rueckgaengig machbar)

  Start:          powershell -ExecutionPolicy Bypass -File .\tuning.ps1
                  -> fragt bei jeder Zusatz-Gruppe nach (j/n)
  Ohne Fragen:    .\tuning.ps1 -Gruppen livetv,casting,amazonapps
  Rueckgaengig:   .\tuning.ps1 -Undo        (schaltet ALLES aus allen Gruppen wieder an)

  Paketliste nach dem Open-Source-Projekt FireFrame (github.com/dyascj/FireFrame, debloat.txt),
  geprueft auf Fire OS 7.7. Pakete, die es auf dem Stick nicht gibt, werden uebersprungen.
  Es wird nichts geloescht, nur deaktiviert.
#>
param([string]$Ip = "192.168.1.74", [string]$Gruppen = "", [switch]$Undo)

$adb = Join-Path $PSScriptRoot "adb.exe"
$T = "$($Ip):5555"
function Sh([string]$c) { & $adb -s $T shell $c 2>&1 | ForEach-Object { "$_" } }

# Immer: Werbung, Tracking, Telemetrie, ungenutzte Extras (nichts davon braucht man zum Streamen)
$safe = @(
    "com.amazon.kso", "com.amazon.kso.blackbird", "com.amazon.ftvads.deeplinking", "com.amazon.hybridadidservice",
    "com.amazon.tv.acr", "com.amazon.media.recommendations", "com.amazon.sneakpeek",
    "com.amazon.shoptv.client", "com.amazon.shoptv.firetv.client", "com.amazon.tigris", "com.amazon.tv.easyupgrade",
    "com.amazon.hedwig", "com.amazon.minitv.android.app",
    "com.amazon.device.crashmanager", "com.amazon.dp.logger", "com.amazon.logan", "com.amazon.device.logmanager",
    "com.amazon.client.metrics", "com.amazon.client.metrics.api", "com.amazon.tv.fw.metrics",
    "com.amazon.wirelessmetrics.service", "com.amazon.perfc", "com.amazon.perfcollection", "com.amazon.wifilocker",
    "com.amazon.d3", "com.amazon.gamehub", "com.amazon.spiderpork", "com.amazon.tahoe",
    "com.amazon.tmm.tutorial", "com.amazon.storm.lightning.tutorial", "com.amazon.tv.releasenotes",
    "com.amazon.tv.csapp", "com.amazon.ods.kindleconnect",
    "com.amazon.brightdata.coralreef", "com.amazon.brightdata.jellyfish"
)

# Zusatz-Gruppen zum Auswaehlen
$groups = [ordered]@{
    "primevideo" = @{ Text = "Prime Video (groesste App im Speicher, ~190 MB) - nur wenn du es NICHT nutzt";
                      Pkgs = @("com.amazon.firebat") }
    "livetv"     = @{ Text = "Amazons Live-TV-Leiste (weckt Tubi, Netflix usw. im Hintergrund)";
                      Pkgs = @("com.amazon.tv.livetv") }
    "casting"    = @{ Text = "Bildschirm spiegeln von Amazon (Netflix/YouTube vom Handy gehen weiter)";
                      Pkgs = @("com.amazon.cast.sink", "com.amazon.awvflingreceiver") }
    "amazonapps" = @{ Text = "Amazon Music, Amazon Photos, Silk-Browser";
                      Pkgs = @("com.amazon.bueller.music", "com.amazon.bueller.photos", "com.amazon.cloud9") }
    "alexa"      = @{ Text = "Alexa auf dem Stick (Sprachsuche per Mikrofon-Taste geht dann NICHT mehr)";
                      Pkgs = @("com.amazon.vizzini", "com.amazon.alexa.externalmediaplayer.fireos",
                               "com.amazon.alexamediaplayer.runtime.ftv", "com.amazon.alexadirectivebrokerservice",
                               "com.amazon.alexa.datastore.app", "com.amazon.aca", "com.amazon.vizzini.ftvcds",
                               "com.amazon.cardinal", "com.amazon.tv.alexaalerts", "com.amazon.tv.alexanotifications",
                               "com.amazon.comms.starktachyon", "com.amazon.rtcsessioncontroller",
                               "com.amazon.audiohome", "com.amazon.whad", "com.amazon.whasettings",
                               "com.amazon.wha.mediabrowserservice") }
}

& $adb connect $T | Out-Host

if ($Undo) {
    Write-Host "Mache alles rueckgaengig ..." -ForegroundColor Yellow
    $all = $safe + ($groups.Values | ForEach-Object { $_.Pkgs })
    foreach ($p in $all) { Sh "pm enable $p" | Out-Null }
    Sh "settings put global window_animation_scale 1.0"
    Sh "settings put global transition_animation_scale 1.0"
    Sh "settings put global animator_duration_scale 1.0"
    Write-Host "Fertig. Stick einmal neu starten." -ForegroundColor Green
    exit 0
}

# Welche Zusatz-Gruppen?
$chosen = @()
if ($Gruppen -ne "") {
    $chosen = $Gruppen.Split(",") | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $groups.Contains($_) }
} else {
    Write-Host "`nZusaetzlich abschalten? (j = ja, Enter = nein)" -ForegroundColor Cyan
    foreach ($k in $groups.Keys) {
        $a = Read-Host ("  {0,-11} {1}" -f $k, $groups[$k].Text)
        if ($a -match "^[jJyY]") { $chosen += $k }
    }
}

Write-Host "`n[1] Animationen halb so lang (wirkt deutlich flotter) ..." -ForegroundColor Cyan
Sh "settings put global window_animation_scale 0.5"
Sh "settings put global transition_animation_scale 0.5"
Sh "settings put global animator_duration_scale 0.5"

function Disable($list) {
    foreach ($p in $list) {
        $exists = Sh "pm list packages $p" | Select-String -SimpleMatch "package:$p"
        if (-not $exists) { continue }
        $r = (Sh "pm disable-user --user 0 $p") -join " "
        if ($r -match "disabled") { Write-Host "   aus:        $p" -ForegroundColor Green }
        else { Write-Host "   geschuetzt: $p (bleibt an)" -ForegroundColor DarkGray }
    }
}

Write-Host "`n[2] Werbung, Tracking und Telemetrie von Amazon abschalten ..." -ForegroundColor Cyan
Disable $safe
foreach ($k in $chosen) {
    Write-Host "`n[+] Gruppe $k ..." -ForegroundColor Cyan
    Disable $groups[$k].Pkgs
}

Write-Host "`n[3] Speicher aufraeumen (App-Caches leeren) ..." -ForegroundColor Cyan
Sh "pm trim-caches 999G" | Out-Null
Sh "am kill-all" | Out-Null

Write-Host "`n[4] CleanHome fuer Geschwindigkeit vorkompilieren (dauert ~30 s) ..." -ForegroundColor Cyan
Sh "cmd package compile -m speed -f de.cleanhome.launcher" | Out-Host

Write-Host "`n[5] Freier Arbeitsspeicher:" -ForegroundColor Cyan
Sh "cat /proc/meminfo" | Select-String "MemAvailable" | Out-Host
Write-Host "`nFertig. Am besten den Stick einmal neu starten." -ForegroundColor Green
Write-Host "Rueckgaengig jederzeit mit:  .\tuning.ps1 -Undo"
