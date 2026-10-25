"""Rasterises the icon (same geometry as make_icon.py) to docs/icon-preview.png with Pillow."""
import math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image, ImageDraw, ImageFilter
import importlib.util
spec = importlib.util.spec_from_file_location("mk", os.path.join(os.path.dirname(__file__), "make_icon.py"))

S = 8  # pixels per viewport unit
W = 108 * S

def lerp(a, b, t): return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
def hexc(h): h = h[-6:]; return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))

def pebble_pts(cx, cy, r, seed=7, turn=0.35, steps=400):
    import random
    rnd = random.Random(seed)
    p2, p3, p5 = (rnd.random() * math.tau for _ in range(3))
    rad = []
    for i in range(steps):
        t = math.tau * i / steps + turn
        rad.append(1 + 0.035 * math.sin(2 * t + p2) + 0.040 * math.sin(3 * t + p3) + 0.012 * math.sin(5 * t + p5))
    m = max(rad)
    return [((cx + r * rad[i] / m * math.cos(math.tau * i / steps)) * S, (cy + r * rad[i] / m * math.sin(math.tau * i / steps)) * S) for i in range(steps)]

def icon():
    bg = Image.new("RGB", (W, W))
    a, b = hexc("10152A"), hexc("2F6BF6")
    px = bg.load()
    for y in range(W):
        for x in range(W):
            px[x, y] = lerp(a, b, (x + y) / (2 * W))
    glow = Image.new("L", (W, W), 0)
    gp = glow.load()
    for y in range(W):
        for x in range(W):
            d = math.hypot(x / S - 54, y / S - 52) / 40
            if d < 1: gp[x, y] = round(41 * (1 - d))
    bg.paste((255, 255, 255), mask=glow)
    sh = Image.new("L", (W, W), 0)
    ImageDraw.Draw(sh).polygon(pebble_pts(54, 56.5, 25.5), fill=72)
    bg.paste((0, 0, 0), mask=sh.filter(ImageFilter.GaussianBlur(S * 1.5)))
    ImageDraw.Draw(bg).polygon(pebble_pts(54, 53, 25), fill=(255, 255, 255))
    # chevrons with a blue->violet gradient
    def chevron(pts, alpha):
        m = Image.new("L", (W, W), 0)
        d = ImageDraw.Draw(m)
        sw = 5.2 * S
        P = [(x * S, y * S) for x, y in pts]
        d.line(P, fill=255, width=round(sw), joint="curve")
        for x, y in (P[0], P[-1]): d.ellipse([x - sw / 2, y - sw / 2, x + sw / 2, y + sw / 2], fill=255)
        grad = Image.new("RGB", (W, W))
        g = grad.load(); c1, c2 = hexc("2F7CF6"), hexc("7B5CFF")
        for y in range(W):
            for x in range(W):
                g[x, y] = lerp(c1, c2, max(0, min(1, (x - 43 * S) / (22 * S))))
        bg.paste(grad, mask=m.point(lambda v: v * alpha))
    chevron([(43.5, 65.5), (54, 55), (64.5, 65.5)], 0.42)
    chevron([(43.5, 55), (54, 44.5), (64.5, 55)], 1.0)
    return bg

full = icon()
def masked(shape):
    m = Image.new("L", (W, W), 0); d = ImageDraw.Draw(m)
    if shape == "squircle": d.rounded_rectangle([18 * S, 18 * S, 90 * S, 90 * S], radius=20 * S, fill=255)
    else: d.ellipse([18 * S, 18 * S, 90 * S, 90 * S], fill=255)
    out = Image.new("RGB", (W, W), (233, 235, 240)); out.paste(full, mask=m)
    return out.crop((14 * S, 14 * S, 94 * S, 94 * S))
tile = 80 * S
sheet = Image.new("RGB", (W + tile * 2 + 60, W + 20), (233, 235, 240))
sheet.paste(full, (10, 10))
sheet.paste(masked("squircle"), (W + 30, 10 + (W - tile) // 2))
sheet.paste(masked("circle"), (W + tile + 50, 10 + (W - tile) // 2))
out = os.path.join(os.path.dirname(__file__), "..", "docs", "icon-preview.png")
sheet.resize((sheet.width // 2, sheet.height // 2), Image.LANCZOS).save(out)
print("wrote docs/icon-preview.png")
