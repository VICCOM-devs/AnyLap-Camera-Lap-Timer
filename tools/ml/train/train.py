"""Train the car-signature network on composed sprites (and, when given, real crops).

    python tools/ml/train/train.py --sprites D:/anylap-ml/sprites --out D:/anylap-ml/runs/v1 --steps 60000
      [--photos D:/anylap-ml/grounds] [--real D:/anylap-ml/real] [--size 64] [--batch 256] [--workers 6]

The last 10 % of the library's cars are held out (never a training class) and used by evaluate.py as "cars nobody
has seen": that number – naming accuracy on unseen cars from a few calibration views – is the one that matters.
Checkpoints: <out>/last.pt, <out>/best.pt (by held-out accuracy), metrics in <out>/log.jsonl.
"""
import argparse, json, math, os, sys, time
import numpy as np
import torch
import torch.nn.functional as F

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(HERE, "..", "synth"))
from model import CarNet, ArcFace, count_params
from compose import SpriteDataset, torch_dataset, Library
from evaluate import evaluate

def split_ids(root, held_frac=0.1, seed=0):
    cars = Library(root).cars
    rng = np.random.default_rng(seed); perm = list(rng.permutation(cars))
    n_held = max(2, int(len(perm) * held_frac))
    return sorted(perm[n_held:]), sorted(perm[:n_held])

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sprites", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--photos", default=None, help="folder of ground photos (asphalt, carpet, dirt …) mixed in 50/50")
    ap.add_argument("--real", default=None, help="folder from tools/ml/data/real_crops.py (extra identities + real eval)")
    ap.add_argument("--steps", type=int, default=60000); ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=2e-3); ap.add_argument("--size", type=int, default=64)
    ap.add_argument("--width", type=float, default=1.0); ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--eval_every", type=int, default=2000); ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--resume", default=None)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(a.seed)
    train_ids, held = split_ids(a.sprites, seed=a.seed)
    print(f"{len(train_ids)} training cars, {len(held)} held out, device {dev}", flush=True)
    ds = torch_dataset(a.sprites, train_ids, a.size, a.photos, seed=a.seed)
    n_classes = len(train_ids)
    real = None
    if a.real:
        from real_crops import RealCrops
        real = RealCrops(a.real, size=a.size)
        n_classes += real.num_classes
        print(f"real crops: {real.num_classes} identities, {len(real)} crops", flush=True)
    loader = torch.utils.data.DataLoader(ds, batch_size=a.batch, num_workers=a.workers, persistent_workers=a.workers > 0, prefetch_factor=4 if a.workers > 0 else None)
    held_ds = SpriteDataset(a.sprites, held, a.size, a.photos)

    net = CarNet(width=a.width).to(dev)
    head = ArcFace(net.emb[-1].out_features, n_classes).to(dev)
    print("params", count_params(net), flush=True)
    params = list(net.parameters()) + list(head.parameters())
    opt = torch.optim.AdamW(params, lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=a.steps, pct_start=0.05, final_div_factor=50)
    scaler = torch.amp.GradScaler(enabled=dev == "cuda")
    step = 0; best = -1.0
    if a.resume:
        ck = torch.load(a.resume, map_location=dev, weights_only=False)
        net.load_state_dict(ck["model"]); head.load_state_dict(ck["head"]); opt.load_state_dict(ck["opt"]); step = ck["step"]; best = ck.get("best", -1)
        for _ in range(step): sched.step()
    log = open(os.path.join(a.out, "log.jsonl"), "a")

    def save(name, metrics=None):
        torch.save(dict(model=net.state_dict(), head=head.state_dict(), opt=opt.state_dict(), step=step, best=best, width=a.width, size=a.size,
                        held_out=held, train_ids=train_ids, metrics=metrics), os.path.join(a.out, name))

    net.train(); t0 = time.time(); run = dict(loss=0.0, arc=0.0, cnt=0.0, nose=0.0, n=0)
    for x, y, cnt, nose, _ in loader:
        if step >= a.steps: break
        if real is not None and real.num_classes > 0:
            # mix a quarter of real crops into each batch (their ids follow the sprite ids)
            k = a.batch // 4
            rx, ry, rc = real.batch(k)
            x = torch.cat([x[: a.batch - k], rx]); y = torch.cat([y[: a.batch - k], ry + len(train_ids)]); cnt = torch.cat([cnt[: a.batch - k], rc]); nose = torch.cat([nose[: a.batch - k], torch.zeros(k, 2)])
            nose_mask = torch.cat([torch.ones(a.batch - k), torch.zeros(k)])
        else:
            nose_mask = torch.ones(len(x))
        x, y, cnt, nose, nose_mask = x.to(dev, non_blocking=True), y.to(dev), cnt.to(dev), nose.to(dev), nose_mask.to(dev)
        with torch.autocast(device_type=dev, dtype=torch.float16, enabled=dev == "cuda"):
            e, c, n = net(x)
            has_id = y >= 0
            arc = F.cross_entropy(head(e[has_id], y[has_id]), y[has_id]) if has_id.any() else e.sum() * 0
            lc = F.cross_entropy(c, cnt)
            ln = ((n - nose) ** 2).sum(1) * (cnt > 0).float() * nose_mask
            ln = ln.sum() / max(1.0, ((cnt > 0).float() * nose_mask).sum())
            loss = arc + 0.5 * lc + 2.0 * ln
        opt.zero_grad(set_to_none=True)
        scaler.scale(loss).backward(); scaler.unscale_(opt); torch.nn.utils.clip_grad_norm_(params, 5.0)
        scaler.step(opt); scaler.update(); sched.step(); step += 1
        run["loss"] += loss.item(); run["arc"] += arc.item(); run["cnt"] += lc.item(); run["nose"] += ln.item(); run["n"] += 1
        if step % 100 == 0:
            rate = 100 * a.batch / (time.time() - t0); t0 = time.time()
            print(f"step {step} loss {run['loss']/run['n']:.3f} arc {run['arc']/run['n']:.3f} count {run['cnt']/run['n']:.3f} nose {run['nose']/run['n']:.4f} lr {sched.get_last_lr()[0]:.2e} {rate:.0f} img/s", flush=True)
            run = dict(loss=0.0, arc=0.0, cnt=0.0, nose=0.0, n=0)
        if step % a.eval_every == 0 or step == a.steps:
            m = json.loads(json.dumps(evaluate(net, held_ds, dev, races=12), default=float))
            m["step"] = step; m["time"] = time.time()
            if real is not None and real.has_eval: m["real"] = real.evaluate(net, dev)
            print("EVAL", json.dumps(m), flush=True); log.write(json.dumps(m) + "\n"); log.flush()
            save("last.pt", m)
            if m["accuracy"] > best: best = m["accuracy"]; save("best.pt", m)
            net.train()
    save("last.pt")
    print("done; best held-out accuracy", best)

if __name__ == "__main__":
    main()
