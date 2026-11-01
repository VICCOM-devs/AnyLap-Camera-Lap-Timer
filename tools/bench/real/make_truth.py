#!/usr/bin/env python3
"""
Ground truth for a real race clip: every crossing of the finish line, found independently of the app's
engine, ready to be checked by eye.

  python3 -I make_truth.py <video> --from 285 --to 790 --line 711,511,656,611 --out work/

1. Band tracker at the video's full resolution: median background of the band around the line (±70 px
   across it), foreground blobs, tracks linked frame to frame (Hungarian on predicted position), one
   crossing per track (sub-frame time where its centre crosses the line).
2. Each crossing gets a tile (the frame where the car is nearest the line, line drawn in red) on contact
   sheets work/sheet_NN.png, numbered, with the time and a colour-cluster guess.
3. You check the sheets, write the car of every tile into work/labels.csv (index,car – a guess you agree
   with needs no line; "drop" removes a tile: a hand, a marshal), and run with --finish to write the
   "crossings" list for clips/<name>.json. Check each car's lap rhythm in the printout: a lap of about
   twice the usual length means a crossing is missing (look at the raw blobs around that time), a very
   short one means a duplicate or a wrong label.

Needs numpy, scipy, pillow, ffmpeg. Line = x1,y1,x2,y2 in video pixels (same direction as the app's line).
"""
import argparse, csv, json, os, subprocess, sys
import numpy as np
from scipy import ndimage as ndi
from scipy.optimize import linear_sum_assignment
from PIL import Image, ImageDraw

