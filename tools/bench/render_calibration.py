import bpy, os, time, json
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view
SRC = '/home/claude/blend/AnyLapBlender.blend'
OUT = '/home/claude/blend'
def setup():
    bpy.ops.wm.open_mainfile(filepath=SRC)
    sc = bpy.context.scene; r = sc.render
    r.resolution_x, r.resolution_y, r.resolution_percentage = 480, 270, 100
    c = sc.cycles; c.device = 'CPU'; c.samples = 12; c.use_adaptive_sampling = True; c.use_denoising = True
    r.image_settings.file_format = 'PNG'
    return sc
log = open(f'{OUT}/render.log', 'a')
def say(*a): print(*a, file=log, flush=True)

# ---------------- race, frames 1–80 as animated
sc = setup()
os.makedirs(f'{OUT}/race', exist_ok=True)
t0 = time.time()
for f in range(sc.frame_start, sc.frame_end + 1):
    p = f'{OUT}/race/{f:04d}.png'
    if os.path.exists(p): continue
    sc.frame_set(f); sc.render.filepath = p; bpy.ops.render.render(write_still=True)
say("race done", round(time.time() - t0), "s")

# ---------------- meta: line in image, metres per pixel at the line
sc = setup(); sc.frame_set(1); cam = sc.camera
def img(p):
    v = world_to_camera_view(sc, cam, p); return (v.x, 1 - v.y)
line = [img(Vector((0.405, -0.075, 0))), img(Vector((-0.405, -0.075, 0)))]
a = img(Vector((0, -0.575, 0.033))); b = img(Vector((0, 0.425, 0.033)))
mpp = 1.0 / (abs(b[0] - a[0]) * 480)
json.dump({"line": line, "metersPerProcPx": mpp}, open(f'{OUT}/meta.json', 'w'))
say("meta", line, mpp)

# ---------------- calibration: each car alone, props frozen at frame 1, slow + fast pass in its race lane
cars = [o.name for o in sc.objects if o.name.endswith(' bil')]
import csv
lanes = {}
for row in csv.DictReader(open(f'{OUT}/crossings.csv')): lanes[row['car']] = float(row['x_lane'])
for name in cars:
    for speed in (3.0, 7.0):
        d = f'{OUT}/cal/{name.replace(" ", "_")}_{int(speed)}'
        os.makedirs(d, exist_ok=True)
        sc = setup(); sc.frame_set(1)
        for o in sc.objects:                       # freeze everything at its frame-1 pose
            if o.animation_data:
                mw = o.matrix_world.copy(); o.animation_data_clear(); o.matrix_world = mw
        for o in sc.objects:
            if o.name.endswith(' bil') and o.name != name:
                o.hide_render = True
        car = sc.objects[name]
        car.rotation_euler = (0, 0, 0)
        y0, y1 = -1.6, 1.6                          # well outside the view on both sides
        n = int(round((y1 - y0) / speed * 60))
        car.location = (lanes[name], y0, 0.033); car.keyframe_insert('location', frame=1)
        car.location = (lanes[name], y1, 0.033); car.keyframe_insert('location', frame=1 + n)
        for fc in car.animation_data.action.fcurves:
            for k in fc.keyframe_points: k.interpolation = 'LINEAR'
        t0 = time.time(); kept = 0
        for f in range(1, n + 2):
            sc.frame_set(f)
            v = world_to_camera_view(sc, sc.camera, car.matrix_world.translation)
            if not (-0.15 <= v.x <= 1.15): continue   # skip frames where the car is far outside
            p = f'{d}/{f:04d}.png'
            if not os.path.exists(p):
                sc.render.filepath = p; bpy.ops.render.render(write_still=True)
            kept += 1
        # truth for this pass: crossing frame
        tc = 1 + ((-0.075) - y0) / (y1 - y0) * n
        json.dump({"car": name, "speed": speed, "lane": lanes[name], "crossFrame": tc, "frames": n + 1}, open(f'{d}/truth.json', 'w'))
        say("cal", name, speed, kept, "frames", round(time.time() - t0), "s")
say("ALL DONE")
