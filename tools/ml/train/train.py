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
from model import CarNet, ArcFace, count_params, build_model, BACKBONES
from compose import SpriteDataset, torch_dataset, Library
from evaluate import evaluate

def gpu_augment(x, nose, gen):
    """Cheap per-sample variations on the GPU so one composed picture can be used several times: 90-degree turns
    and mirror (nose label turned with it), brightness, contrast, colour balance, noise."""
    B = x.shape[0]
    k = torch.randint(0, 4, (B,), device=x.device, generator=gen); fl = torch.rand(B, device=x.device, generator=gen) < 0.5
    out = x.clone(); nz = nose.clone()
    for r in range(4):
        idx = (k == r).nonzero().flatten()
        if len(idx) == 0 or r == 0: continue
        out[idx] = torch.rot90(x[idx], r, dims=(2, 3))
        dx, dy = nose[idx, 0], nose[idx, 1]
        for _ in range(r): dx, dy = dy, -dx          # image rot90 (counter-clockwise in array terms)
        nz[idx, 0], nz[idx, 1] = dx, dy
    idx = fl.nonzero().flatten()
    if len(idx): out[idx] = torch.flip(out[idx], dims=(3,)); nz[idx, 0] = -nz[idx, 0]
    b = 1 + 0.25 * (torch.rand(B, 1, 1, 1, device=x.device, generator=gen) - 0.5)
    c = 1 + 0.3 * (torch.rand(B, 1, 1, 1, device=x.device, generator=gen) - 0.5)
    w = 1 + 0.1 * (torch.rand(B, 3, 1, 1, device=x.device, generator=gen) - 0.5)
    m = out.mean(dim=(1, 2, 3), keepdim=True)
    out = ((out - m) * c + m) * b * w + 0.02 * torch.randn(out.shape, device=x.device, generator=gen)
    return out.clamp(0, 1), nz

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
    ap.add_argument("--lr", type=float, default=2e-3); ap.add_argument("--size", type=int, default=None)
    ap.add_argument("--width", type=float, default=1.0); ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--eval_every", type=int, default=2000); ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--resume", default=None)
    ap.add_argument("--backbone", choices=BACKBONES, default="carnet", help="carnet (from scratch, 64 px) or an ImageNet-pretrained phone network")
    ap.add_argument("--no_pretrained", action="store_true", help="pretrained backbones: start from random weights (tests only)")
    ap.add_argument("--reuse", type=int, default=1, help="use each composed batch this many times with GPU variations (the CPU makes pictures slower than the GPU learns)")
    ap.add_argument("--freeze_steps", type=int, default=-1, help="pretrained backbones: train only the heads for this many steps first (default 5 %% of the run)")
    ap.add_argument("--backbone_lr", type=float, default=0.3, help="pretrained backbones: their LR = this x --lr after unfreezing")
    ap.add_argument("--margin", type=float, default=0.3); ap.add_argument("--scale", type=float, default=30.0)
    ap.add_argument("--margin_warmup", type=float, default=0.0, help="share of the run with no margin, then eased in over the same again (0 = full margin from the start; tested Oct 31: warm-up 0.15 learned slower)")
    ap.add_argument("--hours", type=float, default=0, help="train for this long instead of --steps (schedule, checks, margin and unfreeze follow the clock)")
    ap.add_argument("--held", type=float, default=0.1, help="share of cars held out as never-seen test cars")
    a = ap.parse_args()
    if a.size is None: a.size = 64 if a.backbone == "carnet" else 96
    os.makedirs(a.out, exist_ok=True)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(a.seed)
    train_ids, held = split_ids(a.sprites, held_frac=a.held, seed=a.seed)
    print(f"{len(train_ids)} training cars, {len(held)} held out (never trained on), device {dev}" + (f" ({torch.cuda.get_device_name(0)})" if dev == "cuda" else " - NO GPU, this will be slow"), flush=True)
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
    # the same test on cars it trains on: high here + low on unseen = memorising; low on both = not learning
    rs = np.random.default_rng(1); train_probe = sorted(rs.choice(train_ids, size=min(len(train_ids), max(8, len(held))), replace=False).tolist())
    train_ds = SpriteDataset(a.sprites, train_probe, a.size, a.photos)

    net = build_model(a.backbone, a.width, pretrained=not a.no_pretrained).to(dev)
    head = ArcFace(net.emb[-1].out_features, n_classes, s=a.scale, m=0.0).to(dev)
    print(f"model {a.backbone} ({'pretrained on real photos' if a.backbone != 'carnet' and not a.no_pretrained else 'from scratch'}), {count_params(net)} params, input {a.size} px, each batch used {a.reuse}x", flush=True)
    pretrained = a.backbone != "carnet"
    head_params = net.heads() + list(head.parameters())
    body_params = [p for p in net.parameters() if all(p is not q for q in head_params)]
    params = body_params + head_params
    lrs = [a.lr * (a.backbone_lr if pretrained else 1.0), a.lr]
    opt = torch.optim.AdamW([dict(params=body_params, weight_decay=1e-4), dict(params=head_params, weight_decay=0.0)], lr=a.lr)
    if a.hours: a.steps = 10 ** 9          # the clock decides
    t_begin = time.time()
    paused = [0.0]   # seconds spent in checks: they don't count towards --hours
    def progress(st):   # 0 → 1 over the run, by steps or by the training clock
        return min(1.0, (time.time() - t_begin - paused[0]) / (a.hours * 3600)) if a.hours else st / a.steps
    def lr_factor(p):   # 5 % warm-up, then cosine down to 2 %
        return p / 0.05 if p < 0.05 else 0.02 + 0.98 * 0.5 * (1 + math.cos(math.pi * (p - 0.05) / 0.95))
    def set_lr(p):
        for g, base in zip(opt.param_groups, lrs): g["lr"] = base * max(0.02, lr_factor(p))
    freeze_p = (0.05 if a.freeze_steps < 0 else a.freeze_steps / max(1, a.steps)) if pretrained else 0.0
    freeze = freeze_p > 0
    def set_frozen(fz):
        for p in body_params: p.requires_grad_(not fz)
    if freeze: set_frozen(True); print(f"first {100 * freeze_p:.0f} % of the run: only the new heads learn (the pretrained network is frozen)", flush=True)
    def margin_at(p):   # 0 for the first warm-up share of the run, then eased in linearly to --margin
        w = a.margin_warmup
        return 0.0 if p < w else a.margin * min(1.0, (p - w) / max(1e-9, w))
    gen = torch.Generator(device=dev); gen.manual_seed(a.seed)
    scaler = torch.amp.GradScaler(enabled=dev == "cuda")
    step = 0; best = -1.0
    if a.resume:
        ck = torch.load(a.resume, map_location=dev, weights_only=False)
        net.load_state_dict(ck["model"]); head.load_state_dict(ck["head"]); opt.load_state_dict(ck["opt"]); step = ck["step"]; best = ck.get("best", -1)

    log = open(os.path.join(a.out, "log.jsonl"), "a")

    def save(name, metrics=None):
        torch.save(dict(model=net.state_dict(), head=head.state_dict(), opt=opt.state_dict(), step=step, best=best, width=a.width, size=a.size, backbone=a.backbone,
                        held_out=held, train_ids=train_ids, metrics=metrics), os.path.join(a.out, name))

    def set_title(text):
        try:
            import ctypes; ctypes.windll.kernel32.SetConsoleTitleW(text)
        except Exception:
            pass
    net.train(); t0 = time.time(); t_start = time.time(); run = dict(loss=0.0, arc=0.0, cnt=0.0, nose=0.0, n=0)
    def batches():
        for b in loader:
            for r in range(max(1, a.reuse)): yield b, r
    next_check = 0.1; frozen = freeze
    for (x, y, cnt, nose, _), rep in batches():
        p = progress(step)
        if step >= a.steps or p >= 1.0: break
        if frozen and p >= freeze_p: set_frozen(False); frozen = False; print(f"step {step}: unfreezing the pretrained network", flush=True)
        head.m = margin_at(p); set_lr(p)
        if real is not None and real.num_classes > 0:
            # mix a quarter of real crops into each batch (their ids follow the sprite ids)
            k = a.batch // 4
            rx, ry, rc = real.batch(k)
            x = torch.cat([x[: a.batch - k], rx]); y = torch.cat([y[: a.batch - k], ry + len(train_ids)]); cnt = torch.cat([cnt[: a.batch - k], rc]); nose = torch.cat([nose[: a.batch - k], torch.zeros(k, 2)])
            nose_mask = torch.cat([torch.ones(a.batch - k), torch.zeros(k)])
        else:
            nose_mask = torch.ones(len(x))
        x, y, cnt, nose, nose_mask = x.to(dev, non_blocking=True), y.to(dev), cnt.to(dev), nose.to(dev), nose_mask.to(dev)
        if rep > 0: x, nose = gpu_augment(x, nose, gen)
        with torch.autocast(device_type=dev, dtype=torch.float16, enabled=dev == "cuda"):
            e, c, n = net(x)
            lc = F.cross_entropy(c.float(), cnt)
            ln = ((n.float() - nose) ** 2).sum(1) * (cnt > 0).float() * nose_mask
            ln = ln.sum() / max(1.0, ((cnt > 0).float() * nose_mask).sum())
        has_id = y >= 0   # the identity loss in full precision, outside autocast
        arc = F.cross_entropy(head(e[has_id], y[has_id]), y[has_id]) if has_id.any() else e.float().sum() * 0
        loss = arc + 0.5 * lc + 2.0 * ln
        opt.zero_grad(set_to_none=True)
        scaler.scale(loss).backward(); scaler.unscale_(opt); torch.nn.utils.clip_grad_norm_(params, 5.0)
        scaler.step(opt); scaler.update(); step += 1
        run["loss"] += loss.item(); run["arc"] += arc.item(); run["cnt"] += lc.item(); run["nose"] += ln.item(); run["n"] += 1
        if step % 10 == 0 and step % 100 != 0:   # live counter between the permanent lines
            done = progress(step); el = time.time() - t_start
            eta = el / max(1e-9, done) * (1 - done) / 3600
            line = f"  step {step} ({100 * done:.1f} %) · loss {loss.item():.3f} · ~{eta:.1f} h left · best so far {best:.1f} %"
            sys.stdout.write("\r" + line[:118].ljust(118)); sys.stdout.flush()
            if step % 50 == 0: set_title(f"AnyLap 2/3 training · {100 * done:.0f} % · ~{eta:.1f} h left · best {best:.1f} %")
        if step % 100 == 0:
            sys.stdout.write("\r" + " " * 118 + "\r")
            rate = 100 * a.batch / (time.time() - t0) / max(1, a.reuse); t0 = time.time()   # new pictures per second
            done = progress(step); eta = (time.time() - t_start) / max(1e-9, done) * (1 - done) / 3600
            print(f"{time.strftime('%H:%M')} [{100 * done:4.1f} %  ~{eta:.1f} h left] step {step} loss {run['loss']/run['n']:.3f} arc {run['arc']/run['n']:.3f} count {run['cnt']/run['n']:.3f} nose {run['nose']/run['n']:.4f} margin {head.m:.2f} lr {opt.param_groups[-1]["lr"]:.2e} {rate:.0f} img/s", flush=True)
            run = dict(loss=0.0, arc=0.0, cnt=0.0, nose=0.0, n=0)
        p = progress(step)
        check = (p >= next_check) if a.hours else (step % a.eval_every == 0 or step == a.steps)
        if check:
            t_check = time.time()
            n_checks = min(10, int(p * 10 + 1e-9))
            next_check = (n_checks + 1) / 10
            m = json.loads(json.dumps(evaluate(net, held_ds, dev, races=12), default=float))
            if (a.hours and n_checks % 5 == 0) or (not a.hours and (step % (a.eval_every * 5) == 0 or step == a.steps)):   # look-alike stress less often
                m["lookalike_accuracy"] = evaluate(net, held_ds, dev, races=8, lookalike=True, n_intruder=0)["accuracy"]
            m["train_cars_accuracy"] = evaluate(net, train_ds, dev, races=6, n_intruder=0)["accuracy"]
            m["step"] = step; m["time"] = time.time()
            if real is not None and real.has_eval: m["real"] = real.evaluate(net, dev)
            sys.stdout.write("\r" + " " * 118 + "\r")
            print(f"{time.strftime('%H:%M')} CHECK at step {step}: unseen cars named right {m['accuracy']:.1f} % (training cars {m['train_cars_accuracy']:.1f} %, guessing = 12.5 %)  ·  intruders called a car {m.get('intruder_called_car_mean')} %"
                  + (f"  ·  look-alikes {m['lookalike_accuracy']:.1f} %" if 'lookalike_accuracy' in m else ""), flush=True)
            log.write(json.dumps(m) + "\n"); log.flush()
            save("last.pt", m)
            if m["accuracy"] > best: best = m["accuracy"]; save("best.pt", m)
            net.train()
            paused[0] += time.time() - t_check
    if a.hours and next_check <= 1.0 + 1e-9:   # the final check at the end of the clock
        m = json.loads(json.dumps(evaluate(net, held_ds, dev, races=12), default=float)); m["step"] = step; m["time"] = time.time()
        m["lookalike_accuracy"] = evaluate(net, held_ds, dev, races=8, lookalike=True, n_intruder=0)["accuracy"]
        print(f"{time.strftime('%H:%M')} FINAL CHECK: unseen cars named right {m['accuracy']:.1f} %  ·  intruders called a car {m.get('intruder_called_car_mean')} %  ·  look-alikes {m['lookalike_accuracy']:.1f} %", flush=True)
        log.write(json.dumps(m) + "\n"); log.flush()
        if m["accuracy"] > best: best = m["accuracy"]; save("best.pt", m)
    save("last.pt")
    print("done; best held-out accuracy", best)

if __name__ == "__main__":
    main()
