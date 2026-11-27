"""Contact sheets and automatic checks for the widget audit (27 Nov 2026).

Reads the photographs and bounds from audit.sh (one folder per wallpaper, merged into audit/), and writes
audit/out/: a contact sheet per wallpaper, screen and scene (every finish), a same-place sheet per wallpaper,
and faults.md listing what the checks found. The checks cover what pixels can prove:

  F1  The finish doesn't reach the widget's edge: an opaque finish whose edge strip is still the bare wallpaper.
  F2  Something drawn in the widget's margin or over its edge: an edge strip far busier than the bare wallpaper.
  F3  Not the same tone: at the same place and size, in the same finish, an app's finish differs from
      Fold Widgets' own (CIE76 colour difference over the four edge strips, against the weather widget).

Layout quality (cut titles, crowding, empty bands, overlaps) is judged by eye from the sheets.
"""
import glob, os, re, sys
from collections import defaultdict
from PIL import Image, ImageDraw, ImageFont, ImageStat

ROOT = sys.argv[1] if len(sys.argv) > 1 else "audit"
OUT = os.path.join(ROOT, "out"); os.makedirs(OUT, exist_ok=True)
DP = 2.625
OPAQUE = {"smoked", "rosso", "avorio", "carbonio", "ceramica", "visconti", "paper", "ottone"}
ORDER = ["vetro", "cristallo", "goccia", "smoked", "membrane", "rosso", "avorio", "carbonio", "ceramica", "visconti", "paper", "ottone"]

def font(n):
    for f in ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]:
        if os.path.exists(f): return ImageFont.truetype(f, n)
    return ImageFont.load_default()

def bounds(txt):
    out = []
    for line in open(txt):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 7: out.append((p[0], int(p[3]), int(p[4]), int(p[5]), int(p[6])))
    return out

