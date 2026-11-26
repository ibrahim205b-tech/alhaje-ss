"""Write output/Ruqayya_ar.srt from the voice timeline (every spoken line)."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
tl = json.loads((ROOT / "assets" / "audio" / "timeline.json").read_text())


def ts(x):
    ms = int(round(x * 1000))
    h, ms = divmod(ms, 3600000)
    m, ms = divmod(ms, 60000)
    s, ms = divmod(ms, 1000)
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


lines = []
# the verse is on screen, not spoken — still give it to screen readers
lines.append((0.35, 3.8, "﴿وَمَنْ أَحْيَاهَا فَكَأَنَّمَا أَحْيَا النَّاسَ جَمِيعًا﴾ — المائدة ٣٢"))
caps = tl["captions"]
for i, c in enumerate(caps):
    nxt = caps[i + 1]["start"] - 0.02 if i + 1 < len(caps) else 1e9
    lines.append((c["start"], min(c["end"] + 0.15, nxt), c["text"]))
lines.append((tl["vo_end"] + 0.15, tl["total"] - 0.1,
              "شارِك القصّة — roqayya.pages.dev"))

out = ROOT / "output" / "Ruqayya_ar.srt"
with open(out, "w", encoding="utf-8") as f:
    for i, (a, b, t) in enumerate(lines, 1):
        f.write(f"{i}\n{ts(a)} --> {ts(b)}\n‫{t}‬\n\n")
print("->", out)
