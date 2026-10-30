"""How good is a signature model at the job the app has: given a few calibration views of each car in a race
(photos / passes), name every crossing by nearest signature.

Protocol (per race): pick `grid` held-out cars (never seen in training), take `k_cal` random views of each as
the calibration (sprites composed like any other sample, at the calibration's own random angle/scale/light)
-> prototype = mean signature; then `n_query` composed crossings per car at random conditions -> nearest
prototype. Reported: accuracy overall and by elevation bucket, scale bucket, blur, neighbours; plus the count
head's accuracy and the nose error. Look-alike stress: races built from cars of one colour family.
"""
import json, math
import numpy as np
import torch

def _batch(ds, rng, items):
    xs = []
    for identity, elev, s in items:
        img, y = ds.sample(rng, identity=identity, elev=elev, px_per_cm=s)
        xs.append((img, y))
    x = torch.from_numpy(np.stack([i.transpose(2, 0, 1) for i, _ in xs])).float() / 255
    return x, [y for _, y in xs]

@torch.no_grad()
def embed(net, x, device, bs=512):
    out = []; cnt = []; nose = []
    for i in range(0, len(x), bs):
        e, c, n = net(x[i:i + bs].to(device)); out.append(e.cpu()); cnt.append(c.argmax(1).cpu()); nose.append(n.cpu())
    return torch.cat(out), torch.cat(cnt), torch.cat(nose)

def evaluate(net, ds, device, races=20, grid=8, k_cal=6, n_query=12, seed=123, lookalike=False):
    """ds: a SpriteDataset over the held-out cars. Returns a dict of metrics."""
    net.eval()
    rng = np.random.default_rng(seed)
    cars = list(range(ds.num_classes))
    if grid > len(cars): grid = len(cars)
    right = 0; total = 0
    by_elev = {}; by_scale = {}; by_blur = {}; by_nb = {}
    count_right = 0; count_total = 0; nose_err = []
    families = None
    if lookalike:
        # group by hue family of the base colour, race within a family
        def fam(c):
            r, g, b = ds.lib.specs[ds.lib.cars[c]]["base"]; mx, mn = max(r, g, b), min(r, g, b)
            if mx - mn < 0.08: return "grey" if mx > 0.15 else "black"
            h = (math.atan2(math.sqrt(3) * (g - b), 2 * r - g - b) / (2 * math.pi)) % 1.0
            return f"h{int(h * 8)}"
        families = {}
        for c in cars: families.setdefault(fam(c), []).append(c)
        families = [v for v in families.values() if len(v) >= 2]
    for r in range(races):
        if families:
            f = families[rng.integers(len(families))]
            ids = list(rng.choice(f, size=min(grid, len(f)), replace=False))
        else:
            ids = list(rng.choice(cars, size=grid, replace=False))
        # calibration: a few views each, one random rig (elevation) per race like a real calibration on the race rig
        cal_elev = int(rng.choice([30, 50, 70, 90]))
        cal_scale = math.exp(rng.uniform(math.log(1.5), math.log(6.0)))
        items = [(i, cal_elev, cal_scale * rng.uniform(0.8, 1.25)) for i in ids for _ in range(k_cal)]
        x, ys = _batch(ds, rng, items)
        e, _, _ = embed(net, x, device)
        protos = torch.stack([e[j * k_cal:(j + 1) * k_cal].mean(0) for j in range(len(ids))])
        protos = torch.nn.functional.normalize(protos, dim=1)
        # queries at random conditions
        q_items = [(i, None, None) for i in ids for _ in range(n_query)]
        xq, yq = _batch(ds, rng, q_items)
        eq, cq, nq = embed(net, xq, device)
        sim = eq @ protos.T
        pred = sim.argmax(1).numpy()
        truth = np.repeat(np.arange(len(ids)), n_query)
        for qi, (p, t, y) in enumerate(zip(pred, truth, yq)):
            ok = int(p == t); right += ok; total += 1
            eb = f"e{int(y['elev'])}"; by_elev.setdefault(eb, [0, 0]); by_elev[eb][0] += ok; by_elev[eb][1] += 1
            L = y["car_len_px"]; sb = "<20px" if L < 20 else "<40px" if L < 40 else "<80px" if L < 80 else ">=80px"
            by_scale.setdefault(sb, [0, 0]); by_scale[sb][0] += ok; by_scale[sb][1] += 1
            bb = "sharp" if y["blur_px"] < 2 else "blur<0.2L" if y["blur_px"] < 0.2 * L else "blur>=0.2L"
            by_blur.setdefault(bb, [0, 0]); by_blur[bb][0] += ok; by_blur[bb][1] += 1
            nb = f"nb{y['neighbours']}"; by_nb.setdefault(nb, [0, 0]); by_nb[nb][0] += ok; by_nb[nb][1] += 1
            count_right += int(int(cq[qi]) == y["count"]); count_total += 1
            nose_err.append(math.hypot(float(nq[qi, 0]) - y["nose_dx"], float(nq[qi, 1]) - y["nose_dy"]) * ds.size)
    pct = lambda d: {k: round(100 * v[0] / max(1, v[1]), 1) for k, v in sorted(d.items())}
    return dict(accuracy=round(100 * right / max(1, total), 2), n=total, by_elevation=pct(by_elev), by_size=pct(by_scale),
                by_blur=pct(by_blur), by_neighbours=pct(by_nb), count_accuracy=round(100 * count_right / max(1, count_total), 1),
                nose_err_px_median=round(float(np.median(nose_err)), 2) if nose_err else None, lookalike=lookalike)

if __name__ == "__main__":
    import argparse, os, sys
    here = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, here); sys.path.insert(0, os.path.join(here, "..", "synth"))
    from compose import SpriteDataset
    from model import CarNet
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint"); ap.add_argument("sprites"); ap.add_argument("--ids", help="file with held-out car ids, one per line")
    ap.add_argument("--races", type=int, default=20); ap.add_argument("--lookalike", action="store_true"); ap.add_argument("--photos")
    a = ap.parse_args()
    ck = torch.load(a.checkpoint, map_location="cpu", weights_only=False)
    net = CarNet(width=ck.get("width", 1.0)); net.load_state_dict(ck["model"])
    ids = [l.strip() for l in open(a.ids)] if a.ids else ck.get("held_out")
    ds = SpriteDataset(a.sprites, ids=ids, size=ck.get("size", 64), photo_dir=a.photos)
    dev = "cuda" if torch.cuda.is_available() else "cpu"; net.to(dev)
    print(json.dumps(evaluate(net, ds, dev, races=a.races, lookalike=a.lookalike), indent=1))
