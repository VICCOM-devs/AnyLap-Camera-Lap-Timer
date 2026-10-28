"""race10_<seed>.json + frames -> out/race10_<seed>.html: the race as the app would show it.
Standings, lap table, graphs, Check tray (redesigned cards: every car boxed + labelled, race-direction arrow,
tap a box to change it, tap the score for an explanation), start lights and photo-finish ghosts."""
import json, sys, base64, io, statistics, os, math, re
from PIL import Image, ImageDraw, ImageFont
B = '/home/claude/blend'
seed = sys.argv[1] if len(sys.argv) > 1 else '1'
R = json.load(open(f'{B}/out/race10_{seed}.json'))
cars = R['cars']; NL = R['laps']; FPS = R['fps']; W0, H0 = R['w'], R['h']; LINE = R['line']
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
LIVERY = {'Red_7': '#d8231c', 'Blue_14': '#2a5bd0', 'Yellow_2': '#f0c419', 'White_33': '#e8e6df', 'Green_51': '#1f8a3a',
          'Black_88': '#3a3a42', 'Orange_4': '#f07a12', 'Silver_19': '#aab0b8', 'Purple_27': '#7a2bb0', 'Teal_61': '#16a3a3'}
KIND = {'Red_7': 'GT3', 'Blue_14': 'LMP', 'Yellow_2': 'Formula', 'White_33': 'Rally', 'Green_51': 'Touring', 'Black_88': 'Drift',
        'Orange_4': 'Group 5', 'Silver_19': 'Supercar', 'Purple_27': 'Stock car', 'Teal_61': 'Hypercar'}
def nice(c): return c.replace('_', ' ') if c else 'Unknown'
def sw(c): return f'<span class="sw" style="background:{LIVERY.get(c, "#555")}"></span>'
def fmt(t): return '—' if t is None else (f"{int(t // 60)}:{t % 60:06.3f}" if t >= 60 else f"{t:.3f}")
ev = R['events']; outs = R['outs']; truth = R['truth']
SIM = {tuple(k.split('|')): v for k, v in R['sim'].items()}

# ---------------------------------------------------------------- counted crossings (engine + lap identity)
counted = {c: [] for c in cars}
for o in outs:
    if o['car']: counted[o['car']].append(dict(t=o['t'], i=o['i'], why=o['why']))
for c in cars: counted[c].sort(key=lambda x: x['t'])
tru = {c: sorted([x for x in truth if x['car'] == c], key=lambda x: x['t']) for c in cars}
def laps_of(xs): return [xs[0]['t']] + [xs[k]['t'] - xs[k - 1]['t'] for k in range(1, len(xs))] if xs else []
def standings(src):
    rows = []
    for c in cars:
        lt = laps_of(src[c]); n = len(lt)
        rows.append(dict(car=c, laps=n, total=src[c][-1]['t'] if n else None, best=min(lt[1:] or lt) if lt else None,
                         avg=statistics.mean(lt[1:]) if n > 1 else None, sd=statistics.pstdev(lt[1:]) if n > 2 else 0, finish=src[c][-1]['t'] if n else 1e9))
    rows.sort(key=lambda r: (-r['laps'], r['finish']))
    lead = rows[0]
    for i, r in enumerate(rows):
        r['pos'] = i + 1
        r['gap'] = 'Winner' if i == 0 else (f"+{r['finish'] - lead['finish']:.3f}" if r['laps'] == lead['laps'] else f"+{lead['laps'] - r['laps']} lap")
    return rows
stE = standings(counted); stT = standings(tru)
S = R['summary']
sameOrder = [r['car'] for r in stE] == [r['car'] for r in stT]

# ---------------------------------------------------------------- picture helpers
_frames = {}
def frame(fi):
    p = f'{B}/out/r10_{seed}/f{fi}.ppm'
    if not os.path.exists(p): return None
    if p not in _frames: _frames[p] = Image.open(p).convert('RGB')
    return _frames[p]
def nearest_frame(t):
    fi = int(t * FPS)
    for d in (0, 1, -1, 2, -2, 3, -3):
        if frame(fi + d) is not None: return fi + d
    return None
def quad_at(e, t):
    """The fitted outline (4 corners, 0..1) moved to time t; it was taken in the frame after the centre crossed."""
    q = e.get('quad')
    if not q: return None
    t0 = math.ceil(e['centre'] * FPS - 1e-6) / FPS; k = t - t0
    return [q[i] + (e['v'][0] if i % 2 == 0 else e['v'][1]) * k for i in range(8)]
def box_at(e, t):
    b = e['box']; k = t - e['t']
    return [b[0] + e['v'][0] * k, b[1] + e['v'][1] * k, b[2] + e['v'][0] * k, b[3] + e['v'][1] * k]
def name_of(i):
    n = [o['car'] for o in outs if o['i'] == i and o['car']]
    return ' + '.join(nice(x) for x in n) if n else '?'
def b64(im):
    b = io.BytesIO(); im.save(b, 'PNG'); return 'data:image/png;base64,' + base64.b64encode(b.getvalue()).decode()
