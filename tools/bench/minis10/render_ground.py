"""Empty ground for the layer composites: the bench scene with the track and its surroundings re-surfaced.
usage: python -I render_ground.py <dirt|carpet>  ->  form/grounds/<name>.png (480x270, same camera as the passes)"""
import os, bpy, sys, math
OUT = '/home/claude/blend/form/grounds'; os.makedirs(OUT, exist_ok=True)
LINE_Y = -0.075
name = sys.argv[1]

bpy.ops.wm.open_mainfile(filepath='/home/claude/bd/blend/AnyLapBlender.blend')
sc = bpy.context.scene
for o in sc.objects:
    if o.animation_data:
        mw = o.matrix_world.copy(); o.animation_data_clear(); o.matrix_world = mw
    if o.name.endswith(' bil') or o.name.startswith('Icosphere'): o.hide_render = True
cam = sc.camera; cam.location = (0.0, LINE_Y, 1.8); cam.rotation_euler = (0, 0, -math.pi / 2)
r = sc.render; r.resolution_x, r.resolution_y, r.resolution_percentage = 480, 270, 100
c = sc.cycles; c.device = 'CPU'; c.samples = 32; c.use_adaptive_sampling = True; c.use_denoising = True
r.image_settings.file_format = 'PNG'


def mat(nm):
    m = bpy.data.materials.new(nm); m.use_nodes = True
    nt = m.node_tree; nt.nodes.clear()
    out = nt.nodes.new('ShaderNodeOutputMaterial'); bsdf = nt.nodes.new('ShaderNodeBsdfPrincipled')
    nt.links.new(bsdf.outputs[0], out.inputs[0])
    tc = nt.nodes.new('ShaderNodeTexCoord')
    return m, nt, bsdf, tc


def ramp(nt, src, stops):
    cr = nt.nodes.new('ShaderNodeValToRGB'); nt.links.new(src, cr.inputs[0])
    els = cr.color_ramp.elements
    while len(els) < len(stops): els.new(0.5)
    for e, (pos, col) in zip(els, stops): e.position = pos; e.color = (*col, 1)
    return cr


def noise(nt, vec, scale, detail=6.0, rough=0.6):
    n = nt.nodes.new('ShaderNodeTexNoise'); n.inputs['Scale'].default_value = scale
    n.inputs['Detail'].default_value = detail; n.inputs['Roughness'].default_value = rough
    nt.links.new(vec, n.inputs['Vector']); return n


def mix(nt, a, b, fac, mode='MIX'):
    m = nt.nodes.new('ShaderNodeMix'); m.data_type = 'RGBA'; m.blend_type = mode
    nt.links.new(fac, m.inputs['Factor']); nt.links.new(a, m.inputs[6]); nt.links.new(b, m.inputs[7])
    return m.outputs[2]


