"""The 10 minis in the AnyLap scene seen by the bench camera (1.8 m straight above the line)."""
import bpy, sys, math
sys.path.insert(0, '/home/claude/blend')
LINE_Y = -0.075
bpy.ops.wm.open_mainfile(filepath='/home/claude/bd/blend/AnyLapBlender.blend')
import minis10
sc = bpy.context.scene
for o in sc.objects:
    if o.animation_data:
        mw = o.matrix_world.copy(); o.animation_data_clear(); o.matrix_world = mw
for n in ('Icosphere', 'Icosphere.001'):
    if n in sc.objects: sc.objects[n].hide_render = True
cars = minis10.build_cars(sc)
# two rows of five across the track, just before / on the line
for i, (nm, r) in enumerate(cars):
    row, col = divmod(i, 5)
    r.location = ((col - 2) * 0.13, LINE_Y + (0.12 if row == 0 else -0.10), 0)
cam = sc.camera; cam.location = (0.0, LINE_Y, 1.8); cam.rotation_euler = (0, 0, -math.pi / 2)
r = sc.render; r.resolution_x, r.resolution_y = int(sys.argv[1]), int(sys.argv[2]); r.resolution_percentage = 100
c = sc.cycles; c.device = 'CPU'; c.samples = int(sys.argv[3]); c.use_denoising = True
sc.frame_set(1)
r.image_settings.file_format = 'PNG'; r.filepath = sys.argv[4]; bpy.ops.render.render(write_still=True)
