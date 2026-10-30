"""Sprite library for the AnyLap recognition model: randomised Mini-Z-scale cars rendered over the whole
hemisphere of camera angles, car + shadow separately on transparent film, with exact metadata.

Runs inside Blender (GPU at home) or with the `bpy` wheel (CPU, small tests):

    blender -b -P tools/ml/sprites/render_sprites.py -- --out D:/anylap-ml/sprites --cars 300 --seed 0
    python -I tools/ml/sprites/render_sprites.py --out /tmp/sprites --cars 3 --quick      # bpy wheel

Each car = one random spec for the minis10 generator (tools/bench/minis10/minis10.py: body kind, size, colours,
livery, sponsors, number, wear, matte/metal, optional tape strip).  Views: elevation x relative azimuth
(ELEVATIONS, azimuths_for(elev): 132 views per car), each under its own random sun (direction, softness, strength) and sky light.
Output per view, at a fixed scale (car length ~ LEN_PX px, camera far enough for a weak perspective):
    <car>/<view>_car.png      RGBA, the car only (film transparent, ground hidden)
    <car>/<view>_shadow.png   RGBA, the car's shadow only (shadow catcher, car invisible to the camera)
    <car>/<view>.json         angles, sun, px per cm, car size, nose/tail/centre in sprite px, bbox
    <car>/car.json            the spec (what the car is), one per car
The compositor (tools/ml/synth) never needs Blender: it scales, rotates and lights these on random grounds.
"""
import argparse, json, math, os, random, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "bench", "minis10"))

# ---------------------------------------------------------------- settings
ELEVATIONS = [20, 30, 40, 50, 60, 70, 80, 90]      # camera elevation above the ground plane (90 = straight down)
# camera azimuth relative to the car's nose (0 = looking at the nose); from above, in-plane rotation in the
# compositor covers the rest, so steep views need fewer azimuths: 24 + 24 + 24 + 24 + 12 + 12 + 6 + 6 = 132 views/car
def azimuths_for(elev): return list(range(0, 360, 15 if elev <= 50 else 30 if elev <= 70 else 60))
LEN_PX = 110                                        # car length in sprite pixels (the compositor scales down from here)
SPRITE = 192                                        # sprite canvas (room for the shadow)
CAM_DIST = 3.0                                      # m, weak perspective; the compositor adds in-frame perspective

KINDS = ["gt", "lmp", "formula", "rally", "touring", "drift", "group5", "super", "stock", "hyper"]
LIVERIES = ["twin", "split", "nose", "swoosh", "band", "stickerbomb", "stripes3", "centre", "flames", "diagonal"]
WORDS = ["VOLTAX", "NORDLINE", "KAPPA OIL", "AXELRO", "SKYFJORD", "ION CELL", "BRAKKO", "LUMEN", "TORQ", "GRUSVÄG", "MOXI",
         "ARCTIC TYRES", "HELIX", "KORVO", "BYTE MOTORS", "SIDEWAYS", "NEON KAI", "ZORRO", "DRFT", "GRIP0", "RETRO", "OKTAN",
         "VALBY", "STERLA", "QUARTZ", "MIRA", "BIG HAUL", "FIZZCO", "RAPIDO", "CORN KING", "AERIX", "NOVA", "KINETIQ",
         "SOLVIK", "TEMPO", "GLIMT OIL", "FJÄLL", "POLARIS", "DUNE", "CIRRUS", "VEKTOR", "GRAFIT", "LIMA", "SLIDE", "KAIJU",
         "ROKKU", "SMOKE", "HALVA", "ORBIT", "PIXEL", "TUNDRA", "KOBOLT", "SPARK", "MAGMA", "BOREAL", "VIVID", "ZINK"]