def dirt(nm, base_dark, base_light, ruts=True):
    m, nt, bsdf, tc = mat(nm)
    v = tc.outputs['Object']
    big = noise(nt, v, 3.0, 4)                                     # patches, a few dm
    mid = noise(nt, v, 40.0, 8)                                    # clods, ~cm
    col = ramp(nt, big.outputs['Fac'], [(0.35, base_dark), (0.65, base_light)])
    grain = ramp(nt, mid.outputs['Fac'], [(0.3, (0.6, 0.6, 0.6)), (0.7, (1.15, 1.1, 1.05))])
    c1 = mix(nt, col.outputs[0], grain.outputs[0], big.outputs['Fac'], 'MULTIPLY')
    vor = nt.nodes.new('ShaderNodeTexVoronoi'); vor.inputs['Scale'].default_value = 140.0   # pebbles 5–10 mm
    nt.links.new(v, vor.inputs['Vector'])
    peb = ramp(nt, vor.outputs['Distance'], [(0.0, (0.78, 0.74, 0.68)), (0.18, (0.78, 0.74, 0.68)), (0.22, (0, 0, 0))])
    pmask = nt.nodes.new('ShaderNodeMath'); pmask.operation = 'GREATER_THAN'; pmask.inputs[1].default_value = 0.55
    pn = noise(nt, v, 25.0, 2); nt.links.new(pn.outputs['Fac'], pmask.inputs[0])
    pm2 = nt.nodes.new('ShaderNodeMath'); pm2.operation = 'MULTIPLY'
    nt.links.new(pmask.outputs[0], pm2.inputs[0])
    sep = nt.nodes.new('ShaderNodeSeparateColor'); nt.links.new(peb.outputs[0], sep.inputs[0])
    nt.links.new(sep.outputs[0], pm2.inputs[1])
    c2 = mix(nt, c1, peb.outputs[0], pm2.outputs[0])
    if ruts:  # two darker, smoother tyre tracks along the road (x = ±0.22 m)
        sx = nt.nodes.new('ShaderNodeSeparateXYZ'); nt.links.new(v, sx.inputs[0])
        a = nt.nodes.new('ShaderNodeMath'); a.operation = 'ABSOLUTE'; nt.links.new(sx.outputs[0], a.inputs[0])
        d = nt.nodes.new('ShaderNodeMath'); d.operation = 'SUBTRACT'; d.inputs[1].default_value = 0.22; nt.links.new(a.outputs[0], d.inputs[0])
        a2 = nt.nodes.new('ShaderNodeMath'); a2.operation = 'ABSOLUTE'; nt.links.new(d.outputs[0], a2.inputs[0])
        rut = ramp(nt, a2.outputs[0], [(0.0, (0.55, 0.55, 0.55)), (0.06, (0.55, 0.55, 0.55)), (0.11, (1, 1, 1))])
        c2 = mix(nt, c2, rut.outputs[0], big.outputs['Fac'], 'MULTIPLY')
        nt.nodes[-1].inputs['Factor'].default_value = 1.0
    nt.links.new(c2, bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = 0.95
    bump = nt.nodes.new('ShaderNodeBump'); bump.inputs['Strength'].default_value = 0.35
    nt.links.new(mid.outputs['Fac'], bump.inputs['Height']); nt.links.new(bump.outputs[0], bsdf.inputs['Normal'])
    return m


def carpet(nm, base, var):
    m, nt, bsdf, tc = mat(nm)
    v = tc.outputs['Object']
    fib = noise(nt, v, 1500.0, 2, 0.5)                             # fibres (below a pixel: just noise)
    mot = noise(nt, v, 6.0, 5)                                     # wear and mottling
    col = ramp(nt, mot.outputs['Fac'], [(0.3, tuple(x * (1 - var) for x in base)), (0.7, tuple(min(1, x * (1 + var)) for x in base))])
    g = ramp(nt, fib.outputs['Fac'], [(0.35, (0.8, 0.8, 0.8)), (0.65, (1.12, 1.12, 1.12))])
    c = mix(nt, col.outputs[0], g.outputs[0], mot.outputs['Fac'], 'MULTIPLY')
    nt.nodes[-1].inputs['Factor'].default_value = 1.0
    nt.links.new(c, bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = 1.0
    if 'Sheen Weight' in bsdf.inputs: bsdf.inputs['Sheen Weight'].default_value = 0.5
    bump = nt.nodes.new('ShaderNodeBump'); bump.inputs['Strength'].default_value = 0.2
    nt.links.new(fib.outputs['Fac'], bump.inputs['Height']); nt.links.new(bump.outputs[0], bsdf.inputs['Normal'])
    return m


if name == 'dirt':
    road = dirt('dirt road', (0.20, 0.13, 0.08), (0.42, 0.30, 0.19))
    verge = dirt('dirt verge', (0.10, 0.12, 0.05), (0.25, 0.22, 0.10), ruts=False)
elif name == 'carpet':
    road = carpet('track carpet', (0.16, 0.18, 0.22), 0.12)       # grey-blue RC carpet
    verge = carpet('border carpet', (0.05, 0.06, 0.07), 0.15)
else:
    raise SystemExit('dirt or carpet')
for o in sc.objects:
    if o.type != 'MESH': continue
    if o.name == 'Bana':
        for i in range(len(o.data.materials)): o.data.materials[i] = road
    elif o.name == 'Plane':
        for i in range(len(o.data.materials)): o.data.materials[i] = verge
r.filepath = f'{OUT}/{name}.png'
bpy.ops.render.render(write_still=True)
print('wrote', r.filepath)
