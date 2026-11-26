"""Sound for «فرصة أخرى للحياة».

1. Cuts the voice take into sentences, tightens the pauses and the pace.
2. Lays the sentences on the film's timeline and writes timeline.json
   (sentence + caption + word-anchor times the picture is cut to).
3. Composes the score (felt piano, string ensemble, soft pulse) in code.
4. Builds the sound design (room tone, shutter, paper, reverse swell...).
5. Ducks the score under the voice and masters to -14 LUFS / -1 dBTP.

Everything is generated here: no third-party music or sound library is used,
so the film carries no copyright claims.
"""
import json
import subprocess
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.io import wavfile

ROOT = Path(__file__).resolve().parent.parent
AUD = ROOT / "assets" / "audio"
TMP = AUD / "work"
TMP.mkdir(parents=True, exist_ok=True)
SR = 48000
TEMPO = 1.18          # voice pace (1.0 = as generated; the take was too slow)
VO_START = 3.90       # the verse owns the first four seconds
END_HOLD = 3.00       # end card after the last word
rng = np.random.default_rng(7)

# ---------------------------------------------------------------------------
# 1. Voice: sentence segments of the take (seconds in the raw take)
# ---------------------------------------------------------------------------
# (id, src_start, src_end, gap_after_in_output)
SEGMENTS = [
    ("A", 0.62, 4.95, 0.30),   # اسمي رقية… عمري واحد وعشرون عاماً، من كربلاء
    ("B", 5.30, 6.45, 0.40),   # أحب التصوير.            (shutter in the gap)
    ("C", 7.02, 10.30, 0.36),  # كنت أصور الورد، والشجر، وضوء المساء
    ("D", 10.78, 14.95, 0.50),  # ثم صارت صوري… ممرات المستشفى، وأكياس العلاج
    ("E", 15.70, 17.45, 0.20),  # أصبت بسرطان العظام
    ("F", 17.72, 23.90, 0.50),  # استأصلوا… إحدى عشرة جرعة من العلاج الكيميائي
    ("G", 24.50, 28.80, 1.05),  # لكن الفحص الأخير… ما زال يتقدم   (silence)
    ("H", 29.67, 31.62, 0.12),  # أنا لا أطلب المستحيل…
    ("I", 31.76, 34.08, 0.38),  # أطلب فرصة أخرى للحياة
    ("J", 34.12, 39.42, 0.50),  # ما زلت متمسكة بالأمل… على أيدي الناس
    ("K", 40.50, 43.98, 0.22),  # قصتي كاملة في الرابط الموجود في صفحتي
    ("L", 44.12, 49.25, 0.00),  # وإن لم تستطع أن تساعدني… فلا تجعل قصتي تتوقف عندك
]

# Captions: (src_start, src_end, text, hidden?) — times in the raw take,
# taken from a word-level transcription of it. Hidden captions are the lines
# the film sets as large typography instead.
CAPTIONS = [
    (0.62, 1.70, "اسمي رقيّة…", False),
    (1.85, 4.95, "عمري واحدٌ وعشرون عاماً، من كربلاء.", False),
    (5.30, 6.45, "أحبُّ التصوير.", False),
    (7.02, 10.30, "كنتُ أصوّر الورد، والشجر، وضوء المساء.", False),
    (10.78, 12.05, "ثمّ صارت صوري…", False),
    (12.05, 14.95, "ممرّات المستشفى، وأكياس العلاج.", False),
    (15.70, 17.45, "أُصبتُ بسرطان العظام.", False),
    (17.72, 20.08, "استأصلوا جزءاً من عظام حوضي،", False),
    (20.10, 23.90, "وتحمّلتُ إحدى عشرة جرعةً من العلاج الكيميائي.", False),
    (24.50, 26.10, "لكنّ الفحص الأخير…", False),
    (26.20, 28.80, "أظهرَ أنّ المرض ما زال يتقدّم.", False),
    (29.67, 31.62, "أنا لا أطلبُ المستحيل…", True),
    (31.76, 34.08, "أطلبُ فرصةً أخرى للحياة.", True),
    (34.12, 36.45, "ما زلتُ متمسّكةً بالأمل…", False),
    (36.45, 39.42, "وأؤمن أنّ الفرج قد يأتي على أيدي الناس.", False),
    (40.50, 43.98, "قصّتي كاملة في الرابط الموجود في صفحتي.", False),
    (44.12, 46.55, "وإن لم تستطع أن تساعدني…", False),
    (46.60, 49.25, "فلا تجعل قصّتي تتوقّف عندك.", True),
]