# colour families: (weight, sampler) – saturated bodies, whites, greys, blacks, metallics, pastels, fluos
def _hsv(h, s, v):
    i = int(h * 6) % 6; f = h * 6 - int(h * 6); p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    return [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
def rand_colour(rnd):
    r = rnd.random()
    if r < 0.42: return _hsv(rnd.random(), rnd.uniform(0.75, 1.0), rnd.uniform(0.35, 0.95))   # saturated
    if r < 0.58: g = rnd.uniform(0.80, 0.97); return (g, g, g * rnd.uniform(0.97, 1.0))        # white
    if r < 0.70: g = rnd.uniform(0.25, 0.70); return (g, g * rnd.uniform(0.97, 1.03), g * rnd.uniform(0.97, 1.06))  # grey / silver
    if r < 0.82: g = rnd.uniform(0.02, 0.07); return (g, g, g * rnd.uniform(1.0, 1.4))          # black / very dark
    if r < 0.92: return _hsv(rnd.random(), rnd.uniform(0.25, 0.5), rnd.uniform(0.7, 0.95))     # pastel
    return _hsv(rnd.choice([0.08, 0.17, 0.33, 0.5, 0.9]), 1.0, 1.0)                              # fluo

def random_spec(i, seed):
    rnd = random.Random(seed * 100003 + i)
    kind = rnd.choice(KINDS)
    if kind == "formula":
        L, W, H = rnd.uniform(18.5, 20.5), rnd.uniform(8.5, 9.5), rnd.uniform(3.2, 3.8)
    else:
        L, W, H = rnd.uniform(14.5, 20.0), rnd.uniform(7.6, 10.0), rnd.uniform(3.4, 6.2)
    base = rand_colour(rnd)
    acc = rand_colour(rnd); acc2 = rand_colour(rnd)
    if rnd.random() < 0.35: acc = (0.97, 0.97, 0.95)      # white accents are common
    if rnd.random() < 0.25: acc2 = (0.03, 0.03, 0.03)
    sp = dict(
        name=f"car{i:04d}", kind=kind, L=round(L, 1), W=round(W, 1), H=round(H, 1), wr=round(rnd.uniform(1.05, 1.5), 2),
        base=base, acc=acc, acc2=acc2, livery=rnd.choice(LIVERIES), num=str(rnd.randint(1, 99)),
        rim=rnd.choice([(0.75, 0.75, 0.78), (0.08, 0.08, 0.08), (0.85, 0.85, 0.85), (0.95, 0.70, 0.05), (0.20, 0.20, 0.22), (0.6, 0.05, 0.05)]),
        damage=rnd.choices([0, 1, 2], [0.5, 0.3, 0.2])[0], sponsors=rnd.sample(WORDS, rnd.randint(3, 5)), seed=rnd.randint(1, 10 ** 6),
    )
    if rnd.random() < 0.22: sp["matte"] = True
    if rnd.random() < 0.25: sp["metal"] = rnd.uniform(0.3, 0.8)
    if rnd.random() < 0.20: sp["tape"] = rnd.choice([(0.10, 0.95, 0.15), (0.98, 0.95, 0.05), (0.98, 0.1, 0.6), (0.98, 0.45, 0.02), (0.1, 0.9, 0.95), (0.2, 0.3, 1.0)])
    return sp

# ---------------------------------------------------------------- blender
def main():
    import bpy
    from mathutils import Vector
    from bpy_extras.object_utils import world_to_camera_view
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--cars", type=int, default=300)
    ap.add_argument("--first", type=int, default=0, help="first car index (resume / split over runs)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--samples", type=int, default=24)
    ap.add_argument("--quick", action="store_true", help="3 elevations x 8 azimuths, 8 samples (smoke test)")
    ap.add_argument("--cpu", action="store_true")
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    os.environ.setdefault("MINIS_TEX", os.path.join(a.out, "_tex"))
    import minis10
    for attr, wins in (("FONT_B", "arialbd.ttf"), ("FONT_C", "arialbd.ttf"), ("FONT_I", "arialbi.ttf")):
        if not os.path.exists(getattr(minis10, attr)):
            for cand in (os.path.join("C:/Windows/Fonts", wins), "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf"):
                if os.path.exists(cand): setattr(minis10, attr, cand); break
    elevations, samples = (ELEVATIONS, a.samples) if not a.quick else ([30, 60, 90], 8)
    az_for = azimuths_for if not a.quick else (lambda e: list(range(0, 360, 90)))

    # ---- a clean scene: ground (shadow catcher), sun, sky, camera
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    cy = sc.cycles; cy.samples = samples; cy.use_adaptive_sampling = True; cy.use_denoising = True
    cy.device = "CPU" if a.cpu else "GPU"
    if not a.cpu:
        try:
            prefs = bpy.context.preferences.addons["cycles"].preferences
            for t in ("OPTIX", "CUDA", "HIP", "METAL", "ONEAPI"):
                try:
                    prefs.compute_device_type = t; prefs.get_devices()
                    if any(d.use for d in prefs.devices if d.type == t): break
                except Exception: continue
            else: cy.device = "CPU"
        except Exception: cy.device = "CPU"
    print("render device:", cy.device, flush=True)
    r = sc.render; r.resolution_x = r.resolution_y = SPRITE; r.resolution_percentage = 100
    r.film_transparent = True; r.image_settings.file_format = "PNG"; r.image_settings.color_mode = "RGBA"
    sc.view_settings.view_transform = "Standard"
    bpy.ops.mesh.primitive_plane_add(size=4); ground = bpy.context.object; ground.name = "ground"; ground.is_shadow_catcher = True
    bpy.ops.object.light_add(type="SUN"); sun = bpy.context.object
    world = bpy.data.worlds.new("w"); sc.world = world; world.use_nodes = True
    bg = world.node_tree.nodes["Background"]
    bpy.ops.object.camera_add(); cam = bpy.context.object; sc.camera = cam
    cam.data.lens = 50; cam.data.sensor_width = 36
    # orthographic-ish framing: a 50 mm lens at 3 m sees 2.16 m; we want LEN_PX px per car length -> set the lens from the car's length per view

    def place_camera(elev_deg, az_deg, car_len_m):
        e, z = math.radians(elev_deg), math.radians(az_deg)
        # the car points +Y (nose); azimuth 0 = camera in front of the nose, 90 = on the car's right
        d = CAM_DIST
        cam.location = (d * math.cos(e) * math.sin(z), d * math.cos(e) * math.cos(z), d * math.sin(e) + 0.02)
        direction = Vector((0, 0, 0.02)) - cam.location
        cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
        # focal length so the car is ~LEN_PX px long across the canvas when seen side-on
        cam.data.lens = 36 * d * LEN_PX / (car_len_m * SPRITE)

    def random_sun(rnd):
        se = rnd.uniform(15, 85); sa = rnd.uniform(0, 360)
        sun.rotation_euler = (math.radians(90 - se), 0, math.radians(sa))
        soft = rnd.choice([0.5, 1.0, 2.0, 5.0, 12.0, 25.0])   # degrees: hard sun .. overcast
        sun.data.angle = math.radians(soft)
        strength = rnd.uniform(1.5, 6.0) if soft < 10 else rnd.uniform(0.6, 2.5)
        sun.data.energy = strength
        warm = rnd.uniform(-0.08, 0.10)
        sun.data.color = (1.0, 1.0 - warm * 0.5, 1.0 - warm)
        sky = rnd.uniform(0.15, 0.9)
        bg.inputs[0].default_value = (0.55, 0.65, 0.85, 1.0) if rnd.random() < 0.6 else (0.8, 0.8, 0.8, 1.0)
        bg.inputs[1].default_value = sky
        return dict(elev=se, az=sa, soft=soft, strength=strength, sky=sky)

    def proj(p):
        v = world_to_camera_view(sc, cam, Vector(p)); return (v.x * SPRITE, (1 - v.y) * SPRITE)

    log = open(os.path.join(a.out, "render.log"), "a")
    t_all = time.time()
    for i in range(a.first, a.first + a.cars):
        sp = random_spec(i, a.seed)
        cdir = os.path.join(a.out, sp["name"]); os.makedirs(cdir, exist_ok=True)
        done_flag = os.path.join(cdir, "done")
        if os.path.exists(done_flag): continue
        json.dump(sp, open(os.path.join(cdir, "car.json"), "w"), indent=1)
        rnd = random.Random(sp["seed"] ^ 0xA11A)
        # build the car at the origin, nose +Y
        for o in list(sc.objects):
            if o.name.startswith("car"): bpy.data.objects.remove(o, do_unlink=True)
        for coll in (bpy.data.meshes, bpy.data.materials, bpy.data.images):   # textures of the previous car
            for d in list(coll):
                if d.users == 0: coll.remove(d)
        minis10.SPECS = [sp]
        root = minis10.build_car(sp, (0, 0, 0))
        parts = [ch for ch in root.children_recursive if ch.type == "MESH"]
        L = sp["L"] / 100
        t0 = time.time(); n = 0
        for elev in elevations:
            for az in az_for(elev):
                view = f"e{elev:02d}_a{az:03d}"
                if os.path.exists(os.path.join(cdir, f"{view}.json")): continue
                place_camera(elev, az, L)
                sun_meta = random_sun(rnd)
                bpy.context.view_layer.update()
                # pass 1: the car alone
                ground.hide_render = True
                for p in parts: p.visible_camera = True
                sc.render.filepath = os.path.join(cdir, f"{view}_car.png"); bpy.ops.render.render(write_still=True)
                # pass 2: only its shadow on the catcher
                ground.hide_render = False
                for p in parts: p.visible_camera = False
                sc.render.filepath = os.path.join(cdir, f"{view}_shadow.png"); bpy.ops.render.render(write_still=True)
                for p in parts: p.visible_camera = True
                # metadata: where the nose, tail, centre and the body corners land in the sprite
                W, H = sp["W"] / 100, sp["H"] / 100
                nose = proj((0, L / 2, H * 0.4)); tail = proj((0, -L / 2, H * 0.4)); centre = proj((0, 0, H * 0.4)); foot = proj((0, 0, 0))
                box = [proj((sx * W / 2, sy * L / 2, sz * H)) for sx in (-1, 1) for sy in (-1, 1) for sz in (0, 1)]
                xs = [b[0] for b in box]; ys = [b[1] for b in box]
                # sprite pixels per real centimetre at the car (from the lens; independent of foreshortening)
                px_per_cm = cam.data.lens * SPRITE / (36 * CAM_DIST * 100)
                json.dump(dict(car=sp["name"], elev=elev, az=az, sun=sun_meta, L_cm=sp["L"], W_cm=sp["W"], H_cm=sp["H"],
                               nose=nose, tail=tail, centre=centre, foot=foot, bbox=[min(xs), min(ys), max(xs), max(ys)],
                               px_per_cm=px_per_cm, sprite=SPRITE, cam_dist_m=CAM_DIST, lens_mm=cam.data.lens),
                          open(os.path.join(cdir, f"{view}.json"), "w"))
                n += 1
        open(done_flag, "w").write("ok")
        print(f"{sp['name']} {sp['kind']} {n} views {time.time() - t0:.0f} s", file=log, flush=True)
        print(f"{sp['name']} {sp['kind']} {n} views {time.time() - t0:.0f} s", flush=True)
    print(f"DONE {a.cars} cars in {(time.time() - t_all) / 60:.1f} min", file=log, flush=True)

if __name__ == "__main__":
    main()
