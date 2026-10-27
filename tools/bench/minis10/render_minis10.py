"""Overhead passes for the 10 minis (minis10.py), same rig and passes as render_cars10.py.
(lanes -0.2,-0.1,0.1,0.2 at 5.5 m/s; lane 0 at 7.5 m/s), each car alone. Output: form/minis10/<car>_<kind>_<lane>_<speed>/NNNN.png + truth.json."""
import bpy, sys, os, json, math, time
sys.path.insert(0, '/home/claude/blend')
from bpy_extras.object_utils import world_to_camera_view
OUT = '/home/claude/blend/form/minis10'; LINE_Y = -0.075
os.makedirs(OUT, exist_ok=True)
log = open(f'{OUT}/render.log', 'a')

def setup():
    bpy.ops.wm.open_mainfile(filepath='/home/claude/bd/blend/AnyLapBlender.blend')
    import minis10 as cars10
    sc = bpy.context.scene
    for o in sc.objects:
        if o.animation_data:
            mw = o.matrix_world.copy(); o.animation_data_clear(); o.matrix_world = mw
    for n in ('Icosphere', 'Icosphere.001'): sc.objects[n].hide_render = True
    cars = cars10.build_cars(sc)
    for name, root in cars:
        root.location = (0, -5, 0)   # parked far outside the view
    cam = sc.camera; cam.location = (0.0, LINE_Y, 1.8); cam.rotation_euler = (0, 0, -math.pi / 2)
    bpy.context.view_layer.update()
    r = sc.render; r.resolution_x, r.resolution_y, r.resolution_percentage = 480, 270, 100
    c = sc.cycles; c.device = 'CPU'; c.samples = 8; c.use_adaptive_sampling = True; c.use_denoising = True
    r.image_settings.file_format = 'PNG'
    return sc, dict(cars)

def img(sc, p):
    v = world_to_camera_view(sc, sc.camera, p); return (v.x, 1 - v.y)

sc, cars = setup()
sc.render.filepath = f'{OUT}/empty.png'
if not os.path.exists(sc.render.filepath): bpy.ops.render.render(write_still=True)
from mathutils import Vector
a = img(sc, Vector((0.405, LINE_Y, 0))); b = img(sc, Vector((-0.405, LINE_Y, 0)))
p = img(sc, Vector((0, LINE_Y - 0.5, 0.03))); q = img(sc, Vector((0, LINE_Y + 0.5, 0.03)))
mpp = 1.0 / (math.hypot((q[0] - p[0]) * 480, (q[1] - p[1]) * 270))
along = {f"{x/100:.2f}": (lambda c: ((c[0] - a[0]) * (b[0] - a[0]) + (c[1] - a[1]) * (b[1] - a[1])) / ((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2))(img(sc, Vector((x / 100, LINE_Y, 0.03)))) for x in range(-45, 46)}
json.dump({"line": [a, b], "metersPerProcPx": mpp, "along": along, "cars": list(cars.keys())}, open(f'{OUT}/meta.json', 'w'))

def render_pass(name, kind, lane, speed):
    d = f'{OUT}/{name.replace(" ", "_")}_{kind}_{lane:+.1f}_{speed}'
    if os.path.exists(f'{d}/truth.json'): return
    os.makedirs(d, exist_ok=True)
    sc, cars = setup()
    root = cars[name]
    y0, y1 = -1.5, 1.5
    n = int(math.ceil((y1 - y0) / speed * 60)) + 1
    root.location = (lane, y0, 0); root.keyframe_insert('location', frame=1)
    root.location = (lane, y0 + speed * (n - 1) / 60, 0); root.keyframe_insert('location', frame=n)
    for fc in root.animation_data.action.fcurves:
        for k in fc.keyframe_points: k.interpolation = 'LINEAR'
    tc = 1 + ((LINE_Y - y0) / speed) * 60
    t0 = time.time(); kept = []
    for f in range(1, n + 1):
        sc.frame_set(f)
        v = img(sc, root.matrix_world.translation)
        if not (-0.12 <= v[0] <= 1.12): continue
        pth = f'{d}/{f:04d}.png'
        if not os.path.exists(pth): sc.render.filepath = pth; bpy.ops.render.render(write_still=True)
        kept.append(f)
    json.dump({"car": name.replace(" ", "_"), "kind": kind, "lane": lane, "speed": speed, "crossFrame": tc, "firstFrame": kept[0]}, open(f'{d}/truth.json', 'w'))
    print(name, kind, lane, speed, len(kept), "frames", round(time.time() - t0), "s", file=log, flush=True)

names = list(cars.keys())
for nm in names:
    render_pass(nm, 'cal', 0.0, 3.5)
    render_pass(nm, 'cal', 0.0, 7.0)
for nm in names:
    for lane in (-0.2, -0.1, 0.1, 0.2): render_pass(nm, 'race', lane, 5.5)
    render_pass(nm, 'race', 0.0, 7.5)
print("DONE", file=log, flush=True)
