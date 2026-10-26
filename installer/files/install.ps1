# MonolithicOS installer for Windows (macOS and Linux: install.sh, same steps).
#   install.ps1 install [tv-ip]      install MonolithicOS and make it the Home screen
#   install.ps1 uninstall [tv-ip]    put the TV back exactly as Google shipped it
# Everything goes over ADB on the home network: nothing is rooted, every change can be undone.
# Kept to plain ASCII: Windows PowerShell 5.1 reads scripts without a BOM as the local code page.

param([string]$Mode = "install", [string]$TvIp = "")

$ErrorActionPreference = "Continue"
$Here = $PSScriptRoot
$Pkg = "com.joe.home"
$Act = "$Pkg/.HomeActivity"
$Apk = Join-Path $Here "MonolithicOS.apk"
$Gtv = "com.google.android.apps.tv.launcherx"        # Google TV's launcher
$Atv = "com.google.android.tvlauncher"               # the older Android TV launcher
$Setup = "com.google.android.tungsten.setupwraith"    # the setup wizard (a hidden fallback Home)
$GoogleDream = "com.google.android.apps.tv.dreamx/.service.Backdrop"
$Watchdog = 'com.joe.home/.Watchdog\$Receiver'       # the \ keeps the TV's shell from expanding $Receiver
$Last = Join-Path $Here ".last-tv"

# Switched off by "Debloat & tune": TCL's ad, promo and telemetry services, store-demo bits, and
# Android's ad and personalisation services. Anything not on the TV is skipped. Accessibility
# features (TalkBack, hearing aid) and Google Assistant stay.
$Junk = @(
    "com.tcl.esticker", "com.tcl.partnercustomizer", "com.tcl.messagebox", "com.tcl.bi", "com.tcl.tv.tclhome_passive",
    "com.tcl.bootadservice", "com.tcl.overseasappshow", "com.tcl.guard", "com.tcl.tvweishi", "com.tcl.notereminder",
    "com.tcl.smartalexa", "com.tcl.exhibit", "com.tcl.hotelmenu", "com.tcl.ocean.instructions", "com.tcl.logkit",
    "com.tcl.browser", "com.tcl.tv.plus", "com.tcl.channelplus", "com.tcl.smartlink.core", "com.tcl.interactive",
    "com.tcl.usercenter", "android.autoinstalls.config.google.gtvpai", "com.google.android.feedback",
    "com.google.android.play.games", "com.android.printspooler", "com.android.adservices.api",
    "com.android.ondevicepersonalization.services", "com.android.federatedcompute.services",
    "com.android.tv.feedbackconsent"
)
# Preinstalled ad-supported and promo apps, removed for this user (the Play Store or the uninstaller
# brings them back). Netflix, YouTube, Prime Video, Disney+ and Spotify are never touched.
$Freebies = @(
    "com.tubitv", "tv.pluto.android", "com.haystack.android", "com.xumo.xumo", "com.cbs.ca", "com.cbs.ott",
    "ca.bellmedia.cravetv", "com.quickplay.android.bellmediaplayer", "com.google.android.youtube.tvmusic"
)

# ---- looks ----------------------------------------------------------------------------------
function Say([string]$s = "") { Write-Host $s }
function Step([string]$s) { Write-Host "==> " -NoNewline -ForegroundColor White; Write-Host $s }
function Ok([string]$s) { Write-Host "    ok  " -NoNewline -ForegroundColor Green; Write-Host $s }
function Pause-End { Write-Host ""; Read-Host "Press Enter to close" | Out-Null }
function Die([string]$s) {
    Write-Host ""
    Write-Host "Stopped: " -NoNewline -ForegroundColor Red
    Write-Host $s
    Pause-End
    exit 1
}
function Banner {
    Write-Host ""
    Write-Host "   |#|  M O N O L I T H I C O S" -ForegroundColor White
    Write-Host "   |#|  an Apple TV-style home screen for Google TV" -ForegroundColor DarkGray
    Write-Host ""
}

