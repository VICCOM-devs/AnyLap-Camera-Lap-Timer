"""Results page for a training run: open report.html in any browser.

    python tools/ml/train/report.py C:/anylap-ml/runs/v1 C:/anylap-ml/sprites [--photos C:/anylap-ml/grounds]

Reads <run>/log.jsonl and <run>/best.pt, runs a bigger final evaluation on the held-out cars (cars the network
never trained on), and writes <run>/report.html: headline numbers, learning curve, accuracy by camera angle,
car size, motion blur and touching cars, look-alikes, intruders (cat, rat, shoe …) called a car, and example
pictures of right and wrong answers. Everything is inside the one HTML file (pictures embedded).
"""
import argparse, base64, html, io, json, math, os, sys
import numpy as np
import torch
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(HERE, "..", "synth"))
from model import CarNet
from compose import SpriteDataset, INTRUDERS
from evaluate import evaluate, embed

def png64(arr, scale=2):
    im = Image.fromarray(arr)
    if scale != 1: im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    b = io.BytesIO(); im.save(b, "PNG"); return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()

def bar_table(title, d, note="", lower_better=False):
    if not d: return ""
    def colour(v):
        g = (100 - v) if lower_better else v
        return '#2bb673' if g >= 90 else '#e0a400' if g >= 70 else '#e34'
    rows = "".join(f"<tr><td>{html.escape(str(k))}</td><td class=num>{v:.1f} %</td><td><div class=bar style='width:{max(0, min(100, v)) * 2:.0f}px;background:{colour(v)}'></div></td></tr>" for k, v in d.items())
    return f"<h3>{title}</h3>{f'<p class=note>{note}</p>' if note else ''}<table>{rows}</table>"

def curve_svg(log):
    pts = [(m["step"], m["accuracy"], m.get("intruder_called_car_mean")) for m in log if "step" in m]
    if len(pts) < 2: return ""
    W, H, P = 640, 220, 34
    xmax = max(p[0] for p in pts)
    X = lambda s: P + (W - 2 * P) * s / xmax; Y = lambda v: H - P - (H - 2 * P) * v / 100
    acc = " ".join(f"{X(s):.1f},{Y(a):.1f}" for s, a, _ in pts)
    intr = " ".join(f"{X(s):.1f},{Y(i):.1f}" for s, _, i in pts if i is not None)
    grid = "".join(f"<line x1={P} x2={W - P} y1={Y(v)} y2={Y(v)} stroke='#333'/><text x=4 y={Y(v) + 4} fill='#888' font-size=11>{v}%</text>" for v in (0, 25, 50, 75, 100))
    return (f"<svg width={W} height={H} style='background:#141516;border-radius:10px'>{grid}"
            f"<polyline points='{acc}' fill=none stroke='#2bb673' stroke-width=2.5/>"
            f"<polyline points='{intr}' fill=none stroke='#e34' stroke-width=2 stroke-dasharray='5 4'/>"
            f"<text x={W - P - 150} y=18 fill='#2bb673' font-size=12>naming accuracy</text>"
            f"<text x={W - P - 150} y=34 fill='#e34' font-size=12>intruders called a car</text>"
            f"<text x={W - P} y={H - 8} fill='#888' font-size=11 text-anchor=end>training step {xmax}</text></svg>")

