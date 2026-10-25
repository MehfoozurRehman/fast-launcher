# Fast Launcher

A small, fast Android home screen built with plain Android views (no AndroidX, no Compose, no Gradle).
Made for a Honor X8b (Snapdragon 680, 90 Hz) that felt janky with heavier launchers.

## What it does
- **Home**: big clock and date (tap for alarms / calendar), two-row dock with notification badges.
- **Drawer**: swipe up; follows your finger, settles with velocity. Search launches the top hit on Enter.
- **Icons**: pebbles. Each app sits on a white organic blob plate, with its own artwork set into an
  echo of that shape. Every app gets a gentle variation seeded from its name. Shadows are baked in.
  Real adaptive icons are used even on Honor, whose icon theme normally swaps them for pre-shaped bitmaps
  (see `Icons.raw`: adaptive icons are inflated from their XML, bitmap icons decoded from raw PNG bytes).
- **Long-press an app**: anchored menu with Add to dock (tap the dashed slot you want), App info, Uninstall.
- **Long-press home**: wallpaper panel, the launcher's only settings.
  - Wallpapers come from [wallhaven.cc](https://wallhaven.cc) (SFW, portrait, at least 1080x1920).
  - **New one** swaps the wallpaper and remembers the old one as disliked, so it never comes back.
  - **Browse**: grid by theme or Popular, endless scrolling, preview with your real clock and dock on top.
  - **Change automatically**: off, daily, every 2 or 3 days, from the themes you pick. Runs as a periodic
    job and is also checked whenever you return home, since OEM battery managers delay jobs.

## Performance notes
- Grids (`AppGridView`, the wallpaper grid) are single custom views drawing pre-rendered GPU bitmaps for
  visible rows only. No child views, no layout passes while scrolling.
- Icons are rendered once and cached as PNGs, so a restart after the OS kills the launcher is instant.
- The drawer and home become hardware layers only while animating.
- The wallpaper is the system wallpaper, so the launcher draws no background of its own.

Measured on the X8b:

| | Janky frames | 90th percentile frame |
|---|---|---|
| Smart Launcher, drawer scroll | 19% | 69 ms |
| Fast Launcher, drawer scroll | 0% | 12 ms |
| Fast Launcher, drawer open/close | 0.3% | 12 ms |

## Build and install
Needs JDK 17 and the Android SDK (build-tools 36, platform 35). With the phone on USB debugging:

```bash
./deploy.sh
```

This builds, installs, sets the app as the home app and opens it. A signing key is generated locally on
first build (`debug.keystore`, not committed). Notification badges need notification access for
"Fast Launcher badges" (Settings > Notification access).