ap = argparse.ArgumentParser()
ap.add_argument('video'); ap.add_argument('--from', dest='t0', type=float, required=True); ap.add_argument('--to', dest='t1', type=float, required=True)
ap.add_argument('--line', required=True); ap.add_argument('--fps', type=float, default=30); ap.add_argument('--band', type=float, default=70)
ap.add_argument('--gate', type=float, default=30, help='max px a tracked blob may be from its predicted place')
ap.add_argument('--newgate', type=float, default=75, help='max px for the second sighting of a new track')
ap.add_argument('--minarea', type=int, default=60)
ap.add_argument('--clusters', type=int, default=8); ap.add_argument('--out', default='work'); ap.add_argument('--finish', action='store_true')
a = ap.parse_args()
os.makedirs(a.out, exist_ok=True)
x1, y1, x2, y2 = map(float, a.line.split(','))
L = np.hypot(x2 - x1, y2 - y1); pad = int(a.band + L * 0.6)
X0 = int(max(0, min(x1, x2) - pad)); Y0 = int(max(0, min(y1, y2) - pad))
W = int(max(x1, x2) + pad) - X0; H = int(max(y1, y2) + pad) - Y0
vw, vh = map(int, subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height', '-of', 'csv=p=0', a.video], capture_output=True, text=True).stdout.strip().split(','))
W = min(W, vw - X0); H = min(H, vh - Y0)
X0 -= X0 % 2; Y0 -= Y0 % 2; W -= W % 2; H -= H % 2   # ffmpeg crops YUV video to even sizes
A = np.array([x1 - X0, y1 - Y0]); B = np.array([x2 - X0, y2 - Y0]); u = (B - A) / L; nrm = np.array([u[1], -u[0]])

def frames():
    p = subprocess.Popen(['ffmpeg', '-v', 'error', '-ss', str(a.t0), '-to', str(a.t1), '-i', a.video, '-vf', f'crop={W}:{H}:{X0}:{Y0}', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], stdout=subprocess.PIPE)
    while True:
        b = p.stdout.read(W * H * 3)
        if len(b) < W * H * 3: return
        yield np.frombuffer(b, np.uint8).reshape(H, W, 3)

def finish():
    C = json.load(open(f'{a.out}/candidates.json'))
    lf = f'{a.out}/labels.csv'
    lab = {int(r[0]): r[1].strip() for r in csv.reader(open(lf)) if r and r[0].strip().isdigit()} if os.path.exists(lf) else {}
    out = []
    for i, c in enumerate(C):
        car = lab.get(i, c['guess'])
        if car != 'drop': out.append([round(c['t'], 3), car, round(c['along'], 3)])
    for car in sorted({c[1] for c in out}):
        ts = [c[0] for c in out if c[1] == car]; laps = np.diff(ts); med = np.median(laps) if len(laps) else 0
        odd = [(ts[i], round(laps[i], 2)) for i in range(len(laps)) if laps[i] < 0.75 * med or laps[i] > 1.3 * med]
        print(f'{car:14s} {len(ts):3d} crossings, median lap {med:.2f} s, odd laps {odd}')
    json.dump(out, open(f'{a.out}/crossings.json', 'w'))
    print(f'{len(out)} crossings → {a.out}/crossings.json (paste as "crossings" into clips/<name>.json)')

if a.finish: finish(); sys.exit()

# ---- 1. background, blobs
bg = np.median(np.array([f for k, f in enumerate(frames()) if k % 45 == 0]), 0).astype(np.float32)
yy, xx = np.mgrid[0:H, 0:W]
S = (xx - A[0]) * nrm[0] + (yy - A[1]) * nrm[1]; T = ((xx - A[0]) * u[0] + (yy - A[1]) * u[1]) / L
band = (np.abs(S) < a.band) & (T > -0.08) & (T < 1.08)
blobs = []
for f in frames():
    f = f.astype(np.float32)
    m = (np.abs(f - bg).sum(2) > 55) & band
    m = ndi.binary_closing(ndi.binary_opening(m, iterations=1), iterations=2)
    lab, n = ndi.label(m); out = []
    for i, sl in enumerate(ndi.find_objects(lab) if n else [], 1):
        mm = lab[sl] == i; ar = int(mm.sum())
        if ar < a.minarea: continue
        cy, cx = ndi.center_of_mass(mm); cy += sl[0].start; cx += sl[1].start
        out.append([cx, cy, ar, float(S[int(cy), int(cx)]), float(T[int(cy), int(cx)])] + list(f[sl][mm].mean(0)))
    blobs.append(out)
# ---- 2. tracks → one crossing per track
tracks, live = [], []
for k, bl in enumerate(blobs):
    pred = [(t['p'][-1][0] + t.get('v', (0, 0))[0] * (k - t['p'][-1][5]), t['p'][-1][1] + t.get('v', (0, 0))[1] * (k - t['p'][-1][5])) for t in live]
    used = set()
    if live and bl:
        M = np.full((len(live), len(bl)), 1e6)
        for i, (px, py) in enumerate(pred):
            for j, b in enumerate(bl):
                d = np.hypot(px - b[0], py - b[1])
                if d < (a.gate if 'v' in live[i] else a.newgate): M[i, j] = d
        for i, j in zip(*linear_sum_assignment(M)):
            if M[i, j] < 1e6:
                t = live[i]; b = bl[j]; p = t['p'][-1]
                t['v'] = ((b[0] - p[0]) / (k - p[5]), (b[1] - p[1]) / (k - p[5])); t['p'].append(b[:5] + [k] + b[5:8]); used.add(j)
    for j, b in enumerate(bl):
        if j not in used: t = {'p': [b[:5] + [k] + b[5:8]]}; live.append(t); tracks.append(t)
    live = [t for t in live if k - t['p'][-1][5] <= 2]
C = []
for t in tracks:
    P = t['p']
    for q, r in zip(P, P[1:]):
        if (q[3] < 0) != (r[3] < 0):
            fr = q[3] / (q[3] - r[3]); near = q if abs(q[3]) <= abs(r[3]) else r
            rgb = np.mean([p[6:9] for p in P if p[2] > 200] or [p[6:9] for p in P], 0)
            C.append(dict(t=a.t0 + (q[5] + fr * (r[5] - q[5])) / a.fps, along=q[4] + fr * (r[4] - q[4]), rgb=[float(x) for x in rgb], xy=[float(near[0]), float(near[1])], k=int(near[5])))
            break
C.sort(key=lambda c: c['t'])
# colour clusters as a first guess (k-means on chromaticity + brightness)
F = np.array([[c['rgb'][0], c['rgb'][1], c['rgb'][2]] for c in C]); F = np.c_[F / (F.sum(1, keepdims=True) + 1), F.sum(1) / 765 * 0.7]
rng = np.random.default_rng(1); cen = F[rng.choice(len(F), a.clusters, replace=False)]
for _ in range(30):
    g = ((F[:, None] - cen[None]) ** 2).sum(2).argmin(1); cen = np.array([F[g == i].mean(0) if (g == i).any() else cen[i] for i in range(a.clusters)])
for c, gi in zip(C, g): c['guess'] = f'c{gi}'
json.dump(C, open(f'{a.out}/candidates.json', 'w'))
# ---- 3. sheets
need = {}
for i, c in enumerate(C): need.setdefault(c['k'], []).append(i)
tiles = {}
for k, f in enumerate(frames()):
    if k not in need: continue
    im = Image.fromarray(f)
    for i in need[k]:
        c = C[i]; cx, cy = map(int, c['xy'])
        hw = max(55, a.band * 0.6); hh = hw * 42 / 55; k = 88 / hw   # tile grows with the band (big cars, close cameras)
        t = im.crop((int(cx - hw), int(cy - hh), int(cx + hw), int(cy + hh))).resize((176, 134), Image.LANCZOS); d = ImageDraw.Draw(t)
        d.line([((A[0] - cx + hw) * k, (A[1] - cy + hh) * k), ((B[0] - cx + hw) * k, (B[1] - cy + hh) * k)], fill=(255, 0, 0))
        d.rectangle([0, 0, 176, 13], fill=(0, 0, 0)); d.text((2, 1), f"{i} {c['guess']} {c['t']:.2f}", fill=(255, 255, 0)); tiles[i] = t
order = sorted(range(len(C)), key=lambda i: (C[i]['guess'], C[i]['t']))   # by colour group: odd ones stand out
for s in range(0, len(order), 48):
    sh = Image.new('RGB', (176 * 8, 134 * 6), (40, 40, 40))
    for j, i in enumerate(order[s:s + 48]): sh.paste(tiles[i], ((j % 8) * 176, (j // 8) * 134))
    sh.save(f'{a.out}/sheet_{s // 48:02d}.png')
print(f'{len(C)} candidate crossings, {(len(order) + 47) // 48} sheets in {a.out}/ – label them in {a.out}/labels.csv, then --finish')
