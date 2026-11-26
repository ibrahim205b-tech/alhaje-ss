"""Picture for «فرصة أخرى للحياة» — renders 1440x2560 (2K vertical) @ 30 fps.

    python3 build/video.py                 # full render -> output/
    python3 build/video.py --stills 1,5,9  # review frames -> output/stills/
    python3 build/video.py --clean         # also export a no-captions master

Reads assets/photos (from assets.py) and assets/audio/timeline.json +
final_mix.wav (from audio.py). Every cut is driven by the voice timeline.
"""
import json
import math
import subprocess
import sys
from functools import lru_cache
from multiprocessing import Pool
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parent.parent
PH = ROOT / "assets" / "photos"
FONTS = ROOT / "assets" / "fonts"
AUD = ROOT / "assets" / "audio"
OUT = ROOT / "output"
OUT.mkdir(exist_ok=True)

W, H, FPS = 1440, 2560, 30
TL = json.loads((AUD / "timeline.json").read_text())
SEG = {s["id"]: s for s in TL["segments"]}
AN = TL["anchors"]
TOTAL = TL["total"]
NFRAMES = int(math.ceil(TOTAL * FPS))
TEMPO = TL["tempo"]
SHOW_CAPTIONS = True

IVORY = (239, 230, 214)
WHITE = (255, 255, 255)

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def lin(t, a, b):
    return clamp((t - a) / (b - a)) if b > a else float(t >= b)


def ease_out(x):
    return 1 - (1 - x) ** 3


def ease_io(x):
    return x * x * (3 - 2 * x)


def win(t, a, b, fi=0.3, fo=0.3):
    """1 inside [a,b] with fades."""
    return min(lin(t, a, a + fi), 1 - lin(t, b - fo, b))


@lru_cache(None)
def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size, layout_engine=ImageFont.Layout.RAQM)


# ---------------------------------------------------------------------------
# colour grade (applied once per photograph)
# ---------------------------------------------------------------------------
def grade(img, mode):
    a = np.asarray(img.convert("RGB")).astype(np.float32) / 255
    lum = (a * [0.2126, 0.7152, 0.0722]).sum(2, keepdims=True)
    sat = {"warm": 0.86, "cold": 0.62, "doc": 0.7, "neutral": 0.8}[mode]
    a = lum + (a - lum) * sat
    s = a * a * (3 - 2 * a)                       # gentle S-curve
    a = a + (s - a) * 0.38
    a = np.clip((a - 0.025) / 0.975, 0, 1)        # deep blacks
    hi = a ** 2
    if mode == "warm":
        a += hi * [0.035, 0.012, -0.03]
        a += (1 - a) ** 3 * [-0.008, 0.0, 0.012]  # whisper of cool in shadows
    elif mode == "cold":
        a += hi * [-0.01, 0.005, 0.02]
        a += (1 - a) ** 3 * [-0.01, 0.006, 0.02]
    elif mode == "doc":
        a += hi * [0.02, 0.01, -0.02]
    else:
        a += hi * [0.02, 0.008, -0.015]
    return Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8))


MODES = {"lamp": "warm", "tree": "warm", "portrait": "warm",
         "corridor": "cold", "iv": "cold", "card": "cold", "arm": "cold",
         "report": "doc", "door": "neutral", "exterior": "warm"}

PRINTS = {}
BGS = {}


def load_assets():
    if PRINTS:
        return
    for name, mode in MODES.items():
        src = Image.open(PH / f"{name}.png")
        alpha = src.getchannel("A") if src.mode == "RGBA" else None
        g = grade(src, mode)
        # upscale once, carefully (sources are small screenshots)
        k = 1500 / max(g.size)
        big = g.resize((round(g.width * k), round(g.height * k)), Image.LANCZOS)
        big = big.filter(ImageFilter.UnsharpMask(radius=2.2, percent=55, threshold=2))
        gray = ImageOps.grayscale(big).convert("RGB")
        gray = Image.blend(gray, Image.new("RGB", gray.size, (40, 44, 50)), 0.08)
        a_big = alpha.resize(big.size, Image.LANCZOS) if alpha else None
        PRINTS[name] = (big, gray, a_big)
        # background: blurred, darkened, covering the frame
        small = g.convert("RGB")
        sc = max(180 / small.width, 320 / small.height)
        small = small.resize((max(1, round(small.width * sc)), max(1, round(small.height * sc))), Image.LANCZOS)
        small = ImageOps.fit(small, (180, 320)).filter(ImageFilter.GaussianBlur(9))
        bg = small.resize((W, H), Image.BICUBIC)
        BGS[name] = bg


