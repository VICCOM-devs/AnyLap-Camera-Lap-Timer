import bpy, csv, json
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view
bpy.ops.wm.open_mainfile(filepath='/home/claude/blend/AnyLapBlender.blend')
sc = bpy.context.scene; cam = sc.camera
def img(x, y, z):
    v = world_to_camera_view(sc, cam, Vector((x, y, z))); return (v.x * 480, (1 - v.y) * 270)
a = img(0.405, -0.075, 0); b = img(-0.405, -0.075, 0)
def along(x):
    p = img(x, -0.075, 0.033)
    dx, dy = b[0] - a[0], b[1] - a[1]
    return ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)
out = {str(round(x, 3)): along(x) for x in [i / 1000 for i in range(-450, 451)]}
json.dump(out, open('/home/claude/blend/along.json', 'w'))
for x in (-0.3, -0.1, 0.0, 0.1, 0.3): print(x, round(along(x), 3), "linear guess", round((0.405 - x) / 0.81, 3))
