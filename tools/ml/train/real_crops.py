"""Real crops shared by the app (Settings → Car recognition → "Help improve car recognition"): download them from
Supabase and use them for training (extra identities) and – more importantly – as the real-world test set.

Download (service role key, never ships in the app):
    set SUPABASE_URL=https://<project>.supabase.co   set SUPABASE_SERVICE_KEY=...
    python tools/ml/train/real_crops.py download D:/anylap-ml/real
Layout: <root>/<user_id>/<racer_id>/<crop_id>.jpg + .json (the row's meta)  ·  "none" for crops of nobody.
Training/eval use the subject's box from meta["boxes"] (subject = true), grown by 30 %, resized to the model's input.
"""
import glob, io, json, math, os, sys
import numpy as np
from PIL import Image

def download(root):
    import urllib.request
    url = os.environ["SUPABASE_URL"].rstrip("/"); key = os.environ["SUPABASE_SERVICE_KEY"]
    hdr = {"apikey": key, "Authorization": f"Bearer {key}"}
    rows = []; off = 0
    while True:
        req = urllib.request.Request(f"{url}/rest/v1/anylap_crops?select=*&order=created_at&offset={off}&limit=1000", headers=hdr)
        page = json.load(urllib.request.urlopen(req))
        rows += page; off += len(page)
        if len(page) < 1000: break
    print(len(rows), "rows")
    n = 0
    for r in rows:
        d = os.path.join(root, r["user_id"], r["racer_id"] or "none"); os.makedirs(d, exist_ok=True)
        ext = os.path.splitext(r["path"])[1]
        img = os.path.join(d, r["id"] + ext)
        if not os.path.exists(img):
            req = urllib.request.Request(f"{url}/storage/v1/object/anylap-crops/{r['path']}", headers=hdr)
            open(img, "wb").write(urllib.request.urlopen(req).read()); n += 1
        json.dump(r, open(os.path.join(d, r["id"] + ".json"), "w"))
    print(n, "new files")

class RealCrops:
    """Crops grouped by (user, racer). Identities with >= min_per_id crops are classes; 20 % of each is held for eval."""
    def __init__(self, root, size=64, min_per_id=8, seed=0):
        self.size = size
        rng = np.random.default_rng(seed)
        items = {}
        for js in glob.glob(os.path.join(root, "*", "*", "*.json")):
            r = json.load(open(js)); user, racer = js.split(os.sep)[-3:-1]
            if racer == "none": continue
            img = os.path.splitext(js)[0] + os.path.splitext(r["path"])[1]
            if not os.path.exists(img): continue
            meta = r.get("meta") or {}
            box = next((b for b in meta.get("boxes", []) if b.get("subject")), None)
            if box is None: continue
            items.setdefault((user, racer), []).append((img, box))
        self.ids = sorted(k for k, v in items.items() if len(v) >= min_per_id)
        self.train = []; self.eval = []
        for i, k in enumerate(self.ids):
            v = items[k]; rng.shuffle(v); n_eval = max(1, len(v) // 5)
            self.eval += [(i, *x) for x in v[:n_eval]]; self.train += [(i, *x) for x in v[n_eval:]]
        self.rng = rng
        self._cache = {}

    @property
    def num_classes(self): return len(self.ids)
    @property
    def has_eval(self): return len(self.eval) > 0
    def __len__(self): return len(self.train) + len(self.eval)

    def crop(self, img_path, box, jitter=0.0):
        if img_path not in self._cache:
            if len(self._cache) > 5000: self._cache.clear()
            self._cache[img_path] = Image.open(img_path).convert("RGB")
        im = self._cache[img_path]; W, H = im.size
        l, t, r, b = box["l"] * W, box["t"] * H, box["r"] * W, box["b"] * H
        w, h = r - l, b - t; cx, cy = (l + r) / 2 + jitter * w, (t + b) / 2 + jitter * h
        s = max(w, h) * 1.6
        c = im.crop((int(cx - s / 2), int(cy - s / 2), int(cx + s / 2), int(cy + s / 2))).resize((self.size, self.size), Image.BILINEAR)
        return np.asarray(c, np.uint8)

    def batch(self, k):
        import torch
        xs = []; ys = []
        for _ in range(k):
            i, img, box = self.train[self.rng.integers(len(self.train))]
            arr = self.crop(img, box, jitter=self.rng.uniform(-0.15, 0.15)).astype(np.float32)
            arr = np.clip(arr * self.rng.uniform(0.8, 1.2) + self.rng.normal(0, 4, arr.shape), 0, 255)
            xs.append(arr.transpose(2, 0, 1) / 255); ys.append(i)
        return torch.tensor(np.stack(xs), dtype=torch.float32), torch.tensor(ys), torch.ones(k, dtype=torch.long)

    def evaluate(self, net, dev, k_cal=6):
        """Prototype from k_cal training crops per identity, nearest prototype over the eval crops."""
        import torch
        net.eval()
        with torch.no_grad():
            protos = []
            for i in range(self.num_classes):
                mine = [x for x in self.train if x[0] == i][:k_cal]
                x = torch.tensor(np.stack([self.crop(img, box).transpose(2, 0, 1) for _, img, box in mine]), dtype=torch.float32) / 255
                e, _, _ = net(x.to(dev)); protos.append(torch.nn.functional.normalize(e.mean(0), dim=0).cpu())
            protos = torch.stack(protos)
            x = torch.tensor(np.stack([self.crop(img, box).transpose(2, 0, 1) for _, img, box in self.eval]), dtype=torch.float32) / 255
            e, _, _ = net(x.to(dev)); e = e.cpu()
            pred = (e @ protos.T).argmax(1).numpy(); truth = np.array([i for i, _, _ in self.eval])
        net.train()
        return dict(accuracy=round(100 * float((pred == truth).mean()), 2), n=len(truth), identities=self.num_classes)

if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "download": download(sys.argv[2])
    else: print(__doc__)
