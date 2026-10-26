<p align="center"><img src="docs/banner.jpg" alt="MonolithicOS" width="100%"></p>

<p align="center">
  <a href="https://github.com/ManjotSarna/MonolithicOS/releases/latest"><img src="https://img.shields.io/github/v/release/ManjotSarna/MonolithicOS?style=flat-square&color=6d8cff&label=download" alt="Download"></a>
  <img src="https://img.shields.io/badge/Google%20TV%20%2F%20Android%20TV-9%2B-black?style=flat-square" alt="Google TV / Android TV 9+">
  <img src="https://img.shields.io/badge/root-not%20needed-black?style=flat-square" alt="No root">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-black?style=flat-square" alt="MIT"></a>
</p>

I got tired of the Google TV home screen on my TCL. Ads in every row, stuff I'd never watch, and it felt slow
on a TV that cost way too much. So I made my own. It's basically what I wish Google TV looked like: your apps,
a clock, nothing else, and it's quick.

<p align="center"><img src="docs/screenshots/tv-home.jpg" alt="MonolithicOS home screen" width="100%"></p>

## Google TV vs MonolithicOS

| | Google TV home | MonolithicOS |
|---|---|---|
| **Size** | 24.5 MB app, **~327 MB** with its data and cache | **0.4 MB** app, **~5 MB** all in |
| **Memory** | **~135 MB** across two processes | **~37 MB** |
| **Home screen** | Promoted shows, sponsored rows, autoplaying trailers | **Your apps.** That's it |
| **Ads** | Yes | **None** |
| **Tracking** | Uses what you watch for recommendations and ads | **None.** No account, no analytics |
| **Ad & tracker blocking** | Not built in | **The whole TV**, one switch (AdGuard DNS) |
| **In the background** | Refreshing rows and recommendations | **Nothing** when you're not using it |
| **Phone remote** | Needs Google's app and your Google account | **Any phone browser.** No account, stays on your Wi-Fi, PIN-locked |

<sub>Sizes and memory measured on my TCL QM851G (Google TV 14), October 2026.</sub>

## What it does

- **Fast.** The whole thing is about 400 KB and uses ~37 MB of RAM. Nothing runs in the background, no rows loading.
- **Looks like tvOS.** Frosted glass dock, apps tilt and lift when you land on them, they zoom open.
- **No ads, no tracking.** No account either.
- **Cards for F1 and your teams.** Countdown to the next race or game, live score while it's on, results after.
  Football (Premier League, LaLiga, Serie A, Bundesliga, Ligue 1, MLS...), NFL, NBA, NHL, MLB.
- **Phone remote.** Touchpad, type into the TV's search boxes, open apps, switch inputs, change settings.
  Android app included.
- **Settings on the TV.** Ad blocking for the whole TV, animation speed, app icon brightness, night mode,
  screensaver, all of it.
- **Inputs in a dropdown** by the clock, plus a Now Playing card and the weather.
- **4 OLED wallpapers** built in, or send your own photos from your phone.
- **Optional debloat.** Turns off the TCL/Google junk and the preinstalled freebie apps.
- **Nothing permanent.** No root. The uninstaller puts your TV back to stock in about 10 seconds.

<p align="center">
  <img src="docs/wallpapers/horizon.jpg" width="24%" alt="Horizon">
  <img src="docs/wallpapers/slab.jpg" width="24%" alt="Slab">
  <img src="docs/wallpapers/eclipse.jpg" width="24%" alt="Eclipse">
  <img src="docs/wallpapers/wordmark.jpg" width="24%" alt="Wordmark">
</p>

## Install

You need the TV and a PC or Mac on the same Wi-Fi.

1. **On the TV:** Settings > System > About, click **Android TV OS build** 7 times. Then Settings > System >
   Developer options > turn on **USB debugging**. Note the TV's IP (Settings > Network & Internet).
2. **Download [MonolithicOS.zip](https://github.com/ManjotSarna/MonolithicOS/releases/latest/download/MonolithicOS.zip)** and unzip it.
3. **Double-click** `Install MonolithicOS (Windows).bat` or `Install MonolithicOS (Mac).command`. Type the IP,
   hit **Allow** on the TV. Done in about 20 seconds.

Mac: right-click > Open the first time. Windows: if SmartScreen shows up, More info > Run anyway.
Linux: `bash files/install.sh install`. The installer grabs Google's ADB tools on its own if you don't have them.

**Phone remote:** open `http://<tv-ip>:8899` on your phone. The PIN is in MonolithicOS Settings (last tile under Apps).
On Android, tap **Get** up top for the app. That's also where you set your name, city, wallpaper and cards.

## Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/tv-team.jpg" alt="Team card"></td>
    <td width="50%"><img src="docs/screenshots/tv-f1.jpg" alt="F1"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/tv-inputs.jpg" alt="Inputs"></td>
    <td><img src="docs/screenshots/tv-settings.jpg" alt="Settings"></td>
  </tr>
</table>

<p align="center">
  <img src="docs/screenshots/phone-remote.jpg" width="23%" alt="Remote">
  <img src="docs/screenshots/phone-apps.jpg" width="23%" alt="Apps">
  <img src="docs/screenshots/phone-look.jpg" width="23%" alt="Look">
  <img src="docs/screenshots/phone-team.jpg" width="23%" alt="Add a team">
</p>

## Remote

| | |
|---|---|
| **OK** | Open the app |
| **Hold OK** | Move/add/remove dock apps, app info, settings |
| **Up** from the dock | Your cards, then Inputs |
| **Down** from the dock | All your apps |
| **Back / Home** | Back to the first app |

## Good to know

- Built and tested on a TCL QM851G (Google TV 14). Should work on most Google TV / Android TV 9+ stuff
  (Chromecast with Google TV, Sony, Hisense...). Not Fire TV, Roku, Samsung or LG.
- With Google's home screen off, the remote's YouTube button and voice search for shows don't work.
  YouTube is in the dock; Netflix and Prime buttons work from the home screen.
- If a TV update turns Google's stuff back on, you'll get a banner. Just run the installer again.
- It only talks to Open-Meteo (weather), ipapi.co (once, to guess your city), ESPN and Jolpica (cards, if you
  add any) and AdGuard DNS (if ad blocking is on).

## Uninstall

Double-click `Uninstall MonolithicOS`. Or on the TV: MonolithicOS Settings > Restore Google TV Home.

## Build it

JDK 17 and the Android SDK (34), no Gradle: `./tools/package.sh`

---

Made by Joe ([@ManjotSarna](https://github.com/ManjotSarna)), with a lot of help from Claude. MIT licensed.
Not affiliated with Apple, Google or TCL.
