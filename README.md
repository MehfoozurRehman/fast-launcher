# Fast Launcher

A small, fast Android home screen built with plain Android views (no AndroidX, no Compose, no Gradle).
Made for a Honor X8b (Snapdragon 680, 90 Hz) that felt janky with heavier launchers.

## What it does
- **Home**: big clock, date and current weather from [open-meteo.com](https://open-meteo.com) (tap for
  alarms / calendar / weather app), two-row dock with notification badges.
- **Drawer**: swipe up over a blurred copy of the wallpaper. It follows your finger and the rows cascade in,
  so a slow drag shows the transition and a flick finishes fast. It can be caught mid-animation.
  Search finds apps, contacts and sums (`12*8+4`), with a web search below; Enter launches the top hit.
- **Wallpaper swaps** cross-fade: the launcher shows the new image itself while Android rebinds its
  wallpaper window, hiding the black blink.
- **Icons**: pebbles. Each app sits on an organic blob plate (white in light mode, near-black in dark mode), with its own artwork set into an
  echo of that shape. Every app gets a gentle variation seeded from its name. Shadows are baked in.
  Real adaptive icons are used even on Honor, whose icon theme normally swaps them for pre-shaped bitmaps
  (see `Icons.raw`: adaptive icons are inflated from their XML, bitmap icons decoded from raw PNG bytes).
- **Long-press an app**: a card with the app's own shortcuts (round icons, e.g. recent WhatsApp chats),
  round Info / Hide / Uninstall buttons and Add to dock (tap the dashed slot you want). Hidden apps
  stay out of the drawer but still come up in search; type “hidden” to list them.
  Single-colour shortcut icons (Facebook's, for example) are tinted so they show on the dark card.
- **Swipe down on home** opens the notifications page; **double-tap** empty space to lock the phone.
- **Under the date**: the next alarm and calendar event when they fall within the coming day.
- **Notifications, from any app**: swipe down from the left side of the status bar. Tap one to open it,
  swipe it right to dismiss, or Clear all. Swipe sideways to move between notifications and controls;
  a swipe from the screen edge (the back gesture) closes the panel.
  Ongoing ones (USB mode, charging, downloads with their progress) are listed first and can be tapped
  but not dismissed. Notification buttons work (Reply opens a reply field in the card, Mark as read
  and the like act without closing the panel). **Original** in the panel's top bar opens the
  phone's own notifications or control centre (whichever page you're on), just that once. An incoming call closes the panel; in landscape the phone's own pull-down is used.
- **Control panel, from any app**: swipe down from the right half of the status bar (needs the
  "Fast Launcher controls" accessibility service, which reads no screen content). Wi-Fi, Mobile data,
  Bluetooth and Torch tiles; a now-playing card (album art, live progress you can drag to seek, controls; tap it to open the player) beside tall brightness and volume sliders that show the real level; and a
  grid with Hotspot, Auto-rotate, Aeroplane, Location, Dark mode, Do not disturb, Sound mode, Eye
  comfort, Battery saver, Screenshot, Screen record, Cast, Calculator, Recorder, Lock and Power.
  Controls Android lets apps change are switched in place; the rest open their exact system page.
  Long-press the brightness slider to switch auto-brightness on or off.
  Tap Bluetooth to see paired devices and which one is connected (or to get Android's turn-on prompt).
  Long-press any round button and drag it to rearrange the grid; the order is remembered.
  The pull strip is removed while the phone is locked.
- **Look**: like the system's control centre, the panel shows whatever was on screen, blurred, behind
  see-through tiles (the capture stays in memory and is never saved). Menus, the wallpaper sheet
  and messages are frosted glass over the blurred wallpaper. Everything follows light and dark mode.
- **Type**: clock, date and headings use Montserrat (bundled, SIL Open Font License, see `assets/fonts/OFL.txt`).
- **Long-press home**: wallpaper panel, the launcher's only settings.
  - Wallpapers come from [wallhaven.cc](https://wallhaven.cc) (SFW, portrait, at least 1080x1920).
  - **New one** swaps the wallpaper and remembers the old one as disliked, so it never comes back.
  - **Browse**: grid by theme or Popular, endless scrolling, preview with your real clock and dock on top.
  - **Change automatically**: off, daily, every 2 or 3 days, from the themes you pick. Runs as a periodic
    job and is also checked whenever you return home, since OEM battery managers delay jobs.

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
adb shell pm grant app.fastlauncher android.permission.WRITE_SECURE_SETTINGS
```

```bash
adb shell pm grant app.fastlauncher android.permission.POST_NOTIFICATIONS
```

```bash
adb shell appops set app.fastlauncher MANAGE_EXTERNAL_STORAGE allow
```

```bash
adb shell dumpsys deviceidle whitelist +app.fastlauncher
```

In order: switching the panel service back on if Android turns it off (a force-stop, for example by a
phone cleaner, does that); the screen recorder's Stop notification; reading the current wallpaper so a
photo set from the gallery still gets the blur (Android 13+ allows that only with all-files access);
and keeping battery saving from putting the launcher to sleep. The other permissions (calendar,
contacts, nearby devices, notification access, accessibility, modify system settings) are the usual
Android prompts; the report's checklist shows any that are missing.

## If something goes wrong
- **Report a problem** is at the bottom of the wallpaper sheet (long-press the home screen). It shares a
  plain-text report: the installed version (`1.<n>-<commit>`), a checklist of what's switched on, the
  launcher's own log of crashes and warnings, and its recent system log. Send it to yourself and paste it
  into an issue or a chat.
- With the phone connected, the same report as a file:

```bash
adb shell am broadcast -a app.fastlauncher.DEBUG --es debug report -p app.fastlauncher
```

```bash
adb shell cat /sdcard/Android/data/app.fastlauncher/files/report.txt
```

- If Honor's launcher ever takes over as home: Settings > Apps > Default apps > Home app > Fast Launcher.
