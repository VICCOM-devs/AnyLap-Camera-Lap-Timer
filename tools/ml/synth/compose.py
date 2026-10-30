"""On-the-fly training data for the AnyLap recognition model (domain randomisation).

Takes the sprite library (tools/ml/sprites) and composes, per sample, a finish-line crop the way a phone would
see it: a random ground (procedural or a photo), the car at a random scale (12–150 px long), in-plane rotation,
its own shadow with random strength/softness, a random line stripe under it, sometimes a touching neighbour
or two, motion blur along its heading, sensor noise, exposure / white-balance drift, defocus, JPEG and chroma
subsampling – all at once, so the network never sees the same picture twice and never learns one rig.

    ds = SpriteDataset("D:/anylap-ml/sprites", ids=train_ids, size=64)
    img, y = ds.sample(rng)        # img uint8 HxWx3 ; y = dict(identity, count, nose_dx, nose_dy, elev, px_per_cm, ...)

No Blender, no GPU needed; a DataLoader with workers makes thousands of samples per second.
"""
import glob, io, json, math, os, random
import numpy as np
from PIL import Image, ImageFilter, ImageEnhance

# ---------------------------------------------------------------- library
class Library:
    """All sprites: cars -> views; each view = car RGBA, shadow RGBA, metadata (loaded lazily, cached)."""
    def __init__(self, root, ids=None):
        self.root = root
        cars = sorted(d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d)) and os.path.exists(os.path.join(root, d, "done")))
        if ids is not None: cars = [c for c in cars if c in set(ids)]
        if not cars: raise RuntimeError(f"no finished cars in {root}")
        self.cars = cars
        self.index = {c: i for i, c in enumerate(cars)}
        self.views = {c: sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(root, c, "e*_a*.json"))) for c in cars}
        self.specs = {c: json.load(open(os.path.join(root, c, "car.json"))) for c in cars}
        self._cache = {}

    def view(self, car, name):
        k = (car, name)
        if k not in self._cache:
            d = os.path.join(self.root, car)
            meta = json.load(open(os.path.join(d, name + ".json")))
            img = np.asarray(Image.open(os.path.join(d, name + "_car.png")).convert("RGBA"))
            sh = np.asarray(Image.open(os.path.join(d, name + "_shadow.png")).convert("RGBA"))
            if len(self._cache) > 4000: self._cache.clear()
            self._cache[k] = (img, sh, meta)
        return self._cache[k]

    def pick_view(self, rng, car, elev=None):
        vs = self.views[car]
        if elev is not None:
            cand = [v for v in vs if abs(int(v[1:3]) - elev) <= 5] or vs
            return rng.choice(cand)
        return rng.choice(vs)