# Word anchors the picture cuts on (raw-take seconds, from the transcription)
ANCHORS = {
    "word_ward": 7.80,       # الورد
    "word_shajar": 8.40,     # والشجر
    "word_daw": 9.30,        # وضوء المساء
    "word_thumma": 10.90,    # ثم
    "word_mamarrat": 12.10,  # ممرات
    "word_akyas": 13.70,     # وأكياس
    "word_11": 20.90,        # إحدى عشرة
    "word_lakin": 24.60,     # لكن
    "word_yataqaddam": 28.00,  # يتقدم
    "word_faraj": 37.20,     # الفرج
    "word_aydi": 38.60,      # أيدي الناس
    "word_fala": 46.70,      # فلا تجعل
}


def ff(*args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


def read(path):
    sr, x = wavfile.read(path)
    x = x.astype(np.float32)
    if x.dtype != np.float32 or np.abs(x).max() > 2:
        x /= 32768.0
    if x.ndim == 1:
        x = np.stack([x, x], 1)
    assert sr == SR, sr
    return x


raw = AUD / "vo_mayssa_raw.wav"
timeline = {"tempo": TEMPO, "segments": [], "captions": [], "anchors": {}}
t = VO_START
seg_out = {}
vo_parts = []
for sid, a, b, gap in SEGMENTS:
    out = TMP / f"vo_{sid}.wav"
    ff("-i", str(raw), "-af",
       f"atrim=start={a}:end={b},asetpts=PTS-STARTPTS,"
       "highpass=f=75,lowpass=f=11000,"
       "equalizer=f=220:t=q:w=1:g=1.5,equalizer=f=3200:t=q:w=1.2:g=2,"
       "acompressor=threshold=-22dB:ratio=2.5:attack=8:release=120:makeup=2,"
       f"rubberband=tempo={TEMPO}:pitchq=quality:formant=preserved:transients=smooth,"
       "aresample=48000,afade=t=in:d=0.012,areverse,afade=t=in:d=0.04,areverse",
       "-ac", "2", str(out))
    x = read(out)
    dur = len(x) / SR
    seg_out[sid] = (t, a)
    timeline["segments"].append({"id": sid, "start": round(t, 3), "end": round(t + dur, 3)})
    vo_parts.append((t, x))
    t += dur + gap

VO_END = t
TOTAL = VO_END + END_HOLD
timeline["vo_start"] = VO_START
timeline["vo_end"] = round(VO_END, 3)
timeline["total"] = round(TOTAL, 3)


def src2out(s):
    """Map a raw-take time to film time."""
    best = None
    for tol in (0.0, 0.05):
        for sid, a, b, _ in SEGMENTS:
            if a - tol <= s <= b + tol:
                best = sid
                break
        if best:
            break
    assert best, s
    o, a = seg_out[best]
    return o + (s - a) / TEMPO


for a, b, text, hidden in CAPTIONS:
    timeline["captions"].append({"start": round(src2out(a), 3),
                                 "end": round(src2out(b), 3),
                                 "text": text, "hidden": hidden})
for k, s in ANCHORS.items():
    timeline["anchors"][k] = round(src2out(s), 3)
S = {d["id"]: d for d in timeline["segments"]}
(AUD / "timeline.json").write_text(json.dumps(timeline, ensure_ascii=False, indent=1))
print(f"voice {VO_START:.2f}–{VO_END:.2f}s, film {TOTAL:.2f}s")

N = int(TOTAL * SR) + SR
vo = np.zeros((N, 2), np.float32)
for st, x in vo_parts:
    i = int(st * SR)
    vo[i:i + len(x)] += x

# ---------------------------------------------------------------------------
# 2. Instruments
# ---------------------------------------------------------------------------
def mtof(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def lp(x, fc, order=2):
    sos = signal.butter(order, fc, "low", fs=SR, output="sos")
    return signal.sosfilt(sos, x, axis=0)


def hp(x, fc, order=2):
    sos = signal.butter(order, fc, "high", fs=SR, output="sos")
    return signal.sosfilt(sos, x, axis=0)


def bp(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, hi], "band", fs=SR, output="sos")
    return signal.sosfilt(sos, x, axis=0)


def pan(x, p):
    """p in [-1, 1] (equal power)."""
    a = (p + 1) * np.pi / 4
    return np.stack([x * np.cos(a), x * np.sin(a)], 1)


def felt_piano(m, vel=0.5, dur=3.0, tail=4.0):
    """Soft 'felt' piano note: inharmonic partials, per-partial decay,
    muted hammer, damper release."""
    f0 = mtof(m)
    L = int((dur + tail) * SR)
    tt = np.arange(L) / SR
    B = 0.00035
    y = np.zeros(L)
    base_t60 = np.interp(m, [36, 60, 84], [9.0, 5.5, 2.2])
    for n in range(1, 11):
        fn = n * f0 * np.sqrt(1 + B * n * n)
        if fn > 7000:
            break
        amp = (1 / n ** 1.45) * (0.55 + 0.45 * vel) ** (n * 0.35)
        t60 = base_t60 / (1 + 0.55 * (n - 1))
        env = np.exp(-6.9 * tt / t60)
        y += amp * env * np.sin(2 * np.pi * fn * tt + rng.uniform(0, 6.28))
    att = np.minimum(1, tt / 0.007)
    damp = np.where(tt < dur, 1.0, np.exp(-(tt - dur) / 0.35))
    y *= att * damp
    hammer = lp(rng.standard_normal(int(0.03 * SR)) * np.exp(-np.arange(int(0.03 * SR)) / (0.004 * SR)), 1800)
    y[:len(hammer)] += 0.03 * hammer
    y = lp(y, 2600 + 1800 * vel)
    return (vel * 0.32 * y).astype(np.float32)


def strings(m, dur, vel=0.5, attack=1.8, release=2.2, bright=1800):
    """String ensemble: detuned band-limited saws, slow bow, delayed vibrato."""
    L = int((dur + release) * SR)
    tt = np.arange(L) / SR
    out = np.zeros((L, 2))
    nv = 6
    for v in range(nv):
        det = (v - (nv - 1) / 2) * 3.2  # cents
        vib = 1 + (2 ** (5.5 / 1200) - 1) * np.sin(2 * np.pi * (4.8 + 0.3 * v) * tt + v) * np.minimum(1, tt / 1.5)
        f = mtof(m) * 2 ** (det / 1200) * vib
        ph = np.cumsum(f) / SR + rng.uniform()
        saw = 2 * (ph % 1.0) - 1
        out += pan(saw, -0.7 + 1.4 * v / (nv - 1))
    out = lp(out, bright, 4)
    out = hp(out, 90)
    env = np.minimum(1, tt / attack) ** 1.6
    env *= np.where(tt < dur, 1.0, np.exp(-(tt - dur) / (release / 3)))
    return (vel * 0.05 * out * env[:, None]).astype(np.float32)


def pulse(vel=0.5):
    L = int(0.9 * SR)
    tt = np.arange(L) / SR
    f = 46 + 30 * np.exp(-tt / 0.03)
    y = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.22)
    y += 0.15 * lp(rng.standard_normal(L) * np.exp(-tt / 0.008), 900)
    return (vel * 0.5 * y).astype(np.float32)


