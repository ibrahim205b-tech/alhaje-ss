"""Prepare the photographs used in the film.

Reads the original screenshots/photos from ../source (not committed: they hold
personal data), crops each photograph, blurs personal identifiers, and writes
the anonymised crops to ../assets/photos.

Re-run this whenever higher-resolution originals are dropped into source/ —
the renderer only reads assets/photos.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "source"
OUT = ROOT / "assets" / "photos"
OUT.mkdir(parents=True, exist_ok=True)


def blur(im, box, r=16):
    reg = im.crop(box).filter(ImageFilter.GaussianBlur(r))
    im.paste(reg, box[:2])
    return im


# --- Instagram profile: Ruqayya's own photographs + her portrait -----------
ig = Image.open(SRC / "instagram_profile.png").convert("RGB")
k = ig.width / 921  # coordinates below were measured on a 921px-wide view


def igcrop(box):
    return ig.crop(tuple(int(v * k) for v in box))


igcrop((310, 1336, 608, 1640)).save(OUT / "lamp.png")    # evening lamp, tea
igcrop((618, 1336, 921, 1640)).save(OUT / "tree.png")    # trees and sky

p = igcrop((69, 309, 181, 421)).convert("RGBA")          # profile portrait
m = Image.new("L", p.size, 0)
ImageDraw.Draw(m).ellipse((2, 2, p.size[0] - 2, p.size[1] - 2), fill=255)
p.putalpha(m)
p.save(OUT / "portrait.png")

# --- Hospital collage (six panels) -----------------------------------------
col = Image.open(SRC / "hospital_collage.jpg").convert("RGB")
pw, ph = col.width // 2, col.height // 3
names = ["card", "door", "corridor", "arm", "iv", "exterior"]
for i, n in enumerate(names):
    x, y = (i % 2) * pw, (i // 2) * ph
    im = col.crop((x + 3, y + 3, x + pw - 3, y + ph - 3))
    if n == "card":
        # handwritten full name + the already-masked rows under it
        blur(im, (95, 232, 300, 300), 14)
    im.save(OUT / f"{n}.png")

# --- PET/CT report, page 3 (impression) ------------------------------------
rep = Image.open(SRC / "petct_page3.jpg").convert("RGB")
blur(rep, (40, 385, 760, 445), 14)          # patient name line
rep.crop((30, 300, 1200, 960)).save(OUT / "report.png")

print("photos ->", OUT)
