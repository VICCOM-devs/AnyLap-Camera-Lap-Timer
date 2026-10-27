"""race50_<seed>.json (+ crops) -> out/race50.html: the race as the app would show it.
Standings, 50x10 lap table, position chart, lap-time chart, consistency, timing error, and the Check tray."""
import json, sys, base64, io, statistics, os
from PIL import Image
B = '/home/claude/blend'
seed = sys.argv[1] if len(sys.argv) > 1 else '1'
R = json.load(open(f'{B}/out/race50_{seed}.json'))
cars = R['cars']
LIVERY = {  # body colour per car (identity colour; matches the Blender livery)
    'Red_7': '#d8231c', 'White_12': '#e8e8ea', 'White_21': '#e8e8ea', 'Blue_3': '#2a6bd8', 'Yellow_44': '#e9c21a',
    'Green_9': '#2aa04a', 'Silver_5': '#a9adb5', 'Black_16': '#4a4a52', 'Orange_23': '#ef7a1b', 'Pink_8': '#e85aa0'}
ACC = {'White_21': '#2a6bd8', 'White_12': '#d8231c'}   # stripe/roof colour for the two white cars (dashed line + swatch ring)
def nice(c): return c.replace('_', ' ')
def b64png(ppm, scale=3):
    im = Image.open(ppm).convert('RGB'); im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    b = io.BytesIO(); im.save(b, 'PNG'); return 'data:image/png;base64,' + base64.b64encode(b.getvalue()).decode()

# ---------------------------------------------------------------- laps
def laps_of(cross):  # list of crossings t -> list of lap times
    return [cross[i]['t'] - cross[i - 1]['t'] for i in range(1, len(cross))]
tru = {c: R['trueLaps'][c] for c in cars}
# app-side lap sanity (as RaceEngine should do): a lap shorter than 60 % of the car's median lap cannot be a lap
# of this track – the crossing is parked in the Check tray instead of counted
eng = {}; short = []
for c in cars:
    xs = R['engineLaps'][c]; med = statistics.median(laps_of(xs)) if len(xs) > 2 else 0
    kept = []
    for x in xs:
        if kept and med and x['t'] - kept[-1]['t'] < 0.6 * med:
            short.append(dict(kind='SHORT LAP', t=x['t'], car=c, truth=None, cands='', note=f"{x['t']-kept[-1]['t']:.2f} s after the same car's last crossing (median lap {med:.1f} s) – not counted", crop=None)); continue
        kept.append(x)
    eng[c] = kept
reviews = R['reviews'] + short
# the engine's own review card for the same crossing, if any, gets the short-lap note too
for sh in short:
    for r in R['reviews']:
        if abs(r['t'] - sh['t']) < 0.02 and r['car'] == sh['car']: sh['crop'] = r['crop']; sh['truth'] = r['truth']; sh['cands'] = r['cands']

NL = max(len(v) for v in tru.values()) - 1
# position after each lap: order by time of completing lap k
def positions(src):
    pos = {c: [] for c in cars}
    for k in range(1, NL + 1):
        done = sorted([(src[c][k]['t'], c) for c in cars if len(src[c]) > k])
        order = [c for _, c in done]
        for c in cars: pos[c].append(order.index(c) + 1 if c in order else None)
    return pos
posE = positions(eng); posT = positions(tru)
def standings(src):
    rows = []
    for c in cars:
        lt = laps_of(src[c]); n = len(lt)
        rows.append(dict(car=c, laps=n, total=src[c][-1]['t'] - src[c][0]['t'] if n else 0, best=min(lt) if lt else None, last=lt[-1] if lt else None,
                         avg=statistics.mean(lt) if lt else None, sd=statistics.pstdev(lt) if n > 1 else 0, finish=src[c][-1]['t'] if src[c] else 1e9))
    rows.sort(key=lambda r: (-r['laps'], r['finish']))
    for i, r in enumerate(rows): r['pos'] = i + 1
    lead = rows[0]
    for r in rows:
        r['gap'] = 'Leader' if r is lead else (f"+{r['finish'] - lead['finish']:.2f}" if r['laps'] == lead['laps'] else f"+{lead['laps'] - r['laps']} lap{'s' if lead['laps'] - r['laps'] > 1 else ''}")
    return rows
