#!/usr/bin/env bash
# MonolithicOS installer for macOS and Linux (Windows: install.ps1, same steps).
#   bash install.sh install [tv-ip]      install MonolithicOS and make it the Home screen
#   bash install.sh uninstall [tv-ip]    put the TV back exactly as Google shipped it
# Everything goes over ADB on the home network: nothing is rooted, every change can be undone.

set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=com.joe.home
ACT=$PKG/.HomeActivity
APK="$HERE/MonolithicOS.apk"
GTV=com.google.android.apps.tv.launcherx          # Google TV's launcher
ATV=com.google.android.tvlauncher                 # the older Android TV launcher
SETUP=com.google.android.tungsten.setupwraith      # the setup wizard (a hidden fallback Home)
GOOGLE_DREAM=com.google.android.apps.tv.dreamx/.service.Backdrop
LAST="$HERE/.last-tv"

# Switched off by "Debloat & tune": TCL's ad, promo and telemetry services, store-demo bits, and
# Android's ad and personalisation services. Anything not on the TV is skipped. Accessibility
# features (TalkBack, hearing aid) and Google Assistant stay.
JUNK=(
  com.tcl.esticker com.tcl.partnercustomizer com.tcl.messagebox com.tcl.bi com.tcl.tv.tclhome_passive
  com.tcl.bootadservice com.tcl.overseasappshow com.tcl.guard com.tcl.tvweishi com.tcl.notereminder
  com.tcl.smartalexa com.tcl.exhibit com.tcl.hotelmenu com.tcl.ocean.instructions com.tcl.logkit
  com.tcl.browser com.tcl.tv.plus com.tcl.channelplus com.tcl.smartlink.core com.tcl.interactive
  com.tcl.usercenter android.autoinstalls.config.google.gtvpai com.google.android.feedback
  com.google.android.play.games com.android.printspooler com.android.adservices.api
  com.android.ondevicepersonalization.services com.android.federatedcompute.services
  com.android.tv.feedbackconsent
)
# Preinstalled ad-supported and promo apps, removed for this user (the Play Store or the uninstaller
# brings them back). Netflix, YouTube, Prime Video, Disney+ and Spotify are never touched.
FREEBIES=(
  com.tubitv tv.pluto.android com.haystack.android com.xumo.xumo com.cbs.ca com.cbs.ott
  ca.bellmedia.cravetv com.quickplay.android.bellmediaplayer com.google.android.youtube.tvmusic
)

# ---- looks ----------------------------------------------------------------------------------
if [ -t 1 ]; then B=$'\e[1m'; D=$'\e[2m'; G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; N=$'\e[0m'; else B= D= G= R= Y= N=; fi
say()  { printf '%s\n' "$*"; }
step() { printf '%s\n' "${B}==>${N} $*"; }
ok()   { printf '%s\n' "    ${G}ok${N}  $*"; }
die()  { printf '\n%s\n\n' "${R}${B}Stopped:${N} $*"; pause; exit 1; }
pause() { [ -t 0 ] && { printf '%s' "${D}Press Enter to close${N}"; read -r _; }; return 0; }

banner() {
  printf '\n%s\n' "${B}   ▐█▌  M O N O L I T H I C O S${N}"
  printf '%s\n\n' "${D}   ▐█▌  an Apple TV-style home screen for Google TV${N}"
}

# ---- adb ------------------------------------------------------------------------------------
find_adb() {
  if [ -x "$HERE/platform-tools/adb" ]; then ADB="$HERE/platform-tools/adb"; return; fi
  if command -v adb >/dev/null 2>&1; then ADB=adb; return; fi
  local os; case "$(uname -s)" in Darwin) os=darwin ;; Linux) os=linux ;; *) die "unsupported system $(uname -s)" ;; esac
  step "Getting Google's ADB tools (one time, about 7 MB)"
  curl -fsSL -o "$HERE/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" \
    || die "couldn't download ADB. Check the internet connection and run this again."
  unzip -q -o "$HERE/platform-tools.zip" -d "$HERE" && rm -f "$HERE/platform-tools.zip" || die "couldn't unpack ADB"
  ADB="$HERE/platform-tools/adb"
  ok "ADB ready"
}

