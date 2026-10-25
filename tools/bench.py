"""
Everyday-use performance benchmark for Fast Launcher, driven over adb.

Each scenario resets the launcher's frame stats, performs real touch input, then reads
`dumpsys gfxinfo <pkg> framestats`. For every frame we report the total time and how it
splits between the UI thread (input, animation, measure/layout, draw recording) and the
render thread (sync, GPU command issue, swap).

    python tools/bench.py              # all scenarios
    python tools/bench.py drawer_flick # just one
"""
import re
import statistics
import subprocess
import sys
import time

PKG = "app.fastlauncher"
VSYNC_MS = 1000 / 90  # the X8b runs its panel at 90 Hz


def adb(*args, check=False):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode:
        raise RuntimeError(r.stderr)
    return r.stdout


def sh(cmd):
    return adb("shell", cmd)


def home():
    sh("input keyevent KEYCODE_HOME")
    time.sleep(0.8)


def reset():
    sh(f"dumpsys gfxinfo {PKG} reset")


def frames():
    """gfxinfo's summary covers every frame since reset (framestats on this phone keeps only 10)."""
    out = sh(f"dumpsys gfxinfo {PKG}")
    def num(label):
        m = re.search(re.escape(label) + r"\s*:?\s*(\d+)", out)
        return int(m.group(1)) if m else 0
    def ms(label):
        m = re.search(re.escape(label) + r":\s*(\d+)ms", out)
        return int(m.group(1)) if m else 0
    return {
        "frames": num("Total frames rendered"), "janky": num("Janky frames"),
        "p50": ms("50th percentile"), "p90": ms("90th percentile"), "p99": ms("99th percentile"),
        "gpu90": ms("90th gpu percentile"), "gpu99": ms("99th gpu percentile"),
        "slow_ui": num("Number Slow UI thread"), "slow_upload": num("Number Slow bitmap uploads"),
        "slow_draw": num("Number Slow issue draw commands"), "deadline": num("Number Frame deadline missed"),
    }


def report(name, r):
    f = r["frames"] or 1
    print(f"{name:14s} frames={r['frames']:4d} janky={100 * r['janky'] / f:5.1f}%  p50={r['p50']:3d} p90={r['p90']:3d} "
          f"p99={r['p99']:3d} ms  gpu p90/p99={r['gpu90']}/{r['gpu99']}  "
          f"slowUI={r['slow_ui']} upload={r['slow_upload']} drawcmd={r['slow_draw']} deadline={r['deadline']}")
    return r


# ---------------------------------------------------------------------------- scenarios

def s_idle():
    home(); reset(); time.sleep(10)
    return frames()


def s_drawer_flick():
    home(); reset()
    for _ in range(10):
        sh("input swipe 540 1700 540 700 110"); time.sleep(0.6)
        sh("input swipe 540 700 540 1700 110"); time.sleep(0.6)
    return frames()


def s_drawer_slow():
    home(); reset()
    for _ in range(3):
        sh("input swipe 540 1900 540 700 1500"); time.sleep(0.8)
        sh("input swipe 540 600 540 1800 1500"); time.sleep(0.8)
    return frames()


def s_drawer_scroll():
    home(); sh("input swipe 540 1700 540 600 110"); time.sleep(0.8); reset()
    for _ in range(8):
        sh("input swipe 540 1900 540 400 80"); time.sleep(0.8)
        sh("input swipe 540 500 540 1900 80"); time.sleep(0.8)
    return frames()


def s_search():
    home(); sh("input swipe 540 1700 540 600 110"); time.sleep(0.8); reset()
    sh("input tap 985 202"); time.sleep(0.8)
    for ch in "whatsap":
        sh(f"input text {ch}"); time.sleep(0.35)
    for _ in range(7):
        sh("input keyevent KEYCODE_DEL"); time.sleep(0.25)
    sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
    return frames()


def s_menu():
    home(); reset()
    for x in (540, 937, 143):
        sh(f"input swipe {x} 2040 {x} 2040 800"); time.sleep(0.9)
        sh("input keyevent KEYCODE_BACK"); time.sleep(0.6)
    return frames()


def s_sheet():
    home(); reset()
    for _ in range(3):
        sh("input swipe 540 1200 540 1200 900"); time.sleep(1.2)
        sh("input keyevent KEYCODE_BACK"); time.sleep(0.7)
    return frames()


def s_app_return():
    home(); reset()
    for _ in range(4):
        sh("am start -n com.android.settings/.Settings"); time.sleep(1.5)
        sh("input keyevent KEYCODE_HOME"); time.sleep(1.5)
    return frames()


def s_browser():
    home(); sh("input swipe 540 1200 540 1200 900"); time.sleep(1.2)
    tap_text("Browse"); time.sleep(3); reset()
    for _ in range(6):
        sh("input swipe 540 1900 540 500 90"); time.sleep(1.0)
    rows = frames()
    for _ in range(3):
        sh("input keyevent KEYCODE_BACK"); time.sleep(0.5)
    return rows


def tap_text(text):
    xml = sh("uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml")
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")


SCENARIOS = {
    "idle": s_idle, "drawer_flick": s_drawer_flick, "drawer_slow": s_drawer_slow,
    "drawer_scroll": s_drawer_scroll, "search": s_search, "menu": s_menu, "sheet": s_sheet,
    "app_return": s_app_return, "browser": s_browser,
}


def restore_home():
    sh(f"cmd package set-home-activity {PKG}/.MainActivity")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)


def cold_start():
    """Kill the launcher; Android relaunches home by itself and logs how long the first frame took."""
    times = []
    for _ in range(2):  # Honor resets the default home after repeated force-stops
        adb("logcat", "-c")
        sh(f"am force-stop {PKG}")
        time.sleep(4)
        log = adb("logcat", "-d", "-s", "ActivityTaskManager:I")
        m = re.search(r"Displayed " + re.escape(PKG) + r"/\.MainActivity[^+]*\+(?:(\d+)s)?(\d+)ms", log)
        if m:
            times.append(int(m.group(1) or 0) * 1000 + int(m.group(2)))
    restore_home()
    print(f"{'cold_start':14s} first frame after a kill: {times} ms (median {statistics.median(times) if times else '-'})")


def memory():
    out = sh(f"dumpsys meminfo {PKG}")
    m = re.search(r"TOTAL PSS:\s+(\d+)", out)
    g = re.search(r"Graphics:\s+(\d+)", out)
    print(f"{'memory':16s} PSS={int(m.group(1)) // 1024 if m else '?'} MB  graphics={int(g.group(1)) // 1024 if g else '?'} MB")


if __name__ == "__main__":
    restore_home()
    pick = sys.argv[1:] or list(SCENARIOS)
    for name in pick:
        if name == "cold_start":
            cold_start(); continue
        if name == "memory":
            memory(); continue
        report(name, SCENARIOS[name]())
    if not sys.argv[1:]:
        cold_start()
        memory()
    home()
