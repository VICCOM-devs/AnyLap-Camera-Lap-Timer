"""Overhead passes for the minis10 grid, each car alone, 1.8 m straight above the line.
cal: lane 0 at 3.5 and 7.0 m/s · race: lanes -0.2,-0.1,0.1,0.2 at 5.5 m/s, lane 0 at 7.5 m/s ·
diag: crossing the line at +25 deg (lane -0.05) and -38 deg (lane +0.05) at 5.5 m/s (car yawed along its path) ·
gate test (Red 7 only): 55 deg.  truth.json: crossFrame (centre), noseFrame (front-most point), tailFrame."""
import os, bpy, sys, json, math, time
sys.path.insert(0, '/home/claude/blend')
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector, Matrix
LAYER = os.environ.get('LAYER') == '1'   # car + its shadow only (transparent film, ground = shadow catcher)
OUT = '/home/claude/blend/form/minis_layer' if LAYER else '/home/claude/blend/form/minis10'; LINE_Y = -0.075
os.makedirs(OUT, exist_ok=True)
log = open(f'{OUT}/render.log', 'a')
SAMPLES = int(os.environ.get('SAMPLES', '8'))

def setup(only=None):
    bpy.ops.wm.open_mainfile(filepath='/home/claude/bd/blend/AnyLapBlender.blend')
    import importlib, minis10; importlib.reload(minis10)
    sc = bpy.context.scene
    for o in sc.objects:
        if o.animation_data:
            mw = o.matrix_world.copy(); o.animation_data_clear(); o.matrix_world = mw
    for n in ('Icosphere', 'Icosphere.001'):
        if n in sc.objects: sc.objects[n].hide_render = True
    if only: minis10.SPECS = [sp for sp in minis10.SPECS if sp['name'] == only]   # just the car being rendered
    cars = minis10.build_cars(sc)
    for name, root in cars: root.location = (0, -5, 0)
    cam = sc.camera; cam.location = (0.0, LINE_Y, 1.8); cam.rotation_euler = (0, 0, -math.pi / 2)
    bpy.context.view_layer.update()
    r = sc.render; r.resolution_x, r.resolution_y, r.resolution_percentage = 480, 270, 100
    c = sc.cycles; c.device = 'CPU'; c.samples = SAMPLES; c.use_adaptive_sampling = True; c.use_denoising = True
    r.image_settings.file_format = 'PNG'
    if LAYER:
        r.film_transparent = True; r.image_settings.color_mode = 'RGBA'
        for o in sc.objects:
            if o.type == 'MESH' and o.name in ('Plane', 'Bana', 'Cube.001', 'Cube.002'): o.is_shadow_catcher = True
    return sc, dict(cars)

def img(sc, p):
    v = world_to_camera_view(sc, sc.camera, p); return (v.x, 1 - v.y)

def corners(root):
    pts = []
    for ch in root.children_recursive:
        if ch.type != 'MESH': continue
        M = root.matrix_world.inverted() @ ch.matrix_world
        pts += [M @ Vector(c) for c in ch.bound_box]
    return pts

sc, cars = setup()
ALL = list(cars.keys())
sc.render.filepath = f'{OUT}/empty.png'
if not os.path.exists(sc.render.filepath): bpy.ops.render.render(write_still=True)
a = img(sc, Vector((0.405, LINE_Y, 0))); b = img(sc, Vector((-0.405, LINE_Y, 0)))
p = img(sc, Vector((0, LINE_Y - 0.5, 0.03))); q = img(sc, Vector((0, LINE_Y + 0.5, 0.03)))
mpp = 1.0 / (math.hypot((q[0] - p[0]) * 480, (q[1] - p[1]) * 270))
along = {f"{x/100:.2f}": (lambda c: ((c[0] - a[0]) * (b[0] - a[0]) + (c[1] - a[1]) * (b[1] - a[1])) / ((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2))(img(sc, Vector((x / 100, LINE_Y, 0.03)))) for x in range(-45, 46)}
if os.environ.get('MINIS') != 'twins': json.dump({"line": [a, b], "metersPerProcPx": mpp, "along": along, "cars": list(cars.keys())}, open(f'{OUT}/meta.json', 'w'))

def render_pass(name, kind, lane, speed, ang=0.0):
    d = f'{OUT}/{name.replace(" ", "_")}_{kind}_{lane:+.2f}_{speed}_{ang:+.0f}'
    if os.path.exists(f'{d}/truth.json'): return
    os.makedirs(d, exist_ok=True)
    sc, cars = setup(name)
    root = cars[name]
    ar = math.radians(ang); vy = speed * math.cos(ar); vx = speed * math.sin(ar)
    root.rotation_euler = (0, 0, -ar)
    bpy.context.view_layer.update()
    pts = corners(root)
    R = Matrix.Rotation(-ar, 3, 'Z')
    ys = [(R @ c).y for c in pts]
    front, back = max(ys), min(ys)
    y0, y1 = -1.5, 1.5
    n = int(math.ceil((y1 - y0) / vy * 60)) + 1
    def at(f):  # root position at frame f (1-based), crossing lane at the line
        y = y0 + vy * (f - 1) / 60
        return (lane + (y - LINE_Y) * math.tan(ar), y, 0)
    root.location = at(1); root.keyframe_insert('location', frame=1)
    root.location = at(n); root.keyframe_insert('location', frame=n)
    act = root.animation_data.action
    fcs = act.fcurves if hasattr(act, 'fcurves') else [fc for l in act.layers for s in l.strips for cb in s.channelbags for fc in cb.fcurves]
    for fc in fcs:
        for k in fc.keyframe_points: k.interpolation = 'LINEAR'
    fr = lambda yc: 1 + ((yc - y0) / vy) * 60
    tc, tn, tt = fr(LINE_Y), fr(LINE_Y - front), fr(LINE_Y - back)
    t0 = time.time(); kept = []
    for f in range(1, n + 1):
        sc.frame_set(f)
        v = img(sc, root.matrix_world.translation)
        if abs(root.matrix_world.translation.y - LINE_Y) > 0.8: continue
        pth = f'{d}/{f:04d}.png'
        if not os.path.exists(pth): sc.render.filepath = pth; bpy.ops.render.render(write_still=True)
        kept.append(f)
    json.dump({"car": name.replace(" ", "_"), "kind": kind, "lane": lane, "speed": speed, "angle": ang,
               "crossFrame": tc, "noseFrame": tn, "tailFrame": tt, "firstFrame": kept[0],
               "front_m": front, "back_m": back}, open(f'{d}/truth.json', 'w'))
    print(name, kind, lane, speed, ang, len(kept), "frames", round(time.time() - t0), "s", file=log, flush=True)

names = ALL
only = sys.argv[1:]
for nm in names:
    if only and nm.replace(' ', '_') not in only: continue
    render_pass(nm, 'cal', 0.0, 3.5); render_pass(nm, 'cal', 0.0, 7.0)
    for lane in (-0.2, -0.1, 0.1, 0.2): render_pass(nm, 'race', lane, 5.5)
    render_pass(nm, 'race', 0.0, 7.5)
    render_pass(nm, 'diag', -0.05, 5.5, 25.0); render_pass(nm, 'diag', 0.05, 5.5, -38.0)
if not only and os.environ.get('MINIS') != 'twins': render_pass('Red 7', 'gate', 0.0, 5.5, 55.0)
print("DONE", file=log, flush=True)