# ---- adb ------------------------------------------------------------------------------------
function Find-Adb {
    $local = Join-Path $Here "platform-tools\adb.exe"
    if (Test-Path $local) { return $local }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    Step "Getting Google's ADB tools (one time, about 7 MB)"
    $zip = Join-Path $Here "platform-tools.zip"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = "SilentlyContinue"
        Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath $Here -Force
        Remove-Item $zip -Force
    } catch {
        Die "couldn't download ADB. Check the internet connection and run this again. ($($_.Exception.Message))"
    }
    Ok "ADB ready"
    return $local
}

function Ask-Ip([string]$given) {
    $prev = ""
    if (Test-Path $Last) { $prev = (Get-Content $Last -Raw).Trim() }
    $ip = $given
    while (-not $ip) {
        Say "On the TV: Settings > Network & Internet > your Wi-Fi shows the TV's IP address."
        $prompt = "TV IP address"
        if ($prev) { $prompt = "TV IP address [$prev]" }
        $ip = (Read-Host $prompt).Trim()
        if (-not $ip) { $ip = $prev }
    }
    $ip = ($ip -split ":")[0]
    Set-Content -Path $Last -Value $ip -NoNewline
    return $ip
}

function Adb { & $script:AdbExe @args 2>&1 | Out-String }
function Tv { ((& $script:AdbExe -s $script:T shell @args 2>&1 | Out-String) -replace "`r", "").TrimEnd() }

function Connect-Tv {
    Step "Connecting to the TV at $script:Ip"
    for ($i = 1; $i -le 45; $i++) {
        Adb connect $script:T | Out-Null
        $state = (Adb -s $script:T get-state).Trim()
        if ($state -eq "device") { Ok "connected"; return }
        if ($i -eq 2) {
            Write-Host "    Look at the TV: " -NoNewline -ForegroundColor Yellow
            Write-Host "allow USB debugging, tick 'Always allow from this computer', press Allow."
            Write-Host "    Nothing on screen? Turn on USB debugging first (see the README), and check the IP." -ForegroundColor DarkGray
        }
        Start-Sleep -Seconds 2
    }
    Die "couldn't reach $script:Ip. Is USB debugging on, is the IP right, and are this PC and the TV on the same network?"
}

