"""Studio renders of the 10 minis: per-car 3/4, side, top + a lineup. Usage:
python -I minis_studio.py [per|lineup] [samples] [names...]"""
import bpy, sys, math, os
sys.path.insert(0, '/home/claude/blend')
import minis10
from mathutils import Vector
OUT = '/home/claude/blend/minis_out'
mode = sys.argv[1] if len(sys.argv) > 1 else 'per'
samples = int(sys.argv[2]) if len(sys.argv) > 2 else 16
only = sys.argv[3:]

def scene_base(res):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = 'CYCLES'; c = sc.cycles; c.device = 'CPU'; c.samples = samples; c.use_denoising = True
    sc.render.resolution_x, sc.render.resolution_y = res; sc.render.resolution_percentage = 100
    sc.view_settings.view_transform = 'Standard'
    w = bpy.data.worlds.new('w'); sc.world = w; w.use_nodes = True
    w.node_tree.nodes['Background'].inputs['Color'].default_value = (0.55, 0.57, 0.62, 1)
    w.node_tree.nodes['Background'].inputs['Strength'].default_value = 0.3
    bpy.ops.mesh.primitive_plane_add(size=6, location=(0, 0, 0))
    fl = bpy.context.active_object; m = bpy.data.materials.new('floor'); m.use_nodes = True
    b = m.node_tree.nodes['Principled BSDF']; b.inputs['Base Color'].default_value = (0.16, 0.16, 0.17, 1); b.inputs['Roughness'].default_value = 0.6
    fl.data.materials.append(m)
    for loc, en, sz in (((1.2, -1.0, 1.6), 50, 1.2), ((-1.4, 0.6, 1.2), 22, 1.5), ((0, 1.5, 0.8), 14, 1.0)):
        bpy.ops.object.light_add(type='AREA', location=loc); l = bpy.context.active_object
        l.data.energy = en; l.data.size = sz
        d = Vector((0, 0, 0.03)) - l.location; l.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam')); sc.collection.objects.link(cam); sc.camera = cam
    return sc, cam

def aim(cam, loc, tgt, lens=85):
    cam.location = loc; cam.data.lens = lens
    cam.rotation_euler = (Vector(tgt) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()

if mode == 'per':
    for sp in minis10.SPECS:
        if only and sp['name'] not in only: continue
        sc, cam = scene_base((640, 400))
        minis10.build_car(sp, (0, 0, 0))
        tag = sp['name'].replace(' ', '_')
        for view, loc, tgt, lens in (('q', (0.42, 0.50, 0.26), (0, 0, 0.02), 60),
                                     ('s', (0.75, 0.0, 0.03), (0, 0, 0.025), 85),
                                     ('t', (0.0, 0.0, 0.9), (0, 0, 0), 85),
                                     ('r', (-0.38, -0.48, 0.22), (0, 0, 0.02), 60)):
            aim(cam, loc, tgt, lens)
            if view == 't': cam.rotation_euler = (0, 0, -math.pi / 2)
            sc.render.filepath = f'{OUT}/{tag}_{view}.png'; bpy.ops.render.render(write_still=True)
        print('done', tag, flush=True)
else:
    sc, cam = scene_base((1920, 1080))
    for i, sp in enumerate(minis10.SPECS):
        row, col = divmod(i, 5)
        x = (col - 2) * 0.13 + (0.065 if row else 0); y = -row * 0.26 + 0.13
        r = minis10.build_car(sp, (x, y, 0)); r.rotation_euler = (0, 0, math.radians(-25))
    aim(cam, (0.55, 0.95, 0.55), (0.02, -0.02, 0.0), 50)
    sc.render.filepath = f'{OUT}/lineup.png'; bpy.ops.render.render(write_still=True)
    bpy.ops.file.pack_all(); bpy.ops.wm.save_as_mainfile(filepath='/home/claude/blend/AnyLap_minis10_studio.blend')