def add(buf, x, t0, gain=1.0, p=0.0):
    i = int(t0 * SR)
    if i >= len(buf):
        return
    if x.ndim == 1:
        x = pan(x, p)
    n = min(len(x), len(buf) - i)
    buf[i:i + n] += gain * x[:n]


def make_ir(seconds=3.4, pre=0.025, damp=5500):
    L = int(seconds * SR)
    tt = np.arange(L) / SR
    ir = rng.standard_normal((L, 2)) * np.exp(-6.9 * tt / seconds)[:, None]
    ir = lp(ir, damp)
    ir[:int(pre * SR)] = 0
    ir /= np.sqrt((ir ** 2).sum(0))
    return ir.astype(np.float32)


IR = make_ir()


def reverb(x, wet=0.35):
    w = np.stack([signal.fftconvolve(x[:, c], IR[:, c])[:len(x)] for c in (0, 1)], 1)
    return (1 - wet) * x + wet * w * 1.2


# ---------------------------------------------------------------------------
# 3. Score — D minor, opening to F major for the hope section
# ---------------------------------------------------------------------------
piano = np.zeros((N, 2), np.float32)
strs = np.zeros((N, 2), np.float32)
low = np.zeros((N, 2), np.float32)

CH = {  # (bass, [upper arpeggio])
    "Dm9": (38, [57, 62, 64, 65, 69]),
    "Bbmaj7": (34, [53, 57, 62, 65, 69]),
    "F": (41, [57, 60, 65, 69, 72]),
    "C/E": (40, [55, 60, 64, 67, 72]),
    "Gm": (43, [55, 58, 62, 67, 70]),
    "A": (45, [57, 61, 64, 69, 73]),
    "Fadd9": (41, [60, 65, 67, 69, 72]),
}