# ---------------------------------------------------------------- grounds
def _noise(rng, h, w, octaves=4, persistence=0.55):
    """Cheap value noise 0..1 via upscaled random grids."""
    out = np.zeros((h, w), np.float32); amp = 1.0; tot = 0.0
    for o in range(octaves):
        gh, gw = max(2, h >> (octaves - o + 1)), max(2, w >> (octaves - o + 1))
        g = rng.random((gh, gw)).astype(np.float32)
        im = Image.fromarray((g * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        out += amp * np.asarray(im, np.float32) / 255; tot += amp; amp *= persistence
    return out / tot

def procedural_ground(rng, h, w):
    """Asphalt / carpet / dirt / tiles / concrete / wood / grass-ish, random tint. Returns float RGB 0..1."""
    kind = rng.choice(["asphalt", "asphalt", "carpet", "dirt", "tiles", "concrete", "wood", "turf", "paper"])
    n = _noise(rng, h, w)
    fine = rng.random((h, w)).astype(np.float32)
    if kind == "asphalt":
        base = rng.uniform(0.18, 0.5); g = base + (fine - 0.5) * rng.uniform(0.05, 0.2) + (n - 0.5) * 0.1
        rgb = np.stack([g, g, g * rng.uniform(1.0, 1.08)], -1)
    elif kind == "carpet":
        c = np.array([rng.uniform(0.15, 0.5), rng.uniform(0.2, 0.55), rng.uniform(0.3, 0.7)]) if rng.random() < 0.6 else np.array([rng.uniform(0.25, 0.6)] * 3)
        rgb = c[None, None] * (1 + (fine - 0.5) * 0.15 + (n - 0.5) * 0.08)[..., None]
    elif kind == "dirt":
        c = np.array([rng.uniform(0.45, 0.7), rng.uniform(0.35, 0.55), rng.uniform(0.2, 0.4)])
        stones = (rng.random((h, w)) < 0.004).astype(np.float32)
        stones = np.asarray(Image.fromarray((stones * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(3)), np.float32) / 255
        rgb = c[None, None] * (0.75 + 0.5 * n)[..., None] * (1 + (fine - 0.5) * 0.12)[..., None] + stones[..., None] * 0.25
    elif kind == "tiles":
        base = rng.uniform(0.5, 0.9); t = int(rng.integers(14, 60))
        yy, xx = np.mgrid[0:h, 0:w]
        grout = ((yy % t) < 2) | ((xx % t) < 2)
        g = np.where(grout, base * 0.6, base) + (fine - 0.5) * 0.05
        rgb = np.stack([g, g * rng.uniform(0.95, 1.0), g * rng.uniform(0.9, 1.0)], -1)
    elif kind == "concrete":
        g = rng.uniform(0.45, 0.75) + (n - 0.5) * 0.15 + (fine - 0.5) * 0.06
        rgb = np.stack([g, g, g * 0.98], -1)
    elif kind == "wood":
        yy = np.mgrid[0:h, 0:w][0 if rng.random() < 0.5 else 1]
        grain = 0.5 + 0.5 * np.sin(yy * rng.uniform(0.3, 1.2) + n * 6)
        c = np.array([0.6, 0.42, 0.25]) * rng.uniform(0.7, 1.2)
        rgb = c[None, None] * (0.8 + 0.3 * grain)[..., None]
    elif kind == "turf":
        c = np.array([0.15, rng.uniform(0.35, 0.55), 0.12])
        rgb = c[None, None] * (0.7 + 0.6 * fine)[..., None] * (0.8 + 0.4 * n)[..., None]
    else:  # paper / painted floor
        g = rng.uniform(0.6, 0.95); rgb = np.stack([g, g, g], -1) * (1 + (n - 0.5) * 0.04)[..., None]
    rgb = np.clip(rgb, 0, 1).astype(np.float32)
    # a kerb or barrier edge sometimes
    if rng.random() < 0.3:
        edge = rng.integers(0, 4); width = rng.integers(max(2, h // 12), max(3, h // 4))
        col = np.array(rng.choice([(0.9, 0.1, 0.1), (0.95, 0.95, 0.95), (0.1, 0.3, 0.9), (0.95, 0.8, 0.1), (0.05, 0.05, 0.05)]), np.float32)
        if edge == 0: rgb[:width] = col
        elif edge == 1: rgb[-width:] = col
        elif edge == 2: rgb[:, :width] = col
        else: rgb[:, -width:] = col
    return rgb

class Grounds:
    """Random ground patch: procedural, or a crop of a photo from `photo_dir` (any JPG/PNG the user drops there)."""
    def __init__(self, photo_dir=None, p_photo=0.5):
        self.photos = sorted(glob.glob(os.path.join(photo_dir, "*"))) if photo_dir and os.path.isdir(photo_dir) else []
        self.photos = [p for p in self.photos if p.lower().endswith((".jpg", ".jpeg", ".png"))]
        self.p_photo = p_photo if self.photos else 0.0
        self._cache = {}

    def get(self, rng, h, w):
        if rng.random() < self.p_photo:
            p = rng.choice(self.photos)
            if p not in self._cache:
                im = Image.open(p).convert("RGB"); im.thumbnail((1024, 1024)); self._cache[p] = im
            im = self._cache[p]
            s = rng.uniform(0.15, 1.0); cw, ch = max(8, int(im.width * s)), max(8, int(im.height * s))
            x0, y0 = rng.integers(0, im.width - cw + 1), rng.integers(0, im.height - ch + 1)
            crop = im.crop((x0, y0, x0 + cw, y0 + ch)).rotate(rng.uniform(0, 360), resample=Image.BILINEAR, expand=False).resize((w, h), Image.BILINEAR)
            return np.asarray(crop, np.float32) / 255
        return procedural_ground(rng, h, w)

# ---------------------------------------------------------------- image ops
def rotate_scale(rgba, angle_deg, scale, out_size):
    """Rotate about the sprite centre and scale; return float RGBA on an out_size canvas (centre kept)."""
    im = Image.fromarray(rgba)
    w = max(1, int(round(im.width * scale)))
    im = im.resize((w, w), Image.LANCZOS if scale < 1 else Image.BICUBIC)
    im = im.rotate(angle_deg, resample=Image.BICUBIC, expand=True)
    canvas = Image.new("RGBA", (out_size, out_size), (0, 0, 0, 0))
    canvas.paste(im, ((out_size - im.width) // 2, (out_size - im.height) // 2), im)
    return np.asarray(canvas, np.float32) / 255

def motion_blur(img, length_px, angle_deg):
    """Directional box blur of an RGBA float image along angle (image coords, 0 = +x): the mean of the image
    shifted along the streak (premultiplied alpha, so the smear fades at its ends like a real one)."""
    n = int(round(length_px))
    if n < 2: return img
    n = min(n, 48)
    pre = img.copy(); pre[..., :3] *= pre[..., 3:4]
    acc = np.zeros_like(pre)
    cx, sy = math.cos(math.radians(angle_deg)), -math.sin(math.radians(angle_deg))
    for i in range(n):
        t = i - (n - 1) / 2
        dx, dy = int(round(t * cx)), int(round(t * sy))
        acc += np.roll(pre, (dy, dx), (0, 1))
    acc /= n
    a = acc[..., 3:4]
    out = np.concatenate([np.where(a > 1e-4, acc[..., :3] / np.maximum(a, 1e-4), 0), a], -1)
    return out

def alpha_over(dst_rgb, src_rgba):
    a = src_rgba[..., 3:4]
    return dst_rgb * (1 - a) + src_rgba[..., :3] * a

def to_uint8(x): return (np.clip(x, 0, 1) * 255 + 0.5).astype(np.uint8)

# ---------------------------------------------------------------- the dataset
class SpriteDataset:
    """
    size: output crop (square). The subject car's centre lands near the crop centre (tracker-box jitter).
    Labels (dict): identity (index into lib.cars), count (0 = no whole car at the centre, 1, 2 = two or more),
    nose_dx/dy (subject nose relative to crop centre, in crop widths), heading_deg (image angle of the nose),
    elev, px_per_cm, blur_px, neighbours.
    """
    def __init__(self, sprite_root, ids=None, size=64, photo_dir=None, p_neighbour=0.35, p_empty=0.06, min_px_per_cm=0.6, max_px_per_cm=8.0):
        self.lib = Library(sprite_root, ids)
        self.size = size
        self.grounds = Grounds(photo_dir)
        self.p_neighbour = p_neighbour; self.p_empty = p_empty
        self.min_s, self.max_s = min_px_per_cm, max_px_per_cm

    @property
    def num_classes(self): return len(self.lib.cars)

    def _place(self, rng, car, view, px_per_cm, theta, canvas, offset, light, shadow_k, shadow_blur, blur_px):
        img, sh, meta = self.lib.view(car, view)
        s = px_per_cm / meta["px_per_cm"]
        c = rotate_scale(img, theta, s, canvas)
        shd = rotate_scale(sh, theta, s, canvas)
        # light: per-car brightness/colour (sun strength differs per view already; this is exposure + WB)
        c[..., :3] = np.clip(c[..., :3] * light, 0, 1)
        if blur_px > 1.5:
            c = motion_blur(c, blur_px, self._heading(meta, theta))
        shd = np.asarray(Image.fromarray(to_uint8(shd)).filter(ImageFilter.GaussianBlur(float(shadow_blur))), np.float32) / 255 if shadow_blur > 0.3 else shd
        shd[..., 3] *= shadow_k
        shd[..., :3] = 0
        # shift to its offset
        dx, dy = int(round(offset[0])), int(round(offset[1]))
        c = np.roll(c, (dy, dx), (0, 1)); shd = np.roll(shd, (dy, dx), (0, 1))
        if dy > 0: c[:dy] = 0; shd[:dy] = 0
        elif dy < 0: c[dy:] = 0; shd[dy:] = 0
        if dx > 0: c[:, :dx] = 0; shd[:, :dx] = 0
        elif dx < 0: c[:, dx:] = 0; shd[:, dx:] = 0
        # the nose / centre in canvas coords after rotate+scale+offset
        def tf(p):
            x, y = (p[0] - meta["sprite"] / 2) * s, (p[1] - meta["sprite"] / 2) * s
            a = math.radians(theta)
            return (x * math.cos(a) + y * math.sin(a) + canvas / 2 + dx, -x * math.sin(a) + y * math.cos(a) + canvas / 2 + dy)
        return c, shd, tf(meta["nose"]), tf(meta["centre"]), meta

    @staticmethod
    def _heading(meta, theta):
        nx, ny = meta["nose"][0] - meta["tail"][0], meta["nose"][1] - meta["tail"][1]
        return math.degrees(math.atan2(-ny, nx)) + theta

    def sample(self, rng, identity=None, elev=None, px_per_cm=None):
        S = self.size; canvas = S * 2
        car = self.lib.cars[identity] if identity is not None else rng.choice(self.lib.cars)
        view = self.lib.pick_view(rng, car, elev)
        _, _, meta = self.lib.view(car, view)
        e = meta["elev"]
        if px_per_cm is None:
            px_per_cm = math.exp(rng.uniform(math.log(self.min_s), math.log(self.max_s)))
        car_len_px = px_per_cm * meta["L_cm"]
        # keep the car inside the crop: if it's bigger than the crop, scale so it's at most 1.4 crops (partial views are fine)
        px_per_cm = min(px_per_cm, 1.4 * S / meta["L_cm"])
        theta = rng.uniform(0, 360) if e >= 70 else rng.uniform(-30, 30)
        # exposure / white balance for the whole picture + sun strength per car (both cars share it)
        light = np.array([rng.uniform(0.75, 1.25)] * 3, np.float32) * np.array([rng.uniform(0.9, 1.1), 1.0, rng.uniform(0.9, 1.1)], np.float32)
        shadow_k = rng.uniform(0.15, 1.0); shadow_blur = float(rng.choice([0, 0, 0.6, 1.2, 2.5])) * px_per_cm / 2
        # motion blur: shutter x speed, 0..0.6 car lengths
        blur_px = float(rng.choice([0, 0, 0.05, 0.1, 0.2, 0.35, 0.6])) * px_per_cm * meta["L_cm"] * rng.uniform(0.6, 1.0)
        jitter = px_per_cm * meta["L_cm"] * 0.25
        offset = (rng.uniform(-jitter, jitter), rng.uniform(-jitter, jitter))

        ground = self.grounds.get(rng, canvas, canvas)
        ground = np.clip(ground * light[None, None] * rng.uniform(0.85, 1.1), 0, 1)
        # the finish-line stripe (tape / paint) under the cars, random angle through the centre
        if rng.random() < 0.7:
            yy, xx = np.mgrid[0:canvas, 0:canvas]
            a = math.radians(rng.uniform(0, 180)); d = (xx - canvas / 2) * math.sin(a) - (yy - canvas / 2) * math.cos(a)
            width = rng.uniform(1.5, 6) * px_per_cm
            stripe = np.clip(1 - (np.abs(d) - width / 2) / 1.0, 0, 1)[..., None]
            col = np.array(rng.choice([(0.95, 0.95, 0.95), (0.1, 0.85, 0.9), (0.95, 0.85, 0.1), (0.95, 0.3, 0.1), (0.05, 0.05, 0.05)]), np.float32) * light
            ground = ground * (1 - stripe * 0.9) + col * stripe * 0.9

        cars = []
        empty = rng.random() < self.p_empty
        if not empty:
            cars.append((car, view, offset, True))
            # neighbours: abreast or nose-to-tail, touching or a small gap, same scale and elevation
            n_nb = 0
            if rng.random() < self.p_neighbour: n_nb = 1 + (rng.random() < 0.25)
            for _ in range(n_nb):
                other = rng.choice([c for c in self.lib.cars if c != car])
                ov = self.lib.pick_view(rng, other, e)
                _, _, om = self.lib.view(other, ov)
                head = math.radians(self._heading(meta, theta))
                side = rng.random() < 0.6
                gap = rng.uniform(-0.15, 0.6) * px_per_cm * meta["W_cm"]  # negative = overlapping a little
                if side:
                    dist = px_per_cm * (meta["W_cm"] + om["W_cm"]) / 2 + gap
                    ang = head + math.pi / 2 * rng.choice([-1, 1])
                    along = rng.uniform(-0.5, 0.5) * px_per_cm * meta["L_cm"]
                    off = (offset[0] + dist * math.cos(ang) + along * math.cos(head), offset[1] - dist * math.sin(ang) - along * math.sin(head))
                else:
                    dist = px_per_cm * (meta["L_cm"] + om["L_cm"]) / 2 + gap
                    sgn = rng.choice([-1, 1])
                    off = (offset[0] + sgn * dist * math.cos(head), offset[1] - sgn * dist * math.sin(head))
                cars.append((other, ov, off, False))

        comp = ground.copy(); shadows = np.zeros((canvas, canvas), np.float32); layers = []
        nose = centre = None
        for cid, v, off, subject in cars:
            c, shd, n_, ce, m = self._place(rng, cid, v, px_per_cm, theta, canvas, off, light, shadow_k, shadow_blur, blur_px)
            shadows = np.maximum(shadows, shd[..., 3]); layers.append(c)
            if subject: nose, centre = n_, ce
        # a stray moving shadow (walking legs, a barrier post) sometimes
        if rng.random() < 0.2:
            blob = np.zeros((canvas, canvas), np.float32)
            cx, cy, r = rng.uniform(0, canvas), rng.uniform(0, canvas), rng.uniform(0.3, 1.5) * S
            yy, xx = np.mgrid[0:canvas, 0:canvas]
            blob = np.clip(1 - np.hypot(xx - cx, yy - cy) / r, 0, 1) ** 0.5
            shadows = np.maximum(shadows, blob * rng.uniform(0.3, 0.8))
        comp = comp * (1 - shadows[..., None] * rng.uniform(0.5, 0.95))
        # back to front: neighbours first, subject last (it's the one at the line) – order of overlap is random
        order = list(range(len(layers))); rng.shuffle(order)
        for i in order: comp = alpha_over(comp, layers[i])

        # crop the centre S x S, then the camera: resolution loss, defocus, noise, chroma subsampling, JPEG
        y0 = x0 = (canvas - S) // 2
        crop = comp[y0:y0 + S, x0:x0 + S]
        pil = Image.fromarray(to_uint8(crop))
        if rng.random() < 0.4:  # the analysis frame is lower-res than the sprite: downscale and back
            f = rng.uniform(0.4, 0.9); pil = pil.resize((max(4, int(S * f)),) * 2, Image.BILINEAR).resize((S, S), Image.BILINEAR)
        if rng.random() < 0.3: pil = pil.filter(ImageFilter.GaussianBlur(float(rng.uniform(0.3, 1.2))))
        if rng.random() < 0.5: pil = ImageEnhance.Contrast(pil).enhance(float(rng.uniform(0.8, 1.2)))
        arr = np.asarray(pil, np.float32)
        arr += rng.normal(0, rng.uniform(1, 12), arr.shape).astype(np.float32)
        if rng.random() < 0.3: arr = 255 * (np.clip(arr / 255, 0, 1) ** rng.uniform(0.75, 1.3))
        pil = Image.fromarray(to_uint8(arr / 255))
        if rng.random() < 0.7:
            buf = io.BytesIO(); pil.save(buf, "JPEG", quality=int(rng.integers(35, 96)), subsampling=int(rng.choice([0, 2, 2]))); buf.seek(0); pil = Image.open(buf).convert("RGB")
        out = np.asarray(pil, np.uint8)

        if empty:
            y = dict(identity=-1, count=0, nose_dx=0.0, nose_dy=0.0, heading=0.0)
        else:
            # count = cars whose centre is inside the crop (the subject + neighbours that made it in)
            cnt = 0
            for cid, v, off, subject in cars:
                if abs(off[0]) < S * 0.5 and abs(off[1]) < S * 0.5: cnt += 1
            y = dict(identity=self.lib.index[car], count=min(cnt, 2),
                     nose_dx=(nose[0] - canvas / 2) / S, nose_dy=(nose[1] - canvas / 2) / S, heading=self._heading(meta, theta) % 360)
        y.update(elev=e, px_per_cm=px_per_cm, blur_px=float(blur_px), neighbours=max(0, len(cars) - 1), car=car, view=view, car_len_px=car_len_px)
        return out, y

# ---------------------------------------------------------------- torch glue
def torch_dataset(sprite_root, ids, size, photo_dir=None, seed=0, **kw):
    import torch
    from torch.utils.data import IterableDataset

    class _DS(IterableDataset):
        def __init__(self): self.ds = SpriteDataset(sprite_root, ids, size, photo_dir, **kw)
        def __iter__(self):
            wi = torch.utils.data.get_worker_info()
            rng = np.random.default_rng(seed + (wi.id if wi else 0) * 7919 + int.from_bytes(os.urandom(4), "little"))
            while True:
                img, y = self.ds.sample(rng)
                x = torch.from_numpy(np.ascontiguousarray(img.transpose(2, 0, 1))).float() / 255
                yield x, torch.tensor(y["identity"]), torch.tensor(y["count"]), torch.tensor([y["nose_dx"], y["nose_dy"]], dtype=torch.float32), torch.tensor([y["elev"], y["px_per_cm"]], dtype=torch.float32)
    return _DS()

if __name__ == "__main__":
    import argparse, sys
    ap = argparse.ArgumentParser(description="contact sheet of random samples")
    ap.add_argument("sprites"); ap.add_argument("--out", default="samples.png"); ap.add_argument("--n", type=int, default=64); ap.add_argument("--size", type=int, default=64); ap.add_argument("--photos", default=None)
    a = ap.parse_args()
    ds = SpriteDataset(a.sprites, size=a.size, photo_dir=a.photos)
    rng = np.random.default_rng(1)
    cols = 8; rows = (a.n + cols - 1) // cols; sheet = Image.new("RGB", (cols * a.size, rows * a.size))
    for i in range(a.n):
        img, y = ds.sample(rng); sheet.paste(Image.fromarray(img), ((i % cols) * a.size, (i // cols) * a.size))
    sheet.save(a.out); print("wrote", a.out)