ask_ip() {
  local last=""; [ -f "$LAST" ] && last=$(cat "$LAST")
  IP="${1:-}"
  while [ -z "$IP" ]; do
    say "On the TV: ${B}Settings > Network & Internet >${N} your Wi-Fi shows the TV's IP address."
    printf '%s' "${B}TV IP address${N}${last:+ [$last]}: "
    read -r IP
    IP="${IP:-$last}"
  done
  IP="${IP%%:*}"
  printf '%s' "$IP" > "$LAST"
  T="$IP:5555"
}

connect() {
  step "Connecting to the TV at $IP"
  local i state
  for i in $(seq 1 45); do
    "$ADB" connect "$T" >/dev/null 2>&1
    state=$("$ADB" -s "$T" get-state 2>/dev/null)
    [ "$state" = device ] && { ok "connected"; return; }
    if [ "$i" -eq 2 ]; then
      say "    ${Y}Look at the TV:${N} allow USB debugging, tick ${B}Always allow from this computer${N}, press ${B}Allow${N}."
      say "    ${D}Nothing on screen? Turn on USB debugging first (see the README), and check the IP.${N}"
    fi
    sleep 2
  done
  die "couldn't reach $IP. Is USB debugging on, is the IP right, and are this computer and the TV on the same network?"
}

tv() { "$ADB" -s "$T" shell "$@" 2>&1 | tr -d '\r'; }
# package lists, read once and re-read after changes (one call each instead of two per package)
ALL=""; OFF=""
pkgs() { ALL=$(tv pm list packages); OFF=$(tv pm list packages -d); }
has() { [ -n "$ALL" ] || pkgs; printf '%s\n' "$ALL" | grep -qx "package:$1"; }
enabled() { has "$1" && ! printf '%s\n' "$OFF" | grep -qx "package:$1"; }
current_home() { tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; }
focused_pkg() { tv dumpsys activity activities | grep -m1 mFocusedApp | grep -oE "[A-Za-z0-9_.]+/" | head -1 | tr -d /; }

a11y() {   # add (on) or drop (off) one accessibility service, leaving any others alone
  local svc=$1 cur rest
  cur=$(tv settings get secure enabled_accessibility_services); [ "$cur" = null ] && cur=""
  rest=$(printf '%s' "$cur" | tr ':' '\n' | grep -vx "$svc" | grep . | paste -sd: -)
  if [ "$2" = off ]; then
    if [ -n "$rest" ]; then tv settings put secure enabled_accessibility_services "$rest" >/dev/null
    else tv settings delete secure enabled_accessibility_services >/dev/null; tv settings put secure accessibility_enabled 0 >/dev/null; fi
    return
  fi
  tv settings put secure accessibility_enabled 0 >/dev/null   # clear and write back: a stale list can stay unbound
  sleep 1
  tv settings put secure enabled_accessibility_services "${rest:+$rest:}$svc" >/dev/null
  tv settings put secure accessibility_enabled 1 >/dev/null
}

# ---- checks ---------------------------------------------------------------------------------
check_tv() {
  step "Checking the TV"
  tv pm list features | grep -q "android.software.leanback" || die "this isn't an Android TV or Google TV device."
  local sdk; sdk=$(tv getprop ro.build.version.sdk)
  [ "${sdk:-0}" -ge 28 ] 2>/dev/null || die "MonolithicOS needs Android TV 9 or newer (this TV reports API ${sdk:-?})."
  has "$GTV" || has "$ATV" || die "no Google TV or Android TV launcher here (Fire TV and other systems aren't supported)."
  MODEL="$(tv getprop ro.product.model) ($(tv getprop ro.product.manufacturer), Android $(tv getprop ro.build.version.release))"
  ok "$MODEL"
}

# The setup wizard is a hidden fallback Home that outranks normal apps, so it has to be switched off
# with Google's launcher, and switching it off deletes any Wi-Fi network it saved during setup.
check_wifi() {
  if enabled "$SETUP" && tv dumpsys wifi | grep -q "cname=$SETUP"; then
    die "the TV's setup wizard still owns your saved Wi-Fi, and the switch would delete it (and cut this
         connection). Fix, once: on the TV go to Settings > Network & Internet > your network > Forget,
         join it again and type the password. Then run this again."
  fi
}