stE = standings(eng); stT = standings(tru)
S = dict(R['summary'])
S['false_laps'] = S['extra'] + S['wrong'] - len([x for x in short if x['truth'] is None or x['truth'] != x['car']])
# the lap table (engine), with flags where the engine's lap differs from truth by > 50 ms (miss/extra → whole lap shifts)
def fmt(t): return '—' if t is None else (f"{int(t // 60)}:{t % 60:06.3f}" if t >= 60 else f"{t:.3f}")
def tableRows():
    out = []
    for k in range(1, NL + 1):
        cells = []
        for c in cars:
            e = eng[c][k]['t'] - eng[c][k - 1]['t'] if len(eng[c]) > k else None
            t = tru[c][k]['t'] - tru[c][k - 1]['t'] if len(tru[c]) > k else None
            flag = '' if e is None or t is None else ('bad' if abs(e - t) > 0.05 else '')
            if e is None and t is not None: flag = 'miss'
            best = e is not None and e == min(laps_of(eng[c]))
            split = eng[c][k].get('split') if len(eng[c]) > k else False
            cells.append((e, flag, best, split, eng[c][k].get('conf', 1) if len(eng[c]) > k else 1))
        out.append(cells)
    return out
T = tableRows()

# ---------------------------------------------------------------- svg charts
W, H = 920, 360; ML, MR, MT, MB = 44, 110, 20, 34
def posChart():
    xs = lambda k: ML + (k - 1) * (W - ML - MR) / max(1, NL - 1)
    ys = lambda p: MT + (p - 1) * (H - MT - MB) / (len(cars) - 1)
    g = [f'<svg viewBox="0 0 {W} {H}" class="chart" role="img" aria-label="Position by lap">']
    for p in range(1, len(cars) + 1): g.append(f'<line x1="{ML}" x2="{W-MR}" y1="{ys(p)}" y2="{ys(p)}" class="grid"/><text x="{ML-8}" y="{ys(p)+4}" class="tick" text-anchor="end">P{p}</text>')
    for k in range(1, NL + 1, 5): g.append(f'<text x="{xs(k)}" y="{H-10}" class="tick" text-anchor="middle">{k}</text>')
    g.append(f'<text x="{(ML+W-MR)/2}" y="{H-0}" class="tick" text-anchor="middle" opacity="0">lap</text>')
    for c in cars:
        pts = [(xs(k + 1), ys(p)) for k, p in enumerate(posE[c]) if p]
        d = ' '.join(f"{'M' if i == 0 else 'L'}{x:.1f},{y:.1f}" for i, (x, y) in enumerate(pts))
        dash = ' stroke-dasharray="6 4"' if c in ACC else ''
        g.append(f'<path d="{d}" fill="none" stroke="{LIVERY[c]}" stroke-width="2.5" stroke-linejoin="round"{dash} data-car="{c}" class="ln"/>')
        if pts: g.append(f'<circle cx="{pts[-1][0]:.1f}" cy="{pts[-1][1]:.1f}" r="4" fill="{LIVERY[c]}" stroke="#0B0B0D" stroke-width="2"/><text x="{W-MR+10}" y="{pts[-1][1]+4:.1f}" class="lbl">{nice(c)}</text>')
    g.append('</svg>'); return '\n'.join(g)
