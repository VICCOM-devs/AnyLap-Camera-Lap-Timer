import bpy, os, time, json
from bpy_extras.object_utils import world_to_camera_view
SRC = '/home/claude/blend/AnyLapBlender.blend'; OUT = '/home/claude/blend'
LANES = [-0.3, -0.1, 0.1, 0.3]
def setup():
    bpy.ops.wm.open_mainfile(filepath=SRC)
    sc = bpy.context.scene; r = sc.render
    r.resolution_x, r.resolution_y, r.resolution_percentage = 480, 270, 100
    c = sc.cycles; c.device = 'CPU'; c.samples = 8; c.use_adaptive_sampling = True; c.use_denoising = True
    r.image_settings.file_format = 'PNG'; return sc
log = open(f'{OUT}/render.log', 'a')
sc = setup()
cars = sorted(o.name for o in sc.objects if o.name.endswith(' bil'))
for i, name in enumerate(cars):
    for speed, lane in ((5.5, LANES[i % 4]), (8.5, LANES[(i + 2) % 4])):
        d = f'{OUT}/pass/{name.replace(" ", "_")}_{speed}'
        os.makedirs(d, exist_ok=True)
        sc = setup(); sc.frame_set(1)
        for o in sc.objects:
            if o.animation_data:
                mw = o.matrix_world.copy(); o.animation_data_clear(); o.matrix_world = mw
        for o in sc.objects:
            if o.name.endswith(' bil') and o.name != name: o.hide_render = True
        car = sc.objects[name]; car.rotation_euler = (0, 0, 0)
        y0, y1 = -1.6, 1.6; n = int(round((y1 - y0) / speed * 60))
        car.location = (lane, y0, 0.033); car.keyframe_insert('location', frame=1)
        car.location = (lane, y1, 0.033); car.keyframe_insert('location', frame=1 + n)
        for fc in car.animation_data.action.fcurves:
            for k in fc.keyframe_points: k.interpolation = 'LINEAR'
        t0 = time.time(); kept = 0
        for f in range(1, n + 2):
            sc.frame_set(f)
            v = world_to_camera_view(sc, sc.camera, car.matrix_world.translation)
            if not (-0.15 <= v.x <= 1.15): continue
            p = f'{d}/{f:04d}.png'
            if not os.path.exists(p): sc.render.filepath = p; bpy.ops.render.render(write_still=True)
            kept += 1
        tc = 1 + ((-0.075) - y0) / (y1 - y0) * n
        json.dump({"car": name, "speed": speed, "lane": lane, "crossFrame": tc}, open(f'{d}/truth.json', 'w'))
        print("pass", name, speed, lane, kept, "frames", round(time.time() - t0), "s", file=log, flush=True)
print("PASSES DONE", file=log, flush=True)