@torch.no_grad()
def examples(net, ds, dev, rng, grid=8, k_cal=6, n_query=4):
    """One race: calibration picture per car, then queries with the network's answer."""
    cars = list(rng.choice(ds.num_classes, size=min(grid, ds.num_classes), replace=False))
    elev = int(rng.choice([30, 50, 70, 90])); scale = math.exp(rng.uniform(math.log(1.5), math.log(5)))
    cal_imgs = {}; protos = []
    for c in cars:
        imgs = [ds.sample(rng, identity=int(c), elev=elev, px_per_cm=scale * rng.uniform(0.8, 1.25))[0] for _ in range(k_cal)]
        cal_imgs[c] = imgs[0]
        e, _, _ = embed(net, torch.from_numpy(np.stack([i.transpose(2, 0, 1) for i in imgs])).float() / 255, dev)
        protos.append(torch.nn.functional.normalize(e.mean(0), dim=0))
    protos = torch.stack(protos)
    out = []
    for ci, c in enumerate(cars):
        for _ in range(n_query):
            img, y = ds.sample(rng, identity=int(c))
            e, cnt, _ = embed(net, torch.from_numpy(img.transpose(2, 0, 1)[None]).float() / 255, dev)
            sim = (e @ protos.T)[0]; p = int(sim.argmax())
            out.append(dict(img=img, truth=cal_imgs[c], pred=cal_imgs[cars[p]], ok=p == ci, sim=float(sim[p]), y=y, count=int(cnt[0])))
    intr = []
    for k in INTRUDERS:
        img, _ = ds.sample(rng, force_intruder=k)
        _, cnt, _ = embed(net, torch.from_numpy(img.transpose(2, 0, 1)[None]).float() / 255, dev)
        intr.append(dict(kind=k, img=img, car=int(cnt[0]) >= 1))
    return out, intr

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run"); ap.add_argument("sprites"); ap.add_argument("--photos", default=None)
    ap.add_argument("--races", type=int, default=40); ap.add_argument("--out", default=None)
    a = ap.parse_args()
    ck = torch.load(os.path.join(a.run, "best.pt"), map_location="cpu", weights_only=False)
    log = [json.loads(l) for l in open(os.path.join(a.run, "log.jsonl")) if l.strip()]
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    net = CarNet(width=ck.get("width", 1.0)); net.load_state_dict(ck["model"]); net.to(dev).eval()
    held = ck.get("held_out"); size = ck.get("size", 64)
    ds = SpriteDataset(a.sprites, ids=held, size=size, photo_dir=a.photos)
    print("final evaluation on", len(held), "held-out cars …", flush=True)
    m = evaluate(net, ds, dev, races=a.races, n_intruder=60)
    la = evaluate(net, ds, dev, races=max(8, a.races // 3), lookalike=True, n_intruder=0)["accuracy"]
    rng = np.random.default_rng(7)
    ex, intr = examples(net, ds, dev, rng)
    n_train = len(ck.get("train_ids") or [])

    def ex_html(items):
        cells = []
        for e in items:
            cls = "ok" if e["ok"] else "bad"
            cells.append(f"<div class='ex {cls}'><img src='{png64(e['img'])}'><div class=arrow>{'✓' if e['ok'] else '✗'}</div>"
                         f"<img src='{png64(e['pred'])}' title='the car it chose (its calibration picture)'>"
                         f"{'' if e['ok'] else f'<img src=' + chr(39) + png64(e['truth']) + chr(39) + ' title=right car>'}"
                         f"<div class=cap>{int(e['y']['elev'])}° · {e['y']['car_len_px']:.0f} px · sim {e['sim']:.2f}</div></div>")
        return "".join(cells)
    intr_html = "".join(f"<div class='ex {'bad' if i['car'] else 'ok'}'><img src='{png64(i['img'])}'><div class=cap>{i['kind']}: {'called a car ✗' if i['car'] else 'not a car ✓'}</div></div>" for i in intr)

    body = f"""
<h1>AnyLap car recognition · {html.escape(os.path.basename(os.path.normpath(a.run)))}</h1>
<p class=note>Trained on {n_train} generated cars, tested on {len(held)} cars it has never seen – the way the app works:
a few calibration pictures of each car in a race, then every crossing named by the nearest signature.
Step {ck.get('step')} of training. Pictures are composed: random ground, light, angle, size, blur, touching cars, intruders.</p>
<div class=tiles>
 <div class=tile><b>{m['accuracy']:.1f} %</b><span>crossings named right<br>(8-car races, unseen cars)</span></div>
 <div class=tile><b>{la:.1f} %</b><span>look-alike races<br>(all cars one colour family)</span></div>
 <div class=tile><b>{m['intruder_called_car_mean']:.1f} %</b><span>intruders called a car<br>(cat, rat, shoe … – lower is better)</span></div>
 <div class=tile><b>{m['count_accuracy']:.1f} %</b><span>"one / two / no car" right</span></div>
 <div class=tile><b>{m['nose_err_px_median']:.1f} px</b><span>nose position error<br>(median, 64 px crop)</span></div>
</div>
<h2>Learning curve</h2>{curve_svg(log)}
<div class=cols>
<div>{bar_table("By camera angle (elevation)", m['by_elevation'], "90° = straight down, 20–30° = low tripod at the barrier")}</div>
<div>{bar_table("By car size in the picture", m['by_size'], "car length in pixels at the line")}</div>
<div>{bar_table("By motion blur", m['by_blur'], "L = car length")}</div>
<div>{bar_table("Touching / close cars", m['by_neighbours'], "nb0 = alone, nb1 = one car touching, nb2 = two")}</div>
<div>{bar_table("Intruders called a car (lower is better)", m['intruder_called_car'], "each crossing at the line is an intruder, no car", lower_better=True)}</div>
</div>
<h2>Examples – one race</h2><p class=note>Left: what crossed the line · then the car the network named (its calibration picture) · if wrong, the right car. Caption: camera angle, car length in px, similarity.</p>
<div class=grid>{ex_html(ex)}</div>
<h2>Intruders</h2><div class=grid>{intr_html}</div>
<h2>Raw numbers</h2><pre>{html.escape(json.dumps(dict(final=m, lookalike_accuracy=la), indent=1))}</pre>
"""
    page = f"""<!DOCTYPE html><html><head><meta charset=utf-8><title>AnyLap car recognition report</title><style>
body{{background:#0d0e0f;color:#e8e8ea;font:14px/1.45 system-ui,Segoe UI,sans-serif;max-width:1100px;margin:28px auto;padding:0 18px}}
h1{{font-size:24px}} h2{{margin-top:34px;font-size:18px}} h3{{font-size:14px;margin:16px 0 6px}} .note{{color:#9a9a9f}}
.tiles{{display:flex;gap:12px;flex-wrap:wrap}} .tile{{background:#17181a;border-radius:14px;padding:14px 18px;min-width:170px}}
.tile b{{display:block;font-size:28px;color:#fff}} .tile span{{color:#9a9a9f;font-size:12px}}
.cols{{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:8px 28px}}
table{{border-collapse:collapse}} td{{padding:3px 8px 3px 0}} td.num{{text-align:right;font-variant-numeric:tabular-nums}} .bar{{height:10px;border-radius:5px}}
.grid{{display:flex;flex-wrap:wrap;gap:10px}} .ex{{background:#17181a;border-radius:10px;padding:6px;display:flex;flex-wrap:wrap;align-items:center;gap:4px;border:2px solid transparent;max-width:420px}}
.ex.bad{{border-color:#e34}} .ex img{{width:96px;height:96px;border-radius:6px;image-rendering:pixelated}} .arrow{{font-size:20px;padding:0 4px}}
.cap{{width:100%;color:#9a9a9f;font-size:11px}} pre{{background:#141516;padding:12px;border-radius:10px;overflow:auto;font-size:11px}}
</style></head><body>{body}</body></html>"""
    out = a.out or os.path.join(a.run, "report.html")
    open(out, "w", encoding="utf-8").write(page)
    print("wrote", out)

if __name__ == "__main__":
    main()
