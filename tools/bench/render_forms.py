"""Formation clips from AnyLapBlender.blend under three camera rigs, both directions, plus
per-camera calibration passes (each car alone) and one empty frame. Truth = exact crossing frames
from the linear motion. Usage: python -I render_forms.py <cam>   (cam = 45 | steep | top)"""
import bpy, os, sys, json, math, time
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view
import os
SRC = '/home/claude/blend/AnyLabBlender.blend'.replace('AnyLab', 'AnyLap'); OUT = os.environ.get('FORM_OUT', '/home/claude/blend/form')
RES = int(os.environ.get('FORM_RES', '480'))
CAM = sys.argv[-1]
LINE_Y = -0.075
CARS = ['Vit bil', 'Blå bil', 'Röd bil', 'Grön bil', 'Orange bil', 'Gul bil', 'Rosa bil', 'Grå bil', 'Svart bil']
L = 0.16; W = 0.091  # car length (along travel) and width

def setup():
    bpy.ops.wm.open_mainfile(filepath=SRC)
    sc = bpy.context.scene; r = sc.render
    r.resolution_x, r.resolution_y, r.resolution_percentage = RES, RES * 9 // 16, 100
    c = sc.cycles; c.device = 'CPU'; c.samples = 8; c.use_adaptive_sampling = True; c.use_denoising = True
    r.image_settings.file_format = 'PNG'
    sc.frame_set(1)
    for o in sc.objects:                       # freeze props and cars at their frame-1 pose
        if o.animation_data:
            mw = o.matrix_world.copy(); o.animation_data_clear(); o.matrix_world = mw
    cam = sc.camera
    if CAM == 'steep':
        cam.location = (0.75, 0.125, 1.9)
        aim = Vector((0.0, LINE_Y, 0.0)) - cam.location
        cam.rotation_euler = aim.to_track_quat('-Z', 'Y').to_euler()
    elif CAM in ('top', 'topflat'):
        cam.location = (0.0, LINE_Y, 1.8)
        cam.rotation_euler = (0.0, 0.0, -math.pi / 2)   # straight down, track runs across the image
    for o in sc.objects:
        if o.name.endswith(' bil'): o.hide_render = True; o.rotation_euler = (0, 0, 0)
    if CAM != '45':
        # the floating icospheres sit between a high camera and the track; the legs stay
        for n in ('Icosphere', 'Icosphere.001'): sc.objects[n].hide_render = True
    if CAM == 'topflat':
        # no shadows at all: lights cast none, and no walking legs
        for n in ('Cube.001', 'Cube.002'): sc.objects[n].hide_render = True
        for o in sc.objects:
            if o.type == 'LIGHT': o.data.cycles.cast_shadow = False
    return sc

def img(sc, p):
    v = world_to_camera_view(sc, sc.camera, Vector(p)); return (v.x, 1 - v.y)

def meta(sc):
    a = img(sc, (0.405, LINE_Y, 0)); b = img(sc, (-0.405, LINE_Y, 0))
    p = img(sc, (0, LINE_Y - 0.5, 0.033)); q = img(sc, (0, LINE_Y + 0.5, 0.033))
    mpp = 1.0 / (math.hypot((q[0] - p[0]) * 480, (q[1] - p[1]) * 270))
    along = {}
    for x in [i / 100 for i in range(-45, 46)]:
        c = img(sc, (x, LINE_Y, 0.033))
        dx, dy = b[0] - a[0], b[1] - a[1]
        along[f"{x:.2f}"] = ((c[0] - a[0]) * dx + (c[1] - a[1]) * dy) / (dx * dx + dy * dy)
    return {"line": [a, b], "metersPerProcPx": mpp, "along": along, "cam": CAM, "camLoc": list(sc.camera.location)}