def arp(chord, t0, length, vel=0.42, step=0.46, pattern=(0, 2, 4, 3), bass=True, start_note=0):
    b, up = CH[chord]
    if bass:
        add(piano, felt_piano(b, vel * 0.95, length + 0.6), t0, 1.0, -0.25)
    k = start_note
    tt = t0 + 0.02
    while tt < t0 + length - 0.15:
        n = up[pattern[k % len(pattern)]]
        add(piano, felt_piano(n, vel * (0.85 + 0.15 * rng.random()), step * 2.2), tt, 1.0, 0.25 * np.sin(k))
        k += 1
        tt += step * (1 + 0.04 * rng.standard_normal())


def pad(chord, t0, length, vel=0.5, octave=0, attack=1.8, bright=1700):
    b, up = CH[chord]
    for n in (b + 12, up[0] + octave, up[2] + octave, up[3] + octave):
        add(strs, strings(n, length, vel, attack=attack, bright=bright), t0)


A, B, C, D = S["A"], S["B"], S["C"], S["D"]
E, F, G, H, I, J, K, L_ = S["E"], S["F"], S["G"], S["H"], S["I"], S["J"], S["K"], S["L"]
an = timeline["anchors"]

# Verse (0–4.1): a held low D and two distant notes. Stillness.
add(strs, strings(26 + 12, 4.6, 0.35, attack=2.5, release=2.0, bright=700), 0.2)
add(strs, strings(45, 4.4, 0.22, attack=3.0, release=2.0, bright=900), 0.6)
add(piano, felt_piano(74, 0.30, 2.5, 5), 0.9, 1.8, 0.3)
add(piano, felt_piano(69, 0.26, 2.5, 5), 2.45, 1.8, -0.2)

# Ruqayya, her photographs (A–C): gentle arpeggio Dm9 | Bbmaj7 | F | C/E
t0 = A["start"]
seq = ["Dm9", "Bbmaj7", "F", "C/E"]
span = (D["start"] - t0) / 4
for i, ch in enumerate(seq):
    arp(ch, t0 + i * span, span, vel=0.40 + 0.03 * i)

# Hospital (D–F): colder, sparser — upper notes only, low strings enter
t0 = D["start"]
span = (G["start"] - t0) / 4
for i, ch in enumerate(["Dm9", "Bbmaj7", "Gm", "Dm9"]):
    arp(ch, t0 + i * span, span, vel=0.33, step=0.62, pattern=(4, 2, 3, 1), bass=(i % 2 == 0))
pad("Dm9", E["start"] - 0.4, G["start"] - E["start"] + 0.4, 0.40, attack=2.5, bright=1100)

# The scan (G): strings rise, pulse begins, dominant tension, then cut
t0 = G["start"]
gend = G["end"] + 0.15
pad("Gm", t0 - 0.3, (gend - t0) * 0.55, 0.65, attack=1.2, bright=2000)
pad("A", t0 + (gend - t0) * 0.5, (gend - t0) * 0.5, 0.75, attack=1.0, bright=2400)
for k_ in np.arange(t0 + 0.1, gend - 0.2, 0.86):
    add(low, pulse(0.55), k_)
cut_at = gend                       # everything stops here: the silent beat

# "لا أطلب المستحيل… أطلب فرصة أخرى للحياة" — single notes, high strings
add(piano, felt_piano(62, 0.40, 2.0, 6), H["start"] - 0.05, 1.0, 0.0)
add(piano, felt_piano(65, 0.36, 2.0, 6), H["start"] + 1.0, 1.0, 0.2)
add(piano, felt_piano(69, 0.42, 2.5, 6), I["start"] + 0.05, 1.0, -0.1)
add(piano, felt_piano(41, 0.40, 3.5, 6), I["start"] + 0.05, 1.0, -0.3)
pad("F", I["start"] - 0.2, J["start"] - I["start"] + 0.6, 0.45, octave=12, attack=1.6, bright=2600)

