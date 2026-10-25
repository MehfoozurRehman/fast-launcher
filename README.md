# Fast Launcher

A tiny, fast Android home screen built with plain Android views (no AndroidX, no Compose, no Gradle).
Made for a Honor X8b (Snapdragon 680, 90 Hz) that felt janky with heavier launchers.

## Design
- The grid (`AppGridView`) is a single custom `View` that draws pre-rendered icon bitmaps and only the visible rows. No child views, no layout passes while scrolling.
- Icons are rendered once on a background thread and cached.
- The wallpaper is the system wallpaper, so the launcher draws no background.
- Drawer open/close is a single progress value driving alpha and translation, so it can follow the finger.

## Measured on the X8b (scrolling the drawer)
| Launcher | Janky frames | 90th percentile frame |
|---|---|---|
| Smart Launcher | 19% | 69 ms |
| Fast Launcher | 0.5% | 12 ms |

## Build and install
Needs JDK 17 and the Android SDK (build-tools 36, platform 35). With the phone connected over USB debugging:

```bash
./deploy.sh
```

This builds, installs, sets the app as the home app and opens it. A signing key is generated locally on first build (`debug.keystore`, not committed).

## Roadmap
- Notification badges on icons
- Icon customization (shape, size, icon packs)
- Daily rotating online wallpaper, kept for 2-3 days, with a "don't like this" button
- Ongoing performance work
