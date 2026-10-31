# Fast Launcher

A small, fast Android home screen built with plain Android views (no AndroidX, no Compose, no Gradle).
Made for a Honor X8b (Snapdragon 680, 90 Hz) that felt janky with heavier launchers.

## What it does
- **Home**: a big, tight clock, the date in small tracked-out capitals under it, and frosted pills below for the
  current weather from [open-meteo.com](https://open-meteo.com) (tap for the weather app) and the next alarm or
  calendar event when one falls within the coming day (tap for alarms / calendar). Two-row dock with notification badges.
- **Drawer**: swipe up over a blurred copy of the wallpaper. It follows your finger and the rows cascade in,
  so a slow drag shows the transition and a flick finishes fast. It can be caught mid-animation.
  Search finds apps, contacts, settings screens ("wifi", "battery") and sums (`12*8+4`), with a web search
  below; Enter launches the top hit.
- **Wallpaper swaps** cross-fade: the launcher shows the new image itself while Android rebinds its
  wallpaper window, hiding the black blink.
- **Icons**: pebbles. Each app's own artwork is cut to an organic blob shape, with no plate, rim or shadow
  around it: the icon itself is the shape. Every app gets a gentle variation seeded from its name.
  Real adaptive icons are used even on Honor, whose icon theme normally swaps them for pre-shaped bitmaps
  (see `Icons.raw`: adaptive icons are inflated from their XML, bitmap icons decoded from raw PNG bytes).
- **Long-press an app**: a small solid card with the app's own shortcuts (e.g. recent WhatsApp chats)
  and one row of labelled buttons: Dock (then tap the dashed slot you want), Info, Hide and Uninstall
  (in the dock: Info and Remove). Apps without shortcuts get just the button row. Hidden apps
  stay out of the drawer but still come up in search; type “hidden” to list them.
  Single-colour shortcut icons (Facebook's, for example) are tinted so they show on the dark card.
- **Swipe down on home** opens the phone's own notification shade.
- **Look**: menus, the wallpaper sheet, the Wallpapers screen and messages are frosted glass over the blurred wallpaper (no plain grey). Everything follows light and dark mode.
- **Type**: clock, date and headings use Montserrat (bundled, SIL Open Font License, see `assets/fonts/OFL.txt`).
- **Long-press home**: wallpaper panel, the launcher's only settings: your wallpaper large with New one
  and Browse on it, a row of fresh picks to preview and set (kept until you tap Refresh) and auto-change.
  Themes are the filters inside Browse.
  The lock screen and the notification and quick-settings panels are the phone's own; the launcher does not touch them.
- **Colours** follow the wallpaper: the accent is a soft tone of its most colourful colour (grey keeps blue).
  - Wallpapers come from [wallhaven.cc](https://wallhaven.cc) (SFW, portrait, at least 1080x1920).
  - **New one** swaps the wallpaper and remembers the old one as disliked, so it never comes back.
  - **Browse**: grid by theme or Popular, endless scrolling, preview with your real clock and dock on top.
  - **Change automatically**: off, daily, every 2 or 3 days, from the themes you pick. Runs as a periodic
    job and is also checked whenever you return home, since OEM battery managers delay jobs.

## Why the package is called `com.example.android.apis`
Honor's display policy pins apps it doesn't recognise to a fixed 60 Hz, and gives the packages it lists a
90 Hz plan (the list is `/product_h/region_comm/oversea/xml/agp_config.xml` on the phone). The launcher is
therefore installed under a name that list contains, which makes the screen run at a locked 90 Hz while it
is showing. The code is unchanged: `build.sh` renames only the installed package (`APP_ID=<name> ./deploy.sh`
installs under another name; `APP_ID=app.fastlauncher` gives the original 60 Hz one). The adb commands below
use the 90 Hz name. Moving between package names starts a fresh app, so the dock, hidden apps, look and
wallpaper settings travel in a small file: `--es debug export` (with the old one running) writes
`Documents/FastLauncher/settings.json`, and a fresh install imports it once on its first start.

## Build and install
Needs JDK 17 and the Android SDK (build-tools 36, platform 35). With the phone on USB debugging:

```bash
./deploy.sh
```

This builds, installs, sets the app as the home app and opens it. A signing key is generated locally on
first build (`debug.keystore`, not committed). Keep a copy of it: updates must be signed with the same key,
otherwise Android requires an uninstall (which loses the dock, hidden apps and notification access). Notification badges need notification access for
"Fast Launcher badges" (Settings > Notification access).

One-time grants over adb (they survive restarts and updates). Run each once after a fresh install:

```bash
adb shell pm grant com.example.android.apis android.permission.POST_NOTIFICATIONS
```

```bash
adb shell appops set com.example.android.apis MANAGE_EXTERNAL_STORAGE allow
```

```bash
adb shell dumpsys deviceidle whitelist +com.example.android.apis
```

In order: the one reminder the launcher leaves if it ever stops being the home app; reading the current
wallpaper so a photo set from the gallery still gets the blur (Android 13+ allows that only with all-files
access); and keeping battery saving from putting the launcher to sleep. The other permissions (calendar,
contacts, location for the weather, notification access) are the usual Android prompts; the report's
checklist shows any that are missing.

## If something goes wrong
- **Report a problem** is at the bottom of the wallpaper sheet (long-press the home screen). It shares a
  plain-text report: the installed version (`1.<n>-<commit>`), a checklist of what's switched on, the
  launcher's own log of crashes and warnings, and its recent system log. Send it to yourself and paste it
  into an issue or a chat.
- With the phone connected, the same report as a file:

```bash
adb shell am broadcast -a app.fastlauncher.DEBUG --es debug report -p com.example.android.apis
```

```bash
adb shell cat /sdcard/Android/data/com.example.android.apis/files/report.txt
```

- If Honor's launcher ever takes over as home, the launcher leaves a notification, "Fast Launcher isn't your
  home screen". Tap it and answer the system's question. (Or: Settings > Apps > Default apps > Home app.)
