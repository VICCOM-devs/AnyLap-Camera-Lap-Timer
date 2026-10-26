"""Review sheets: for each formation clip, the analysis frame at every true crossing with the
engine's moving-object boxes, the line, and what the engine reported vs the truth. One PNG per
clip, plus index.html. Usage: python review_sheet.py <cam>"""
import json, glob, os, sys
from PIL import Image, ImageDraw, ImageFont
B = '/home/claude/blend'
cam = sys.argv[1]
S = 2  # upscale
font = ImageFont.load_default()
try:
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 13)
    bold = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 14)
except Exception:
    bold = font
COL = {'Vit_bil': (235, 235, 235), 'Blå_bil': (40, 70, 230), 'Röd_bil': (230, 30, 30), 'Grön_bil': (40, 200, 60),
       'Orange_bil': (255, 140, 0), 'Gul_bil': (240, 220, 20), 'Rosa_bil': (255, 90, 200), 'Grå_bil': (170, 170, 175), 'Svart_bil': (60, 60, 60)}
rows_html = []
for jf in sorted(glob.glob(f'{B}/review/{cam}_*.json')):
    d = json.load(open(jf))
    clip = d['clip']
    frames_dir = f'{B}/form/{cam}/{clip}'
    files = sorted(glob.glob(f'{frames_dir}/*.png'))
    first = int(os.path.basename(files[0])[:4])
    panels = []
    for tr in sorted(d['truth'], key=lambda x: x['t']):
        fi = int(round(tr['t'] * 60))                 # timeline frame index
        k = fi - 120                                   # clip frame index
        if k < 0 or k >= len(files): continue
        im = Image.open(files[k]).convert('RGB').resize((480 * S, 270 * S), Image.NEAREST)
        dr = ImageDraw.Draw(im)
        x1, y1, x2, y2 = d['line']
        dr.line([(x1 * 480 * S, y1 * 270 * S), (x2 * 480 * S, y2 * 270 * S)], fill=(0, 255, 255), width=2)
        # nearest overlay frame (engine reports boxes every 3rd frame)
        bf = min(d['boxes'].keys(), key=lambda f: abs(int(f) - fi), default=None)
        if bf is not None:
            for (l, t, r, b) in d['boxes'][bf]:
                dr.rectangle([l * 480 * S, t * 270 * S, r * 480 * S, b * 270 * S], outline=(255, 255, 0), width=2)
        # events within ±0.1 s
        evs = [e for e in d['events'] if abs(e['t'] - tr['t']) < 0.1]
        y = 4
        dr.text((6, y), f"truth: {tr['car']} crosses at frame {k + first} ({tr['speed']} m/s)", fill=COL.get(tr['car'], (255, 255, 255)), font=bold); y += 18
        for e in evs:
            txt = f"engine: {'✓' if e['accepted'] else '✗'} {e['car'] or 'unknown'}  t={e['t'] - tr['t']:+.3f}s  along {e['along']:.2f}  area {e['area']}{' split' if e['split'] else ''}  [{e['cands']}]"
            dr.text((6, y), txt, fill=(255, 255, 0), font=font); y += 16
        if not evs:
            dr.text((6, y), "engine: nothing within 0.1 s", fill=(255, 80, 80), font=font); y += 16
        panels.append(im)
    if not panels: continue
    W = 480 * S; H = 270 * S
    sheet = Image.new('RGB', (W, (H + 6) * len(panels) + 40), (20, 20, 20))
    ImageDraw.Draw(sheet).text((6, 8), f"{cam}  ·  {clip}  ·  {d['summary']}", fill=(255, 255, 255), font=bold)
    ImageDraw.Draw(sheet).text((6, 24), "yellow = what the engine tracks  ·  cyan = finish line  ·  verdict: does each listed engine line match the truth line above it?", fill=(180, 180, 180), font=font)
    for i, p in enumerate(panels): sheet.paste(p, (0, 40 + i * (H + 6)))
    out = f'{B}/review/{cam}_{clip}.png'
    sheet.save(out)
    rows_html.append(f'<h3>{clip} — {d["summary"]}</h3><img src="{os.path.basename(out)}" width="960">')
open(f'{B}/review/index_{cam}.html', 'w').write(f'<html><body style="background:#111;color:#eee;font-family:sans-serif"><h1>{cam}</h1>' + "\n".join(rows_html) + '</body></html>')
print("sheets:", len(rows_html))