# ---- install --------------------------------------------------------------------------------
install_app() {
  step "Installing MonolithicOS"
  [ -f "$APK" ] || die "MonolithicOS.apk is missing next to this script. Download the release zip again."
  local out; out=$("$ADB" -s "$T" install -r "$APK" 2>&1 | tr -d '\r')
  case "$out" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)   # signed differently (a build from somewhere else)
      "$ADB" -s "$T" uninstall $PKG >/dev/null 2>&1
      out=$("$ADB" -s "$T" install "$APK" 2>&1 | tr -d '\r') ;;
  esac
  case "$out" in *Success*) ;; *) die "the install failed, nothing was changed: $out" ;; esac
  tv am force-stop $PKG >/dev/null
  out=$(tv am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $ACT)
  case "$out" in *"Status: ok"*) ;; *) die "MonolithicOS didn't start, nothing else was changed: $out" ;; esac
  tv cmd package compile -m speed -f $PKG >/dev/null 2>&1   # compiled ahead of time: fast from the first press
  # MonolithicOS Safety: a home of its own below MonolithicOS that only ever shows if MonolithicOS is uninstalled
  # while Google's home is off, with the way back (otherwise: a blank screen, and only a computer gets out)
  "$ADB" -s "$T" install -r "$HERE/MonolithicOS-Safety.apk" 2>&1 | grep -q Success || say "    ${Y}the safety app didn't install${N}"
  ok "installed and starts"

  # Now Playing (media sessions), wallpapers from Pictures, its screensaver, MonolithicOS Settings'
  # TV-wide switches and the phone remote. AUTO_START is TCL's: lets those start at boot.
  tv cmd notification allow_listener $PKG/$PKG.NowPlayingListener >/dev/null
  tv pm grant $PKG android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1
  tv pm grant $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null
  tv cmd appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null
  tv cmd appops set $PKG AUTO_START allow >/dev/null 2>&1
  tv cmd appops write-settings >/dev/null 2>&1
  tv settings put secure screensaver_components $PKG/$PKG.AmbientDream >/dev/null
  a11y $PKG/$PKG.TypeHelper on
  ok "screensaver, Now Playing, phone remote and settings access set up"
}

debloat() {
  step "Debloat & tune"
  local p n=0 m=0
  for p in "${JUNK[@]}"; do enabled "$p" && tv pm disable-user --user 0 "$p" >/dev/null 2>&1 && n=$((n + 1)); done
  for p in "${FREEBIES[@]}"; do has "$p" && [ "$(tv pm uninstall --user 0 "$p")" = Success ] && m=$((m + 1)); done
  tv settings put global private_dns_mode hostname >/dev/null
  tv settings put global private_dns_specifier dns.adguard-dns.com >/dev/null
  for p in window_animation_scale transition_animation_scale animator_duration_scale; do
    tv settings put global $p 0.5 >/dev/null
  done
  pkgs
  ok "$n background services off, $m preinstalled freebie apps removed"
  ok "ad blocking on (AdGuard DNS) and snappier animations (both switchable in MonolithicOS Settings)"
}

switch_home() {
  step "Making MonolithicOS the Home screen"
  # every package change first, the Home choice last: Android forgets it when the set of Home apps changes
  pkgs
  enabled "$SETUP" && tv pm disable-user --user 0 $SETUP >/dev/null
  enabled "$GTV" && tv pm disable-user --user 0 $GTV >/dev/null
  enabled "$ATV" && tv pm disable-user --user 0 $ATV >/dev/null
  sleep 1
  tv cmd package set-home-activity --user 0 $ACT >/dev/null
  sleep 2
  tv input keyevent KEYCODE_HOME
  sleep 3
  if [ "$(current_home)" != "$ACT" ] || [ "$(focused_pkg)" != "$PKG" ]; then
    say "    ${Y}Home went somewhere else, undoing${N}"
    restore_home
    die "the switch didn't stick on this TV and was undone. Your TV is as it was."
  fi
  tv am broadcast -f 0x20 -a com.joe.home.SNAPSHOT -n "$PKG/.Watchdog\\\$Receiver" >/dev/null 2>&1   # update watchdog
  ok "the Home button opens MonolithicOS"
}