# precomputed overlays
def _vignette():
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt(((x - W / 2) / (W * 0.62)) ** 2 + ((y - H * 0.47) / (H * 0.6)) ** 2)
    return np.clip(1 - 0.42 * np.clip(d - 0.35, 0, 1) ** 1.6, 0, 1)[..., None].astype(np.float32)


VIG = None
GRAIN = None
LEAK = None
SHADOW = None


def load_fx():
    global VIG, GRAIN, LEAK, SHADOW
    if VIG is not None:
        return
    VIG = _vignette()
    rng = np.random.default_rng(3)
    GRAIN = []
    for _ in range(6):
        g = rng.normal(0, 1, (H // 2, W // 2)).astype(np.float32)
        gi = Image.fromarray(np.clip(g * 40 + 128, 0, 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
        GRAIN.append(((np.asarray(gi).astype(np.float32) - 128) / 40 * 4.2)[..., None])
    # warm light leak (low-res radial, upsampled)
    y, x = np.mgrid[0:320, 0:180].astype(np.float32)
    d = np.sqrt(((x - 150) / 120) ** 2 + ((y - 60) / 200) ** 2)
    m = np.clip(1 - d, 0, 1) ** 2
    leak = np.stack([m * 255, m * 150, m * 70], 2).astype(np.uint8)
    LEAK = np.asarray(Image.fromarray(leak).resize((W, H), Image.BICUBIC)).astype(np.float32)
    # soft shadow tile
    s = Image.new("L", (256, 256), 0)
    ImageDraw.Draw(s).rectangle((40, 40, 216, 216), fill=255)
    SHADOW = s.filter(ImageFilter.GaussianBlur(22))


def comp(canvas, layer, x, y):
    """alpha-composite layer onto canvas at (x, y), clipping at edges."""
    x, y = int(round(x)), int(round(y))
    lw, lh = layer.size
    sx, sy = max(0, -x), max(0, -y)
    ex, ey = min(lw, W - x), min(lh, H - y)
    if ex <= sx or ey <= sy:
        return
    if (sx, sy, ex, ey) != (0, 0, lw, lh):
        layer = layer.crop((sx, sy, ex, ey))
    canvas.alpha_composite(layer, (x + sx, y + sy))


def mul_alpha(img, a):
    if a >= 0.999:
        return img
    r, g, b, al = img.split()
    al = al.point(lambda v: int(v * a))
    return Image.merge("RGBA", (r, g, b, al))


# ---------------------------------------------------------------------------
# photographic prints
# ---------------------------------------------------------------------------
def draw_print(cv, name, cx, cy, bw, bh, scale=1.0, rot=0.0, alpha=1.0,
               zoom=1.0, fx=0.5, fy=0.5, sat=1.0, bright=1.0, shadow=0.6, circle=False):
    if alpha <= 0.002:
        return
    col, gray, a_big = PRINTS[name]
    iw, ih = col.size
    k = min(bw / iw, bh / ih) * scale
    dw, dh = max(2, round(iw * k)), max(2, round(ih * k))
    cw, ch = iw / zoom, ih / zoom
    x0 = clamp(fx * iw - cw / 2, 0, iw - cw)
    y0 = clamp(fy * ih - ch / 2, 0, ih - ch)
    box = (x0, y0, x0 + cw, y0 + ch)
    img = col.resize((dw, dh), Image.BICUBIC, box=box)
    if sat < 0.999:
        g = gray.resize((dw, dh), Image.BICUBIC, box=box)
        img = Image.blend(g, img, clamp(sat))
    if bright < 0.999:
        img = Image.blend(Image.new("RGB", img.size, (0, 0, 0)), img, clamp(bright))
    img = img.convert("RGBA")
    if circle:
        m = Image.new("L", (dw * 4, dh * 4), 0)
        ImageDraw.Draw(m).ellipse((6, 6, dw * 4 - 6, dh * 4 - 6), fill=255)
        img.putalpha(m.resize((dw, dh), Image.LANCZOS))
    else:
        # hairline light edge, like the paper edge of a print
        ImageDraw.Draw(img).rectangle((0, 0, dw - 1, dh - 1), outline=(255, 248, 235, 40), width=2)
    pad = 110
    lay = Image.new("RGBA", (dw + 2 * pad, dh + 2 * pad), (0, 0, 0, 0))
    if shadow > 0:
        sh = SHADOW.resize((int((dw + 2 * pad) * 1.0), int((dh + 2 * pad) * 1.0)), Image.BICUBIC)
        if circle:
            sh = Image.new("L", lay.size, 0)
            ImageDraw.Draw(sh).ellipse((pad, pad + 20, pad + dw, pad + dh + 20), fill=255)
            sh = sh.filter(ImageFilter.GaussianBlur(30))
        black = Image.new("RGBA", lay.size, (0, 0, 0, 0))
        black.putalpha(sh.point(lambda v: int(v * shadow)))
        lay.alpha_composite(black, (0, 18 if not circle else 0))
    lay.alpha_composite(img, (pad, pad))
    if abs(rot) > 0.01:
        lay = lay.rotate(rot, Image.BICUBIC, expand=True)
    lay = mul_alpha(lay, alpha)
    comp(cv, lay, cx - lay.width / 2, cy - lay.height / 2)


def drop_in(t, t0, dur=0.5):
    """print landing: (scale, alpha, dy, drot)"""
    p = ease_out(lin(t, t0, t0 + dur))
    return 1.07 - 0.07 * p, lin(t, t0, t0 + 0.16), -46 * (1 - p), 3.0 * (1 - p)


# ---------------------------------------------------------------------------
# typography
# ---------------------------------------------------------------------------
@lru_cache(None)
def text_img(text, fname, size, fill=IVORY, shadow=0.55, pad=40):
    f = font(fname, size)
    d = ImageDraw.Draw(Image.new("RGBA", (8, 8)))
    l, t, r, b = d.textbbox((0, 0), text, font=f, direction="rtl", language="ar")
    w, h = r - l + 2 * pad, b - t + 2 * pad
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if shadow:
        sh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(sh).text((pad - l, pad - t + 3), text, font=f, fill=(0, 0, 0, int(255 * shadow)),
                                direction="rtl", language="ar")
        img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(max(3, size // 14))))
    d.text((pad - l, pad - t), text, font=f, fill=fill + (255,), direction="rtl", language="ar")
    return img


@lru_cache(4096)
def blurred(key, r):
    img = text_img(*key)
    return img.filter(ImageFilter.GaussianBlur(r)) if r > 0.2 else img


def put_text(cv, key, cx, cy, p, out=1.0, rise=22, maxblur=9.0):
    """reveal p 0→1 (blur-in + rise), out 1→0 fade."""
    a = clamp(p) * clamp(out)
    if a <= 0.003:
        return
    r = round((1 - ease_out(clamp(p))) * maxblur * 2) / 2
    img = blurred(key, r)
    img = mul_alpha(img, a)
    dy = (1 - ease_out(clamp(p))) * rise
    comp(cv, img, cx - img.width / 2, cy - img.height / 2 + dy)


def K(text, fname="Amiri-Bold.ttf", size=110, fill=IVORY, shadow=0.55):
    return (text, fname, size, fill, shadow)


# ---------------------------------------------------------------------------
# captions: RTL, highlighted as spoken
# ---------------------------------------------------------------------------
CAP_FONT = ("Tajawal-Bold.ttf", 66)
CAP_MAXW = 1060
CAP_CY = 1885


def wrap(text):
    f = font(*CAP_FONT)
    words = text.split()
    full = f.getlength(text, direction="rtl", language="ar")
    if full <= CAP_MAXW or len(words) < 3:
        return [text]
    best, bi = 1e9, 1
    for i in range(1, len(words)):
        a = f.getlength(" ".join(words[:i]), direction="rtl", language="ar")
        b = f.getlength(" ".join(words[i:]), direction="rtl", language="ar")
        if max(a, b) < best:
            best, bi = max(a, b), i
    return [" ".join(words[:bi]), " ".join(words[bi:])]


@lru_cache(None)
def cap_lines(text):
    out = []
    for line in wrap(text):
        on = text_img(line, CAP_FONT[0], CAP_FONT[1], WHITE, 0.75, 30)
        off = text_img(line, CAP_FONT[0], CAP_FONT[1], (205, 205, 205), 0.6, 30)
        off = mul_alpha(off, 0.42)
        out.append((line, on, off))
    return out


def draw_caption(cv, cap, t):
    a = win(t, cap["start"] - 0.06, cap["end"] + 0.25, 0.12, 0.18)
    if a <= 0:
        return
    lines = cap_lines(cap["text"])
    lens = [len(l[0]) for l in lines]
    tot = sum(lens)
    prog = lin(t, cap["start"], cap["end"] - 0.1) * tot
    lh = 96
    y0 = CAP_CY - (len(lines) - 1) * lh / 2
    for i, (line, on, off) in enumerate(lines):
        done = sum(lens[:i])
        r = clamp((prog - done) / lens[i])
        w, h = on.size
        img = off.copy()
        if r > 0:
            xs = np.arange(w, dtype=np.float32)
            edge = (1 - r) * w            # RTL: reveal from right to left
            m = np.clip((xs - edge) / 70 + 0.5, 0, 1) if r < 1 else np.ones(w, np.float32)
            mask = Image.fromarray((np.tile(m, (h, 1)) * 255).astype(np.uint8))
            onm = on.copy()
            onm.putalpha(Image.fromarray((np.asarray(on.getchannel("A")).astype(np.float32) * (np.tile(m, (h, 1)))).astype(np.uint8)))
            img.alpha_composite(onm)
        img = mul_alpha(img, a)
        comp(cv, img, W / 2 - w / 2, y0 + i * lh - h / 2)


# ---------------------------------------------------------------------------
# small graphic devices
# ---------------------------------------------------------------------------
def viewfinder(cv, cx, cy, size, a, arm=70, wdt=4):
    if a <= 0:
        return
    lay = Image.new("RGBA", (size + 20, size + 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    c = IVORY + (int(210 * a),)
    s = size
    o = 10
    for (x, y, dx, dy) in ((o, o, 1, 1), (o + s, o, -1, 1), (o, o + s, 1, -1), (o + s, o + s, -1, -1)):
        d.line((x, y, x + dx * arm, y), fill=c, width=wdt)
        d.line((x, y, x, y + dy * arm), fill=c, width=wdt)
    # tiny focus point
    d.ellipse((o + s / 2 - 5, o + s / 2 - 5, o + s / 2 + 5, o + s / 2 + 5), outline=c, width=2)
    comp(cv, lay, cx - lay.width / 2, cy - lay.height / 2)


def hairline(cv, cx, cy, w, a):
    if a <= 0:
        return
    lay = Image.new("RGBA", (int(w), 3), IVORY + (int(120 * a),))
    comp(cv, lay, cx - w / 2, cy)


def link_pill(cv, cx, cy, a, glow=0.0):
    if a <= 0:
        return
    t = text_img("roqayya.pages.dev", "Tajawal-Bold.ttf", 62, IVORY, 0.0, 10)
    pw, ph = t.width + 120, 128
    lay = Image.new("RGBA", (pw + 40, ph + 40), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    d.rounded_rectangle((20, 20, 20 + pw, 20 + ph), radius=ph // 2,
                        fill=(239, 230, 214, int(26 + 30 * glow)), outline=IVORY + (int(170 + 60 * glow),), width=3)
    lay.alpha_composite(t, (20 + (pw - t.width) // 2, 20 + (ph - t.height) // 2 + 4))
    lay = mul_alpha(lay, a)
    comp(cv, lay, cx - lay.width / 2, cy - lay.height / 2)


def bg_layer(cv, name, bright, a=1.0):
    if a <= 0:
        return
    bg = BGS[name]
    img = Image.blend(Image.new("RGB", (W, H), (0, 0, 0)), bg, clamp(bright)).convert("RGBA")
    if a < 0.999:
        img = mul_alpha(img, a)
    cv.alpha_composite(img)


# ---------------------------------------------------------------------------
# the film
# ---------------------------------------------------------------------------
A, B, C, D = SEG["A"], SEG["B"], SEG["C"], SEG["D"]
E, F, G, Hs, I, J, Ks, L = (SEG[k] for k in "EFGHIJKL")
SHUT = AN["shutter"]
T_DRAIN = AN["word_thumma"]
T_COR, T_IV = AN["word_mamarrat"] - 0.12, AN["word_akyas"] - 0.12
T_CARD = E["start"] - 0.15
T_ARM = F["start"] - 0.1
T_11 = AN["word_11"] - 0.05
T_REP = G["start"] - 0.3
T_YAT = AN["word_yataqaddam"] - 0.05
T_CUT = G["end"] + 0.15
T_H = Hs["start"] - 0.05
T_I = I["start"] - 0.05
T_J = J["start"] - 0.35
T_AYDI = AN["word_aydi"] - 0.3
T_K = Ks["start"] - 0.25
T_FALA = AN["word_fala"] - 0.05
T_END = L["end"] + 0.15
PULSES = list(np.arange(G["start"] + 0.1, T_CUT - 0.2, 0.86))


def pulse_bump(t):
    v = 0.0
    for p in PULSES:
        if p <= t < p + 0.5:
            v = max(v, math.exp(-(t - p) / 0.12))
    return v


def render(t, captions=True):
    cv = Image.new("RGBA", (W, H), (9, 8, 8, 255))
    leak = 0.0

    # ---- 0. the verse ---------------------------------------------------
    if t < A["start"] + 0.2:
        out = 1 - lin(t, 3.25, 3.85)
        bg_layer(cv, "arm", 0.13 * (0.6 + 0.4 * lin(t, 0, 2.5)), out)
        l1 = K("﴿ وَمَنْ أَحْيَاهَا", "Amiri-Regular.ttf", 112)
        l2 = K("فَكَأَنَّمَا أَحْيَا النَّاسَ جَمِيعًا ﴾", "Amiri-Regular.ttf", 112)
        put_text(cv, l1, W / 2, 1000, lin(t, 0.35, 1.35), out, rise=16, maxblur=12)
        put_text(cv, l2, W / 2, 1185, lin(t, 0.95, 1.95), out, rise=16, maxblur=12)
        hairline(cv, W / 2, 1338, 150 * ease_out(lin(t, 1.6, 2.3)), lin(t, 1.6, 2.1) * out)
        put_text(cv, K("سورة المائدة — الآية ٣٢", "Tajawal-Light.ttf", 42, (200, 192, 178), 0.3), W / 2, 1410,
                 lin(t, 1.8, 2.5), out, rise=8, maxblur=4)

    # ---- 1. Ruqayya (portrait, name) ------------------------------------
    if A["start"] - 0.45 <= t < SHUT:
        a = lin(t, A["start"] - 0.45, A["start"] + 0.35)
        bg_layer(cv, "portrait", 0.30, a)
        z = 1 + 0.05 * lin(t, A["start"], SHUT)
        draw_print(cv, "portrait", W / 2, 900, 640, 640, scale=0.97 + 0.03 * ease_out(a) + 0.02 * lin(t, A["start"], SHUT),
                   alpha=a, zoom=1.3 * z, fy=0.6, circle=True, shadow=0.7)
        name_t = A["start"] + (1.0 - 0.62) / TEMPO
        put_text(cv, K("رقية", "ArefRuqaa-Regular.ttf", 230), W / 2, 1405, lin(t, name_t - 0.1, name_t + 0.7), 1, 26, 14)
        put_text(cv, K("٢١ عاماً — كربلاء", "Tajawal-Light.ttf", 54, (214, 205, 190), 0.4), W / 2, 1585,
                 lin(t, A["start"] + 1.25, A["start"] + 1.95), 1, 12, 5)
        # «أحبّ التصوير»: the viewfinder finds her
        vf = lin(t, B["start"] - 0.05, B["start"] + 0.25)
        viewfinder(cv, W / 2, 900, int(740 + 90 * (1 - ease_out(lin(t, B["start"], B["start"] + 0.6)))), vf)

    # ---- 2. her photographs ---------------------------------------------
    if SHUT <= t < T_COR + 0.45:
        drain = ease_io(lin(t, T_DRAIN, T_COR))
        out = 1 - lin(t, T_COR, T_COR + 0.45)
        sat = 1 - drain
        stack = [("tree", SHUT, -2.2, 0.5, 0.35),
                 ("lamp", AN["word_daw"] - 0.12, 1.4, 0.5, 0.55)]
        top = [s for s in stack if t >= s[1]][-1][0]
        bg_layer(cv, top, 0.30 * (1 - 0.5 * drain), out)
        for i, (nm, t0, rot, fx, fy) in enumerate(stack):
            if t < t0:
                continue
            sc, al, dy, dr = drop_in(t, t0)
            under = sum(1 for s in stack if s[1] <= t and s[1] > t0)
            draw_print(cv, nm, W / 2 + (i - 0.5) * 22, 1060 + dy + under * 6, 1000, 1180,
                       scale=sc * (1 - 0.025 * under), rot=rot + dr, alpha=al * out,
                       zoom=1 + 0.06 * lin(t, t0, T_COR), fx=fx, fy=fy, sat=sat,
                       bright=(1 - 0.22 * under) * (1 - 0.25 * drain))
        if t < SHUT + 0.16:         # shutter flash
            fl = 1 - lin(t, SHUT, SHUT + 0.16)
            cv.alpha_composite(Image.new("RGBA", (W, H), (255, 250, 240, int(80 * fl * fl))))

    # ---- 3. the hospital ------------------------------------------------
    if T_COR <= t < T_REP + 0.4:
        out = 1 - lin(t, T_REP, T_REP + 0.4)
        a = lin(t, T_COR, T_COR + 0.35)
        cur = "corridor" if t < T_IV else "iv" if t < T_CARD else "card" if t < T_ARM else "arm"
        bg_layer(cv, cur, 0.22, a * out)
        stack = [("corridor", T_COR, 1.2, 0.5, 0.5, 1.12), ("iv", T_IV, -2.0, 0.45, 0.3, 1.06),
                 ("card", T_CARD, 1.6, 0.5, 0.55, 1.05), ("arm", T_ARM, -0.8, 0.42, 0.48, 1.22)]
        for i, (nm, t0, rot, fx, fy, zmax) in enumerate(stack):
            if t < t0:
                continue
            nxt = [s[1] for s in stack if s[1] > t0]
            # older prints sink away; keep only the last two visible
            newer = sum(1 for s in stack if t0 < s[1] <= t)
            if newer >= 2:
                continue
            sc, al, dy, dr = drop_in(t, t0, 0.55)
            fade_under = 1 - 0.3 * newer
            zend = T_REP if nm == "arm" else (nxt[0] + 0.6 if nxt else T_REP)
            draw_print(cv, nm, W / 2 + (-14 if i % 2 else 14), 1130 + dy + newer * 10, 1040, 1080,
                       scale=sc * (1 - 0.03 * newer), rot=rot + dr, alpha=al * out * (1 if newer == 0 else 0.9),
                       zoom=1 + (zmax - 1) * ease_io(lin(t, t0, zend)), fx=fx, fy=fy,
                       bright=fade_under)
        # «سرطان العظام»
        ksz = E["start"] + 0.35
        put_text(cv, K("سرطان العظام", "Amiri-Bold.ttf", 120), W / 2, 330,
                 lin(t, ksz, ksz + 0.6), 1 - lin(t, T_ARM + 0.3, T_ARM + 0.8), 18, 10)
        # «١١»
        put_text(cv, K("١١", "Amiri-Bold.ttf", 250), W / 2, 285, lin(t, T_11, T_11 + 0.55), out, 30, 14)
        put_text(cv, K("جرعة علاج كيميائي", "Tajawal-Medium.ttf", 50, (214, 205, 190), 0.4), W / 2, 470,
                 lin(t, T_11 + 0.3, T_11 + 0.9), out, 10, 5)

    # ---- 4. the scan ----------------------------------------------------
    if T_REP <= t < T_CUT:
        a = lin(t, T_REP, T_REP + 0.4)
        bg_layer(cv, "report", 0.16, a)
        sc, al, dy, dr = drop_in(t, T_REP, 0.6)
        bump = 1 + 0.006 * pulse_bump(t)
        z = 1 + 0.42 * ease_io(lin(t, T_REP + 0.3, T_CUT))
        draw_print(cv, "report", W / 2, 1120 + dy, 1260, 1000, scale=sc * bump, rot=0.9 + dr, alpha=al,
                   zoom=z, fx=0.5, fy=0.5 + 0.42 * ease_io(lin(t, T_REP + 0.3, T_CUT)), shadow=0.7)
        put_text(cv, K("ما زال يتقدّم", "Amiri-Bold.ttf", 124), W / 2, 380, lin(t, T_YAT, T_YAT + 0.5), 1, 20, 10)
        put_text(cv, K("الفحص الأخير — آب ٢٠٢٦", "Tajawal-Light.ttf", 42, (200, 192, 178), 0.3), W / 2, 520,
                 lin(t, G["start"] + 0.3, G["start"] + 0.9), 1, 8, 4)

    # ---- 5. silence, then the plea --------------------------------------
    if T_H <= t < T_J + 0.5:
        out = 1 - lin(t, T_J, T_J + 0.5)
        a = lin(t, T_H, T_H + 0.5)
        bg_layer(cv, "door", 0.18, a * out)
        z = 1.05 + 0.4 * ease_io(lin(t, T_H, T_J + 0.5))
        draw_print(cv, "door", W / 2, 800, 780, 860, scale=0.98 + 0.02 * a, alpha=a * out, zoom=z, fx=0.48, fy=0.53,
                   bright=0.85 + 0.15 * lin(t, T_I, T_I + 1.0), shadow=0.7)
        line1_y = 1450 - 40 * ease_io(lin(t, T_I, T_I + 0.5))
        dim = 1 - 0.4 * lin(t, T_I, T_I + 0.5)
        put_text(cv, K("لا أطلبُ المستحيل…", "Amiri-Bold.ttf", 108), W / 2, line1_y,
                 lin(t, T_H + 0.05, T_H + 0.7), out * dim, 20, 12)
        put_text(cv, K("أطلبُ فرصةً أخرى للحياة", "Amiri-Bold.ttf", 120, (247, 236, 214)), W / 2, 1620,
                 lin(t, T_I, T_I + 0.7), out, 24, 14)
        leak = max(leak, 0.10 * lin(t, T_I, T_I + 1.2) * out)

    # ---- 6. hope --------------------------------------------------------
    if T_J <= t < T_K + 0.5:
        out = 1 - lin(t, T_K, T_K + 0.5)
        a = lin(t, T_J, T_J + 0.6)
        cur = "exterior" if t < T_AYDI else "tree"
        bg_layer(cv, cur, 0.26, a * out)
        p = lin(t, T_J, T_AYDI + 0.6)
        ea = 1 - lin(t, T_AYDI, T_AYDI + 0.6)
        draw_print(cv, "exterior", W / 2, 1060 - 20 * p, 1080, 1180, scale=1.0, rot=-0.6,
                   alpha=a * out * (0.35 + 0.65 * ea), zoom=1.12 - 0.08 * p, fx=0.5, fy=0.62 - 0.3 * ease_io(p))
        if t >= T_AYDI:
            sc, al, dy, dr = drop_in(t, T_AYDI, 0.6)
            draw_print(cv, "tree", W / 2 + 10, 1080 + dy, 1000, 1180, scale=sc, rot=1.6 + dr, alpha=al * out,
                       zoom=1.18 - 0.12 * lin(t, T_AYDI, T_K + 0.5), fx=0.5, fy=0.3)
        leak = max(leak, (0.16 + 0.06 * math.sin(t * 1.3)) * a * out)

    # ---- 7. her link, the ask, the end card -----------------------------
    if t >= T_K:
        a = lin(t, T_K, T_K + 0.6)
        bg_layer(cv, "portrait", 0.20, a)
        draw_print(cv, "portrait", W / 2, 470, 320, 320, scale=0.96 + 0.04 * ease_out(a), alpha=a,
                   zoom=1.34, fy=0.6, circle=True, shadow=0.7)
        put_text(cv, K("رقية", "ArefRuqaa-Regular.ttf", 170), W / 2, 790, lin(t, T_K + 0.15, T_K + 0.8), 1, 18, 10)
        put_text(cv, K("قصّتي كاملة في الرابط الموجود في صفحتي", "Tajawal-Medium.ttf", 46, (214, 205, 190), 0.35),
                 W / 2, 975, lin(t, T_K + 0.4, T_K + 1.0), 1, 10, 5)
        glow = 0.5 + 0.5 * math.sin((t - T_K) * 3.2) if t > T_K + 1.5 else 0.0
        link_pill(cv, W / 2, 1110, ease_out(lin(t, T_K + 0.6, T_K + 1.2)), glow * 0.6)
        put_text(cv, K("لا تجعلْ قصّتي", "Amiri-Bold.ttf", 116), W / 2, 1400, lin(t, T_FALA, T_FALA + 0.6), 1, 22, 12)
        put_text(cv, K("تتوقّفُ عندك", "Amiri-Bold.ttf", 116, (247, 236, 214)), W / 2, 1560,
                 lin(t, T_FALA + 0.45, T_FALA + 1.05), 1, 22, 12)
        put_text(cv, K("شارِك القصّة", "Tajawal-Bold.ttf", 64, WHITE, 0.4), W / 2, 1790,
                 lin(t, T_END, T_END + 0.5), 1, 12, 6)
        put_text(cv, K("مشاركتُك قد توصلها إلى من يستطيع أن يساعد", "Tajawal-Light.ttf", 44, (214, 205, 190), 0.35),
                 W / 2, 1880, lin(t, T_END + 0.25, T_END + 0.8), 1, 10, 5)
        put_text(cv, K("الصوت مُولَّد رقمياً لرواية قصّة رقيّة", "Tajawal-Light.ttf", 30, (170, 164, 152), 0.0),
                 W / 2, 1975, lin(t, T_END + 0.5, T_END + 1.0), 1, 0, 2)

    # ---- captions -------------------------------------------------------
    if captions:
        for cap in TL["captions"]:
            if not cap["hidden"] and cap["start"] - 0.1 <= t <= cap["end"] + 0.3:
                draw_caption(cv, cap, t)

    # ---- finishing: light, vignette, grain, black dips ------------------
    arr = np.asarray(cv.convert("RGB")).astype(np.float32)
    if leak > 0:
        arr = 255 - (255 - arr) * (1 - LEAK / 255 * leak)        # screen
    arr *= VIG
    arr += GRAIN[int(t * FPS) % len(GRAIN)]
    black = 0.0
    if T_CUT <= t < T_H:                                      # the silent beat
        black = 1.0 - 0.0
    black = max(black, 1 - lin(t, 0, 0.35), lin(t, TOTAL - 0.6, TOTAL - 0.05))
    if black > 0:
        arr = arr * (1 - black) + 9 * black + GRAIN[int(t * FPS) % len(GRAIN)] * black * 0.6
    return np.clip(arr, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------------------
_CAPS = True


def _init(caps):
    global _CAPS
    _CAPS = caps
    load_assets()
    load_fx()


def _frame(i):
    return render(i / FPS, _CAPS).tobytes()


def encode(path, caps=True, workers=4):
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
           "-r", str(FPS), "-i", "-", "-i", str(AUD / "final_mix.wav"),
           "-map", "0:v", "-map", "1:a",
           "-c:v", "libx264", "-preset", "slow", "-crf", "15", "-tune", "film",
           "-pix_fmt", "yuv420p", "-profile:v", "high", "-level", "5.1",
           "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
           "-x264-params", "aq-mode=3:keyint=60",
           "-c:a", "aac", "-b:a", "320k", "-ar", "48000",
           "-t", f"{NFRAMES / FPS:.3f}", "-movflags", "+faststart", str(path)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    with Pool(workers, _init, (caps,)) as pool:
        for k, buf in enumerate(pool.imap(_frame, range(NFRAMES), chunksize=3)):
            p.stdin.write(buf)
            if k % 150 == 0:
                print(f"  frame {k}/{NFRAMES}", flush=True)
    p.stdin.close()
    p.wait()
    print("->", path)


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--stills" in args:
        load_assets()
        load_fx()
        ts = [float(x) for x in args[args.index("--stills") + 1].split(",")]
        d = OUT / "stills"
        d.mkdir(exist_ok=True)
        for tt in ts:
            Image.fromarray(render(tt)).save(d / f"t{tt:05.2f}.png")
        print("stills ->", d)
    else:
        encode(OUT / "Ruqayya_2K_1440x2560.mp4", True)
        if "--clean" in args:
            encode(OUT / "Ruqayya_2K_1440x2560_no-captions.mp4", False)
