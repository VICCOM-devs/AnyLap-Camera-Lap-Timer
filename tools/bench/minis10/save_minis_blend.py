import bpy, sys
sys.path.insert(0, '/home/claude/blend')
bpy.ops.wm.open_mainfile(filepath='/home/claude/bd/blend/AnyLapBlender.blend')
import minis10
minis10.build_cars(bpy.context.scene)
bpy.ops.file.pack_all()
bpy.ops.wm.save_as_mainfile(filepath='/home/claude/blend/AnyLap_minis10.blend', compress=True)