# Hope (J): F major opens, warm strings, brighter arpeggio
t0 = J["start"]
span = (K["start"] - t0) / 3
for i, ch in enumerate(["F", "C/E", "Bbmaj7"]):
    arp(ch, t0 + i * span, span, vel=0.48, step=0.40, pattern=(0, 2, 4, 3, 2, 1))
    pad(ch, t0 + i * span, span + 0.4, 0.48, attack=1.2, bright=2600)

# Call to action (K–L): lift — strings full, pulse returns softly, octave melody
t0 = K["start"]
span = (L_["end"] - t0) / 3
for i, ch in enumerate(["Dm9", "Bbmaj7", "F"]):
    arp(ch, t0 + i * span, span, vel=0.50, step=0.38, pattern=(0, 2, 4, 3, 4, 2))
    pad(ch, t0 + i * span, span + 0.5, 0.50 + 0.06 * i, octave=12 if i == 2 else 0, attack=1.0, bright=3000)
for k_ in np.arange(t0 + 0.2, L_["end"] - 0.3, 0.86):
    add(low, pulse(0.32), k_)
melody = [(81, 0.0), (79, 0.9), (77, 1.8), (76, 2.9), (77, 4.2), (72, 5.6)]
for n, dt in melody:
    add(piano, felt_piano(n, 0.38, 1.6, 5), t0 + 0.4 + dt * (L_["end"] - t0 - 0.6) / 6.4, 0.8, 0.15)
# End card: resolution on Fadd9, long tail
add(piano, felt_piano(41, 0.45, 3.0, 7), L_["end"] + 0.1, 1.0, -0.3)
for j, n in enumerate([60, 65, 67, 72]):
    add(piano, felt_piano(n, 0.36, 3.0, 7), L_["end"] + 0.1 + 0.11 * j, 0.9, 0.1 * j)
pad("Fadd9", L_["end"], END_HOLD - 0.5, 0.55, octave=12, attack=0.6, bright=2600)

music = reverb(piano, 0.38) + reverb(strs, 0.45) + low * 0.8
# the silent beat: kill the score at cut_at, let it return at H
cs, ce = int(cut_at * SR), int((H["start"] - 0.08) * SR)
fade = np.ones(N, np.float32)
fade[cs:ce] = 0
fade[cs - int(0.05 * SR):cs] = np.linspace(1, 0, int(0.05 * SR))
music *= fade[:, None]
# final fade
fe = int((TOTAL - 0.2) * SR)
music[fe - int(1.2 * SR):fe] *= np.linspace(1, 0, int(1.2 * SR))[:, None]
music[fe:] = 0

# ---------------------------------------------------------------------------
# 4. Sound design
# ---------------------------------------------------------------------------
sfx = np.zeros((N, 2), np.float32)


def noise(sec):
    return rng.standard_normal(int(sec * SR)).astype(np.float32)


# room tone: a quiet room, always there (also through the silent beat)
room = lp(np.stack([noise(TOTAL + 1), noise(TOTAL + 1)], 1), 500)[:N]
room *= 0.010
# hospital air: ventilation + faint fluorescent hum (D..G)
hs, he = D["start"] - 0.3, cut_at
hn = int((he - hs) * SR)
th = np.arange(hn) / SR
air = bp(np.stack([noise(he - hs), noise(he - hs)], 1)[:hn], 150, 1200) * 0.012
hum = (np.sin(2 * np.pi * 100 * th) + 0.4 * np.sin(2 * np.pi * 200 * th) + 0.15 * np.sin(2 * np.pi * 300 * th)) * 0.0022
envh = np.minimum(1, np.minimum(th / 0.8, (he - hs - th) / 0.05))
add(sfx, (air + hum[:, None]) * envh[:, None], hs)

# film-start sub hit as the verse gives way to her voice
tt = np.arange(int(2.0 * SR)) / SR
boom = np.sin(2 * np.pi * np.cumsum(38 + 25 * np.exp(-tt / 0.1)) / SR) * np.exp(-tt / 0.7)
add(sfx, boom * 0.20, A["start"] - 0.25)

