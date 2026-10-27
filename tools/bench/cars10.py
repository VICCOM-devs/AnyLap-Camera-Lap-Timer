"""Ten Mini-Z-style cars with different sizes, shapes, colours and liveries, built procedurally
in the AnyLap scene (replacing the box cars). Each car = bevelled body, cabin, 4 wheels, optional
spoiler, roof number, side stripe / two-tone / roof panel. Usage inside bpy after opening the scene:
  cars = build_cars(scene)  -> list of (name, object)
"""
import bpy, math
from mathutils import Vector

# name, length cm, width cm, height cm, body RGB, livery kind, livery RGB, number, spoiler
CARS = [
    ("Red 7",        17.0, 9.0, 4.8, (0.80, 0.05, 0.04), "stripe",  (1.00, 1.00, 1.00), "7",  True),
    ("White 12",     16.5, 8.8, 4.5, (0.92, 0.92, 0.90), "stripe",  (0.80, 0.05, 0.04), "12", False),
    ("White 21",     16.0, 9.2, 5.2, (0.92, 0.92, 0.90), "roof",    (0.05, 0.15, 0.80), "21", True),
    ("Blue 3",       17.5, 9.5, 4.6, (0.06, 0.20, 0.85), "twotone", (0.95, 0.95, 0.95), "3",  False),
    ("Yellow 44",    15.5, 8.6, 4.2, (0.95, 0.80, 0.05), "stripe",  (0.02, 0.02, 0.02), "44", True),
    ("Green 9",      16.2, 9.0, 5.0, (0.05, 0.55, 0.15), "roof",    (0.95, 0.95, 0.95), "9",  False),
    ("Silver 5",     16.8, 9.1, 4.4, (0.70, 0.72, 0.76), "twotone", (0.10, 0.10, 0.12), "5",  True),
    ("Black 16",     17.0, 9.3, 4.9, (0.03, 0.03, 0.03), "stripe",  (0.90, 0.60, 0.05), "16", True),
    ("Orange 23",    15.8, 8.7, 5.4, (0.95, 0.40, 0.03), "roof",    (0.05, 0.05, 0.05), "23", False),
    ("Pink 8",       16.4, 8.9, 4.7, (0.95, 0.20, 0.60), "twotone", (0.05, 0.05, 0.05), "8",  False),
]

def mat(name, rgb, rough=0.35, metal=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1.0)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    return m

def box(name, size, loc, m, bevel=0.0, parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=loc)
    o = bpy.context.active_object
    o.name = name
    o.scale = (size[0], size[1], size[2])
    o.data.materials.append(m)
    if bevel > 0:
        mod = o.modifiers.new("bevel", "BEVEL"); mod.width = bevel; mod.segments = 3
    if parent is not None:
        o.parent = parent
    return o

def build_car(spec, origin):
    name, L, W, H, rgb, livery, lrgb, number, spoiler = spec
    L /= 100; W /= 100; H /= 100
    root = bpy.data.objects.new(name, None); bpy.context.collection.objects.link(root)
    root.location = origin
    body_m = mat(f"{name} body", rgb, rough=0.25, metal=0.3 if "Silver" in name else 0.0)
    liv_m = mat(f"{name} livery", lrgb, rough=0.35)
    dark = mat(f"{name} dark", (0.02, 0.02, 0.02), rough=0.6)
    glass = mat(f"{name} glass", (0.05, 0.06, 0.08), rough=0.1)
    # chassis + body (y = travel axis)
    body = box("body", (W, L, H * 0.55), (0, 0, 0.012 + H * 0.275), body_m, bevel=0.006, parent=root)
    # cabin
    cab = box("cabin", (W * 0.82, L * 0.45, H * 0.45), (0, -L * 0.05, 0.012 + H * 0.55 + H * 0.2), glass, bevel=0.004, parent=root)
    roof = box("roof", (W * 0.78, L * 0.30, 0.004), (0, -L * 0.07, 0.012 + H * 0.55 + H * 0.425), liv_m if livery == "roof" else body_m, parent=root)
    # livery
    if livery == "stripe":
        box("stripe", (W * 0.18, L * 0.98, 0.003), (0, 0, 0.012 + H * 0.55 + 0.0015), liv_m, parent=root)
        box("stripeL", (0.003, L * 0.9, H * 0.18), (-W / 2 - 0.001, 0, 0.012 + H * 0.30), liv_m, parent=root)
        box("stripeR", (0.003, L * 0.9, H * 0.18), (W / 2 + 0.001, 0, 0.012 + H * 0.30), liv_m, parent=root)
    elif livery == "twotone":
        box("front", (W + 0.002, L * 0.42, H * 0.55 + 0.002), (0, L * 0.29, 0.012 + H * 0.275), liv_m, bevel=0.006, parent=root)
    # roof number (a text object, dark on light / light on dark)
    bpy.ops.object.text_add(location=(0, 0, 0))
    t = bpy.context.active_object
    t.data.body = number; t.data.align_x = 'CENTER'; t.data.align_y = 'CENTER'; t.data.size = W * 0.6
    t.data.extrude = 0.0005
    t.rotation_euler = (0, 0, -math.pi / 2)
    t.location = (0, -L * 0.07, 0.012 + H * 0.55 + H * 0.425 + 0.003)
    lum = 0.2126 * (lrgb[0] if livery == "roof" else rgb[0]) + 0.7152 * (lrgb[1] if livery == "roof" else rgb[1]) + 0.0722 * (lrgb[2] if livery == "roof" else rgb[2])
    t.data.materials.append(dark if lum > 0.4 else mat(f"{name} num", (0.95, 0.95, 0.95)))
    t.parent = root
    # wheels
    for sx in (-1, 1):
        for sy in (-1, 1):
            bpy.ops.mesh.primitive_cylinder_add(radius=0.012, depth=0.011, location=(sx * (W / 2 - 0.004), sy * L * 0.32, 0.012))
            wh = bpy.context.active_object
            wh.rotation_euler = (0, math.pi / 2, 0)
            wh.data.materials.append(dark); wh.parent = root
    if spoiler:
        box("spoiler", (W * 0.95, 0.012, 0.003), (0, -L / 2 + 0.008, 0.012 + H * 0.55 + H * 0.35), liv_m if livery != "roof" else body_m, parent=root)
        for sx in (-1, 1):
            box("spost", (0.004, 0.006, H * 0.35), (sx * W * 0.35, -L / 2 + 0.008, 0.012 + H * 0.55 + H * 0.17), dark, parent=root)
    # rear/front lights
    box("rear", (W * 0.6, 0.002, H * 0.12), (0, -L / 2 - 0.001, 0.012 + H * 0.35), mat(f"{name} rl", (0.9, 0.05, 0.02)), parent=root)
    return root

def build_cars(scene):
    # hide the old box cars, keep everything else
    for o in scene.objects:
        if o.name.endswith(" bil"): o.hide_render = True; o.hide_viewport = True
    out = []
    for i, spec in enumerate(CARS):
        out.append((spec[0], build_car(spec, (0.0, -3.0 - i * 0.4, 0.0))))
    return out