# package lists, read once and re-read after changes
function Read-Pkgs {
    $script:All = (Tv pm list packages) -split "`n"
    $script:Off = (Tv pm list packages -d) -split "`n"
}
function Has([string]$p) { return $script:All -contains "package:$p" }
function Enabled([string]$p) { return (Has $p) -and -not ($script:Off -contains "package:$p") }
function Current-Home {
    $lines = (Tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -split "`n"
    return $lines[-1].Trim()
}
function Focused-Pkg {
    $line = ((Tv dumpsys activity activities) -split "`n" | Where-Object { $_ -match "mFocusedApp" } | Select-Object -First 1)
    if ($line -match "([A-Za-z0-9_.]+)/") { return $Matches[1] }
    return ""
}

function A11y([string]$svc, [string]$state) {   # add (on) or drop (off) one accessibility service, leaving any others alone
    $cur = Tv settings get secure enabled_accessibility_services
    if ($cur -eq "null") { $cur = "" }
    $rest = (($cur -split ":") | Where-Object { $_ -and $_ -ne $svc }) -join ":"
    if ($state -eq "off") {
        if ($rest) { Tv settings put secure enabled_accessibility_services $rest | Out-Null }
        else { Tv settings delete secure enabled_accessibility_services | Out-Null; Tv settings put secure accessibility_enabled 0 | Out-Null }
        return
    }
    Tv settings put secure accessibility_enabled 0 | Out-Null   # clear and write back: a stale list can stay unbound
    Start-Sleep -Seconds 1
    $list = if ($rest) { "${rest}:$svc" } else { $svc }
    Tv settings put secure enabled_accessibility_services $list | Out-Null
    Tv settings put secure accessibility_enabled 1 | Out-Null
}

# ---- checks ---------------------------------------------------------------------------------
function Check-Tv {
    Step "Checking the TV"
    if (-not ((Tv pm list features) -match "android.software.leanback")) { Die "this isn't an Android TV or Google TV device." }
    $sdk = 0
    [int]::TryParse((Tv getprop ro.build.version.sdk), [ref]$sdk) | Out-Null
    if ($sdk -lt 28) { Die "MonolithicOS needs Android TV 9 or newer (this TV reports API $sdk)." }
    Read-Pkgs
    if (-not ((Has $Gtv) -or (Has $Atv))) { Die "no Google TV or Android TV launcher here (Fire TV and other systems aren't supported)." }
    Ok "$(Tv getprop ro.product.model) ($(Tv getprop ro.product.manufacturer), Android $(Tv getprop ro.build.version.release))"
}

# The setup wizard is a hidden fallback Home that outranks normal apps, so it has to be switched off
# with Google's launcher, and switching it off deletes any Wi-Fi network it saved during setup.
function Check-Wifi {
    if ((Enabled $Setup) -and ((Tv dumpsys wifi) -match "cname=$Setup")) {
        Die ("the TV's setup wizard still owns your saved Wi-Fi, and the switch would delete it (and cut this " +
             "connection). Fix, once: on the TV go to Settings > Network & Internet > your network > Forget, " +
             "join it again and type the password. Then run this again.")
    }
}

# ---- install --------------------------------------------------------------------------------
function Install-App {
    Step "Installing MonolithicOS"
    if (-not (Test-Path $Apk)) { Die "MonolithicOS.apk is missing next to this script. Download the release zip again." }
    $out = Adb -s $script:T install -r $Apk
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {   # signed differently (a build from somewhere else)
        Adb -s $script:T uninstall $Pkg | Out-Null
        $out = Adb -s $script:T install $Apk
    }
    if ($out -notmatch "Success") { Die "the install failed, nothing was changed: $out" }
    Tv am force-stop $Pkg | Out-Null
    $out = Tv am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $Act
    if ($out -notmatch "Status: ok") { Die "MonolithicOS didn't start, nothing else was changed: $out" }
    Tv cmd package compile -m speed -f $Pkg | Out-Null   # compiled ahead of time: fast from the first press
    Ok "installed and starts"

    # Now Playing (media sessions), wallpapers from Pictures, its screensaver, MonolithicOS Settings'
    # TV-wide switches and the phone remote. AUTO_START is TCL's: lets those start at boot.
    Tv cmd notification allow_listener "$Pkg/$Pkg.NowPlayingListener" | Out-Null
    Tv pm grant $Pkg android.permission.READ_MEDIA_IMAGES | Out-Null
    Tv pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS | Out-Null
    Tv cmd appops set $Pkg SYSTEM_ALERT_WINDOW allow | Out-Null
    Tv cmd appops set $Pkg AUTO_START allow | Out-Null
    Tv cmd appops write-settings | Out-Null
    Tv settings put secure screensaver_components "$Pkg/$Pkg.AmbientDream" | Out-Null
    A11y "$Pkg/$Pkg.TypeHelper" "on"
    Ok "screensaver, Now Playing, phone remote and settings access set up"
}

function Debloat {
    Step "Debloat & tune"
    Read-Pkgs
    $n = 0; $m = 0
    foreach ($p in $Junk) {
        if (Enabled $p) { if ((Tv pm disable-user --user 0 $p) -match "disabled") { $n++ } }
    }
    foreach ($p in $Freebies) {
        if (Has $p) { if ((Tv pm uninstall --user 0 $p) -eq "Success") { $m++ } }
    }
    Tv settings put global private_dns_mode hostname | Out-Null
    Tv settings put global private_dns_specifier dns.adguard-dns.com | Out-Null
    foreach ($s in "window_animation_scale", "transition_animation_scale", "animator_duration_scale") {
        Tv settings put global $s 0.5 | Out-Null
    }
    Ok "$n background services off, $m preinstalled freebie apps removed"
    Ok "ad blocking on (AdGuard DNS) and snappier animations (both switchable in MonolithicOS Settings)"
}

function Switch-Home {
    Step "Making MonolithicOS the Home screen"
    # every package change first, the Home choice last: Android forgets it when the set of Home apps changes
    Read-Pkgs
    if (Enabled $Setup) { Tv pm disable-user --user 0 $Setup | Out-Null }
    if (Enabled $Gtv) { Tv pm disable-user --user 0 $Gtv | Out-Null }
    if (Enabled $Atv) { Tv pm disable-user --user 0 $Atv | Out-Null }
    Start-Sleep -Seconds 1
    Tv cmd package set-home-activity --user 0 $Act | Out-Null
    Start-Sleep -Seconds 2
    Tv input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 3
    if ((Current-Home) -ne $Act -or (Focused-Pkg) -ne $Pkg) {
        Write-Host "    Home went somewhere else, undoing" -ForegroundColor Yellow
        Restore-Home
        Die "the switch didn't stick on this TV and was undone. Your TV is as it was."
    }
    Tv am broadcast -f 0x20 -a com.joe.home.SNAPSHOT -n $Watchdog | Out-Null   # update watchdog
    Ok "the Home button opens MonolithicOS"
}

function Do-Install {
    Banner
    $script:AdbExe = Find-Adb
    $script:Ip = Ask-Ip $TvIp
    $script:T = "$($script:Ip):5555"
    Connect-Tv
    Check-Tv
    Check-Wifi
    Say ""
    Say "  Debloat & tune (recommended): switch off TCL and Google ad, promo and telemetry services,"
    Say "  remove preinstalled freebie apps (Tubi, Pluto TV and co; Netflix, YouTube, Prime Video, Disney+"
    Say "  and Spotify stay), block ads with AdGuard DNS, snappier animations. All undone by the uninstaller."
    $yn = Read-Host "  Debloat & tune too? [Y/n]"
    Say ""
    Install-App
    if ($yn -notmatch "^[Nn]") { Debloat }
    Switch-Home
    Say ""
    Write-Host "  Done. Welcome to MonolithicOS." -ForegroundColor Green
    Say ""
    Say "  Phone remote: on your phone (same Wi-Fi) open http://$($script:Ip):8899"
    Say "  The PIN is in MonolithicOS Settings on the TV (the last tile in Apps)."
    Say "  Android: tap Get there for the full-screen MonolithicOS app."
    Write-Host "  Wait 15 seconds before restarting the TV (Android saves settings lazily)." -ForegroundColor DarkGray
    Pause-End
}

# ---- uninstall ------------------------------------------------------------------------------
function Restore-Home {
    Read-Pkgs
    foreach ($p in $Setup, $Gtv, $Atv) { if (Has $p) { Tv pm enable --user 0 $p | Out-Null } }
    $homes = (Tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -split "`n"
    foreach ($p in $Gtv, $Atv) {
        $launcher = $homes | Where-Object { $_.Trim().StartsWith("$p/") } | Select-Object -First 1
        if ($launcher) { Tv cmd package set-home-activity --user 0 $launcher.Trim() | Out-Null; break }
    }
    Start-Sleep -Seconds 2
    Tv input keyevent KEYCODE_HOME | Out-Null
}

function Do-Uninstall {
    Banner
    $script:AdbExe = Find-Adb
    $script:Ip = Ask-Ip $TvIp
    $script:T = "$($script:Ip):5555"
    Connect-Tv
    Step "Putting the TV back the way Google shipped it"
    Tv am broadcast -f 0x20 -a com.joe.home.FORGET -n $Watchdog | Out-Null
    A11y "$Pkg/$Pkg.TypeHelper" "off"
    A11y "$Pkg/$Pkg.KeyHelper" "off"
    if ((Tv settings get secure screensaver_components) -eq "$Pkg/$Pkg.AmbientDream") {
        Tv settings put secure screensaver_components $GoogleDream | Out-Null
    }
    Read-Pkgs
    foreach ($p in $Junk) { if (Has $p) { Tv pm enable --user 0 $p | Out-Null } }
    foreach ($p in $Freebies) { Tv cmd package install-existing --user 0 $p | Out-Null }
    if ((Tv settings get global private_dns_specifier) -eq "dns.adguard-dns.com") {
        Tv settings put global private_dns_mode opportunistic | Out-Null
        Tv settings delete global private_dns_specifier | Out-Null
    }
    foreach ($s in "window_animation_scale", "transition_animation_scale", "animator_duration_scale") {
        Tv settings put global $s 1 | Out-Null
    }
    Ok "apps, ad blocking and animations back to normal"
    Restore-Home
    Ok "Google's Home screen is back"
    if ((Adb -s $script:T uninstall $Pkg) -match "Success") { Ok "MonolithicOS removed" }
    Say ""
    Write-Host "  Done. The TV is back to stock. Thanks for trying MonolithicOS." -ForegroundColor Green
    Pause-End
}

switch ($Mode) {
    "uninstall" { Do-Uninstall }
    default { Do-Install }
}
