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
- **Icons**: pebbles. Each app sits on a white organic blob plate, with its own artwork set into an
  echo of that shape. Every app gets a gentle variation seeded from its name. Shadows are baked in.
  Real adaptive icons are used even on Honor, whose icon theme normally swaps them for pre-shaped bitmaps
  (see `Icons.raw`: adaptive icons are inflated from their XML, bitmap icons decoded from raw PNG bytes).
- **Long-press an app**: a card with the app's own shortcuts (round icons, e.g. recent WhatsApp chats),
  round Info / Hide / Uninstall buttons and Add to dock (tap the dashed slot you want). Hidden apps
  collect behind a pill at the bottom of the drawer.
- **Swipe down on home** opens the notifications page; **double-tap** empty space to lock the phone.
- **Under the date**: the next alarm and calendar event when they fall within the coming day.
- **Notifications, from any app**: swipe down from the left side of the status bar. Tap one to open it,
  swipe it sideways to dismiss, or Clear all. The bell in the panel's header switches between pages.
- **Control panel, from any app**: swipe down from the right half of the status bar (needs the
  "Fast Launcher controls" accessibility service, which reads no screen content). Wi-Fi, Mobile data,
  Bluetooth and Torch tiles; a now-playing card (album art, live progress you can drag to seek, controls; tap it to open the player) beside tall brightness and volume sliders that show the real level; and a
  grid with Hotspot, Auto-rotate, Aeroplane, Location, Dark mode, Do not disturb, Sound mode, Eye
  comfort, Battery saver, Screenshot, Screen record, Cast, Calculator, Recorder, Lock and Power.
  Controls Android lets apps change are switched in place; the rest open their exact system page.
  The pull strip is removed while the phone is locked.
- **Light and dark mode**: the panel, menus and wallpaper sheet follow the system theme, using the
  same colours as the system's own pop-ups.
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