def lapChart():
    allL = [t for c in cars for t in laps_of(eng[c])]
    lo = min(allL) - 0.2; hi = statistics.median(allL) + 2.0
    cw, ch, ml, mr, mt, mb = 440, 120, 40, 10, 14, 18
    cols = 2; rows = (len(cars) + 1) // 2
    Wt = cols * cw + 20; Ht = rows * ch + 10
    xs = lambda k, cx: cx + ml + (k - 1) * (cw - ml - mr) / max(1, NL - 1)
    ys = lambda v, cy: cy + mt + (hi - min(v, hi)) * (ch - mt - mb) / (hi - lo)
    g = [f'<svg viewBox="0 0 {Wt} {Ht}" class="chart" role="img" aria-label="Lap time by lap, one panel per car">']
    for i, c in enumerate(cars):
        cx = (i % cols) * (cw + 20); cy = (i // cols) * ch
        lt = laps_of(eng[c])
        for v in (lo + 0.2, lo + 1.2, lo + 2.2):
            g.append(f'<line x1="{cx+ml}" x2="{cx+cw-mr}" y1="{ys(v,cy):.1f}" y2="{ys(v,cy):.1f}" class="grid"/><text x="{cx+ml-6}" y="{ys(v,cy)+4:.1f}" class="tick" text-anchor="end">{v:.1f}</text>')
        pts = [(xs(k + 1, cx), ys(t, cy)) for k, t in enumerate(lt)]
        d = ' '.join(f"{'M' if j == 0 else 'L'}{x:.1f},{y:.1f}" for j, (x, y) in enumerate(pts))
        g.append(f'<path d="{d}" fill="none" stroke="{LIVERY[c]}" stroke-width="2" stroke-linejoin="round"/>')
        for k, t in enumerate(lt):
            if t > hi: g.append(f'<text x="{xs(k+1,cx):.1f}" y="{cy+mt+8}" class="tick" text-anchor="middle" fill="{LIVERY[c]}">▲{t:.0f}</text>')
        if i >= len(cars) - cols:
            for k in range(1, NL + 1, 10): g.append(f'<text x="{xs(k,cx):.1f}" y="{cy+ch-4}" class="tick" text-anchor="middle">{k}</text>')
        g.append(f'<text x="{cx+ml+4}" y="{cy+mt-2}" class="lbl">{nice(c)} · best {min(lt):.3f}</text>')
    g.append('</svg>'); return '\n'.join(g)
def barChart(vals, label, unit, maxv=None):
    maxv = maxv or max(v for _, v in vals) * 1.15
    Hh = 28 * len(vals) + 10
    g = [f'<svg viewBox="0 0 {W} {Hh}" class="chart" role="img" aria-label="{label}">']
    for i, (c, v) in enumerate(vals):
        y = i * 28 + 6; w = (W - 300) * v / maxv
        g.append(f'<text x="150" y="{y+15}" class="lbl" text-anchor="end">{nice(c)}</text><rect x="160" y="{y}" width="{w:.1f}" height="20" rx="4" fill="{LIVERY[c]}"/><text x="{160+w+8:.1f}" y="{y+15}" class="val">{v:.3f}{unit}</text>')
    g.append('</svg>'); return '\n'.join(g)
def errHist():
    errs = []
    for c in cars:
        for a, b in zip(eng[c], tru[c]): pass
    return ''

# ---------------------------------------------------------------- html
def swatch(c): return f'<span class="sw" style="background:{LIVERY[c]};{"box-shadow:inset 0 0 0 3px "+ACC[c] if c in ACC else ""}"></span>'
rowsE = ''.join(f'<tr><td class="pos">{r["pos"]}</td><td>{swatch(r["car"])}{nice(r["car"])}</td><td>{r["laps"]}</td><td class="mono">{fmt(r["total"])}</td><td class="mono">{r["gap"]}</td><td class="mono best">{fmt(r["best"])}</td><td class="mono">{fmt(r["avg"])}</td><td class="mono">±{r["sd"]:.2f}</td></tr>' for r in stE)
truthOrder = [r['car'] for r in stT]; engOrder = [r['car'] for r in stE]
sameOrder = truthOrder == engOrder
head = ''.join(f'<th>{swatch(c)}<span class="h">{nice(c).split()[0]}<br>{nice(c).split()[1]}</span></th>' for c in cars)
body = []
for k, cells in enumerate(T):
    tds = []
    for (e, flag, best, split, conf) in cells:
        cls = ' '.join(x for x in ['mono', flag, 'best' if best else '', 'split' if split else '', 'low' if conf < 0.12 else ''] if x)
        tds.append(f'<td class="{cls}" title="{"split from a touching car" if split else ""}{" · low margin" if conf < 0.12 else ""}">{fmt(e)}</td>')
    body.append(f'<tr><td class="pos">{k+1}</td>{"".join(tds)}</tr>')
kinds = {}
for r in reviews: kinds[r['kind']] = kinds.get(r['kind'], 0) + 1
cards = []
for i, r in enumerate(reviews):
    img = b64png(r['crop']) if r.get('crop') and os.path.exists(r['crop']) else ''
    who = nice(r['car']) if r['car'] else 'Unknown'
    cands = r['cands'].replace('_', ' ')
    cards.append(f'''<div class="card k-{r['kind'].split()[0].replace('?','').lower()}">
  <img src="{img}" alt="crop at the line, t={r['t']:.2f} s">
  <div class="cb"><div class="kind">{r['kind']}</div><div class="t">{r['t']:.2f} s · {r['note']}</div>
  <div class="who">{swatch(r['car']) if r['car'] else ''}{who}</div><div class="cands">{cands}</div>
  <div class="btns"><button class="pill" onclick="verdict(this,'ok')">Looks right</button><button class="pill ghost" onclick="verdict(this,'no')">Wrong</button></div>
  <div class="truth">Truth: {nice(r['truth']) if r['truth'] else 'no car crossed'}</div></div></div>''')
spins = '<br>'.join(R['incidents']).replace('_', ' ')
html = f'''<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AnyLap · 10 cars · {NL} laps · simulated race</title>
<style>
:root{{--bg:#0B0B0D;--s1:#141418;--s2:#1C1C22;--s3:#26262E;--stroke:#2E2E38;--text:#F3F3F5;--dim:#A3A3AE;--faint:#6B6B78;--red:#E10600;--good:#2CC26B;--warn:#F2B01E;--bad:#FF4D4D;--font:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--text);font-family:var(--font);-webkit-font-smoothing:antialiased}}
.wrap{{max-width:1180px;margin:0 auto;padding:20px 16px 90px}}
header{{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:18px}}
h1{{font-size:34px;font-weight:900;letter-spacing:-0.03em;margin:0;line-height:1}}h1 small{{display:block;font-size:13px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:var(--red);margin-bottom:8px}}
.pills{{display:flex;gap:8px;flex-wrap:wrap}}.pill{{background:var(--red);color:#fff;border:0;border-radius:999px;padding:9px 16px;font-weight:800;font-size:13px;letter-spacing:.02em;cursor:pointer;box-shadow:0 0 18px rgba(225,6,0,.35)}}.pill.ghost{{background:transparent;border:1px solid var(--stroke);color:var(--dim);box-shadow:none}}
.tabs{{display:flex;gap:6px;background:var(--s1);padding:6px;border-radius:999px;border:1px solid var(--stroke);margin:14px 0 18px;overflow:auto}}.tab{{flex:1;text-align:center;padding:10px 14px;border-radius:999px;font-weight:800;font-size:14px;color:var(--dim);cursor:pointer;white-space:nowrap;min-height:44px}}.tab.on{{background:var(--red);color:#fff;box-shadow:0 0 18px rgba(225,6,0,.4)}}
.panel{{display:none}}.panel.on{{display:block}}
.card0{{background:var(--s1);border:1px solid var(--stroke);border-radius:16px;padding:16px;margin-bottom:14px}}
h2{{font-size:13px;font-weight:800;letter-spacing:.12em;text-transform:uppercase;color:var(--dim);margin:0 0 10px}}
.kpis{{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-bottom:14px}}.kpi{{background:var(--s1);border:1px solid var(--stroke);border-radius:16px;padding:14px 16px}}.kpi b{{display:block;font-size:30px;font-weight:900;letter-spacing:-0.03em;line-height:1.05}}.kpi span{{font-size:12px;color:var(--dim);font-weight:700;letter-spacing:.06em;text-transform:uppercase}}.kpi.good b{{color:var(--good)}}.kpi.bad b{{color:var(--bad)}}.kpi.warn b{{color:var(--warn)}}
table{{border-collapse:collapse;width:100%;font-size:14px}}th{{text-align:left;font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--dim);padding:8px 8px;border-bottom:1px solid var(--stroke);vertical-align:bottom}}td{{padding:9px 8px;border-bottom:1px solid #1d1d23}}
.pos{{font-weight:900;color:var(--dim);width:36px}}.mono{{font-variant-numeric:tabular-nums;font-feature-settings:"tnum"}}.best{{color:#C68CFF;font-weight:800}}
.sw{{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:8px;vertical-align:-1px;border:1px solid rgba(255,255,255,.18)}}
.laps{{overflow:auto;max-height:70vh}}.laps th{{position:sticky;top:0;background:var(--s1);z-index:1}}.laps td{{padding:5px 6px;font-size:12.5px;text-align:right}}.laps th .h{{display:inline-block;font-size:10px;line-height:1.1}}.laps td.pos{{text-align:left}}
td.bad{{background:rgba(242,176,30,.14);color:var(--warn)}}td.miss{{background:rgba(255,77,77,.18);color:var(--bad)}}td.split::after{{content:" ◫";color:var(--dim)}}td.low::after{{content:" ?";color:var(--warn)}}
.chart{{width:100%;height:auto;display:block}}.grid{{stroke:#24242c;stroke-width:1}}.tick{{fill:var(--faint);font-size:11px;font-family:var(--font)}}.lbl{{fill:var(--text);font-size:12px;font-weight:700;font-family:var(--font)}}.val{{fill:var(--dim);font-size:12px;font-family:var(--font)}}
.ln{{transition:opacity .2s}}.chart:hover .ln{{opacity:.25}}.chart .ln:hover{{opacity:1;stroke-width:4}}
.legend{{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:12px;color:var(--dim);margin-top:8px}}
.cards{{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px}}.card{{background:var(--s1);border:1px solid var(--stroke);border-radius:16px;overflow:hidden}}.card img{{width:100%;aspect-ratio:1;object-fit:cover;image-rendering:pixelated;display:block;background:#000}}.cb{{padding:12px 14px 14px}}.kind{{font-size:11px;font-weight:900;letter-spacing:.12em;text-transform:uppercase;color:var(--warn)}}.k-wrong .kind,.k-extra .kind,.k-missed .kind{{color:var(--bad)}}.k-short .kind{{color:var(--good)}}.k-split .kind,.k-close .kind{{color:var(--dim)}}.t{{font-size:12px;color:var(--dim);margin:3px 0 8px}}.who{{font-size:18px;font-weight:900;letter-spacing:-0.02em}}.cands{{font-size:12px;color:var(--faint);margin:4px 0 10px;font-variant-numeric:tabular-nums}}.btns{{display:flex;gap:8px}}.btns .pill{{flex:1;min-height:40px;padding:8px 10px}}.truth{{display:none;margin-top:10px;font-size:12px;color:var(--dim);border-top:1px solid var(--stroke);padding-top:8px}}.card.done .truth{{display:block}}.card.done .btns{{display:none}}
.note{{font-size:13px;color:var(--dim);line-height:1.5}}.foot{{display:flex;justify-content:center;padding:12px}}.foot div{{background:var(--s2);border:1px solid var(--stroke);border-radius:999px;padding:10px 18px;font-size:12px;color:var(--dim);box-shadow:0 8px 30px rgba(0,0,0,.5)}}
.cmp{{display:grid;grid-template-columns:1fr 1fr;gap:12px}}@media(max-width:700px){{.cmp{{grid-template-columns:1fr}}h1{{font-size:26px}}}}
.ok{{color:var(--good);font-weight:800}}.no{{color:var(--bad);font-weight:800}}
</style></head><body><div class="wrap">
<header><h1><small>AnyLap · Simulated race · overhead camera · 60 fps</small>10 cars · {NL} laps</h1>
<div class="pills"><span class="pill ghost">{S['durationS']/60:.1f} min</span><span class="pill ghost">{S['crossings']} crossings</span><span class="pill">Check ({len(reviews)})</span></div></header>
<div class="kpis">
<div class="kpi {'good' if S['missed']==0 else 'bad'}"><b>{S['missed']}</b><span>missed crossings</span></div>
<div class="kpi {'good' if S['extra']==0 else 'bad'}"><b>{S['extra']}</b><span>false crossings</span></div>
<div class="kpi {'good' if len(short)>0 or S['extra']==0 else 'warn'}"><b>{len(short)}</b><span>caught by lap sanity</span></div>
<div class="kpi {'good' if S['wrong']==0 else 'bad'}"><b>{S['wrong']}</b><span>wrong car</span></div>
<div class="kpi {'good' if S['unknown']==0 else 'warn'}"><b>{S['unknown']}</b><span>unknown car</span></div>
<div class="kpi"><b>{S['timingMeanMs']:.1f}<span style="font-size:14px"> ms</span></b><span>mean timing error (max {S['timingMaxMs']:.0f})</span></div>
<div class="kpi {'good' if sameOrder else 'bad'}"><b>{'✓' if sameOrder else '✗'}</b><span>final order {'matches' if sameOrder else 'differs from'} truth</span></div>
</div>
<div class="tabs"><div class="tab on" onclick="show(0,this)">Standings</div><div class="tab" onclick="show(1,this)">Laps</div><div class="tab" onclick="show(2,this)">Graphs</div><div class="tab" onclick="show(3,this)">Check ({len(reviews)})</div><div class="tab" onclick="show(4,this)">Setup</div></div>
<section class="panel on"><div class="card0"><h2>Result · as counted by the engine</h2><table><thead><tr><th>P</th><th>Car</th><th>Laps</th><th>Total</th><th>Gap</th><th>Best</th><th>Avg</th><th>Spread</th></tr></thead><tbody>{rowsE}</tbody></table></div>
<div class="card0"><h2>Against the truth (what actually happened in the simulation)</h2><div class="cmp"><div><table><thead><tr><th>P</th><th>Engine</th></tr></thead><tbody>{''.join(f'<tr><td class="pos">{i+1}</td><td>{swatch(c)}{nice(c)}</td></tr>' for i,c in enumerate(engOrder))}</tbody></table></div><div><table><thead><tr><th>P</th><th>Truth</th></tr></thead><tbody>{''.join(f'<tr><td class="pos">{i+1}</td><td class="{"ok" if engOrder[i]==c else "no"}">{swatch(c)}{nice(c)}</td></tr>' for i,c in enumerate(truthOrder))}</tbody></table></div></div>
<p class="note">Right car on {S['right']} of {S['crossings']} crossings. Spins written into the schedule: {len(R['incidents'])}.</p></div></section>
<section class="panel"><div class="card0"><h2>Lap times · engine</h2><div class="laps"><table><thead><tr><th>Lap</th>{head}</tr></thead><tbody>{''.join(body)}</tbody></table></div>
<p class="note"><span style="color:#C68CFF">purple</span> = personal best · <span style="color:var(--warn)">amber</span> = differs from truth by &gt; 50 ms · <span style="color:var(--bad)">red</span> = lap missing · ◫ = separated from a touching car · ? = low margin</p></div></section>
<section class="panel"><div class="card0"><h2>Position by lap</h2>{posChart()}<div class="legend">{''.join(f'<span>{swatch(c)}{nice(c)}</span>' for c in cars)} · dashed = the two white cars</div></div><div class="card0"><h2>Lap time by lap (s) · one panel per car, spins marked ▲</h2>{lapChart()}</div>
<div class="card0"><h2>Consistency · lap-time spread (s, lower is better)</h2>{barChart(sorted([(r['car'], r['sd']) for r in stE], key=lambda x: x[1]), 'Consistency', ' s')}</div>
<div class="card0"><h2>Best lap (s)</h2>{barChart(sorted([(r['car'], r['best']) for r in stE], key=lambda x: x[1]), 'Best lap', ' s', maxv=max(r['best'] for r in stE)*1.08)}</div>
</section>
<section class="panel"><div class="card0"><h2>Check · {len(reviews)} crossings the engine was not sure about ({', '.join(f"{k} {v}" for k,v in kinds.items())})</h2>
<p class="note">Each card is the overhead frame at the moment the car hit the line (3× zoom). Tap a verdict to reveal what really happened. In the app these would be fixed with one tap and the lap table would update.</p>
<div class="cards">{''.join(cards)}</div></div></section>
<section class="panel"><div class="card0"><h2>How this race was made</h2><p class="note">Ten Blender mini-z-sized cars (16–19 cm, different heights, bodies, liveries, a metallic one, a black one, two white look-alikes told apart only by their stripe/roof colour), each rendered crossing the line alone from a camera 1.8 m straight above (480×270 engine resolution, 8 Cycles samples, 1/120 s shutter, 60 fps). The race is composited from those passes: each car has its own pace (17.4–18.6 s laps), ±0.3 s lap noise, 4 % chance of a 2–7 s spin per lap, a rolling start 0.45 s apart. Cars arriving within 0.12 s of each other take neighbouring lanes 10 cm apart, so they cross abreast with about 1 cm between bodies. Every crossing's true time and car are known, which is what the Check cards and the amber/red cells are scored against.</p>
<p class="note">Pace: {', '.join(f"{nice(c)} {R['pace'][c]:.2f} s" for c in cars)}.<br>Spins: {spins or 'none'}</p></div></section>
</div><div class="foot"><div>Seed {seed} · engine HEAD split-v2 · this is a simulation, not footage</div></div>
<script>
function show(i,el){{document.querySelectorAll('.panel').forEach((p,k)=>p.classList.toggle('on',k===i));document.querySelectorAll('.tab').forEach(t=>t.classList.toggle('on',t===el));}}
function verdict(b,v){{const c=b.closest('.card');c.classList.add('done');c.querySelector('.truth').insertAdjacentHTML('afterbegin','<span class="'+(v==='ok'?'ok':'no')+'">You said: '+(v==='ok'?'looks right':'wrong')+'</span> · ');}}
</script></body></html>'''
open(f'{B}/out/race50_{seed}.html', 'w').write(html)
print('wrote', f'{B}/out/race50_{seed}.html', len(html) // 1024, 'kB')
