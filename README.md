# Fast Launcher

A small, fast Android home screen built with plain Android views (no AndroidX, no Compose, no Gradle).
Made for a Honor X8b (Snapdragon 680, 90 Hz) that felt janky with heavier launchers.

## What it does
- **Home**: big clock, date and current weather from [open-meteo.com](https://open-meteo.com) (tap for
  alarms / calendar / weather app), two-row dock with notification badges.
- **Drawer**: swipe up over a blurred copy of the wallpaper. It follows your finger and the rows cascade in,
  so a slow drag shows the transition and a flick finishes fast. It can be caught mid-animation.
  Search launches the top hit on Enter.
- **Wallpaper swaps** cross-fade: the launcher shows the new image itself while Android rebinds its
  wallpaper window, hiding the black blink.
- **Icons**: pebbles. Each app sits on a white organic blob plate, with its own artwork set into an
  echo of that shape. Every app gets a gentle variation seeded from its name. Shadows are baked in.
  Real adaptive icons are used even on Honor, whose icon theme normally swaps them for pre-shaped bitmaps
  (see `Icons.raw`: adaptive icons are inflated from their XML, bitmap icons decoded from raw PNG bytes).
- **Long-press an app**: a card with the app's own shortcuts (round icons, e.g. recent WhatsApp chats),
  round Info / Hide / Uninstall buttons and Add to dock (tap the dashed slot you want). Hidden apps
  collect behind a pill at the bottom of the drawer.
- **Swipe down on home** opens the notification shade.
- **Type**: clock, date and headings use Montserrat (bundled, SIL Open Font License, see `assets/fonts/OFL.txt`).
- **Long-press home**: wallpaper panel, the launcher's only settings.
  - Wallpapers come from [wallhaven.cc](https://wallhaven.cc) (SFW, portrait, at least 1080x1920).
  - **New one** swaps the wallpaper and remembers the old one as disliked, so it never comes back.
  - **Browse**: grid by theme or Popular, endless scrolling, preview with your real clock and dock on top.
  - **Change automatically**: off, daily, every 2 or 3 days, from the themes you pick. Runs as a periodic
    job and is also checked whenever you return home, since OEM battery managers delay jobs.

## Performance notes
- Grids (`AppGridView`, the wallpaper grid) are single custom views drawing pre-rendered GPU bitmaps for
  visible rows only. No child views, no layout passes while scrolling.
- Icons are rendered once and cached as raw pixels (no PNG decode), and kept in a process-wide cache,
  so an activity recreation or a cold start shows every icon on the first frame.
- The drawer and home become hardware layers only while animating.
- The wallpaper is the system wallpaper, so the launcher draws no background of its own.

Measured on the X8b with `python tools/bench.py` (real touch input over adb, every frame counted;
a frame is janky when it misses the 90 Hz deadline). Steady numbers across three runs:

| Everyday action | Janky frames | 99th percentile frame |
|---|---|---|
| Home screen, idle | no frames drawn, ~0 CPU | - |
| Drawer flick open/close | 0-0.4% | 15 ms |
| Drawer slow drag | 0-0.6% | 15 ms |
| Drawer scrolling | 0% | 13 ms |
| Wallpaper browser scrolling | 0.5-1.2% | 15 ms |
| Back home from an app | 0% (Honor draws the transition) | 13 ms |
| Long-press menu | 9-12% (of ~20 frames) | 21 ms |
| Search | 9-15% (of ~30 frames) | 24 ms |
| Wallpaper panel | 3-4% | 34 ms |
| Cold start, first frame | ~300 ms, of which ~210 ms is Android process + window setup | - |

The remaining late frames are the first two or three frames of a transition that starts after an idle
moment (Back key, closing a card): the GPU has clocked down and frames cost ~20 ms until it ramps up.
Removing the dim, the labels, the icons or the blur changes GPU time by about 1 ms, so there is nothing
left in the content to cut; steady scrolling is bounded by the panel's fixed per-frame GPU cost.

What made the difference:
- No shadows on drawer labels (they were regenerated every frame while rows faded in).
- The blur is dimmed with a colour filter in the same draw, not with a second full-screen layer.
- Home stops drawing once the drawer covers it; drawer fades without an offscreen buffer.
- Insets ignore the keyboard (the IME used to re-lay out the whole launcher on every show/hide).
- App shortcuts are fetched while the finger is still down; the menu card closes instantly.
- The wallpaper panel is built once and pre-drawn invisibly; so is the drawer after startup.
- The search field is created on first use; the dock has no scroller; job scheduling is off the main thread.

## Build and install
Needs JDK 17 and the Android SDK (build-tools 36, platform 35). With the phone on USB debugging:

```bash
./deploy.sh
```

This builds, installs, sets the app as the home app and opens it. A signing key is generated locally on
first build (`debug.keystore`, not committed). Keep a copy of it: updates must be signed with the same key,
otherwise Android requires an uninstall (which loses the dock, hidden apps and notification access). Notification badges need notification access for
"Fast Launcher badges" (Settings > Notification access).