ONLY = os.environ.get('FORM_ONLY', '').split(',') if os.environ.get('FORM_ONLY') else None
def render_clip(name, cars, log):
    if ONLY is not None and not any(name.startswith(o) for o in ONLY): return
    """cars: list of (carName, laneX, offsetM, speedMps, dir). y(t) = start + dir*speed*t + offset."""
    d = f'{OUT}/{CAM}/{name}'
    if os.path.exists(f'{d}/truth.json'): return
    os.makedirs(d, exist_ok=True)
    sc = setup()
    y_extent = 1.9 if CAM != 'top' else 1.4
    objs = []
    for (cn, lane, off, speed, dr) in cars:
        o = sc.objects[cn]; o.hide_render = False; objs.append((o, lane, off, speed, dr))
    # time so the first car starts outside and the last ends outside
    spd = max(c[3] for c in cars); dr = cars[0][4]
    offs = [c[2] for c in cars]
    y0 = -dr * y_extent - (max(offs) if dr > 0 else min(offs))
    total = (2 * y_extent + (max(offs) - min(offs))) / spd
    n = int(math.ceil(total * 60)) + 2
    for (o, lane, off, speed, d_) in objs:
        o.location = (lane, y0 + off, 0.033); o.keyframe_insert('location', frame=1)
        o.location = (lane, y0 + off + d_ * speed * (n - 1) / 60, 0.033); o.keyframe_insert('location', frame=n)
        for fc in o.animation_data.action.fcurves:
            for k in fc.keyframe_points: k.interpolation = 'LINEAR'
    truth = []
    for (o, lane, off, speed, d_) in objs:
        tc = 1 + ((LINE_Y - (y0 + off)) / (d_ * speed)) * 60
        truth.append({"car": o.name.replace(' ', '_'), "lane": lane, "speed": speed, "dir": d_, "crossFrame": tc})
    t0 = time.time(); kept = []
    for f in range(1, n + 1):
        sc.frame_set(f)
        vis = any(-0.15 <= img(sc, o.matrix_world.translation)[0] <= 1.15 for (o, *_rest) in objs)
        if not vis: continue
        p = f'{d}/{f:04d}.png'
        if not os.path.exists(p): sc.render.filepath = p; bpy.ops.render.render(write_still=True)
        kept.append(f)
    json.dump({"cars": truth, "firstFrame": kept[0], "frames": kept}, open(f'{d}/truth.json', 'w'))
    print(CAM, name, len(kept), "frames", round(time.time() - t0), "s", file=log, flush=True)

log = open(f'{OUT}/render_{CAM}.log', 'a')
os.makedirs(f'{OUT}/{CAM}', exist_ok=True)
sc = setup()
json.dump(meta(sc), open(f'{OUT}/{CAM}/meta.json', 'w'))
sc.render.filepath = f'{OUT}/{CAM}/empty.png'
if not os.path.exists(sc.render.filepath): bpy.ops.render.render(write_still=True)

# calibration (not for 45: already rendered earlier)
if CAM not in ('45', 'topflat'):
    for i, c in enumerate(CARS):
        lane = [-0.1, 0.1][i % 2]
        render_clip(f'cal_{c.replace(" ", "_")}_3', [(c, lane, 0, 3.5, 1)], log)
        render_clip(f'cal_{c.replace(" ", "_")}_7', [(c, lane, 0, 7.0, 1)], log)

G = 0.01  # 1 cm gaps
for dr, dn in ((1, 'R'), (-1, 'L')):
    render_clip(f'tail2_{dn}', [('Rosa bil', 0.0, 0, 5.0, dr), ('Orange bil', 0.0, -dr * (L + G), 5.0, dr)], log)
    render_clip(f'tail3_{dn}', [('Blå bil', -0.2, 0, 6.0, dr), ('Grön bil', -0.2, -dr * (L + G), 6.0, dr), ('Gul bil', -0.2, -dr * 2 * (L + G), 6.0, dr)], log)
    render_clip(f'abreast3_{dn}', [('Svart bil', -(W + G), 0, 5.5, dr), ('Rosa bil', 0.0, 0, 5.5, dr), ('Grön bil', W + G, 0, 5.5, dr)], log)
    render_clip(f'abreast4_{dn}', [('Grå bil', -1.5 * (W + G), 0, 5.0, dr), ('Vit bil', -0.5 * (W + G), 0, 5.0, dr), ('Röd bil', 0.5 * (W + G), 0, 5.0, dr), ('Orange bil', 1.5 * (W + G), 0, 5.0, dr)], log)
    # a clump: 2 abreast, 1 in the middle behind, 2 abreast behind that – all 1 cm apart
    render_clip(f'clump5_{dn}', [('Röd bil', -0.5 * (W + G), 0, 5.0, dr), ('Blå bil', 0.5 * (W + G), 0, 5.0, dr),
                                  ('Gul bil', 0.0, -dr * (L + G), 5.0, dr),
                                  ('Vit bil', -0.5 * (W + G), -dr * 2 * (L + G), 5.0, dr), ('Grå bil', 0.5 * (W + G), -dr * 2 * (L + G), 5.0, dr)], log)
    # look-alikes abreast: white + grey touching, black right behind
    render_clip(f'lookalike_{dn}', [('Vit bil', -0.5 * (W + G), 0, 5.0, dr), ('Grå bil', 0.5 * (W + G), 0, 5.0, dr), ('Svart bil', 0.0, -dr * (L + G), 5.0, dr)], log)
print("DONE", file=log, flush=True)