# camera shutter after «أحبّ التصوير» — the cut into her photographs
shutter_t = B["end"] + 0.10
c1 = hp(noise(0.006), 2500) * np.hanning(int(0.006 * SR))
c2 = bp(noise(0.03), 900, 5000) * np.exp(-np.arange(int(0.03 * SR)) / (0.006 * SR))
add(sfx, c1 * 0.35, shutter_t, p=0.1)
add(sfx, c2 * 0.30, shutter_t + 0.055, p=0.1)
timeline["anchors"]["shutter"] = round(shutter_t + 0.05, 3)

# soft whoosh under the print drops in the photo sequence
def whoosh(sec=0.5, lo=300, hi=3000, g=0.05):
    x = bp(noise(sec), lo, hi)
    e = np.sin(np.linspace(0, np.pi, len(x))) ** 2
    return x * e * g


for key in ("word_daw", "word_mamarrat", "word_akyas"):
    add(sfx, whoosh(0.45, g=0.035), an[key] - 0.30, p=0.0)

# paper as the report enters
pr = bp(noise(0.7), 1800, 7000) * (np.abs(lp(noise(0.7), 18)) * 6).clip(0, 1)
add(sfx, pr * 0.10, G["start"] - 0.45, p=-0.2)

# reverse swell into the silent beat (sucks the air out, then nothing)
sw = reverb(np.stack([bp(noise(1.4), 400, 6000)] * 2, 1) * np.linspace(0, 1, int(1.4 * SR))[:, None] ** 3, 0.6)
sw *= np.linspace(0, 1, len(sw))[:, None] ** 2
add(sfx, sw * 0.06, cut_at - 1.4)

# a single held breath of air when hope opens (J)
add(sfx, whoosh(1.6, 600, 9000, 0.02), J["start"] - 0.8)

sfx += room

# ---------------------------------------------------------------------------
# 5. Mix: duck score under the voice, master
# ---------------------------------------------------------------------------
env = np.abs(vo[:, 0])
win = int(0.08 * SR)
env = np.convolve(env, np.ones(win) / win, "same")
env = env / (env.max() + 1e-9)
att = np.exp(-1 / (0.03 * SR))
rel = np.exp(-1 / (0.45 * SR))
# one-pole attack/release follower on a decimated envelope (fast)
dec = 48
e_d = env[::dec]
g = np.zeros_like(e_d)
a1, r1 = att ** dec, rel ** dec
cur = 0.0
for i, v in enumerate(e_d):
    cur = a1 * cur + (1 - a1) * v if v > cur else r1 * cur + (1 - r1) * v
    g[i] = cur
duck = np.repeat(g, dec)[:N]
duck = 1 - 0.55 * np.clip(duck * 3, 0, 1)           # ~ -7 dB under speech

mix = vo * 1.0 + music * 0.55 * duck[:, None] + sfx
mix = hp(mix, 25).astype(np.float32)
pre = TMP / "mix_pre.wav"
wavfile.write(pre, SR, (mix[: int(TOTAL * SR)] / max(1e-9, np.abs(mix).max()) * 0.8).astype(np.float32))

# stems (for anyone re-editing in a DAW)
for name, x in (("stem_voice", vo), ("stem_music", music * 0.55 * duck[:, None]), ("stem_sfx", sfx)):
    wavfile.write(TMP / f"{name}.wav", SR, x[: int(TOTAL * SR)].astype(np.float32))

# two-pass EBU R128 to -14 LUFS, -1 dBTP
r = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(pre), "-af",
                    "loudnorm=I=-14:TP=-1.0:LRA=9:print_format=json", "-f", "null", "-"],
                   capture_output=True, text=True).stderr
js = json.loads(r[r.rindex("{"):r.rindex("}") + 1])
ff("-i", str(pre), "-af",
   f"loudnorm=I=-14:TP=-1.0:LRA=9:measured_I={js['input_i']}:measured_TP={js['input_tp']}:"
   f"measured_LRA={js['input_lra']}:measured_thresh={js['input_thresh']}:offset={js['target_offset']}:linear=true,"
   "aresample=48000", "-c:a", "pcm_s24le", str(AUD / "final_mix.wav"))
(AUD / "timeline.json").write_text(json.dumps(timeline, ensure_ascii=False, indent=1))
print("mix ->", AUD / "final_mix.wav")
