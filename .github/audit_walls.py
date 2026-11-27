"""Test wallpapers for the widget audit (27 Nov 2026), 1968 x 2520 so both displays crop from them.

still:   a soft photographic dusk: dark sky into warm light, blurred shapes (a calm still image).
pattern: bold, saturated geometry in the manner of Tapet's generated wallpapers (a busy still image).
"""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter

W, H = 1968, 2520
out = sys.argv[1]
random.seed(27)

def still():
    im = Image.new("RGB", (W, H))
    px = im.load()
    for y in range(H):
        t = y / H
        r = int(18 + 190 * t ** 1.6); g = int(24 + 110 * t ** 1.8); b = int(52 + 40 * (1 - t))
        for x in range(W):
            px[x, y] = (r, g, b)
    d = ImageDraw.Draw(im, "RGBA")
    for _ in range(14):
        cx, cy, rr = random.randint(0, W), random.randint(0, H), random.randint(120, 420)
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=(random.randint(150, 255), random.randint(90, 200), random.randint(60, 160), 70))
    return im.filter(ImageFilter.GaussianBlur(40))

def pattern():
    im = Image.new("RGB", (W, H), (20, 18, 40))
    d = ImageDraw.Draw(im)
    pal = [(255, 87, 34), (255, 193, 7), (0, 188, 212), (233, 30, 99), (76, 175, 80), (63, 81, 181), (250, 250, 250)]
    s = 164
    for gy in range(-1, H // s + 2):
        for gx in range(-1, W // s + 2):
            x, y = gx * s, gy * s
            c1, c2 = random.sample(pal, 2)
            if (gx + gy) % 2:
                d.polygon([(x, y), (x + s, y), (x, y + s)], fill=c1); d.polygon([(x + s, y), (x + s, y + s), (x, y + s)], fill=c2)
            else:
                d.rectangle([x, y, x + s, y + s], fill=c1); d.ellipse([x + 20, y + 20, x + s - 20, y + s - 20], fill=c2)
    for i in range(0, W + H, 90):
        d.line([(i, 0), (i - H, H)], fill=(255, 255, 255), width=6)
    return im

(still() if out.endswith("still.png") else pattern()).save(out)