ORANGE = (255, 138, 0)
def picture(t, involved, focus=None, scale=3, ghosts=None):
    """Frame at time t, cropped around the involved cars, every car boxed + labelled with a leader line,
    race-direction arrow and a tick on the finish line. Returns (data-url, box list in % for the tap layer)."""
    # the frame the engine took the focus car's box in (first frame after its centre crossed): no extrapolation for it
    fe = [e for e in involved if focus and e['i'] in focus] or involved
    fi = math.ceil(fe[0]['centre'] * FPS - 1e-6) if fe and not ghosts else None
    if fi is None or frame(fi) is None: fi = nearest_frame(t)
    if fi is None: return '', []
    ft = fi / FPS
    im = frame(fi).resize((W0 * scale, H0 * scale), Image.LANCZOS)
    d = ImageDraw.Draw(im, 'RGBA')
    font = ImageFont.truetype(FONT, 12 * scale // 3 + 4)
    # finish line + ticks
    lx1, ly1, lx2, ly2 = [LINE[0] * W0 * scale, LINE[1] * H0 * scale, LINE[2] * W0 * scale, LINE[3] * H0 * scale]
    d.line([(lx1, ly1), (lx2, ly2)], fill=(255, 255, 255, 110), width=2)
    boxes = []
    pts = []
    for e in involved:
        col = ORANGE if (focus is None or e['i'] in focus) else (255, 200, 120)
        q = quad_at(e, ft)
        if q:
            poly = [(q[2 * k] * W0 * scale, q[2 * k + 1] * H0 * scale) for k in range(4)]
            d.polygon(poly, outline=col + (255,), width=3)
            d.line([poly[0], poly[1]], fill=(255, 255, 255, 230), width=3)   # nose edge
            x0, y0 = min(p[0] for p in poly), min(p[1] for p in poly); x1, y1 = max(p[0] for p in poly), max(p[1] for p in poly)
        else:
            b = box_at(e, ft); x0, y0, x1, y1 = [b[0] * W0 * scale, b[1] * H0 * scale, b[2] * W0 * scale, b[3] * H0 * scale]
            d.rounded_rectangle([x0, y0, x1, y1], radius=4, outline=col + (255,), width=2)
        pts += [(x0, y0), (x1, y1)]
        boxes.append(dict(i=e['i'], l=x0, t=y0, r=x1, b=y1))
    # ghosts (photo finish): boxes at 10 steps between this frame and the next, nose step highlighted
    if ghosts:
        for g in ghosts:
            for k, (gb, hit) in enumerate(g):
                if len(gb) == 8:
                    poly = [(gb[2 * j] * W0 * scale, gb[2 * j + 1] * H0 * scale) for j in range(4)]
                    d.polygon(poly, outline=(255, 255, 255, 255 if hit else 55), width=3 if hit else 1)
                else:
                    x0, y0, x1, y1 = [gb[0] * W0 * scale, gb[1] * H0 * scale, gb[2] * W0 * scale, gb[3] * H0 * scale]
                    d.rectangle([x0, y0, x1, y1], outline=(255, 255, 255, 255 if hit else 55), width=3 if hit else 1)
    # crop around everything involved
    if pts:
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
        half = max(max(xs) - min(xs), max(ys) - min(ys)) / 2 + 70 * scale / 3 + 40
        half = max(half, 110 * scale / 3)
        X0 = max(0, cx - half); Y0 = max(0, cy - half); X1 = min(im.width, cx + half); Y1 = min(im.height, cy + half)
    else:
        X0, Y0, X1, Y1 = 0, 0, im.width, im.height
    # labels (callouts) – placed outside the box, towards the crop's free side, with a leader line
    placed = []
    for e, bx in zip(involved, boxes):
        lab = name_of(e['i'])
        tw = d.textlength(lab, font=font); lw, lh = tw + 12, 26
        cx = (bx['l'] + bx['r']) / 2
        lx = min(max(cx - lw / 2, X0 + 4), X1 - lw - 4)
        cands_ = [(lx, bx['t'] - lh - 12, 'top'), (lx, bx['b'] + 12, 'bottom'), (lx, bx['t'] - 2 * lh - 18, 'top')]
        def hits(y): return any(not (lx + lw < px0 or lx > px1 or y + lh < py0 or y > py1) for px0, py0, px1, py1 in placed)
        ly, side = next(((x, y, sd) for x, y, sd in cands_ if Y0 + 2 <= y <= Y1 - lh - 2 and not hits(y)), cands_[0])[1:]
        ly = min(max(ly, Y0 + 2), Y1 - lh - 2)
        placed.append((lx, ly, lx + lw, ly + lh))
        ay = bx['t'] if ly < bx['t'] else bx['b']
        d.line([(cx, ay), (min(max(cx, lx + 8), lx + lw - 8), ly + (lh if ly < bx['t'] else 0))], fill=ORANGE + (230,), width=2)
        d.rounded_rectangle([lx, ly, lx + lw, ly + lh], radius=7, fill=(20, 20, 24, 225), outline=ORANGE + (255,), width=2)
        d.text((lx + 6, ly + 4), lab, font=font, fill=(255, 255, 255))
    # race direction: arrow from the involved cars' velocity
    if involved:
        vx = sum(e['v'][0] for e in involved); vy = sum(e['v'][1] for e in involved)
        n = math.hypot(vx * W0, vy * H0) or 1
        ux, uy = vx * W0 / n, vy * H0 / n
        ax, ay = X0 + 34, Y1 - 30
        ex, ey = ax + ux * 50, ay + uy * 50
        if ex < X0 + 10 or ex > X1 - 10: ax, ex = X1 - 34, X1 - 34 + ux * 50
        d.line([(ax, ay), (ex, ey)], fill=(255, 255, 255, 235), width=5)
        a = math.atan2(uy, ux)
        d.polygon([(ex + 12 * math.cos(a), ey + 12 * math.sin(a)), (ex + 10 * math.cos(a + 2.4), ey + 10 * math.sin(a + 2.4)), (ex + 10 * math.cos(a - 2.4), ey + 10 * math.sin(a - 2.4))], fill=(255, 255, 255, 235))
    crop = im.crop((int(X0), int(Y0), int(X1), int(Y1)))
    for bx in boxes:
        for k, v in (('l', X0), ('r', X0), ('t', Y0), ('b', Y0)): bx[k] = (bx[k] - v) / ((X1 - X0) if k in 'lr' else (Y1 - Y0)) * 100
    return b64(crop), boxes

def card_picture(e, focus_ids):
    """The app's Check card: the crossing's own picture, every car outlined with its fitted calibration
    picture, tags placed by the shared LabelLayout in a free band round the picture (never over a car)."""
    c = e.get('card')
    p = f"{B}/out/r10_{seed}/s{e['i']}.ppm"
    if not c or not os.path.exists(p): return None
    K = c['K']; S_ = int(c['S']); ox, oy = c['ox'], c['oy']
    pw, ph = int(round(c['w'] * K)), int(round(c['h'] * K))
    W_, H_ = S_, S_
    can = Image.new('RGB', (W_, H_), (24, 24, 28))
    can.paste(Image.open(p).convert('RGB').resize((pw, ph), Image.LANCZOS), (int(round(ox)), int(round(oy))))
    M = ox; MY = oy
    # outlines on their own layer, clipped to the picture (a car half out of frame is outlined where it is seen)
    lay = Image.new('RGBA', (W_, H_), (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    if c.get('line'):
        L = c['line']; d.line([(M + L[0] * pw, MY + L[1] * ph), (M + L[2] * pw, MY + L[3] * ph)], fill=(255, 255, 255, 90), width=2)
    for car in c['cars']:
        q = car['poly']; poly = [(q[2 * k], q[2 * k + 1]) for k in range(4)]
        on = car['id'] in focus_ids or not focus_ids
        col = ORANGE if on else (255, 200, 120)
        d.polygon(poly, outline=col + (255,), width=3)
        d.line([poly[0], poly[1]], fill=(255, 255, 255, 235), width=3)   # nose edge
    mask = Image.new('L', (W_, H_), 0); ImageDraw.Draw(mask).rectangle([M, MY, M + pw - 1, MY + ph - 1], fill=255)
    can.paste(lay, (0, 0), Image.composite(lay.getchannel('A'), Image.new('L', (W_, H_), 0), mask))
    d = ImageDraw.Draw(can, 'RGBA')
    font = ImageFont.truetype(FONT, 13)
    boxes = []
    for car in c['cars']:
        x, y, w, h, ax, ay = car['tag']
        lx = min(max(ax, x), x + w); ly = min(max(ay, y), y + h)
        on = car['id'] in focus_ids or not focus_ids
        col = ORANGE if on else (255, 200, 120)
        d.line([(ax, ay), (lx, ly)], fill=col + (235,), width=2)
        d.ellipse([ax - 3, ay - 3, ax + 3, ay + 3], fill=col + (255,))
        d.rounded_rectangle([x, y, x + w, y + h], radius=7, fill=(20, 20, 24, 235), outline=col + (255,), width=2)
        lab = nice(car['id']) if car['id'] else '?'
        tw = d.textlength(lab, font=font)
        d.text((x + (w - tw) / 2, y + 5), lab, font=font, fill=(255, 255, 255))
        q = car['poly']; xs = q[0::2]; ys = q[1::2]
        boxes.append(dict(i=e['i'], l=max(min(xs), M) / W_ * 100, t=max(min(ys), MY) / H_ * 100, r=min(max(xs), M + pw) / W_ * 100, b=min(max(ys), MY + ph) / H_ * 100))
    # race direction: arrow in the bottom-left of the band
    vx, vy = e['v']; n = math.hypot(vx * W0, vy * H0) or 1; ux, uy = vx * W0 / n, vy * H0 / n
    ax_, ay_ = 26, H_ - 22; ex, ey = ax_ + ux * 16, ay_ + uy * 16; sx, sy = ax_ - ux * 16, ay_ - uy * 16
    d.line([(sx, sy), (ex, ey)], fill=(255, 255, 255, 235), width=4)
    a = math.atan2(uy, ux)
    d.polygon([(ex + 9 * math.cos(a), ey + 9 * math.sin(a)), (ex + 8 * math.cos(a + 2.4), ey + 8 * math.sin(a + 2.4)), (ex + 8 * math.cos(a - 2.4), ey + 8 * math.sin(a - 2.4))], fill=(255, 255, 255, 235))
    return b64(can), boxes

# ---------------------------------------------------------------- Check tray
WHY = {'RENAMED_BY_TIMING': ('Renamed by lap timing', 'timing'), 'NAMED_BY_TIMING': ('Named by lap timing', 'timing'),
       'CHECK_LINE': ('Added from the check line', 'timing'), 'SECOND_IN_BLOB': ('Two cars in one blob', 'timing'),
       'MISSING_LAP': ('Missing lap found', 'timing'), 'NOT_COUNTED': ('Not counted', 'warn'), 'UNKNOWN': ('Unknown car', 'warn')}
evByI = {e['i']: e for e in ev}
outsByI = {}
for o in outs: outsByI.setdefault(o['i'], []).append(o)
def truth_near(t, car=None, win=0.12):
    xs = [x for x in truth if abs(x['t'] - t) < win and (car is None or x['car'] == car)]
    return min(xs, key=lambda x: abs(x['t'] - t)) if xs else None
items = []; usedI = set()
def involved_at(t, win=0.1):
    return [e for e in ev if abs(e['t'] - t) < win and e['box'] and (e['accepted'] or e['rescue'] or (outsByI.get(e['i']) or [{}])[0].get('car'))]
for e in ev:
    i = e['i']; os_ = outsByI.get(i, [])
    whys = [o['why'] for o in os_]
    kind = None; note = ''
    for o in os_:
        if o['why'] in WHY: kind = WHY[o['why']][0]; note = o['note'].replace('_', ' ')
    if kind is None and e['accepted'] and e['named'] and e['cands']:
        best = e['cands'][0][1]; second = e['cands'][1] if len(e['cands']) > 1 else None
        base = 1 - SIM.get((e['cands'][0][0], second[0]), 0.5) if second else 1
        if best < 0.85 or e['conf'] < 0.4 * base:
            kind = 'Close call'; note = f"{nice(e['cands'][0][0])} {best:.2f} won by {e['conf']:.2f}; this pair usually differs by {base:.2f}"
    if kind is None and e['split']:
        if i in usedI: continue
        pair = [x for x in ev if x['split'] and abs(x['t'] - e['t']) < 0.1]
        usedI.update(x['i'] for x in pair)
        kind = 'Cars touching'; note = 'separated from a blob of touching cars'
    if kind is None and not e['accepted'] and not e['rescue'] and not any(o['car'] for o in os_):
        kind = 'Not a racer?'; note = f"gate turned it down ({e['match']:.2f} best match)"
    # not counted, but it is the car that *was* counted at that very moment: a second piece of it
    if not any(o['car'] for o in os_) and e['cands']:
        c0 = e['cands'][0][0]
        same = [k for k in counted.get(c0, []) if abs(k['t'] - e['t']) < 0.06 and k['i'] != i]
        if same: note = f"A second piece of {nice(c0)}, cut out of the touching cars – {nice(c0)} is already counted at {fmt(same[0]['t'])}. Nothing to fix."
        if same and kind in ('Not counted', 'Not a racer?'): kind = 'Second piece'
    if kind is None and e['rescue'] and 'IGNORED' in whys:
        if not e['cands'] or e['cands'][0][1] < 0.5: continue   # a shadow at the check line, nobody due – not worth a card
        kind = 'Missed?'; note = 'check line saw something, nobody was due'
    if kind is None: continue
    tr = truth_near(e['t'])
    items.append(dict(t=e['t'], kind=kind, note=note, i=i, cands=e['cands'], truth=tr, outs=os_, ev=e))
# misses and extras from the simulation truth (the app can't know these)
mat = set()
for x in truth:
    hit = [c for c in counted[x['car']] if abs(c['t'] - x['t']) < 0.15]
    if hit: mat.add((x['car'], round(hit[0]['t'], 4)))
    else: items.append(dict(t=x['t'], kind='MISSED (truth)', note=f"{nice(x['car'])} lap {x['lap']} crossed here and was not counted", i=None, cands=[], truth=x, outs=[], ev=None))
for c in cars:
    for k in counted[c]:
        if (c, round(k['t'], 4)) not in mat: items.append(dict(t=k['t'], kind='EXTRA (truth)', note=f"counted for {nice(c)}, but no {nice(c)} crossed", i=k['i'], cands=evByI[k['i']]['cands'] if k['i'] in evByI else [], truth=truth_near(k['t']), outs=outsByI.get(k['i'], []), ev=evByI.get(k['i'])))
items.sort(key=lambda x: x['t'])
# one card per moment: everything within 60 ms (a split pair, its timing decision, a fragment) is one picture
groups = []
for it in items:
    if groups and it['t'] - groups[-1][-1]['t'] < 0.06 and ('(truth)' in it['kind']) == ('(truth)' in groups[-1][0]['kind']): groups[-1].append(it)
    else: groups.append([it])
merged = []
for g in groups:
    head_ = max(g, key=lambda x: (x['kind'] in [v[0] for v in WHY.values()], len(x['cands'])))
    kinds_ = []
    for x in g:
        if x['kind'] not in kinds_: kinds_.append(x['kind'])
    m = dict(head_); m['kind'] = ' · '.join(kinds_); m['note'] = ' / '.join(dict.fromkeys(sorted((x['note'] for x in g if x['note']), key=lambda n: not n.startswith('A second piece'))))
    m['focus'] = {x['i'] for x in g if x['i'] is not None}
    m['outs'] = [o for x in g for o in x['outs']]
    # every car that really crossed during this moment (not just the one nearest each item: two cars
    # 1 ms apart would leave one out and the card would look wrong when the app got both right)
    trs = [x['truth'] for x in g if x['truth']] + [x for x in truth if any(abs(x['t'] - y['t']) < 0.06 for y in g)]
    m['truths'] = list({(t['car'], t['lap']): t for t in trs}.values())
    merged.append(m)
items = merged
cards = []
EXPLAIN = ("The coloured outline only shows <b>which car</b> it is – that car's calibration picture laid over it. <b>The car itself sets the time</b>: a lap is timed when the car's front (the white edge of its outline) touches the finish line. "
           "<b>{sc}</b> = how closely this car matches {who}'s calibration (1 = identical). The next ones are the runners-up. "
           "<i>Won by</i> = gap to the runner-up. <i>Lap timing</i>: each car has its own lap rhythm – a car can't lap in half its time, "
           "and a car whose lap is due is the likely owner of a doubtful crossing. <i>Split</i> = cut out of a blob of touching cars.")
for k, it in enumerate(items):
    inv = involved_at(it['t'])
    focus = it.get('focus') or ({it['i']} if it['i'] is not None else set())
    head_ev = evByI.get(it['i']) if it['i'] is not None else None
    cp = card_picture(head_ev, {o['car'] for o in it['outs'] if o['car']}) if head_ev else None
    url, boxes = cp if cp else picture(it['t'], inv, focus)
    tr = it['truth']; trs = it.get('truths') or ([tr] if tr else [])
    decided = [o['car'] for o in it['outs'] if o['car']]
    good = (bool(trs) and all(any(abs(c['t'] - x['t']) < 0.15 for c in counted[x['car']]) for x in trs) and all(d in [x['car'] for x in trs] for d in decided)) if '(truth)' not in it['kind'] else False
    if it['kind'] == 'Not a racer?': good = not trs
    verdict = ('Right' if good else 'Wrong') if it['kind'] not in ('MISSED (truth)', 'EXTRA (truth)') else 'Wrong'
    cand = ' · '.join(f"{nice(c)} {s:.2f}" for c, s in it['cands'][:3])
    who = nice(it['cands'][0][0]) if it['cands'] else 'a car'
    taps = ''.join(f'<button class="hit" style="left:{b["l"]:.1f}%;top:{b["t"]:.1f}%;width:{b["r"]-b["l"]:.1f}%;height:{b["b"]-b["t"]:.1f}%" onclick="pick(event,{k},{b["i"]})" aria-label="change this car"></button>' for b in boxes)
    opts = json.dumps([[nice(c), s] for c, s in (evByI[it['i']]['cands'] if it['i'] in evByI else [])] + [[n, 0] for n in [nice(c) for c in cars]])
    cls = 'warn' if it['kind'] in ('Not counted', 'Unknown car', 'Missed?', 'Not a racer?') else ('bad' if '(truth)' in it['kind'] else ('timing' if it['kind'] in [v[0] for v in WHY.values()] else ''))
    cards.append(f'''<div class="card {cls}" id="c{k}" data-opts='{opts}'>
<div class="pic">{'<img src="' + url + '" alt="overhead frame at the line">' if url else '<div class="noimg">no picture</div>'}{taps}</div>
<div class="cb"><div class="kl"><b>{it['kind']}</b> · {fmt(it['t'])} s</div>{('<div class="pc">' + it['note'].split(' / ')[0] + '</div>') if it['note'].startswith('A second piece') else ''}
<div class="sc" onclick="this.nextElementSibling.classList.toggle('on')">{cand or it['note']}</div><div class="ex">{EXPLAIN.format(sc=it['cands'][0][1] if it['cands'] else '–', who=who)}<br><br>{it['note']}</div>
<div class="tru" onclick="this.classList.add('on')"><span>Show truth</span><em>{('Truth: ' + ', '.join(nice(x['car']) + ' lap ' + str(x['lap']) + (' (' + x['tag'] + ')' if x['tag'] else '') for x in trs)) if trs else 'Truth: no car crossed'} · app decision <b class="{'ok' if verdict == 'Right' else 'no'}">{verdict.lower()}</b></em></div></div></div>''')
nCheck = len(items)
kinds = {}
for it in items: kinds[it['kind']] = kinds.get(it['kind'], 0) + 1

# ---------------------------------------------------------------- photo finishes (ghosts)
finishes = []
cev = [(o['t'], o['car'], o['i']) for o in outs if o['car'] and o['i'] in evByI and evByI[o['i']]['box']]
cev.sort()
seen = set()
for a in range(len(cev)):
    for b in range(a + 1, len(cev)):
        if cev[b][0] - cev[a][0] > 0.06: break
        if (cev[a][2], cev[b][2]) in seen or cev[a][1] == cev[b][1] or cev[a][2] == cev[b][2]: continue
        seen.add((cev[a][2], cev[b][2])); finishes.append((cev[a], cev[b]))
ghostCards = []
for (ta, ca, ia), (tb, cb, ib) in finishes[:6]:
    ea, eb = evByI[ia], evByI[ib]
    fi = int(min(ta, tb) * FPS)
    if frame(fi) is None: fi = nearest_frame(min(ta, tb))
    t0 = fi / FPS
    ghosts = []
    for e in (ea, eb):
        steps = [t0 + k / 10 / FPS for k in range(11)]
        hitk = min(range(11), key=lambda k: abs(steps[k] - e['t']))
        ghosts.append([((quad_at(e, s) or box_at(e, s)), k == hitk) for k, s in enumerate(steps)])
    url, _ = picture(t0, [ea, eb], {ia, ib}, scale=4, ghosts=ghosts)
    trA = truth_near(ta, ca, 0.15); trB = truth_near(tb, cb, 0.15)
    dt = (tb - ta) * 1000
    tdt = (trB['t'] - trA['t']) * 1000 if trA and trB else None
    ghostCards.append(f'''<div class="card0 pf"><img src="{url}" alt="photo finish">
<div class="pfr"><div><span class="big">{nice(ca)}</span> ahead by <span class="big">{dt:.1f} ms</span></div>
<div class="note">Frame {fi} → {fi+1}: each car moved in 10 steps by <b>its own</b> speed ({evByI[ia]['speed'] or '–'} vs {evByI[ib]['speed'] or '–'} km/h); the bright outline is the step where its nose touches the line. {nice(ca)} {fmt(ta)} · {nice(cb)} {fmt(tb)}.
{'Truth: ' + nice(trA['car']) + ' ahead by ' + f'{tdt:.1f} ms' + (' ✓' if (dt > 0) == (tdt > 0) else ' ✗') if tdt is not None else ''}</div></div></div>''')

# ---------------------------------------------------------------- charts
W, H = 920, 340; ML, MR, MT, MB = 44, 110, 20, 34
def posChart():
    pos = {c: [] for c in cars}
    for k in range(NL):
        done = sorted([(counted[c][k]['t'], c) for c in cars if len(counted[c]) > k])
        order = [c for _, c in done]
        for c in cars: pos[c].append(order.index(c) + 1 if c in order else None)
    xs = lambda k: ML + (k - 1) * (W - ML - MR) / max(1, NL - 1); ys = lambda p: MT + (p - 1) * (H - MT - MB) / (len(cars) - 1)
    g = [f'<svg viewBox="0 0 {W} {H}" class="chart" role="img" aria-label="Position by lap">']
    for p in range(1, len(cars) + 1): g.append(f'<line x1="{ML}" x2="{W-MR}" y1="{ys(p)}" y2="{ys(p)}" class="grid"/><text x="{ML-8}" y="{ys(p)+4}" class="tick" text-anchor="end">P{p}</text>')
    for k in range(1, NL + 1): g.append(f'<text x="{xs(k)}" y="{H-10}" class="tick" text-anchor="middle">{k}</text>')
    for c in cars:
        pts = [(xs(k + 1), ys(p)) for k, p in enumerate(pos[c]) if p]
        d = ' '.join(f"{'M' if i == 0 else 'L'}{x:.1f},{y:.1f}" for i, (x, y) in enumerate(pts))
        g.append(f'<path d="{d}" fill="none" stroke="{LIVERY[c]}" stroke-width="2.5" stroke-linejoin="round" class="ln"/>')
        if pts: g.append(f'<circle cx="{pts[-1][0]:.1f}" cy="{pts[-1][1]:.1f}" r="4" fill="{LIVERY[c]}" stroke="#0B0B0D" stroke-width="2"/><text x="{W-MR+10}" y="{pts[-1][1]+4:.1f}" class="lbl">{nice(c)}</text>')
    g.append('</svg>'); return '\n'.join(g)
def lapChart():
    allL = [t for c in cars for t in laps_of(counted[c])[1:]]
    lo = min(allL) - 0.2; hi = statistics.median(allL) + 1.5
    xs = lambda k: ML + (k - 2) * (W - ML - MR) / max(1, NL - 2); ys = lambda v: MT + (hi - min(v, hi)) * (H - MT - MB) / (hi - lo)
    g = [f'<svg viewBox="0 0 {W} {H}" class="chart" role="img" aria-label="Lap time by lap">']
    v = math.floor(lo)
    while v <= hi:
        g.append(f'<line x1="{ML}" x2="{W-MR}" y1="{ys(v):.1f}" y2="{ys(v):.1f}" class="grid"/><text x="{ML-8}" y="{ys(v)+4:.1f}" class="tick" text-anchor="end">{v:.0f}</text>'); v += 0.5
    for k in range(2, NL + 1): g.append(f'<text x="{xs(k):.1f}" y="{H-10}" class="tick" text-anchor="middle">{k}</text>')
    for c in cars:
        lt = laps_of(counted[c])
        pts = [(xs(k + 1), ys(t)) for k, t in enumerate(lt) if k >= 1]
        d = ' '.join(f"{'M' if i == 0 else 'L'}{x:.1f},{y:.1f}" for i, (x, y) in enumerate(pts))
        g.append(f'<path d="{d}" fill="none" stroke="{LIVERY[c]}" stroke-width="2" stroke-linejoin="round" class="ln"/>')
        for k, t in enumerate(lt):
            if k >= 1 and t > hi: g.append(f'<text x="{xs(k+1):.1f}" y="{MT+4}" class="tick" text-anchor="middle" fill="{LIVERY[c]}">▲{t:.0f}</text>')
    g.append('</svg>'); return '\n'.join(g)
def barChart(vals, label, unit, maxv=None):
    maxv = maxv or max(v for _, v in vals) * 1.15
    Hh = 28 * len(vals) + 10
    g = [f'<svg viewBox="0 0 {W} {Hh}" class="chart" role="img" aria-label="{label}">']
    for i, (c, v) in enumerate(vals):
        y = i * 28 + 6; w = (W - 300) * v / maxv
        g.append(f'<text x="150" y="{y+15}" class="lbl" text-anchor="end">{nice(c)}</text><rect x="160" y="{y}" width="{w:.1f}" height="20" rx="4" fill="{LIVERY[c]}"/><text x="{160+w+8:.1f}" y="{y+15}" class="val">{v:.3f}{unit}</text>')
    g.append('</svg>'); return '\n'.join(g)
def errChart():
    errs = []
    for x in truth:
        h = [c for c in counted[x['car']] if abs(c['t'] - x['t']) < 0.15]
        if h: errs.append((h[0]['t'] - x['t']) * 1000)
    bins = list(range(-12, 13, 2)); cnt = [sum(1 for e in errs if b <= e < b + 2) for b in bins]
    mx = max(cnt) or 1; Hh = 160
    g = [f'<svg viewBox="0 0 {W} {Hh+30}" class="chart" role="img" aria-label="Timing error histogram">']
    bw = (W - 80) / len(bins)
    for k, (b, n) in enumerate(zip(bins, cnt)):
        h = (Hh - 10) * n / mx; x = 40 + k * bw
        g.append(f'<rect x="{x+3:.1f}" y="{Hh-h:.1f}" width="{bw-6:.1f}" height="{h:.1f}" rx="3" fill="#E10600"/><text x="{x+bw/2:.1f}" y="{Hh+16}" class="tick" text-anchor="middle">{b:+d}</text>')
        if n: g.append(f'<text x="{x+bw/2:.1f}" y="{Hh-h-4:.1f}" class="val" text-anchor="middle">{n}</text>')
    g.append('</svg>'); return '\n'.join(g)

# ---------------------------------------------------------------- lap table
head = ''.join(f'<th>{sw(c)}<span class="h">{nice(c).split()[0]}<br>{nice(c).split()[1]}</span></th>' for c in cars)
body = []
for k in range(NL):
    tds = []
    for c in cars:
        e = laps_of(counted[c])[k] if len(counted[c]) > k else None
        t = laps_of(tru[c])[k] if len(tru[c]) > k else None
        flag = '' if e is None or t is None else ('bad' if abs(e - t) > 0.05 else '')
        if e is None and t is not None: flag = 'miss'
        why = counted[c][k]['why'] if len(counted[c]) > k else ''
        best = e is not None and k >= 1 and e == min(laps_of(counted[c])[1:])
        cls = ' '.join(x for x in ['mono', flag, 'best' if best else '', 'tim' if why not in ('ENGINE', '') else ''] if x)
        tds.append(f'<td class="{cls}" title="{why.replace("_", " ").lower()}">{fmt(e)}</td>')
    body.append(f'<tr><td class="pos">{k+1}</td>{"".join(tds)}</tr>')
rowsE = ''.join(f'<tr><td class="pos">{r["pos"]}</td><td>{sw(r["car"])}{nice(r["car"])}<span class="kd">{KIND[r["car"]]}</span></td><td>{r["laps"]}</td><td class="mono">{fmt(r["total"])}</td><td class="mono">{r["gap"]}</td><td class="mono best">{fmt(r["best"])}</td><td class="mono">{fmt(r["avg"])}</td><td class="mono">±{r["sd"]:.2f}</td></tr>' for r in stE)
cmp = ''.join(f'<tr><td class="pos">{i+1}</td><td>{sw(a["car"])}{nice(a["car"])}</td><td>{sw(b["car"])}{nice(b["car"])}</td><td>{"✓" if a["car"] == b["car"] else "✗"}</td></tr>' for i, (a, b) in enumerate(zip(stE, stT)))
dec = {}
for o in outs: dec[o['why']] = dec.get(o['why'], 0) + 1
diagRows = ''.join(f'<tr><td class="mono">{fmt(x["t"])}</td><td>{sw(x["car"])}{nice(x["car"])}</td><td>{x["tag"]}</td><td>{"✓ counted" if any(abs(c["t"]-x["t"])<0.15 for c in counted[x["car"]]) else "✗ missed"}</td><td class="mono">{(min((c["t"]-x["t"] for c in counted[x["car"]]), key=abs)*1000):+.1f} ms</td></tr>' for x in truth if x['tag'].startswith('diag'))
gate = open(f'{B}/out/gate.txt').read() if os.path.exists(f'{B}/out/gate.txt') else ''
seedRows = ''
import glob
TABLE = os.environ.get('TABLE', f'{B}/out/fit_race10_*.json')
for f in sorted(glob.glob(TABLE), key=lambda x: int(re.sub(r'\D', '', os.path.basename(x).split('_')[-1]) or 0)):
    Q = json.load(open(f))['summary']; sd = f.split('_')[-1].split('.')[0]
    seedRows += f'<tr{" class=on" if sd == seed else ""}><td class="pos">{sd}</td><td class="mono">{Q["engineRight"]} / {Q["crossings"]}</td><td class="mono"><b>{Q["right"]} / {Q["crossings"]}</b></td><td class="mono">{Q["missed"]}</td><td class="mono">{Q["extra"]}</td><td class="mono">{Q["abreast"]}</td><td class="mono">{Q["diag"]}</td><td class="mono">{Q["timingMeanMs"]:.1f} ms</td></tr>'

right = S['right']; N = S['crossings']
html = f'''<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AnyLap · 10 cars · {NL} laps · simulated race v3</title>
<style>
:root{{--bg:#0B0B0D;--s1:#141418;--s2:#1C1C22;--s3:#26262E;--stroke:#2E2E38;--text:#F3F3F5;--dim:#A3A3AE;--faint:#6B6B78;--red:#E10600;--good:#2CC26B;--warn:#F2B01E;--bad:#FF4D4D;--or:#FF8A00;--font:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--text);font-family:var(--font);-webkit-font-smoothing:antialiased}}
.wrap{{max-width:1180px;margin:0 auto;padding:20px 16px 90px}}
header{{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:18px}}
h1{{font-size:34px;font-weight:900;letter-spacing:-0.03em;margin:0;line-height:1}}h1 small{{display:block;font-size:13px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:var(--red);margin-bottom:8px}}
.pills{{display:flex;gap:8px;flex-wrap:wrap}}.pill{{background:var(--red);color:#fff;border:0;border-radius:999px;padding:9px 16px;font-weight:800;font-size:13px;box-shadow:0 0 18px rgba(225,6,0,.35)}}.pill.ghost{{background:transparent;border:1px solid var(--stroke);color:var(--dim);box-shadow:none}}
.hero{{display:grid;grid-template-columns:auto 1fr;gap:18px;align-items:center;background:var(--s1);border:1px solid var(--stroke);border-radius:20px;padding:18px 22px;margin-bottom:14px}}
.hero .n{{font-size:64px;font-weight:900;letter-spacing:-0.04em;line-height:1}}.hero .n small{{font-size:24px;color:var(--dim)}}.hero p{{margin:4px 0;color:var(--dim);font-size:14px;line-height:1.5}}
.tabs{{display:flex;gap:6px;background:var(--s1);padding:6px;border-radius:999px;border:1px solid var(--stroke);margin:14px 0 18px;overflow:auto}}.tab{{flex:1;text-align:center;padding:10px 14px;border-radius:999px;font-weight:800;font-size:14px;color:var(--dim);cursor:pointer;white-space:nowrap;min-height:44px}}.tab.on{{background:var(--red);color:#fff;box-shadow:0 0 18px rgba(225,6,0,.4)}}
.panel{{display:none}}.panel.on{{display:block}}
.card0{{background:var(--s1);border:1px solid var(--stroke);border-radius:16px;padding:16px;margin-bottom:14px}}
h2{{font-size:13px;font-weight:800;letter-spacing:.12em;text-transform:uppercase;color:var(--dim);margin:0 0 10px}}
.kpis{{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-bottom:14px}}.kpi{{background:var(--s1);border:1px solid var(--stroke);border-radius:16px;padding:14px 16px}}.kpi b{{display:block;font-size:30px;font-weight:900;letter-spacing:-0.03em;line-height:1.05}}.kpi span{{font-size:12px;color:var(--dim);font-weight:700;letter-spacing:.06em;text-transform:uppercase}}.kpi.good b{{color:var(--good)}}.kpi.bad b{{color:var(--bad)}}.kpi.warn b{{color:var(--warn)}}
table{{border-collapse:collapse;width:100%;font-size:14px}}th{{text-align:left;font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--dim);padding:8px;border-bottom:1px solid var(--stroke);vertical-align:bottom}}td{{padding:9px 8px;border-bottom:1px solid #1d1d23}}
.pos{{font-weight:900;color:var(--dim);width:36px}}.mono{{font-variant-numeric:tabular-nums}}.best{{color:#C68CFF;font-weight:800}}.kd{{margin-left:8px;font-size:11px;color:var(--faint);font-weight:700;letter-spacing:.06em;text-transform:uppercase}}
.sw{{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:8px;vertical-align:-1px;border:1px solid rgba(255,255,255,.18)}}
.laps{{overflow:auto}}.laps td{{padding:6px;font-size:12.5px;text-align:right}}.laps th .h{{display:inline-block;font-size:10px;line-height:1.1}}.laps td.pos{{text-align:left}}
td.bad{{background:rgba(242,176,30,.14);color:var(--warn)}}td.miss{{background:rgba(255,77,77,.18);color:var(--bad)}}td.tim::after{{content:" ⏱";color:var(--or)}}
.chart{{width:100%;height:auto;display:block}}.grid{{stroke:#24242c;stroke-width:1}}.tick{{fill:var(--faint);font-size:11px;font-family:var(--font)}}.lbl{{fill:var(--text);font-size:12px;font-weight:700;font-family:var(--font)}}.val{{fill:var(--dim);font-size:12px;font-family:var(--font)}}
.ln{{transition:opacity .2s}}.chart:hover .ln{{opacity:.25}}.chart .ln:hover{{opacity:1;stroke-width:4}}
.legend{{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:12px;color:var(--dim);margin-top:8px}}
.cards{{display:grid;grid-template-columns:repeat(auto-fill,minmax(270px,1fr));gap:12px}}.card{{background:var(--s1);border:1px solid var(--stroke);border-radius:16px;overflow:hidden;position:relative}}
.pc{{font-size:13px;color:var(--text);margin:4px 0 2px;line-height:1.35}}.card.timing{{border-color:rgba(255,138,0,.45)}}.card.warn{{border-color:rgba(242,176,30,.45)}}.card.bad{{border-color:rgba(255,77,77,.6)}}
.pic{{position:relative;background:#000}}.pic img{{width:100%;display:block;aspect-ratio:1;object-fit:cover}}.noimg{{aspect-ratio:1;display:grid;place-items:center;color:var(--faint)}}
.hit{{position:absolute;background:transparent;border:0;cursor:pointer;border-radius:6px}}.hit:hover{{background:rgba(255,138,0,.15)}}
.cb{{padding:10px 12px 12px}}.kl{{font-size:13px;color:var(--dim)}}.kl b{{color:var(--text);letter-spacing:.02em}}
.sc{{font-size:12.5px;color:var(--dim);margin-top:6px;cursor:pointer;text-decoration:underline dotted;text-underline-offset:3px}}
.ex{{display:none;font-size:12.5px;line-height:1.5;color:var(--dim);background:var(--s2);border-radius:10px;padding:10px;margin-top:8px}}.ex.on{{display:block}}
.tru{{margin-top:8px;font-size:12.5px;cursor:pointer}}.tru span{{color:var(--or);font-weight:800}}.tru em{{display:none;font-style:normal;color:var(--dim)}}.tru.on span{{display:none}}.tru.on em{{display:inline}}
.ok{{color:var(--good)}}.no{{color:var(--bad)}}
.menu{{position:fixed;z-index:10;background:var(--s2);border:1px solid var(--stroke);border-radius:14px;padding:6px;box-shadow:0 12px 40px rgba(0,0,0,.6);max-height:320px;overflow:auto;min-width:200px}}.menu div{{padding:9px 12px;border-radius:9px;cursor:pointer;font-size:14px;display:flex;justify-content:space-between;gap:12px}}.menu div:hover{{background:var(--s3)}}.menu i{{color:var(--faint);font-style:normal}}
.toast{{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:var(--s2);border:1px solid var(--stroke);border-radius:999px;padding:10px 18px;font-size:13px;display:none}}
.note{{font-size:13px;color:var(--dim);line-height:1.55}}.cmp{{display:grid;grid-template-columns:1fr 1fr;gap:12px}}@media(max-width:700px){{.cmp{{grid-template-columns:1fr}}h1{{font-size:26px}}.hero{{grid-template-columns:1fr}}}}
.lights{{display:flex;gap:14px;justify-content:center;padding:18px 0}}.lt{{width:44px;height:44px;border-radius:50%;background:#2a0b0b;box-shadow:inset 0 0 0 3px #3a1414}}.lt.on{{background:var(--red);box-shadow:0 0 22px rgba(225,6,0,.8)}}
.pfg{{display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:12px}}.pf{{margin:0}}.pf img{{width:100%;border-radius:12px;display:block}}.pfr{{margin-top:10px}}.big{{font-size:20px;font-weight:900}}
tr.on td{{background:rgba(225,6,0,.12)}}pre{{white-space:pre-wrap;font-size:12px;color:var(--dim);background:var(--s2);border-radius:10px;padding:10px}}
</style></head><body><div class="wrap">
<header><h1><small>AnyLap · Simulated race v3 · overhead · 60 fps · nose timing · calibration-picture fit</small>10 cars · {NL} laps</h1>
<div class="pills"><span class="pill ghost">{S['durationS']/60:.1f} min</span><span class="pill ghost">{N} crossings · {S['diag']} diagonal · {S['abreast']} abreast</span><span class="pill">Check ({nCheck})</span></div></header>
<div class="hero"><div class="n">{right}<small> / {N}</small></div><div><b style="font-size:18px">crossings counted for the right car, at the right place</b>
<p>Camera alone: {S['engineRight']} / {N} ({S['engineMissed']} missed, {S['engineExtra']} wrong or extra). With lap-timing identity: {right} / {N} ({S['missed']} missed, {S['extra']} wrong or extra). Final order {'matches' if sameOrder else 'differs from'} the truth.</p></div></div>
<div class="kpis">
<div class="kpi {'good' if S['missed']==0 else 'bad'}"><b>{S['missed']}</b><span>missed laps</span></div>
<div class="kpi {'good' if S['extra']==0 else 'bad'}"><b>{S['extra']}</b><span>wrong / extra laps</span></div>
<div class="kpi"><b>{S['timingMeanMs']:.1f}<span style="font-size:14px"> ms</span></b><span>nose timing error (max {S['timingMaxMs']:.0f})</span></div>
<div class="kpi {'good' if sameOrder else 'bad'}"><b>{'✓' if sameOrder else '✗'}</b><span>final order</span></div>
<div class="kpi"><b>{sum(v for k, v in dec.items() if k not in ('ENGINE','IGNORED'))}</b><span>decided by lap timing</span></div>
</div>
<div class="tabs"><div class="tab on" onclick="show(0,this)">Standings</div><div class="tab" onclick="show(1,this)">Laps</div><div class="tab" onclick="show(2,this)">Graphs</div><div class="tab" onclick="show(3,this)">Check ({nCheck})</div><div class="tab" onclick="show(4,this)">Start &amp; finish</div><div class="tab" onclick="show(5,this)">Setup</div></div>
<section class="panel on"><div class="card0"><h2>Eight races, same cars, different seeds</h2><table><thead><tr><th>Seed</th><th>Camera alone</th><th>With lap timing</th><th>Missed</th><th>Wrong / extra</th><th>Abreast</th><th>Diagonal</th><th>Nose timing</th></tr></thead><tbody>{seedRows}</tbody></table>
<p class="note">The race on this page is seed {seed} (highlighted). Right = the right car, within 0.15 s, <b>and</b> in the right place across the track (a swapped pair counts as wrong). Seeds 9–12 were run fresh after the picture fit was written.</p></div>
<div class="card0"><h2>Result</h2><table><thead><tr><th>P</th><th>Car</th><th>Laps</th><th>Time</th><th>Gap</th><th>Best</th><th>Avg</th><th>Spread</th></tr></thead><tbody>{rowsE}</tbody></table></div>
<div class="card0"><h2>App vs truth</h2><table><thead><tr><th>P</th><th>App</th><th>Truth</th><th></th></tr></thead><tbody>{cmp}</tbody></table></div></section>
<section class="panel"><div class="card0"><h2>Lap times</h2><div class="laps"><table><thead><tr><th>Lap</th>{head}</tr></thead><tbody>{''.join(body)}</tbody></table></div>
<p class="note">Lap 1 runs from lights-out (standing start). <span style="color:#C68CFF">purple</span> = personal best · ⏱ = named, renamed or added by lap timing · <span style="color:var(--warn)">amber</span> = differs from truth by &gt; 50 ms · <span style="color:var(--bad)">red</span> = lap missing.</p></div></section>
<section class="panel"><div class="card0"><h2>Position by lap</h2>{posChart()}<div class="legend">{''.join(f'<span>{sw(c)}{nice(c)}</span>' for c in cars)}</div></div>
<div class="card0"><h2>Lap time by lap (s), laps 2–{NL}, spins ▲</h2>{lapChart()}</div>
<div class="card0"><h2>Timing error vs truth (ms, nose) · {S['timingMeanMs']:.1f} ms mean</h2>{errChart()}</div>
<div class="card0"><h2>Consistency · lap-time spread (s)</h2>{barChart(sorted([(r['car'], r['sd']) for r in stE], key=lambda x: x[1]), 'Consistency', ' s')}</div>
<div class="card0"><h2>Best lap (s)</h2>{barChart(sorted([(r['car'], r['best']) for r in stE if r['best']], key=lambda x: x[1]), 'Best lap', ' s', maxv=max(r['best'] for r in stE if r['best'])*1.06)}</div></section>
<section class="panel"><div class="card0"><h2>Check · {nCheck} ({', '.join(f"{k} {v}" for k, v in kinds.items())})</h2>
<p class="note">Picture first: every car in the picture is outlined with <b>its own calibration picture</b> (true size, turned to its heading, white edge = nose), named as the race counted it; name tags sit where no car is (never over one), the arrow shows the race direction. <b>Tap a box</b> to change that car (ranked by match, or “Not a car”); <b>tap the score line</b> for what the numbers mean; <b>Show truth</b> reveals what really happened in the simulation. Cards marked “(truth)” are things only the simulation knows.</p>
<div class="cards">{''.join(cards)}</div></div></section>
<section class="panel"><div class="card0"><h2>Start · lights out and away we go</h2><div class="lights" id="lights">{''.join('<div class="lt"></div>' for _ in range(5))}</div>
<p class="note" style="text-align:center"><button class="pill" onclick="startLights()">Replay start</button></p>
<p class="note">Standing start from the grid behind the line; the race clock starts at lights-out (5 reds, one per second, then a random hold). Grid: {', '.join(nice(c) for c in R['grid'])}. Lap 1 arrivals were bunched within ~2 s.</p></div>
<div class="card0"><h2>Photo finishes · {len(finishes)} pairs within 60 ms</h2><p class="note">Finish counts when <b>the front touches the line</b> (Settings → Timing; “the whole car has passed” is the other choice).</p><div class="pfg">{''.join(ghostCards) or '<p class="note">none</p>'}</div></div></section>
<section class="panel"><div class="card0"><h2>How this race was made</h2><p class="note">The ten new minis (GT3, LMP, Formula, rally, touring, drift, Group 5, supercar, stock car, hypercar; 15–19.5 cm, wear and stickers), each rendered alone from 1.8 m straight above (480×270, Cycles, 1/120 s blur): two calibration passes, four lanes, a fast pass and two diagonal crossings (+25°, −38°) per car. Passes composited onto the empty track with sensor noise: standing start from lights-out, {NL} laps, per-car pace 17.6–18.6 s ±0.3 s, spins, a forced photo finish (1 frame apart, abreast, lap 4) and a three-wide arrival (lap 7). Diagonal crossings only when no other car is near (cars can't pass through each other in a composite).</p>
<p class="note">Engine (this branch): nose timing (blob edge ahead of the centroid, minus the mask rim and half the blur streak), crossing box + velocity on every event; <b>LapIdentity</b> after the camera: conflicts, unnamed crossings, check-line rescues and missing laps decided by each car's lap rhythm. Decisions: {', '.join(f"{k.replace('_',' ').lower()} {v}" for k, v in dec.items())}.</p>
<p class="note">Pace: {', '.join(f"{nice(c)} {R['pace'][c]:.2f}" for c in cars)} s. Spins: {', '.join(R['incidents']).replace('_',' ') or 'none'}.</p></div>
<div class="card0"><h2>Diagonal crossings in the race</h2><table><thead><tr><th>Time</th><th>Car</th><th>Angle</th><th>Counted</th><th>Timing</th></tr></thead><tbody>{diagRows}</tbody></table></div>
<div class="card0"><h2>Gate test · each pass alone</h2><pre>{gate}</pre></div></section>
</div><div class="toast" id="toast"></div>
<script>
function show(i,el){{document.querySelectorAll('.panel').forEach((p,k)=>p.classList.toggle('on',k===i));document.querySelectorAll('.tab').forEach(t=>t.classList.toggle('on',t===el));}}
let menu=null;function pick(ev,k,i){{ev.stopPropagation();if(menu)menu.remove();const c=document.getElementById('c'+k);const o=JSON.parse(c.dataset.opts);const seen=new Set();menu=document.createElement('div');menu.className='menu';
for(const [n,s] of o){{if(seen.has(n))continue;seen.add(n);const d=document.createElement('div');d.innerHTML=n+(s?'<i>'+s.toFixed(2)+'</i>':'');d.onclick=()=>{{toast(n+' – lap table rebuilt');menu.remove();menu=null;}};menu.appendChild(d);}}
const d=document.createElement('div');d.innerHTML='Not a car';d.onclick=()=>{{toast('Removed – lap table rebuilt');menu.remove();menu=null;}};menu.appendChild(d);
document.body.appendChild(menu);menu.style.left=Math.min(ev.clientX,innerWidth-220)+'px';menu.style.top=Math.min(ev.clientY,innerHeight-330)+'px';}}
document.addEventListener('click',()=>{{if(menu){{menu.remove();menu=null;}}}});
function toast(t){{const e=document.getElementById('toast');e.textContent=t;e.style.display='block';clearTimeout(e._t);e._t=setTimeout(()=>e.style.display='none',1800);}}
function startLights(){{const L=[...document.querySelectorAll('.lt')];L.forEach(l=>l.classList.remove('on'));L.forEach((l,i)=>setTimeout(()=>l.classList.add('on'),1000*(i+1)));setTimeout(()=>L.forEach(l=>l.classList.remove('on')),5000+200+Math.random()*2800);}}
</script></body></html>'''
open(f'{B}/out/race10_{seed}.html', 'w').write(html)
print('wrote', f'{B}/out/race10_{seed}.html', len(html) // 1024, 'kB', '· cards', nCheck, kinds)