do_install() {
  banner
  find_adb; ask_ip "${1:-}"; connect; check_tv; check_wifi
  say ""
  say "  ${B}Debloat & tune${N} (recommended): switch off TCL and Google ad, promo and telemetry services,"
  say "  remove preinstalled freebie apps (Tubi, Pluto TV and co; Netflix, YouTube, Prime Video, Disney+"
  say "  and Spotify stay), block ads with AdGuard DNS, snappier animations. All undone by the uninstaller."
  printf '%s' "  ${B}Debloat & tune too? [Y/n]${N} "
  local yn=y; [ -t 0 ] && read -r yn
  say ""
  install_app
  case "${yn:-y}" in [Nn]*) ;; *) debloat ;; esac
  switch_home
  say ""
  say "  ${G}${B}Done. Welcome to MonolithicOS.${N}"
  say ""
  say "  ${B}Phone remote:${N} on your phone (same Wi-Fi) open ${B}http://$IP:8899${N}"
  say "  The PIN is in MonolithicOS Settings on the TV (the last tile in Apps)."
  say "  Android: tap ${B}Get${N} there for the full-screen MonolithicOS app."
  say "  ${D}Wait 15 seconds before restarting the TV (Android saves settings lazily).${N}"
  say ""
  pause
}

# ---- uninstall ------------------------------------------------------------------------------
restore_home() {
  local launcher p
  pkgs
  has "$SETUP" && tv pm enable --user 0 $SETUP >/dev/null
  has "$GTV" && tv pm enable --user 0 $GTV >/dev/null
  has "$ATV" && tv pm enable --user 0 $ATV >/dev/null
  for p in $GTV $ATV; do
    launcher=$(tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
      | grep -oE "$p/[A-Za-z0-9_.\$]+" | head -1)
    [ -n "$launcher" ] && { tv cmd package set-home-activity --user 0 "$launcher" >/dev/null; break; }
  done
  sleep 2
  tv input keyevent KEYCODE_HOME
}

do_uninstall() {
  banner
  find_adb; ask_ip "${1:-}"; connect
  step "Putting the TV back the way Google shipped it"
  tv am broadcast -f 0x20 -a com.joe.home.FORGET -n "$PKG/.Watchdog\\\$Receiver" >/dev/null 2>&1
  a11y $PKG/$PKG.TypeHelper off
  a11y $PKG/$PKG.KeyHelper off
  [ "$(tv settings get secure screensaver_components)" = "$PKG/$PKG.AmbientDream" ] \
    && tv settings put secure screensaver_components "$GOOGLE_DREAM" >/dev/null
  local p
  for p in "${JUNK[@]}"; do has "$p" && tv pm enable --user 0 "$p" >/dev/null 2>&1; done
  for p in "${FREEBIES[@]}"; do tv cmd package install-existing --user 0 "$p" >/dev/null 2>&1; done
  if [ "$(tv settings get global private_dns_specifier)" = dns.adguard-dns.com ]; then
    tv settings put global private_dns_mode opportunistic >/dev/null
    tv settings delete global private_dns_specifier >/dev/null
  fi
  for p in window_animation_scale transition_animation_scale animator_duration_scale; do
    tv settings put global $p 1 >/dev/null
  done
  ok "apps, ad blocking and animations back to normal"
  restore_home
  ok "Google's Home screen is back"
  "$ADB" -s "$T" uninstall com.joe.home.guard >/dev/null 2>&1
  "$ADB" -s "$T" uninstall $PKG >/dev/null 2>&1 && ok "MonolithicOS removed"
  say ""
  say "  ${G}${B}Done.${N} The TV is back to stock. Thanks for trying MonolithicOS."
  say ""
  pause
}

case "${1:-install}" in
  install) shift; do_install "${1:-}" ;;
  uninstall) shift; do_uninstall "${1:-}" ;;
  *) sed -n '2,5p' "$0" ;;
esac