def lab(rgb):
    def f(c):
        c /= 255; return ((c + 0.055) / 1.055) ** 2.4 if c > 0.04045 else c / 12.92
    r, g, b = (f(x) for x in rgb)
    x, y, z = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.9505, 0.2126 * r + 0.7152 * g + 0.0722 * b, (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.089
    h = lambda t: t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    return (116 * h(y) - 16, 500 * (h(x) - h(y)), 200 * (h(y) - h(z)))

def de(a, b): return sum((p - q) ** 2 for p, q in zip(lab(a), lab(b))) ** 0.5

def strips(x, y, w, h):
    a, b = int(3 * DP), int(8 * DP)
    return {"left": (x + a, y + int(h * .35), x + b, y + int(h * .65)), "right": (x + w - b, y + int(h * .35), x + w - a, y + int(h * .65)),
            "top": (x + int(w * .35), y + a, x + int(w * .65), y + b), "bottom": (x + int(w * .35), y + h - b, x + int(w * .65), y + h - a)}

def spread(im, box):
    g = im.crop(box).convert("L"); hist = g.histogram(); n = sum(hist)
    acc, lo, hi = 0, 0, 255
    for i, c in enumerate(hist):
        acc += c
        if acc >= n * .05 and lo == 0: lo = i
        if acc >= n * .95: hi = i; break
    return hi - lo

def mean(im, box): return tuple(ImageStat.Stat(im.crop(box).convert("RGB")).mean)

faults = []
shots = sorted(glob.glob(os.path.join(ROOT, "**", "*.png"), recursive=True))
shots = [s for s in shots if not s.startswith(OUT)]
name = lambda s: os.path.splitext(os.path.basename(s))[0]
bare = {re.sub("-bare$", "", name(s)): s for s in shots if name(s).endswith("-bare")}

# Scenes: wall-screen-finish-scene
scenes = defaultdict(dict)
for s in shots:
    m = re.match(r"(still|pattern|live)-(main|cover)-([a-z]+)-([ab])$", name(s))
    if not m: continue
    wall, screen, fin, sc = m.groups()
    scenes[(wall, screen, sc)][fin] = s
    txt = s[:-4] + ".txt"
    if not os.path.exists(txt): faults.append(f"| {wall} | {screen} | {fin} | scene {sc} | - | no bounds (Banco didn't place it) |"); continue
    im = Image.open(s).convert("RGB")
    ref = Image.open(bare[f"{wall}-{screen}"]).convert("RGB") if f"{wall}-{screen}" in bare else None
    for label, x, y, w, h in bounds(txt):
        for side, box in strips(x, y, w, h).items():
            if ref is not None and fin in OPAQUE and de(mean(im, box), mean(ref, box)) < 4:
                faults.append(f"| {wall} | {screen} | {fin} | {label} {w/DP:.0f}x{h/DP:.0f} | F1 | the finish doesn't reach the {side} edge |")
            if ref is not None and spread(im, box) > spread(ref, box) + 70:
                faults.append(f"| {wall} | {screen} | {fin} | {label} {w/DP:.0f}x{h/DP:.0f} | F2 | something drawn in the {side} margin |")

# Contact sheets: every finish of one scene
for (wall, screen, sc), by in sorted(scenes.items()):
    fins = [f for f in ORDER if f in by]
    if not fins: continue
    txt = by[fins[0]][:-4] + ".txt"
    bb = bounds(txt) if os.path.exists(txt) else []
    if bb:
        x0 = max(0, min(b[1] for b in bb) - 20); y0 = max(0, min(b[2] for b in bb) - 20)
        x1 = max(b[1] + b[3] for b in bb) + 20; y1 = max(b[2] + b[4] for b in bb) + 20
    else:
        x0, y0, x1, y1 = 0, 0, *Image.open(by[fins[0]]).size
    cw = 900; ch = int(cw * (y1 - y0) / (x1 - x0)); cols = 3; rows = (len(fins) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cw, rows * (ch + 50)), (12, 13, 14)); d = ImageDraw.Draw(sheet)
    for i, f in enumerate(fins):
        tile = Image.open(by[f]).convert("RGB").crop((x0, y0, x1, y1)).resize((cw, ch), Image.LANCZOS)
        cx, cy = (i % cols) * cw, (i // cols) * (ch + 50)
        sheet.paste(tile, (cx, cy + 50)); d.text((cx + 16, cy + 12), f"{f}  ({wall} wallpaper, {screen} screen, scene {sc})", fill=(230, 220, 200), font=font(26))
    sheet.save(os.path.join(OUT, f"sheet-{wall}-{screen}-{sc}.jpg"), quality=88)

# The same place: one row per finish, one column per app, and the tone check
slots = defaultdict(dict)
for s in shots:
    m = re.match(r"(still|pattern|live)-slot-([a-z]+)-([a-z-]+)$", name(s))
    if m: slots[(m.group(1), m.group(2))][m.group(3)] = s
apps = ["fw-weather", "fw-agenda", "fw-music", "nodo", "dorso", "riserva", "settimana"]
for wall in sorted({k[0] for k in slots}):
    fins = [f for f in ORDER if (wall, f) in slots]
    tw, th = 420, 290
    sheet = Image.new("RGB", (len(apps) * tw + 200, len(fins) * (th + 20) + 60), (12, 13, 14)); d = ImageDraw.Draw(sheet)
    for j, a in enumerate(apps): d.text((200 + j * tw + 10, 16), a, fill=(230, 220, 200), font=font(26))
    for i, f in enumerate(fins):
        d.text((10, 60 + i * (th + 20) + th // 2), f, fill=(230, 220, 200), font=font(26))
        ref = None
        for j, a in enumerate(apps):
            s = slots[(wall, f)].get(a)
            if not s or not os.path.exists(s[:-4] + ".txt"): continue
            bb = bounds(s[:-4] + ".txt")
            if not bb: continue
            _, x, y, w, h = bb[0]
            im = Image.open(s).convert("RGB")
            tone = tuple(sum(c) / 4 for c in zip(*(mean(im, b) for b in strips(x, y, w, h).values())))
            if a == "fw-weather": ref = tone
            elif ref is not None and de(tone, ref) > 6:
                faults.append(f"| {wall} | main | {f} | {a} 255x164 | F3 | not the same tone as Fold Widgets' own beside it (difference {de(tone, ref):.0f}; 6 or more is plainly visible) |")
            sheet.paste(im.crop((x - 12, y - 12, x + w + 12, y + h + 12)).resize((tw - 20, th), Image.LANCZOS), (200 + j * tw, 60 + i * (th + 20)))
    sheet.save(os.path.join(OUT, f"same-place-{wall}.jpg"), quality=88)

with open(os.path.join(OUT, "faults.md"), "w") as f:
    f.write("# Widget audit: automatic checks\n\n| Wallpaper | Screen | Finish | Widget | Check | Found |\n|---|---|---|---|---|---|\n")
    f.write("\n".join(faults) + "\n")
print(f"{len(faults)} faults; sheets in {OUT}")
