"""Ten Mini-Z-scale race cars (1:27), built procedurally for the AnyLap bench.

Each car: a lofted body shell (super-ellipse sections with fenders, wheel arches, rounded nose/tail,
tumblehome), a lofted glass cabin, tyres + rims with spokes, chassis plate, wings / fins / splitters
per body type, painted livery texture (stripes, two-tone, sponsor decals with invented brands,
number roundels, panel lines, lights, vents) and optional damage (dents in the mesh, scratches,
rubber marks, torn decals, dirt).

Same API as cars10.py:  build_cars(scene) -> [(name, root_empty)]; root at the car's centre on the
ground, front = +Y.  Textures are written to TEX_DIR and packed into the .blend by the caller.
"""
import bpy, math, os, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

TEX_DIR = os.environ.get("MINIS_TEX", "/home/claude/blend/minis_tex")
def _font(*cands):
    """First font file that exists: Linux (DejaVu), Windows (Arial), macOS – the liveries render anywhere."""
    for c in cands:
        if os.path.exists(c): return c
    return cands[0]
_WF = os.path.join(os.environ.get("WINDIR", "C:/Windows"), "Fonts")
FONT_B = _font("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", os.path.join(_WF, "arialbd.ttf"), "/System/Library/Fonts/Supplemental/Arial Bold.ttf")
FONT_C = _font("/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf", os.path.join(_WF, "ARIALNB.TTF"), os.path.join(_WF, "arialbd.ttf"), "/System/Library/Fonts/Supplemental/Arial Narrow Bold.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf")
FONT_I = _font("/usr/share/fonts/truetype/dejavu/DejaVuSans-BoldOblique.ttf", os.path.join(_WF, "arialbi.ttf"), os.path.join(_WF, "arialbd.ttf"), "/System/Library/Fonts/Supplemental/Arial Bold Italic.ttf")
TEX = 2048

# ---------------------------------------------------------------- specs
# sizes in cm. kind decides the shape. damage 0 clean, 1 used, 2 bruised.
# Brands are invented (no real sponsors / trademarks).
SPECS = [
    dict(name="Red 7", kind="gt", L=17.0, W=8.7, H=4.6, wr=1.15, base=(0.78, 0.04, 0.03), acc=(0.97, 0.97, 0.95),
         acc2=(0.03, 0.03, 0.03), livery="twin", num="7", rim=(0.75, 0.75, 0.78), damage=0,
         sponsors=["VOLTAX", "NORDLINE", "KAPPA OIL"], seed=1),
    dict(name="Blue 14", kind="lmp", L=18.2, W=9.2, H=3.9, wr=1.15, base=(0.04, 0.16, 0.70), acc=(0.96, 0.96, 0.96),
         acc2=(0.95, 0.75, 0.05), livery="split", num="14", rim=(0.08, 0.08, 0.08), damage=1,
         sponsors=["AXELRO", "SKYFJORD", "ION CELL"], seed=2),
    dict(name="Yellow 2", kind="formula", L=19.5, W=9.0, H=3.5, wr=1.2, base=(0.97, 0.80, 0.04), acc=(0.03, 0.03, 0.03),
         acc2=(0.85, 0.05, 0.05), livery="nose", num="2", rim=(0.85, 0.85, 0.85), damage=0,
         sponsors=["BRAKKO", "LUMEN", "TORQ"], seed=3),
    dict(name="White 33", kind="rally", L=15.2, W=8.4, H=5.8, wr=1.4, base=(0.93, 0.93, 0.91), acc=(0.98, 0.42, 0.02),
         acc2=(0.05, 0.20, 0.75), livery="swoosh", num="33", rim=(0.95, 0.95, 0.95), damage=2,
         sponsors=["GRUSVÄG", "MOXI", "ARCTIC TYRES"], seed=4),
    dict(name="Green 51", kind="touring", L=17.8, W=8.8, H=4.9, wr=1.15, base=(0.04, 0.45, 0.16), acc=(0.80, 0.82, 0.85),
         acc2=(0.03, 0.03, 0.03), livery="band", num="51", rim=(0.80, 0.80, 0.82), damage=1,
         sponsors=["HELIX", "KORVO", "BYTE MOTORS"], seed=5),
    dict(name="Black 88", kind="drift", L=16.6, W=9.6, H=4.7, wr=1.2, base=(0.035, 0.035, 0.04), acc=(0.98, 0.25, 0.60),
         acc2=(0.10, 0.85, 0.90), livery="stickerbomb", num="88", rim=(0.95, 0.70, 0.05), damage=2, matte=True,
         sponsors=["SIDEWAYS", "NEON KAI", "ZORRO", "DRFT", "GRIP0"], seed=6),
    dict(name="Orange 4", kind="group5", L=17.2, W=9.4, H=4.4, wr=1.2, base=(0.97, 0.43, 0.04), acc=(0.04, 0.16, 0.55),
         acc2=(0.97, 0.97, 0.95), livery="stripes3", num="4", rim=(0.85, 0.85, 0.85), damage=1,
         sponsors=["RETRO", "OKTAN", "VALBY"], seed=7),
    dict(name="Silver 19", kind="super", L=17.4, W=9.0, H=4.1, wr=1.15, base=(0.66, 0.68, 0.71), acc=(0.80, 0.04, 0.04),
         acc2=(0.03, 0.03, 0.03), livery="centre", num="19", rim=(0.10, 0.10, 0.10), damage=0, metal=0.55,
         sponsors=["STERLA", "QUARTZ", "MIRA"], seed=8),
    dict(name="Purple 27", kind="stock", L=18.4, W=8.7, H=5.0, wr=1.15, base=(0.36, 0.06, 0.55), acc=(0.98, 0.84, 0.06),
         acc2=(0.97, 0.97, 0.95), livery="flames", num="27", rim=(0.10, 0.10, 0.10), damage=2,
         sponsors=["BIG HAUL", "FIZZCO", "RAPIDO", "CORN KING"], seed=9),
    dict(name="Teal 61", kind="hyper", L=17.9, W=9.3, H=4.2, wr=1.2, base=(0.02, 0.58, 0.58), acc=(0.03, 0.03, 0.03),
         acc2=(0.97, 0.97, 0.95), livery="diagonal", num="61", rim=(0.20, 0.20, 0.22), damage=0,
         sponsors=["AERIX", "NOVA", "KINETIQ"], seed=10),
]

# Look-alike twins (Simulation chat, Oct 28): same colour as one of the ten, mostly the same body.
# Red 17 = Red 7's body and livery, only the number and sponsors differ (the hardest case);
# White 8 = White 33's body, other livery; Blue 41 = Blue 14's colour on a GT body;
# Grey 91 = Silver 19's body, a shade darker; Black 98 = Black 88's body, other sticker colours.
TWINS = [
    dict(name="Red 17", kind="gt", L=17.0, W=8.7, H=4.6, wr=1.15, base=(0.78, 0.04, 0.03), acc=(0.97, 0.97, 0.95),
         acc2=(0.03, 0.03, 0.03), livery="twin", num="17", rim=(0.75, 0.75, 0.78), damage=1,
         sponsors=["SOLVIK", "TEMPO", "GLIMT OIL"], seed=11),
    dict(name="White 8", kind="rally", L=15.2, W=8.4, H=5.8, wr=1.4, base=(0.93, 0.93, 0.91), acc=(0.05, 0.20, 0.75),
         acc2=(0.98, 0.42, 0.02), livery="band", num="8", rim=(0.95, 0.95, 0.95), damage=1,
         sponsors=["FJÄLL", "POLARIS", "DUNE"], seed=12),
    dict(name="Blue 41", kind="gt", L=17.0, W=8.7, H=4.6, wr=1.15, base=(0.04, 0.16, 0.70), acc=(0.96, 0.96, 0.96),
         acc2=(0.95, 0.75, 0.05), livery="split", num="41", rim=(0.08, 0.08, 0.08), damage=0,
         sponsors=["CIRRUS", "VEKTOR", "ION CELL"], seed=13),
    dict(name="Grey 91", kind="super", L=17.4, W=9.0, H=4.1, wr=1.15, base=(0.55, 0.57, 0.60), acc=(0.03, 0.03, 0.03),
         acc2=(0.80, 0.04, 0.04), livery="centre", num="91", rim=(0.10, 0.10, 0.10), damage=0, metal=0.55,
         sponsors=["GRAFIT", "QUARTZ", "LIMA"], seed=14),
    dict(name="Black 98", kind="drift", L=16.6, W=9.6, H=4.7, wr=1.2, base=(0.035, 0.035, 0.04), acc=(0.95, 0.85, 0.05),
         acc2=(0.20, 0.90, 0.30), livery="stickerbomb", num="98", rim=(0.85, 0.85, 0.85), damage=1, matte=True,
         sponsors=["SLIDE", "KAIJU", "ROKKU", "DRFT", "SMOKE"], seed=15),
]
# the same two twins with a fluorescent tape strip across the roof (look-alike fix test)
TAPED = [dict(t, name=t["name"] + " tape", tape=c) for t, c in ((TWINS[0], (0.10, 0.95, 0.15)), (TWINS[4], (0.98, 0.95, 0.05)))]
if os.environ.get("MINIS") == "twins": SPECS = TWINS
elif os.environ.get("MINIS") == "tape": SPECS = TAPED
elif os.environ.get("MINIS") == "all": SPECS = SPECS + TWINS

# shape tables per kind: control points over s (0 = rear, 1 = front), heights as fraction of H,
# half-width as fraction of W/2. arch = (rear axle s, front axle s). cab = cabin profile.
SHAPES = {
    "gt":      dict(top=[(0, .52), (.05, .62), (.28, .64), (.55, .60), (.80, .52), (.95, .40), (1, .32)],
                    hw=[(0, .86), (.12, .96), (.5, .92), (.9, .95), (1, .80)], n=4.0, tumble=.10, fender=.08, flare=.05, cabn=3.8, cabt=.20,
                    cab=(.30, .67, .86, [(.30, .60), (.36, .92), (.47, 1.0), (.57, .97), (.67, .58)]),
                    nose=.07, tail=.03, wing="gt"),
    "lmp":     dict(top=[(0, .44), (.1, .48), (.5, .46), (.85, .40), (1, .26)],
                    hw=[(0, .96), (.5, .97), (1, .90)], n=3.2, tumble=.05, fender=.30, flare=.02,
                    cab=(.42, .72, .44, [(.42, .44), (.50, .95), (.58, 1.0), (.64, .90), (.72, .42)]),
                    nose=.09, tail=.02, wing="lmp"),
    "formula": dict(),
    "rally":   dict(top=[(0, .55), (.06, .62), (.55, .60), (.85, .55), (1, .45)],
                    hw=[(0, .90), (.15, .96), (.6, .94), (1, .86)], n=4.5, tumble=.14, fender=.035, flare=.04, cabn=4.5, cabt=.15,
                    cab=(.10, .66, .90, [(.10, .58), (.14, .96), (.46, 1.0), (.56, .94), (.66, .57)]),
                    nose=.06, tail=.015, wing="roof", clearance=.009),
    "touring": dict(top=[(0, .60), (.12, .64), (.30, .63), (.72, .58), (.94, .50), (1, .44)],
                    hw=[(0, .88), (.15, .95), (.6, .94), (1, .86)], n=4.5, tumble=.12, fender=.03, flare=.04, cabn=4.5, cabt=.15,
                    cab=(.24, .70, .90, [(.24, .62), (.34, .95), (.50, 1.0), (.60, .96), (.70, .60)]),
                    nose=.06, tail=.03, wing="lip"),
    "drift":   dict(top=[(0, .55), (.08, .63), (.30, .63), (.75, .57), (.95, .46), (1, .38)],
                    hw=[(0, .84), (.12, .93), (.5, .86), (.88, .92), (1, .80)], n=5.0, tumble=.12, fender=.045, flare=.09, cabn=4.0, cabt=.18,
                    cab=(.30, .66, .88, [(.30, .60), (.37, .93), (.48, 1.0), (.57, .96), (.66, .54)]),
                    nose=.05, tail=.02, wing="drift"),
    "group5":  dict(top=[(0, .50), (.10, .56), (.60, .55), (.90, .45), (1, .37)],
                    hw=[(0, .92), (.12, .97), (.6, .95), (.93, .96), (1, .88)], n=6.0, tumble=.08, fender=.035, flare=.03, cabn=4.0, cabt=.18,
                    cab=(.34, .63, .74, [(.34, .55), (.41, .93), (.50, .98), (.56, .95), (.63, .55)]),
                    nose=.04, tail=.01, wing="group5"),
    "super":   dict(top=[(0, .55), (.15, .60), (.45, .55), (.80, .38), (1, .22)],
                    hw=[(0, .90), (.15, .97), (.55, .93), (.9, .93), (1, .78)], n=3.5, tumble=.12, fender=.14, flare=.03,
                    cab=(.38, .71, .74, [(.38, .56), (.46, .94), (.55, 1.0), (.62, .93), (.71, .42)]),
                    nose=.08, tail=.03, wing="ducktail"),
    "stock":   dict(top=[(0, .62), (.10, .65), (.70, .62), (.92, .55), (1, .50)],
                    hw=[(0, .93), (.5, .96), (1, .90)], n=5.5, tumble=.10, fender=.01, flare=.02, cabn=5.0, cabt=.14,
                    cab=(.30, .65, .90, [(.30, .60), (.38, .95), (.50, 1.0), (.58, .96), (.65, .58)]),
                    nose=.05, tail=.03, wing="blade"),
    "hyper":   dict(top=[(0, .50), (.20, .55), (.50, .49), (.85, .35), (1, .23)],
                    hw=[(0, .88), (.15, .97), (.5, .90), (.9, .95), (1, .80)], n=3.0, tumble=.10, fender=.22, flare=.02,
                    cab=(.36, .72, .66, [(.36, .50), (.46, .93), (.55, 1.0), (.63, .92), (.72, .40)]),
                    nose=.09, tail=.03, wing="swan"),
}

# RC classes (tools/ml sprite library). cab=None: no glass cabin (windows painted on the shell).
# Builder flags come from the spec: lift (cm, body raised on its shocks), body_frac (body width / overall
# width), open (wheels outside the body, no arch cut-outs), ww (tyre width cm), rc (list of hardware).
SHAPES.update({
    "buggy":   dict(top=[(0, .28), (.12, .34), (.40, .42), (.58, .75), (.72, .80), (.86, .50), (1, .28)],
                    hw=[(0, .55), (.25, .62), (.5, .70), (.75, .62), (1, .35)], n=3.0, tumble=.25, fender=0.0, flare=0.0,
                    cab=None, nose=.12, tail=.02, wing="buggy", clearance=.008),
    "truggy":  dict(top=[(0, .32), (.15, .40), (.45, .48), (.60, .78), (.75, .80), (.88, .52), (1, .32)],
                    hw=[(0, .60), (.3, .70), (.55, .75), (.8, .66), (1, .40)], n=3.4, tumble=.22, fender=0.0, flare=0.0,
                    cab=None, nose=.12, tail=.03, wing="buggy", clearance=.008),
    "monster": dict(top=[(0, .42), (.30, .45), (.55, .48), (.85, .45), (1, .38)],
                    hw=[(0, .90), (.5, .95), (1, .88)], n=6.0, tumble=.05, fender=.04, flare=.03, cabn=6.0, cabt=.08,
                    cab=(.36, .62, .86, [(.36, .50), (.43, .96), (.57, 1.0), (.62, .50)]), nose=.04, tail=.02, wing=None),
    "sct":     dict(top=[(0, .52), (.12, .60), (.65, .60), (.88, .55), (1, .45)],
                    hw=[(0, .92), (.5, .96), (1, .90)], n=6.0, tumble=.08, fender=.06, flare=.05, cabn=5.5, cabt=.12,
                    cab=(.28, .62, .86, [(.28, .58), (.36, .95), (.52, 1.0), (.62, .58)]), nose=.05, tail=.03, wing=None),
    "crawler": dict(top=[(0, .50), (.2, .52), (.6, .55), (.8, .55), (1, .50)],
                    hw=[(0, .86), (.5, .88), (1, .84)], n=9.0, tumble=.02, fender=.02, flare=.02, cabn=8.0, cabt=.05,
                    cab=(.35, .68, .88, [(.35, .60), (.40, .98), (.62, 1.0), (.68, .60)]), nose=.02, tail=.01, wing=None),
    "trophy":  dict(top=[(0, .38), (.25, .40), (.40, .46), (.75, .50), (1, .42)],
                    hw=[(0, .80), (.5, .90), (1, .86)], n=5.0, tumble=.06, fender=.05, flare=.08, cabn=5.0, cabt=.10,
                    cab=(.30, .55, .80, [(.30, .48), (.36, .95), (.50, 1.0), (.55, .50)]), nose=.06, tail=.01, wing=None),
    "drag":    dict(top=[(0, .35), (.10, .45), (.30, .48), (.55, .42), (.80, .30), (1, .18)],
                    hw=[(0, .85), (.30, .90), (.70, .75), (1, .55)], n=3.5, tumble=.10, fender=.10, flare=.03,
                    cab=(.22, .45, .60, [(.22, .46), (.28, .92), (.38, 1.0), (.45, .44)]), nose=.15, tail=.02, wing="drift", clearance=.004),
    "kei":     dict(top=[(0, .55), (.10, .58), (.85, .58), (1, .50)],
                    hw=[(0, .90), (.5, .94), (1, .90)], n=7.0, tumble=.04, fender=.02, flare=.02, cabn=7.0, cabt=.06,
                    cab=(.15, .82, .92, [(.15, .56), (.20, .97), (.78, 1.0), (.82, .58)]), nose=.03, tail=.01, wing=None),
    "pan":     dict(top=[(0, .40), (.10, .46), (.50, .44), (.85, .36), (1, .24)],
                    hw=[(0, .95), (.5, .97), (1, .90)], n=3.2, tumble=.10, fender=.25, flare=.02,
                    cab=(.40, .68, .60, [(.40, .44), (.48, .95), (.56, 1.0), (.62, .90), (.68, .42)]), nose=.09, tail=.02, wing="gt"),
})

# ---------------------------------------------------------------- helpers
def prof(points, s, smooth=0.035):
    """Linear table -> gaussian-smoothed value at s (array)."""
    xs = np.linspace(-0.2, 1.2, 701)
    ps = np.array(points)
    ys = np.interp(xs, ps[:, 0], ps[:, 1])
    k = np.exp(-0.5 * (np.arange(-60, 61) * 0.002 / smooth) ** 2); k /= k.sum()
    ys = np.convolve(np.pad(ys, 60, mode="edge"), k, mode="valid")
    return np.interp(s, xs, ys)

def gauss(s, c, w):
    return np.exp(-0.5 * ((s - c) / w) ** 2)

_mats = {}
def pmat(name, rgb, rough=0.4, metal=0.0, coat=0.0, emit=None):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1.0)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if coat: b.inputs["Coat Weight"].default_value = coat
    if emit:
        b.inputs["Emission Color"].default_value = (*emit, 1); b.inputs["Emission Strength"].default_value = 2.0
    return m

def tex_mat(name, path, rough=0.3, metal=0.0, coat=0.6, inside=(0.02, 0.02, 0.02), clear_mask=None):
    """Image-textured paint; back faces (inside of the shell, seen through arches) dark. clear_mask: image
    path, white = painted, black = clear polycarbonate (a Lexan body's unpainted windows / panels)."""
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; nodes = nt.nodes
    b = nodes["Principled BSDF"]; out = nodes["Material Output"]
    img = bpy.data.images.load(path); img.colorspace_settings.name = "sRGB"
    t = nodes.new("ShaderNodeTexImage"); t.image = img; t.interpolation = "Cubic"
    nt.links.new(t.outputs["Color"], b.inputs["Base Color"])
    b.inputs["Roughness"].default_value = rough; b.inputs["Metallic"].default_value = metal
    b.inputs["Coat Weight"].default_value = coat
    geo = nodes.new("ShaderNodeNewGeometry")
    dif = nodes.new("ShaderNodeBsdfDiffuse"); dif.inputs["Color"].default_value = (*inside, 1)
    mix = nodes.new("ShaderNodeMixShader")
    nt.links.new(geo.outputs["Backfacing"], mix.inputs["Fac"])
    nt.links.new(b.outputs["BSDF"], mix.inputs[1]); nt.links.new(dif.outputs["BSDF"], mix.inputs[2])
    if clear_mask:
        g = nodes.new("ShaderNodeBsdfPrincipled")
        g.inputs["Base Color"].default_value = (0.95, 0.95, 0.95, 1); g.inputs["Roughness"].default_value = 0.04
        g.inputs["Transmission Weight"].default_value = 1.0; g.inputs["IOR"].default_value = 1.58
        mi = bpy.data.images.load(clear_mask); mi.colorspace_settings.name = "Non-Color"
        mt = nodes.new("ShaderNodeTexImage"); mt.image = mi
        mix2 = nodes.new("ShaderNodeMixShader")
        nt.links.new(mt.outputs["Color"], mix2.inputs["Fac"])
        nt.links.new(g.outputs["BSDF"], mix2.inputs[1]); nt.links.new(mix.outputs["Shader"], mix2.inputs[2])
        nt.links.new(mix2.outputs["Shader"], out.inputs["Surface"])
    else:
        nt.links.new(mix.outputs["Shader"], out.inputs["Surface"])
    return m

KEY = (1.0, 0.0, 1.0)   # paint this colour on a Lexan body = leave it clear

def clear_mask_from(im, path, key_rgb=(255, 0, 255), glass=False):
    """Mask image (white = paint, black = clear) from the key colour; key pixels themselves turned light grey.
    glass=True also clears the dark window tint (cabin of a Lexan body). Returns the cleaned image."""
    a = np.asarray(im).astype(np.int16)
    clear = (np.abs(a[..., 0] - key_rgb[0]) < 40) & (a[..., 1] < 60) & (np.abs(a[..., 2] - key_rgb[2]) < 40)
    if glass: clear |= (np.abs(a[..., 0] - 28) < 12) & (np.abs(a[..., 1] - 34) < 12) & (np.abs(a[..., 2] - 43) < 12)   # the window tint
    m = np.where(clear, 0, 255).astype(np.uint8)
    Image.fromarray(m).transpose(Image.FLIP_TOP_BOTTOM).filter(ImageFilter.GaussianBlur(1.0)).save(path)
    a[clear] = (225, 225, 225)
    return Image.fromarray(a.astype(np.uint8))

def link(o, parent):
    o.parent = parent
    return o

def box(name, size, loc, m, parent, rot=(0, 0, 0), bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=loc, rotation=rot)
    o = bpy.context.active_object; o.name = name
    o.scale = size; o.data.materials.append(m)
    if bevel:
        md = o.modifiers.new("bevel", "BEVEL"); md.width = bevel; md.segments = 2
    return link(o, parent)

def sph(name, r, loc, m, parent, scale=(1, 1, 1)):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=r, location=loc, segments=16, ring_count=8)
    o = bpy.context.active_object; o.name = name; o.scale = scale; o.data.materials.append(m)
    for p_ in o.data.polygons: p_.use_smooth = True
    return link(o, parent)

def cyl(name, r, depth, loc, m, parent, rot=(0, math.pi / 2, 0), verts=32):
    bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=depth, location=loc, rotation=rot, vertices=verts)
    o = bpy.context.active_object; o.name = name; o.data.materials.append(m)
    return link(o, parent)

# ---------------------------------------------------------------- loft
def loft(name, y0, y1, F, M=56, N=72, nose_R=0.0, tail_R=0.0, dents=(), cap=True):
    """F(s) -> (cx, hw, bot, top, n, tumble, fender). Returns (verts, faces, loop_uvs, meta).
    UV: u = (y - y0)/(y1 - y0); v = 0.5 + signed arc from top centre / P_ref. Caps go to the
    reserved bands v < 0.12 (front: u .5-1, rear: u 0-.5)."""
    L = y1 - y0
    secs = []
    for i in range(N):
        s = i / (N - 1); y = y0 + L * s
        cx, hw, bot, top, n, tumble, fender = F(s)
        th = np.linspace(math.pi, 0, 400)
        c, sn = np.cos(th), np.clip(np.sin(th), 0, 1)
        x = hw * np.sign(c) * np.abs(c) ** (2 / n)
        h = max(top - bot, 1e-4)
        z = bot + h * sn ** (2 / n)
        x = x * (1 - tumble * ((z - bot) / h) ** 2)
        z = z + fender * np.exp(-0.5 * ((np.abs(x) / max(hw, 1e-4) - 0.74) / 0.17) ** 2) * sn ** 0.5
        seg = np.hypot(np.diff(x), np.diff(z)); arc = np.concatenate([[0], np.cumsum(seg)])
        mid = arc[np.argmin(np.abs(x))]
        tgt = np.linspace(0, arc[-1], M)
        xs = np.interp(tgt, arc, x); zs = np.interp(tgt, arc, z); a = tgt - mid
        secs.append((s, y, cx, hw, bot, top, xs, zs, a))
    P = 1.35 * max(sec[8][-1] - sec[8][0] for sec in secs)
    verts, uvs, nrm = [], [], []
    for (s, y, cx, hw, bot, top, xs, zs, a) in secs:
        df = y1 - y; dr = y - y0
        Dn, Dt = 0.20 * L, 0.12 * L
        for x, z, aa in zip(xs, zs, a):
            q = (abs(x) / max(hw, 1e-4)) ** 2
            yy = y
            if df < Dn: yy -= nose_R * q * (1 - df / Dn)
            if dr < Dt: yy += tail_R * q * (1 - dr / Dt)
            cz = bot + 0.35 * (top - bot)
            d = np.array([x, z - cz]); d /= (np.linalg.norm(d) + 1e-9)
            verts.append([cx + x, yy, z]); uvs.append(((yy - y0) / L, 0.5 + aa / P)); nrm.append((d[0], d[1]))
    verts = np.array(verts)
    # dents: (s, arc metres signed, radius m, depth m)
    for (ds, da, dr_, dd) in dents:
        for k, (u, v) in enumerate(uvs):
            dist = math.hypot((u - ds) * L, (v - 0.5) * P - da)
            if dist < dr_ * 1.6:
                w = math.exp(-0.5 * (dist / (dr_ * 0.55)) ** 2) * dd
                verts[k, 0] -= nrm[k][0] * w; verts[k, 2] -= nrm[k][1] * w
    faces, fuv = [], []
    for i in range(N - 1):
        for j in range(M - 1):
            a0 = i * M + j; a1 = a0 + 1; b0 = a0 + M; b1 = b0 + 1
            faces.append([a0, b0, b1, a1]); fuv.append([uvs[a0], uvs[b0], uvs[b1], uvs[a1]])
    if cap:
        for (i, u0, flip) in ((N - 1, 0.5, False), (0, 0.0, True)):
            ring = list(range(i * M, i * M + M))
            sec = secs[i]; hw, bot, top = sec[3], sec[4], sec[5]
            zt = max(sec[7].max(), bot + 1e-3)
            def cuv(k):
                x, z = verts[k, 0] - sec[2], verts[k, 2]
                xn = (x + hw) / (2 * hw + 1e-9)
                if not flip: xn = 1 - xn       # front cap seen from the front: screen right = -x
                return (u0 + 0.02 + 0.46 * xn, 0.01 + 0.10 * (zt - z) / (zt - bot + 1e-9))
            f = ring if flip else ring[::-1]
            faces.append(f); fuv.append([cuv(k) for k in f])
    meta = dict(P=P, L=L, y0=y0, secs=secs)
    return verts, faces, fuv, meta

def mesh_obj(name, verts, faces, fuv, m, parent, smooth=True):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], faces)
    uvl = me.uv_layers.new(name="UVMap")
    li = 0
    for poly, fu in zip(me.polygons, fuv):
        for k, uv in zip(poly.loop_indices, fu):
            uvl.data[k].uv = uv
    me.update()
    o = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(o)
    if smooth:
        for p in me.polygons: p.use_smooth = True
    me.materials.append(m)
    # outward normals
    bpy.context.view_layer.objects.active = o; o.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.mesh.normals_make_consistent(inside=False); bpy.ops.object.mode_set(mode="OBJECT")
    o.select_set(False)
    return link(o, parent)

# ---------------------------------------------------------------- painting
class Painter:
    """Paint in millimetres on a lofted part: along = mm from the rear end, around = mm from the
    top centre line (+ = right side). Image row grows with v; flipped on save."""
    def __init__(self, meta, base):
        self.L = meta["L"] * 1000; self.P = meta["P"] * 1000
        self.im = Image.new("RGB", (TEX, TEX), tuple(int(c * 255) for c in srgb(base)))
        self.d = ImageDraw.Draw(self.im)
    def px(self, along, around):
        return (along / self.L * TEX, (0.5 + around / self.P) * TEX)
    def k(self):  # px per mm (x, y)
        return TEX / self.L, TEX / self.P
    def poly(self, pts, rgb):
        self.d.polygon([self.px(a, b) for a, b in pts], fill=col(rgb))
    def rect(self, a0, b0, a1, b1, rgb, r=0):
        p0 = self.px(a0, b0); p1 = self.px(a1, b1)
        box_ = [min(p0[0], p1[0]), min(p0[1], p1[1]), max(p0[0], p1[0]), max(p0[1], p1[1])]
        if r: self.d.rounded_rectangle(box_, radius=r * self.k()[0], fill=col(rgb))
        else: self.d.rectangle(box_, fill=col(rgb))
    def ellipse(self, a, b, ra, rb, rgb, outline=None, width=0):
        p = self.px(a, b); kx, ky = self.k()
        self.d.ellipse([p[0] - ra * kx, p[1] - rb * ky, p[0] + ra * kx, p[1] + rb * ky], fill=col(rgb) if rgb else None,
                       outline=col(outline) if outline else None, width=int(width * kx))
    def line(self, pts, rgb, w):
        self.d.line([self.px(a, b) for a, b in pts], fill=col(rgb), width=max(1, int(w * self.k()[0])), joint="curve")
    def text(self, s, along, around, h_mm, rgb, font=FONT_B, rot=0, bg=None, pad=1.2, w_mm=None, rr=1.5, outline=None):
        """Centred text, cap height h_mm. Built in mm space (10 px/mm), rotated, then scaled to the
        texture's px/mm on each axis. rot 0 = reads from the right side (or from above with the car
        pointing right); 180 = left side; 90 = along the car's top, read from behind."""
        kx, ky = self.k(); R = 10.0
        f = ImageFont.truetype(font, int(h_mm * R * 1.38) + 1)
        bb = f.getbbox(s); tw, th = bb[2] - bb[0], bb[3] - bb[1]
        tile = Image.new("RGBA", (tw + 4, th + 4), (0, 0, 0, 0))
        ImageDraw.Draw(tile).text((2 - bb[0], 2 - bb[1]), s, font=f, fill=col(rgb) + (255,))
        if w_mm: tile = tile.resize((int(w_mm * R), tile.height), Image.LANCZOS)
        if rot: tile = tile.rotate(rot, expand=True, resample=Image.BICUBIC)
        wm, hm = tile.width / R, tile.height / R
        tile = tile.resize((max(1, int(wm * kx)), max(1, int(hm * ky))), Image.LANCZOS)
        if bg is not None:
            self.rect(along - wm / 2 - pad, around - hm / 2 - pad * .7, along + wm / 2 + pad, around + hm / 2 + pad * .7, bg, r=rr)
        cx, cy = self.px(along, around)
        self.im.paste(tile, (int(cx - tile.width / 2), int(cy - tile.height / 2)), tile)
    def save(self, path, blur=0.6):
        im = self.im.filter(ImageFilter.GaussianBlur(blur)) if blur else self.im
        im.transpose(Image.FLIP_TOP_BOTTOM).save(path)

def srgb(rgb):  # linear -> sRGB 0..1
    return tuple((12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055) for c in rgb)

def col(rgb):
    return tuple(int(max(0, min(1, c)) * 255) for c in srgb(rgb))

def side_arc(meta, s, frac=0.62):
    """Arc (mm) from top centre to a point frac of the way down the side at station s."""
    secs = meta["secs"]; i = int(round(s * (len(secs) - 1)))
    a = secs[i][8]; return abs(a[-1]) * 1000 * frac

def top_half(meta, s):
    """Arc (mm) from centre to the shoulder (where the side turns down) at s."""
    secs = meta["secs"]; i = int(round(s * (len(secs) - 1)))
    xs, zs, a = secs[i][6], secs[i][7], secs[i][8]
    j = np.argmax(np.abs(xs) > 0.80 * np.abs(xs).max())
    return abs(a[j]) * 1000

# ---------------------------------------------------------------- damage painting
def paint_damage(p, sp, rnd, meta):
    lvl = sp["damage"]
    if not lvl: return
    L, P = p.L, p.P
    base = sp["base"]
    light = tuple(min(1, c * 0.5 + 0.55) for c in base)  # scraped paint
    for _ in range(22 * lvl):  # scratches, mostly nose corners and sides
        a = rnd.choice([rnd.uniform(0.82, 1.0), rnd.uniform(0.0, 0.15), rnd.uniform(0.2, 0.8)]) * L
        side = rnd.choice([-1, 1]); b = side * rnd.uniform(0.25, 0.95) * P * 0.36
        pts = [(a, b)]
        ang = rnd.uniform(-0.5, 0.5); ln = rnd.uniform(4, 14 + 6 * lvl)
        for _ in range(4):
            a += math.cos(ang) * ln / 4; b += math.sin(ang) * ln / 4 + rnd.uniform(-0.6, 0.6); pts.append((a, b))
        p.line(pts, light if rnd.random() < 0.7 else (0.75, 0.75, 0.75), rnd.uniform(0.3, 0.9))
    for _ in range(8 * lvl):  # rubber marks from contact
        a = rnd.uniform(0.05, 0.95) * L; side = rnd.choice([-1, 1]); b = side * rnd.uniform(0.55, 0.95) * P * 0.36
        p.ellipse(a, b, rnd.uniform(4, 10), rnd.uniform(0.8, 2.0), (0.02, 0.02, 0.02))
    # scuffed patches: dense short scratches on the nose corners and the side the car was hit on
    for _ in range(3 * lvl):
        ca = rnd.choice([rnd.uniform(.86, .98), rnd.uniform(.02, .12), rnd.uniform(.35, .65)]) * L
        cb = rnd.choice([-1, 1]) * rnd.uniform(0.45, 0.9) * P * 0.36
        for _ in range(25):
            a = ca + rnd.gauss(0, 4); b = cb + rnd.gauss(0, 2.5); ang = rnd.uniform(-.3, .3); ln = rnd.uniform(1.5, 5)
            p.line([(a, b), (a + math.cos(ang) * ln, b + math.sin(ang) * ln)], (0.82, 0.82, 0.80), rnd.uniform(.15, .35))
    if lvl >= 2:  # chipped edges near the nose / tail
        for end in (L * 0.985, L * 0.015):
            for side in (-1, 1):
                b = side * P * 0.36 * rnd.uniform(0.45, 0.8)
                pts = [(end + rnd.uniform(-4, 4), b + rnd.uniform(-4, 4)) for _ in range(6)]
                p.poly(pts, light)

def paint_dirt(im, strength, rnd):
    """Rally dirt: brown speckle, heavier low on the sides and at the rear."""
    w, h = im.size
    noise = Image.effect_noise((w // 8, h // 8), 90).resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(6))
    arr = np.asarray(noise).astype(np.float32) / 255
    v = np.linspace(0, 1, h)[:, None]; u = np.linspace(0, 1, w)[None, :]
    side = np.clip((np.abs(v - 0.5) - 0.18) / 0.2, 0, 1)          # low on the sides
    rear = np.clip((0.25 - u) / 0.25, 0, 1) * 0.6
    mask = np.clip((side + rear) * strength * (arr * 1.6 - 0.25), 0, 0.85)
    spk = (np.asarray(Image.effect_noise((w, h), 120)).astype(np.float32) / 255 > 0.78) * side * strength * 0.7
    mask = np.clip(mask + spk, 0, 0.9)[..., None]
    dirt = np.array([120, 92, 62], np.float32)
    out = np.asarray(im).astype(np.float32) * (1 - mask) + dirt * mask
    return Image.fromarray(out.astype(np.uint8))

# ---------------------------------------------------------------- livery
def paint_body(p, sp, meta, rnd, arches):
    L, P = p.L, p.P
    b0, a1, a2 = sp["base"], sp["acc"], sp["acc2"]
    lv = sp["livery"]; num = sp["num"]
    sideA = lambda s, f=0.55: side_arc(meta, s, f)
    # ---- livery patterns
    if lv == "twin":       # twin stripes over the top + side band
        for o in (-9, 9): p.rect(0, o - 4, L, o + 4, a1)
        for sg in (-1, 1): p.rect(0, sg * (sideA(.5, .72) - 1.5), L, sg * (sideA(.5, .72) + 1.5), a1)
    elif lv == "split":    # blue rear, white front, angled
        p.poly([(L * .55, -P), (L, -P), (L, P), (L * .55, P), (L * .45, 0)], a1)
        p.poly([(L * .53, -P), (L * .55, -P), (L * .45, 0), (L * .55, P), (L * .53, P), (L * .43, 0)], a2)
    elif lv == "swoosh":   # rally swooshes
        for sg in (-1, 1):
            pts = [(L * x, sg * (sideA(.5, .3) + (sideA(.5, 1.0) - sideA(.5, .3)) * (0.5 + 0.5 * math.sin(x * 6))))
                   for x in np.linspace(0, 1, 30)]
            p.line(pts, a1, 5); p.line([(a, b + sg * 4) for a, b in pts], a2, 2.5)
        p.poly([(L * .62, -14), (L, -26), (L, 26), (L * .62, 14)], a2)
        p.poly([(L * .66, -10), (L, -20), (L, 20), (L * .66, 10)], a1)
    elif lv == "band":     # silver lower band + black pinstripe
        for sg in (-1, 1):
            p.rect(0, sg * sideA(.5, .55), L, sg * P, a1)
            p.rect(0, sg * (sideA(.5, .55) - .6), L, sg * (sideA(.5, .55) + .2), a2)
        p.rect(L * .72, -3, L, 3, a1)
    elif lv == "stickerbomb":
        pass               # drawn with the stickers below
    elif lv == "stripes3":  # Martini-ish three-colour stripes (invented colours)
        for sg in (-1, 1):
            for k, c in enumerate((a1, a2, a1)):
                off = sideA(.5, .45) + k * 3.2
                p.rect(0, sg * off, L, sg * (off + 2.6), c)
        p.rect(0, -2.5, L, 2.5, a1); p.rect(0, -4.5, L, -3.2, a2); p.rect(0, 3.2, L, 4.5, a2)
    elif lv == "centre":    # single centre stripe
        p.rect(0, -6, L, 6, a1); p.rect(0, -7, L, -6, a2); p.rect(0, 6, L, 7, a2)
    elif lv == "flames":
        for sg in (-1, 1):
            for k in range(6):
                a0 = L * (0.55 + k * 0.012)
                pts = [(L * 0.99, sg * sideA(.8, .25 + k * .09))]
                pts += [(a0 - rnd.uniform(5, 22), sg * sideA(.8, .3 + k * .09 + j * .01)) for j in range(1)]
                pts += [(L * 0.99, sg * sideA(.8, .35 + k * .09))]
                p.poly(pts, a1 if k % 2 == 0 else (0.98, 0.35, 0.02))
        p.rect(0, -P, L * .08, P, a1)
    elif lv == "diagonal":  # teal front, black rear, diagonal cut
        p.poly([(0, -P), (L * .30, -P), (L * .52, 0), (L * .30, P), (0, P)], a1)
        p.poly([(L * .30, -P), (L * .33, -P), (L * .55, 0), (L * .33, P), (L * .30, P), (L * .52, 0)], a2)
    elif lv == "nose":
        pass
    elif lv == "proc":     # procedural: 2-5 random elements, so no two cars are painted alike
        cols = [a1, a2, (0.97, 0.97, 0.95), (0.03, 0.03, 0.03)]
        for _ in range(rnd.randint(2, 5)):
            c = rnd.choice(cols); kind_ = rnd.choice(["stripe_long", "stripe_cross", "block", "diag", "band", "chevron", "spots", "fade"])
            if kind_ == "stripe_long":
                w_ = rnd.uniform(1.5, 10); o = rnd.uniform(0, 12) * rnd.choice([0, 1])
                for sg in ((-1, 1) if o else (1,)): p.rect(0, sg * o - w_ / 2, L, sg * o + w_ / 2, c)
            elif kind_ == "stripe_cross":
                a = rnd.uniform(.1, .9) * L; w_ = rnd.uniform(2, 14); p.rect(a - w_ / 2, -P, a + w_ / 2, P, c)
            elif kind_ == "block":
                a0 = rnd.uniform(0, .7) * L; p.rect(a0, -P, a0 + rnd.uniform(.15, .5) * L, P, c)
            elif kind_ == "diag":
                a0 = rnd.uniform(.2, .8) * L; d = rnd.uniform(-.3, .3) * L
                p.poly([(a0 - d, -P), (L if rnd.random() < .5 else 0, -P), (L if rnd.random() < .5 else 0, P), (a0 + d, P)], c)
            elif kind_ == "band":
                f = rnd.uniform(.3, .9); w_ = rnd.uniform(1.5, 6)
                for sg in (-1, 1): p.rect(0, sg * sideA(.5, f), L, sg * (sideA(.5, f) + w_), c)
            elif kind_ == "chevron":
                a0 = rnd.uniform(.3, .9) * L; d = rnd.uniform(8, 30); w_ = rnd.uniform(3, 10)
                p.poly([(a0, 0), (a0 - d, -P), (a0 - d - w_, -P), (a0 - w_, 0), (a0 - d - w_, P), (a0 - d, P)], c)
            elif kind_ == "spots":
                r_ = rnd.uniform(1.5, 5)
                for _ in range(rnd.randint(4, 14)): p.ellipse(rnd.uniform(0, L), rnd.uniform(-P, P) * .6, r_, r_, c)
            else:   # fade: steps of colour along the car
                n_ = rnd.randint(4, 8); a0 = rnd.uniform(0, .5) * L
                for k in range(n_):
                    t_ = k / n_; cc = tuple(c[i] * (1 - t_) + b0[i] * t_ for i in range(3))
                    p.rect(a0 + k * L * .05, -P, a0 + (k + 1) * L * .05, P, cc)
    # ---- panel lines (doors, bonnet)
    pl = tuple(c * 0.35 for c in b0) if sum(b0) > 0.3 else (0.12, 0.12, 0.12)
    ra, fa = arches
    for sg in (-1, 1):
        d0, d1 = L * (ra + 0.16), L * (fa - 0.14)
        p.line([(d0, sg * sideA(.5, .2)), (d0, sg * sideA(.5, .95))], pl, .25)
        p.line([(d1, sg * sideA(.5, .2)), (d1, sg * sideA(.5, .95))], pl, .25)
        p.rect(d1 - 9, sg * sideA(.5, .32), d1 - 5, sg * (sideA(.5, .32) + .8), pl)  # door handle
    if sp["kind"] not in ("formula",):
        hb = L * 0.80
        p.line([(hb, -top_half(meta, .8) * .8), (hb, top_half(meta, .8) * .8)], pl, .25)
    # ---- lights on the nose / tail top surface, plus caps
    hl = (0.95, 0.95, 0.85); tl = (0.85, 0.02, 0.02)
    if sp["kind"] != "formula":
        for sg in (-1, 1):
            p.ellipse(L * .965, sg * top_half(meta, .97) * .75, 4.2, 3.0, hl, outline=(0.1, 0.1, 0.1), width=.4)
            p.rect(L * .005, sg * top_half(meta, .03) * .55, L * .035, sg * top_half(meta, .03) * .95, tl)
        # front cap (u .5-1, v 0-.12) and rear cap (u 0-.5): grille + lights
        capimg = p.im; dd = p.d
        W_, H_ = TEX, TEX
        def cap_rect(u0, u1, v0, v1, rgb):
            dd.rectangle([u0 * W_, v0 * H_, u1 * W_, v1 * H_], fill=col(rgb))
        cap_rect(0.5, 1.0, 0.0, 0.12, b0)
        cap_rect(0.62, 0.88, 0.05, 0.10, (0.03, 0.03, 0.03))             # grille
        for uc in (0.56, 0.94): dd.ellipse([(uc - .035) * W_, .02 * H_, (uc + .035) * W_, .05 * H_], fill=col(hl))
        cap_rect(0.0, 0.5, 0.0, 0.12, b0)
        for uc in (0.06, 0.44): cap_rect(uc - .045, uc + .045, .02, .045, tl)
        cap_rect(0.18, 0.32, 0.06, 0.09, (0.03, 0.03, 0.03))              # diffuser
        cap_rect(0.20, 0.30, 0.025, 0.045, (0.95, 0.95, 0.85))              # plate
    # ---- vents (dark) on bonnet / sides
    if sp["kind"] in ("gt", "drift", "touring", "rally", "super", "hyper", "lmp"):
        for sg in (-1, 1):
            p.rect(L * .74, sg * 5, L * .80, sg * 11, (0.03, 0.03, 0.03), r=.8)
        if sp["kind"] in ("super", "hyper", "lmp", "gt"):
            for sg in (-1, 1):
                p.poly([(L * (ra + .12), sg * sideA(.4, .35)), (L * (ra + .24), sg * sideA(.4, .3)),
                        (L * (ra + .24), sg * sideA(.4, .7)), (L * (ra + .15), sg * sideA(.4, .65))], (0.03, 0.03, 0.03))
    # ---- number roundels: both doors + bonnet
    rbg = (0.97, 0.97, 0.95) if sum(b0) < 2.0 else (0.03, 0.03, 0.03)
    rfg = (0.03, 0.03, 0.03) if rbg[0] > 0.5 else (0.97, 0.97, 0.95)
    big = sp["kind"] == "stock"
    dmid = L * (ra + fa) / 2
    for sg in (-1, 1):
        rot = 0 if sg > 0 else 180
        hh = sideA(.5, 1.0) * (0.52 if big else 0.36)
        b = sg * sideA(.5, .55)
        if sp["kind"] in ("group5", "stock", "touring", "gt", "rally"):
            p.rect(dmid - hh * 0.9, b - hh * .62, dmid + hh * 0.9, b + hh * .62, rbg, r=1.2)
        else:
            p.ellipse(dmid, b, hh * .7, hh * .7, rbg)
        p.text(num, dmid, b, hh * 0.78, rfg, font=FONT_B, rot=rot)
    p.ellipse(L * .70, 0, 8, 8, rbg) if sp["kind"] not in ("formula",) else None
    p.text(num, L * .70, 0, 9, rfg, rot=90) if sp["kind"] not in ("formula",) else None
    # ---- sponsor decals (invented brands)
    sps = sp["sponsors"]
    slots = [((ra + .05) * L, .62, 5.0), ((fa + .10) * L, .48, 3.8), ((ra + fa) / 2 * L, .88, 3.2),
             (0.88 * L, .30, 3.0), (0.25 * L, .30, 3.0)]
    pal = [a1, a2, (0.97, 0.97, 0.95), (0.03, 0.03, 0.03)]
    for i, (along, frac, hmm) in enumerate(slots):
        brand = sps[i % len(sps)]
        bgc = pal[(i + sp["seed"]) % len(pal)]
        if bgc == b0: bgc = pal[(i + 1 + sp["seed"]) % len(pal)]
        fg = (0.03, 0.03, 0.03) if sum(srgb(bgc)) > 1.6 else (0.97, 0.97, 0.95)
        for sg in (-1, 1):
            p.text(brand, along, sg * sideA(.5, frac), hmm, fg, font=FONT_C if i % 2 else FONT_I,
                   rot=0 if sg > 0 else 180, bg=bgc, pad=0.9, rr=.8)
    # top decal: windscreen-banner style on the bonnet + small one on the deck
    p.text(sps[0], L * .86, 0, 3.2, (0.97, 0.97, 0.95) if sum(b0) < 1.5 else (0.03, 0.03, 0.03), rot=90)
    if sp["kind"] not in ("formula",):
        p.text(sps[1], L * .10, 0, 3.0, a1 if a1 != b0 else a2, rot=90, bg=(0.03, 0.03, 0.03) if sum(a1) > 1.5 else None)
    # ---- small stickers: chequer strip, flags, contingency dots
    for sg in (-1, 1):
        a0 = L * (fa + .06); b = sg * sideA(.5, .8)
        for k in range(10):
            for r in range(2):
                if (k + r) % 2: p.rect(a0 + k * 1.2, b + r * 1.2 - 1.2, a0 + k * 1.2 + 1.2, b + r * 1.2, (0.03, 0.03, 0.03))
                else: p.rect(a0 + k * 1.2, b + r * 1.2 - 1.2, a0 + k * 1.2 + 1.2, b + r * 1.2, (0.97, 0.97, 0.95))
        for k in range(4):
            cc = [(0.9, 0.1, 0.1), (0.1, 0.3, 0.9), (0.95, 0.8, 0.05), (0.1, 0.6, 0.2)][k]
            p.rect(L * (ra + .02) + k * 4.5, sg * sideA(.5, .2), L * (ra + .02) + k * 4.5 + 3.5, sg * (sideA(.5, .2) + 2.2), cc, r=.4)
    if lv == "stickerbomb":   # lots of overlapping stickers
        brands = sps * 6
        for i in range(34):
            a = rnd.uniform(.05, .95) * L; sg = rnd.choice([-1, 1]); f = rnd.uniform(.1, .95)
            b = sg * sideA(.5, f) if rnd.random() < .75 else rnd.uniform(-14, 14)
            c1 = rnd.choice([a1, a2, (0.97, 0.97, 0.95), (0.95, 0.85, 0.05), (0.55, 0.15, 0.85)])
            fg = (0.03, 0.03, 0.03) if sum(srgb(c1)) > 1.6 else (0.97, 0.97, 0.95)
            if rnd.random() < .3:
                p.ellipse(a, b, 3.5, 3.5, c1); p.text(rnd.choice("★●▲") if False else str(rnd.randint(1, 99)), a, b, 3, fg)
            else:
                p.text(brands[i % len(brands)], a, b, rnd.uniform(2.2, 3.6), fg, font=rnd.choice([FONT_B, FONT_C, FONT_I]),
                       rot=(0 if sg > 0 else 180) if abs(b) > 14 else 90, bg=c1, pad=.7, rr=.6)
    # ---- damage on top
    paint_damage(p, sp, rnd, meta)
    if sp["damage"] >= 2:  # torn decal: chunk of a sponsor scraped back to primer/base
        for sg in (-1, 1):
            a = (ra + .05) * L; b = sg * sideA(.5, .62)
            p.poly([(a + rnd.uniform(4, 9), b + rnd.uniform(-3, 3)) for _ in range(5)], tuple(min(1, c * .5 + .45) for c in b0))

def paint_cabin(p, sp, meta, rnd):
    """Dark glass, roof panel in body colour (livery stripes carried over), number on the roof."""
    L, P = p.L, p.P
    glass = (0.012, 0.016, 0.024)
    p.rect(0, -P, L, P, glass)
    b0 = sp["base"]
    rw = top_half(meta, .5) * 0.85
    p.rect(L * .24, -rw, L * .76, rw, b0, r=2)              # roof panel
    sa = side_arc(meta, .5, 1.0)
    for sg in (-1, 1):
        p.rect(0, sg * sa * .90, L, sg * P, b0)               # sill blends into the body
        p.rect(L * .76, sg * rw * .9, L * .80, sg * sa, b0)    # A pillar
    lv = sp["livery"]
    if lv == "twin":
        for o in (-9, 9): p.rect(L * .24, o - 4, L * .76, o + 4, sp["acc"])
    elif lv == "centre":
        p.rect(L * .24, -6, L * .76, 6, sp["acc"])
    elif lv == "stripes3":
        p.rect(L * .24, -2.5, L * .76, 2.5, sp["acc"])
    elif lv == "diagonal":
        p.rect(L * .24, -rw, L * .76, rw, sp["acc"], r=2)
    for k in range(3):                                         # reflections on the screen
        a = L * rnd.uniform(.84, .95)
        p.line([(a, -rw * 0.8), (a - 3, rw * 0.8)], (0.10, 0.12, 0.15), .5)
    if sp["kind"] != "formula":
        rbg = (0.97, 0.97, 0.95) if sum(b0) < 2.0 else (0.03, 0.03, 0.03)
        rfg = (0.03, 0.03, 0.03) if rbg[0] > .5 else (0.97, 0.97, 0.95)
        hh = min(rw * 0.75, 11.0)
        p.rect(L * .50 - hh * .8, -hh * .62, L * .50 + hh * .8, hh * .62, rbg, r=1.5)
        p.text(sp["num"], L * .50, 0, hh * .9, rfg, rot=90)
        p.text(sp["sponsors"][0], L * .90, 0, 2.2, (0.92, 0.92, 0.92), rot=0)  # windscreen banner

# ---------------------------------------------------------------- car builder
def build_car(sp, origin=(0, 0, 0)):
    rnd = random.Random(sp["seed"])
    os.makedirs(TEX_DIR, exist_ok=True)
    name = sp["name"]; L, W, H = sp["L"] / 100, sp["W"] / 100, sp["H"] / 100; wr = sp["wr"] / 100
    root = bpy.data.objects.new(name, None); bpy.context.collection.objects.link(root)
    root.location = origin
    dark = pmat(f"{name} dark", (0.015, 0.015, 0.015), rough=0.6)
    rubber = pmat(f"{name} tyre", (0.025, 0.025, 0.025), rough=0.85)
    rimm = pmat(f"{name} rim", sp["rim"], rough=0.3, metal=0.7)
    carbon = pmat(f"{name} carbon", (0.03, 0.03, 0.035), rough=0.35, coat=0.5)
    accm = pmat(f"{name} acc", sp["acc"], rough=0.3, coat=0.5)
    basem = pmat(f"{name} basecol", sp["base"], rough=0.3, coat=0.5)
    if sp["kind"] == "formula":
        build_formula(sp, root, rnd, dark, rubber, rimm, carbon, accm, basem)
        if "driver" in sp.get("rc", []):
            add_driver(sp, root, 0.0, -L / 2 + 0.43 * L, H * 0.62)
        return root
    if sp.get("nobody"):
        return build_bare(sp, root, rnd, L, W, H, wr, dark, rubber, rimm, carbon)
    S = sp.get("shape") or SHAPES[sp["kind"]]
    clr = S.get("clearance", 0.006)
    lift = sp.get("lift", 0.0) / 100                     # body raised on its suspension (trucks, crawlers)
    open_w = sp.get("open", False)                       # wheels outside the body (buggies, monsters)
    Wb = W * sp.get("body_frac", 1.0)                    # body width; W = overall width incl. wheels
    wb = sp.get("wb", 0.60 if sp["kind"] == "rally" else 0.58) * L
    centre = 0.01 * L
    ra = 0.5 - wb / (2 * L) - centre / L; fa = 0.5 + wb / (2 * L) - centre / L
    archw = (wr * 1.15) / L
    arch_top = max(clr + 0.002, 2 * wr + 0.0015 - lift)  # wheel top in body coordinates
    bodyp = bpy.data.objects.new(f"{name}__bodyroot", None); bpy.context.collection.objects.link(bodyp)
    bodyp.parent = root; bodyp.location = (0, 0, lift)

    def F(s):
        s = np.asarray(s, float)
        top = prof(S["top"], s) * H + S["fender"] * 0 * H
        hw = prof(S["hw"], s) * Wb / 2 + S["flare"] * Wb / 2 * (gauss(s, ra, archw * .9) + gauss(s, fa, archw * .9))
        hw = np.minimum(hw, Wb / 2)
        cut = 0.0 if open_w else 1.0
        bot = clr + cut * (arch_top - clr) * np.clip(1.25 * (np.maximum(gauss(s, ra, archw * .62), gauss(s, fa, archw * .62)) - .2), 0, 1) ** .7
        bot = np.minimum(bot, top - 0.006)
        fend = S["fender"] * H * (0.35 + 0.65 * np.maximum(gauss(s, ra, archw * 1.1), gauss(s, fa, archw * 1.1)))
        return 0.0, float(hw), float(bot), float(top), S["n"], S["tumble"], float(fend)

    dents = []
    if sp["damage"]:
        for _ in range(2 * sp["damage"]):
            ds = rnd.choice([rnd.uniform(.85, .97), rnd.uniform(.03, .15), rnd.uniform(.3, .7)])
            da = rnd.choice([-1, 1]) * rnd.uniform(0.025, 0.045)
            dents.append((ds, da, rnd.uniform(0.008, 0.014), rnd.uniform(0.0022, 0.0040) * sp["damage"]))
        if sp["damage"] >= 2:   # crumpled front corner
            dents.append((0.99, rnd.choice([-1, 1]) * 0.035, 0.014, 0.007))
    v, f, fu, meta = loft(f"{name}__body", -L / 2, L / 2, F, nose_R=S["nose"] * L, tail_R=S["tail"] * L, dents=dents)
    p = Painter(meta, sp["base"])
    paint_body(p, sp, meta, rnd, (ra, fa))
    if S.get("cab") is None:   # no glass cabin: windows painted on the shell (buggy / truggy bodies)
        glass = (0.012, 0.016, 0.024)
        th = top_half(meta, .66)
        p.poly([(p.L * .56, -th * .7), (p.L * .74, -th * .5), (p.L * .74, th * .5), (p.L * .56, th * .7)], glass)
        for sg in (-1, 1):
            p.poly([(p.L * .50, sg * th * .95), (p.L * .70, sg * th * .9), (p.L * .66, sg * th * 1.25), (p.L * .52, sg * th * 1.3)], glass)
    if sp["kind"] == "rally" or sp.get("dirt"):
        p.im = paint_dirt(p.im, sp.get("dirt", 1.2), rnd); p.d = ImageDraw.Draw(p.im)
    lexan = sp.get("lexan", 0)   # 0 = hard body; 1 = clear windows; 2 = + unpainted panels
    bmask = None
    if lexan >= 2:
        for _ in range(rnd.randint(1, 3)):
            a0 = rnd.uniform(0, .8) * p.L; w_ = rnd.uniform(.08, .3) * p.L
            if rnd.random() < .5: p.rect(a0, -p.P, a0 + w_, p.P, KEY)
            else:
                sg = rnd.choice([-1, 1]); f0 = rnd.uniform(.3, .8)
                p.rect(a0, sg * side_arc(meta, .5, f0), a0 + w_, sg * p.P, KEY)
    tpath = f"{TEX_DIR}/{name.replace(' ', '_')}_body.png"
    if lexan >= 2:
        bmask = f"{TEX_DIR}/{name.replace(' ', '_')}_bodymask.png"
        p.im = clear_mask_from(p.im, bmask); p.d = ImageDraw.Draw(p.im)
    p.save(tpath)
    matte = sp.get("matte", False)
    bodym = tex_mat(f"{name} body", tpath, rough=0.62 if matte else 0.28, metal=sp.get("metal", 0.0), coat=0 if matte else 0.7, clear_mask=bmask)
    body = mesh_obj(f"{name}__body", v, f, fu, bodym, bodyp)

    # ---- cabin
    has_cab = S.get("cab") is not None
    c0, c1, cw, ctop = S["cab"] if has_cab else (.4, .6, 0, [])
    def Fc(sc):
        s = c0 + (c1 - c0) * sc
        bt = float(prof(S["top"], s) * H) - 0.005
        tp = float(prof(ctop, s, smooth=0.02) * H)
        hw = float(prof(S["hw"], s) * Wb / 2 * cw * (0.92 + 0.08 * math.sin(math.pi * sc)))
        return 0.0, hw, bt, max(tp, bt + 0.002), S.get("cabn", 3.2), S.get("cabt", 0.25), 0.0
    if has_cab:
        vc, fc, fuc, metac = loft(f"{name}__cabin", -L / 2 + c0 * L, -L / 2 + c1 * L, Fc, M=40, N=48,
                                  nose_R=0.25 * (c1 - c0) * L, tail_R=0.2 * (c1 - c0) * L)
        pc = Painter(metac, (0.015, 0.02, 0.03)); paint_cabin(pc, sp, metac, rnd)
        cpath = f"{TEX_DIR}/{name.replace(' ', '_')}_cabin.png"
        cmask = None
        if lexan >= 1:
            cmask = f"{TEX_DIR}/{name.replace(' ', '_')}_cabinmask.png"
            pc.im = clear_mask_from(pc.im, cmask, glass=True); pc.d = ImageDraw.Draw(pc.im)
        pc.save(cpath)
        cabm = tex_mat(f"{name} cabin", cpath, rough=0.08 if not matte else 0.3, coat=0.8 if not matte else 0, clear_mask=cmask)
        mesh_obj(f"{name}__cabin", vc, fc, fuc, cabm, bodyp)

    # ---- chassis plate, wheels
    ww = sp["ww"] / 100 if sp.get("ww") else {"drift": 0.0115, "group5": 0.0115, "rally": 0.010}.get(sp["kind"], 0.0095)
    if lift > 0 or open_w:
        box(f"{name}__chassis", (min(Wb, W * 0.5) * 0.8, L * 0.86, 0.003), (0, -centre, max(clr * 0.6 + 0.0015, wr * 0.55)), dark, root)
    else:
        box(f"{name}__chassis", (W * 0.78, L * 0.86, 0.003), (0, -centre, clr * 0.6 + 0.0015), dark, root)
    track = (W / 2 - ww / 2 * 0.2) if open_w else W / 2 - 0.0045
    spokes = sp.get("spokes", 5 if sp["seed"] % 2 else 6)
    knobs = sp.get("knobs", 0)
    for sg_x in (-1, 1):
        for (sa, wwid, rr) in ((ra, ww * sp.get("rear_ww", 1.12), wr), (fa, ww * sp.get("front_ww", 1.0), wr * sp.get("front_r", 1.0))):
            y = -L / 2 + sa * L
            x = sg_x * (track - wwid / 2 + 0.0008)
            add_wheel(name, root, (x, y, rr), rr, wwid, sg_x, rubber, rimm, dark, spokes=spokes,
                      camber=sp.get("camber", 0.12 if sp["kind"] == "drift" else 0.0), knobs=knobs)
    # ---- mirrors
    if has_cab and not open_w:
        sm = c0 + (c1 - c0) * 0.80
        for sg in (-1, 1):
            y = -L / 2 + sm * L
            hw_ = float(prof(S["hw"], sm) * Wb / 2 * cw)
            z = float(prof(S["top"], sm) * H) + 0.006
            box(f"{name}__mirror", (0.007, 0.004, 0.0045), (sg * (hw_ + 0.004), y, z), basem, bodyp, bevel=0.001)
    # ---- aero
    add_aero(sp, S, bodyp, L, Wb if not open_w else W * 0.85, H, ra, fa, carbon, accm, basem, dark, rnd)
    if sp.get("tape"):   # a strip of fluorescent tape across the roof (seen from above)
        cab = S.get("cab") or (.4, .6)
        tm = pmat(f"{name} tape", sp["tape"], rough=0.5, emit=tuple(x * 0.35 for x in sp["tape"]))
        box(f"{name}__tape", (Wb * 0.62, 0.024, 0.0012), (0, -L / 2 + (cab[0] + cab[1]) / 2 * L, H + 0.0008), tm, bodyp)
    # ---- RC hardware (shock towers, shocks, arms, antenna, body clips, bumpers, rails, spare, light bar)
    if sp.get("rc"):
        add_rc(sp, root, L, W, Wb, H, wr, ww, ra, fa, track, lift, clr, dark, carbon, rnd)
    return root

def add_driver(sp, root, x, y, z):
    """Helmet + shoulders (a scale driver figure); from above it's the round helmet that shows."""
    name = sp["name"]
    hc = sp.get("helmet", (0.95, 0.95, 0.95))
    r = sp["H"] / 100 * 0.16
    sph(f"{name}__helmet", r, (x, y, z + r * 1.4), pmat(f"{name} helmet", hc, 0.25, coat=0.8), root)
    box(f"{name}__visor", (r * 1.3, r * 0.5, r * 0.5), (x, y + r * 0.6, z + r * 1.4), pmat(f"{name} visor", (0.02, 0.02, 0.03), 0.05), root)
    sph(f"{name}__shoulders", r * 1.4, (x, y - r * 0.2, z + r * 0.2), pmat(f"{name} suit", sp.get("suit", (0.8, 0.1, 0.1)), 0.6), root, scale=(1.3, 0.8, 0.6))

def build_bare(sp, root, rnd, L, W, H, wr, dark, rubber, rimm, carbon):
    """No body: the chassis as raced without a shell (crawler comps, practice, a lost body) – plate, battery,
    motor, ESC, receiver, servo, wires, plus wheels and the RC hardware."""
    name = sp["name"]
    open_w = True; lift = sp.get("lift", 0.0) / 100; clr = 0.006
    wb = sp.get("wb", 0.6) * L; ra = 0.5 - wb / (2 * L); fa = 0.5 + wb / (2 * L)
    ww = sp.get("ww", wr * 80) / 100
    Wb = W * sp.get("body_frac", 0.6)
    track = W / 2 - ww / 2 * 0.2
    zc = max(0.006, wr * 0.55)
    box(f"{name}__plate", (Wb * 0.7, L * 0.9, 0.003), (0, 0, zc), carbon if rnd.random() < 0.5 else pmat(f"{name} alu plate", (0.6, 0.62, 0.66), 0.3, 0.9), root)
    bat = rnd.choice([(0.05, 0.05, 0.05), (0.1, 0.25, 0.8), (0.8, 0.1, 0.1), (0.95, 0.7, 0.05), (0.2, 0.2, 0.22)])
    sx = rnd.choice([-1, 1]) * Wb * 0.15
    box(f"{name}__battery", (Wb * 0.28, L * 0.42, 0.014), (sx, -L * 0.02, zc + 0.008), pmat(f"{name} bat", bat, 0.4), root, bevel=0.001)
    box(f"{name}__batlabel", (Wb * 0.2, L * 0.18, 0.0004), (sx, -L * 0.02, zc + 0.0152), pmat(f"{name} label", rnd.choice([(0.95, 0.95, 0.95), (0.95, 0.8, 0.1), (0.1, 0.8, 0.9)]), 0.5), root)
    mc = rnd.choice([(0.1, 0.25, 0.8), (0.75, 0.1, 0.1), (0.05, 0.05, 0.05), (0.85, 0.55, 0.1), (0.5, 0.5, 0.55)])
    cyl(f"{name}__motor", 0.0085, 0.022, (-sx * 0.8, -L * 0.28, zc + 0.01), pmat(f"{name} motor", mc, 0.3, 0.8), root, rot=(math.pi / 2, 0, 0), verts=20)
    box(f"{name}__esc", (0.016, 0.02, 0.012), (-sx, L * 0.05, zc + 0.007), dark, root, bevel=0.001)
    cyl(f"{name}__fan", 0.006, 0.002, (-sx, L * 0.05, zc + 0.014), pmat(f"{name} fan", (0.15, 0.15, 0.17), 0.4), root, rot=(0, 0, 0), verts=16)
    box(f"{name}__rx", (0.012, 0.016, 0.007), (-sx, L * 0.22, zc + 0.005), pmat(f"{name} rx", rnd.choice([(0.05, 0.05, 0.05), (0.9, 0.9, 0.9), (0.8, 0.1, 0.1)]), 0.5), root)
    box(f"{name}__servo", (0.02, 0.01, 0.012), (0, L * 0.32, zc + 0.007), dark, root)
    wire = [pmat(f"{name} wr", (0.8, 0.05, 0.05), 0.5), pmat(f"{name} wb", (0.02, 0.02, 0.02), 0.5)]
    for k in range(2):
        _cyl_between(f"{name}__wire", (sx + (k - .5) * 0.004, L * 0.18, zc + 0.012), (-sx + (k - .5) * 0.004, L * 0.06, zc + 0.012), 0.0012, wire[k], root, verts=6)
    for sg_x in (-1, 1):
        for sa in (ra, fa):
            add_wheel(name, root, (sg_x * (track - ww / 2 + 0.0008), -L / 2 + sa * L, wr), wr, ww, sg_x, rubber, rimm, dark,
                      spokes=sp.get("spokes", 6), knobs=sp.get("knobs", 0))
    if sp.get("rc"):
        add_rc(sp, root, L, W, Wb, H * 0.6, wr, ww, ra, fa, track, lift, clr, dark, carbon, rnd)
    return root

def _cyl_between(name, a, b, r, m, parent, verts=12):
    """A cylinder from point a to point b."""
    from mathutils import Vector
    a, b = Vector(a), Vector(b); d = b - a
    if d.length < 1e-5: return None
    rot = Vector((0, 0, 1)).rotation_difference(d.normalized()).to_euler()
    return cyl(name, r, d.length, tuple((a + b) / 2), m, parent, rot=tuple(rot), verts=verts)

def add_rc(sp, root, L, W, Wb, H, wr, ww, ra, fa, track, lift, clr, dark, carbon, rnd):
    """The parts that make an RC car look like an RC car from above and from the side."""
    name = sp["name"]; parts = set(sp["rc"])
    metal = pmat(f"{name} alu", sp.get("alu", (0.55, 0.57, 0.62)), rough=0.3, metal=0.9)
    plast = pmat(f"{name} parts", sp.get("parts_col", (0.03, 0.03, 0.03)), rough=0.5)
    body_top = lift + H * 0.62
    for sa in (ra, fa):
        y = -L / 2 + sa * L
        axle_z = wr
        tower_top = max(body_top - 0.004, axle_z + wr * 0.9) if "towers" in parts else axle_z + wr * 0.8
        if "towers" in parts:
            box(f"{name}__tower", (min(Wb, W * 0.55) * 0.75, 0.0025, tower_top - wr * 0.6), (0, y + (0.006 if sa < 0.5 else -0.006), (tower_top + wr * 0.6) / 2), carbon, root)
        if "arms" in parts:
            inner = min(Wb, W * 0.5) * 0.35
            for sg in (-1, 1):
                ln = abs(track) - inner
                box(f"{name}__arm", (ln, 0.007, 0.0025), (sg * (inner + ln / 2), y, axle_z * 0.85), plast, root)
                cyl(f"{name}__hex", wr * 0.22, 0.004, (sg * (abs(track) - ww * 0.3), y, axle_z), metal, root, verts=6)
        if "shocks" in parts:
            for sg in (-1, 1):
                a = (sg * min(Wb, W * 0.55) * 0.3, y, tower_top - 0.002)
                b = (sg * (abs(track) - ww * 0.9), y, axle_z * 0.9)
                _cyl_between(f"{name}__shock", a, b, 0.0028, pmat(f"{name} shock", sp.get("shock_col", (0.85, 0.65, 0.1)), rough=0.25, metal=0.8), root)
                _cyl_between(f"{name}__spring", a, b, 0.0036, pmat(f"{name} spring", sp.get("spring_col", (0.9, 0.1, 0.1)), rough=0.4), root, verts=8)
    if "antenna" in parts:
        ha = rnd.uniform(0.6, 1.4) * H
        sg = rnd.choice([-1, 1])
        cyl(f"{name}__antenna", 0.0011, ha, (sg * Wb * 0.22, -L * rnd.uniform(0.05, 0.3), body_top + ha / 2 - 0.004),
            pmat(f"{name} ant", sp.get("ant_col", (0.05, 0.05, 0.05)), rough=0.6), root, rot=(0, 0, 0), verts=8)
    if "clips" in parts:
        for sy in (ra + 0.04, fa - 0.06):
            for sg in (-1, 1):
                cyl(f"{name}__post", 0.0013, 0.007, (sg * Wb * 0.14, -L / 2 + sy * L, body_top + 0.001), dark, root, rot=(0, 0, 0), verts=8)
                cyl(f"{name}__clip", 0.0024, 0.0006, (sg * Wb * 0.14 + 0.002, -L / 2 + sy * L, body_top + 0.003), metal, root, rot=(math.pi / 2, 0, 0), verts=10)
    if "bumpers" in parts:
        bz = max(0.006, wr * 0.7)
        box(f"{name}__bumper_f", (Wb * rnd.uniform(0.45, 0.8), 0.008, 0.007), (0, L / 2 + 0.002, bz), plast, root, bevel=0.0015)
        box(f"{name}__bumper_r", (Wb * rnd.uniform(0.4, 0.7), 0.006, 0.006), (0, -L / 2 - 0.001, bz), plast, root, bevel=0.0015)
    if "rails" in parts:
        for sg in (-1, 1):
            box(f"{name}__rail", (0.003, L * 0.9, 0.008), (sg * W * 0.13, 0, wr * 1.05), metal, root)
        for sa in (ra, fa):   # solid axles of a crawler
            _cyl_between(f"{name}__axle", (-abs(track) * 0.85, -L / 2 + sa * L, wr), (abs(track) * 0.85, -L / 2 + sa * L, wr), 0.004, metal, root)
            cyl(f"{name}__diff", 0.009, 0.012, (0, -L / 2 + sa * L, wr), metal, root, rot=(math.pi / 2, 0, 0), verts=16)
    if "spare" in parts:
        cyl(f"{name}__spare", wr * 0.85, ww * 0.8, (0, -L * 0.30, lift + H * 0.62 + ww * 0.4), pmat(f"{name} sparetyre", (0.025, 0.025, 0.025), rough=0.85), root, rot=(0, 0, 0), verts=32)
    if "lightbar" in parts:
        cab = (sp.get("shape") or SHAPES[sp["kind"]]).get("cab") or (.4, .6)
        yl = -L / 2 + cab[1] * L - 0.01
        box(f"{name}__lightbar", (Wb * 0.6, 0.006, 0.005), (0, yl, lift + H + 0.004), dark, root)
        for k in range(5):
            cyl(f"{name}__lamp", 0.0022, 0.002, (-Wb * 0.24 + k * Wb * 0.12, yl + 0.0032, lift + H + 0.004), pmat("lamp", (1, 1, .9), .1, emit=(1, 1, .85)), root, rot=(math.pi / 2, 0, 0), verts=12)
    if "driver" in parts:
        cab = (sp.get("shape") or SHAPES[sp["kind"]]).get("cab") or (.4, .6)
        add_driver(sp, root, -Wb * 0.12 if sp.get("rhd") else Wb * 0.12, -L / 2 + (cab[0] + cab[1]) / 2 * L, lift + H * 0.55)
    if "wheelie" in parts:   # drag car wheelie bar with two small wheels
        for sg in (-1, 1):
            _cyl_between(f"{name}__wbar", (sg * 0.006, -L / 2, wr * 0.8), (sg * 0.012, -L / 2 - L * 0.28, 0.006), 0.0014, metal, root, verts=8)
            cyl(f"{name}__wbwheel", 0.005, 0.004, (sg * 0.014, -L / 2 - L * 0.28, 0.005), dark, root, verts=16)
    if "underglow" in parts:
        c = sp.get("glow", (0.1, 0.6, 1.0))
        box(f"{name}__glow", (Wb * 0.8, L * 0.7, 0.001), (0, 0, max(0.003, lift + clr * 0.5)), pmat(f"{name} glow", c, 0.5, emit=c), root)
    if "cage" in parts:   # roll cage tubes over a crawler / sct cab
        cab = (sp.get("shape") or SHAPES[sp["kind"]]).get("cab") or (.4, .6)
        for sg in (-1, 1):
            _cyl_between(f"{name}__cage", (sg * Wb * 0.4, -L / 2 + cab[0] * L, lift + H * 0.6), (sg * Wb * 0.35, -L / 2 + cab[1] * L, lift + H * 1.02), 0.0012, metal, root, verts=8)

def add_wheel(name, root, loc, r, w, side, rubber, rimm, dark, spokes=5, camber=0.0, knobs=0):
    t = cyl(f"{name}__tyre", r * (0.93 if knobs else 1.0), w, loc, rubber, root, rot=(0, math.pi / 2 + side * camber, 0), verts=40)
    if knobs:   # off-road tread: blocks round the tyre, two staggered rows
        x0, y0, z0 = loc
        for k in range(knobs):
            a = 2 * math.pi * k / knobs
            for row in (-1, 1):
                aa = a + (math.pi / knobs if row > 0 else 0)
                box(f"{name}__knob", (w * 0.38, r * 0.16, r * 0.12),
                    (x0 + row * w * 0.22, y0 + math.sin(aa) * r * 0.97, z0 + math.cos(aa) * r * 0.97), rubber, root, rot=(-aa, 0, 0))
    md = t.modifiers.new("bevel", "BEVEL"); md.width = r * 0.22; md.segments = 4; md.limit_method = "ANGLE"
    for p_ in t.data.polygons: p_.use_smooth = True
    x, y, z = loc
    rim = cyl(f"{name}__rim", r * 0.66, w * 0.6, (x + side * w * 0.22, y, z), rimm, root, rot=(0, math.pi / 2, 0), verts=24)
    cyl(f"{name}__rimdish", r * 0.58, w * 0.62, (x + side * w * 0.23, y, z), dark, root, rot=(0, math.pi / 2, 0), verts=24)
    for k in range(spokes):
        a = 2 * math.pi * k / spokes
        box(f"{name}__spoke", (0.0012, r * 1.08, r * 0.13), (x + side * (w * 0.55), y, z), rimm, root, rot=(a, 0, 0))
    cyl(f"{name}__hub", r * 0.16, w * 0.75, (x + side * w * 0.25, y, z), rimm, root, rot=(0, math.pi / 2, 0), verts=12)

def add_aero(sp, S, root, L, W, H, ra, fa, carbon, accm, basem, dark, rnd):
    name = sp["name"]; kind = S.get("wing")
    deck = float(prof(S["top"], 0.06) * H)
    y_r = -L / 2 + 0.05 * L
    def wing(span, chord, z, y, tilt=0.12, plate_h=0.008, posts=True, mat=carbon, swan=False, roll=0.0):
        box(f"{name}__wing", (span, chord, 0.0018), (0, y, z), mat, root, rot=(tilt, roll, 0), bevel=0.0006)
        for sg in (-1, 1):
            box(f"{name}__endplate", (0.0012, chord * 1.1, plate_h), (sg * span / 2, y, z - plate_h * 0.25 + sg * roll * span / 2),
                mat, root)
            if posts:
                zz = (z + deck) / 2
                if swan:
                    box(f"{name}__post", (0.0015, 0.004, z - deck + 0.004), (sg * span * 0.22, y - chord * .1, zz + 0.002), dark, root)
                else:
                    box(f"{name}__post", (0.0012, 0.004, z - deck), (sg * span * 0.30, y + chord * .1, zz), dark, root)
    if kind is None or kind == "none":
        pass
    elif kind == "buggy":   # big flat RC wing on two posts over the rear
        chord = L * rnd.uniform(0.18, 0.28)
        wing(W * rnd.uniform(0.75, 1.0), chord, H * rnd.uniform(1.05, 1.35), -L / 2 + chord * 0.35, tilt=rnd.uniform(0.12, 0.35), plate_h=rnd.uniform(0.008, 0.02))
    elif kind == "gt":
        wing(W * 0.90, 0.018, H * 1.0, y_r, tilt=0.15)
        box(f"{name}__splitter", (W * 0.86, 0.006, 0.0012), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.0045), carbon, root)
    elif kind == "lmp":
        wing(W * 0.95, 0.016, H * 0.98, y_r, tilt=0.10, plate_h=0.02)
        top_c = float(prof(S["top"], 0.4) * H)
        box(f"{name}__fin", (0.0012, L * 0.38, H * 0.30), (0, -L / 2 + 0.24 * L, top_c + H * 0.15), basem, root)
        box(f"{name}__splitter", (W * 0.94, 0.012, 0.0012), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.0045), carbon, root)
    elif kind == "roof":
        c1 = S["cab"][0]
        box(f"{name}__roofwing", (W * 0.66, 0.012, 0.0016), (0, -L / 2 + c1 * L + 0.002, H * 0.985), basem, root, rot=(-0.12, 0, 0))
        # light pod on the bonnet + mud flaps
        yp = -L / 2 + 0.93 * L; zp = float(prof(S["top"], 0.93) * H) + 0.004
        box(f"{name}__lightpod", (W * 0.62, 0.006, 0.008), (0, yp, zp), dark, root, bevel=0.001)
        for k in range(4):
            cyl(f"{name}__lamp", 0.0032, 0.002, (-W * 0.24 + k * W * 0.16, yp + 0.0035, zp), pmat("lamp", (1, 1, .9), .1, emit=(1, 1, .85)),
                root, rot=(math.pi / 2, 0, 0), verts=16)
        for sg in (-1, 1):
            for sa in (ra, fa):
                box(f"{name}__mudflap", (0.008, 0.0010, 0.007), (sg * (W / 2 - 0.009), -L / 2 + sa * L - sp["wr"] / 100 - 0.002, 0.0045),
                    pmat("flap", sp["acc2"], .6), root)
    elif kind == "lip":
        box(f"{name}__lip", (W * 0.70, 0.008, 0.002), (0, -L / 2 + 0.025 * L, deck + 0.003), carbon, root, rot=(0.35, 0, 0))
        box(f"{name}__splitter", (W * 0.9, 0.008, 0.0012), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.0045), carbon, root)
    elif kind == "drift":
        bent = 0.10 if sp["damage"] >= 2 else 0.0   # bruised: wing knocked crooked
        wing(W * 0.98, 0.017, H * 1.05, y_r - 0.004, tilt=0.18, mat=carbon, roll=bent)
        for sg in (-1, 1):  # canards
            box(f"{name}__canard", (0.010, 0.006, 0.0012), (sg * (W / 2 - 0.004), L / 2 - 0.010, 0.012), carbon, root, rot=(0, sg * 0.4, 0))
        box(f"{name}__splitter", (W * 0.95, 0.012, 0.0012), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.0040), carbon, root)
    elif kind == "group5":
        wing(W * 1.0, 0.024, deck + 0.004, -L / 2 + 0.012, tilt=0.05, posts=False, mat=basem, plate_h=0.008)
        box(f"{name}__splitter", (W * 0.98, 0.012, 0.0015), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.005), dark, root)
    elif kind == "ducktail":
        box(f"{name}__duck", (W * 0.74, 0.012, 0.002), (0, -L / 2 + 0.008, deck + 0.0015), basem, root, rot=(0.4, 0, 0))
        box(f"{name}__splitter", (W * 0.86, 0.010, 0.0012), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.0040), carbon, root)
    elif kind == "blade":
        box(f"{name}__blade", (W * 0.92, 0.0015, 0.006), (0, -L / 2 + 0.004, deck + 0.003), pmat("blade", (.8, .8, .85), .3, .6), root,
            rot=(-0.3, 0, 0))
        box(f"{name}__splitter", (W * 0.9, 0.010, 0.0015), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.0045), dark, root)
    elif kind == "swan":
        wing(W * 0.88, 0.017, H * 1.02, y_r, tilt=0.12, swan=True)
        box(f"{name}__fin", (0.0012, L * 0.30, H * 0.22), (0, -L / 2 + 0.22 * L, float(prof(S["top"], .2) * H) + H * 0.10), carbon, root)
        box(f"{name}__splitter", (W * 0.94, 0.012, 0.0012), (0, L / 2 - S["nose"] * L * 0.45 - 0.003, 0.0040), carbon, root)

def build_formula(sp, root, rnd, dark, rubber, rimm, carbon, accm, basem):
    name = sp["name"]; L, W, H = sp["L"] / 100, sp["W"] / 100, sp["H"] / 100; wr = sp["wr"] / 100
    ra, fa = 0.16, 0.80
    # monocoque + nose + engine cover as one loft, y from rear of gearbox to nose tip
    tops = [(0, .30), (.10, .42), (.30, .62), (.40, .80), (.46, .70), (.55, .52), (.75, .40), (.95, .28), (1, .22)]
    hws = [(0, .10), (.15, .16), (.35, .22), (.50, .22), (.65, .16), (.85, .10), (1, .07)]
    def F(s):
        s = float(s)
        return 0.0, float(prof(hws, s) * W / 2), 0.004, float(prof(tops, s) * H), 3.0, 0.15, 0.0
    v, f, fu, meta = loft(f"{name}__body", -L / 2 + 0.04 * L, L / 2 - 0.02 * L, F, nose_R=0.01 * L, tail_R=0.0)
    p = Painter(meta, sp["base"])
    # livery: black nose stripe, red accents, number on nose, sponsors on engine cover
    p.rect(p.L * .55, -4, p.L, 4, sp["acc"])
    p.rect(p.L * .25, -P_(p), p.L * .33, P_(p), sp["acc2"])
    p.text(sp["num"], p.L * .82, 0, 5.5, sp["acc"] if False else (0.03, 0.03, 0.03), rot=90, bg=(0.97, 0.97, 0.95), pad=.8)
    for sg in (-1, 1):
        p.text(sp["sponsors"][0], p.L * .22, sg * side_arc(meta, .25, .45), 3.6, (0.97, 0.97, 0.95), font=FONT_I,
               rot=0 if sg > 0 else 180, bg=(0.03, 0.03, 0.03), pad=.6)
        p.text(sp["num"], p.L * .60, sg * side_arc(meta, .6, .5), 4.5, (0.03, 0.03, 0.03), rot=0 if sg > 0 else 180)
    p.text(sp["sponsors"][1], p.L * .16, 0, 2.8, (0.03, 0.03, 0.03), rot=90)
    tpath = f"{TEX_DIR}/{name.replace(' ', '_')}_body.png"; p.save(tpath)
    bodym = tex_mat(f"{name} body", tpath, rough=0.25, coat=0.7)
    mesh_obj(f"{name}__body", v, f, fu, bodym, root)
    # sidepods
    for sg in (-1, 1):
        def Fs(s):
            s = float(s)
            return sg * W * 0.24, float(W * 0.09 * (0.6 + 0.4 * math.sin(math.pi * min(1, s * 1.3)))), 0.004, \
                   float(H * (0.30 + 0.16 * math.sin(math.pi * s))), 4.0, 0.1, 0.0
        vs, fs, fus, metas = loft(f"{name}__pod", -L / 2 + 0.24 * L, -L / 2 + 0.60 * L, Fs, M=32, N=40, nose_R=0.01, tail_R=0.0)
        ps = Painter(metas, sp["base"])
        ps.rect(0, -ps.P, ps.L * .3, ps.P, sp["acc"])
        ps.text(sp["sponsors"][2], ps.L * .62, 0, 3.0, (0.03, 0.03, 0.03), rot=90 if sg > 0 else 270)
        spth = f"{TEX_DIR}/{name.replace(' ', '_')}_pod{sg:+d}.png"; ps.save(spth)
        mesh_obj(f"{name}__pod", vs, fs, fus, tex_mat(f"{name} pod", spth, rough=.25, coat=.7), root)
    # wings
    yf = L / 2 - 0.018; yr = -L / 2 + 0.012
    box(f"{name}__fwing", (W * 0.94, 0.016, 0.0018), (0, yf, 0.0042), basem, root, rot=(0.08, 0, 0))
    box(f"{name}__fwing2", (W * 0.80, 0.010, 0.0015), (0, yf - 0.006, 0.0068), accm, root, rot=(0.3, 0, 0))
    for sg in (-1, 1):
        box(f"{name}__fplate", (0.0012, 0.020, 0.008), (sg * W * 0.47, yf, 0.0055), carbon, root)
    box(f"{name}__rwing", (W * 0.70, 0.016, 0.002), (0, yr, H * 0.92), basem, root, rot=(0.18, 0, 0))
    box(f"{name}__rwing2", (W * 0.70, 0.008, 0.0016), (0, yr - 0.006, H * 1.0), accm, root, rot=(0.5, 0, 0))
    for sg in (-1, 1):
        box(f"{name}__rplate", (0.0012, 0.024, H * 0.55), (sg * W * 0.35, yr - 0.002, H * 0.74), carbon, root)
    box(f"{name}__rpost", (0.0015, 0.006, H * 0.5), (0, yr + 0.004, H * 0.6), carbon, root)
    # helmet + halo
    yc = -L / 2 + 0.47 * L
    bpy.ops.mesh.primitive_uv_sphere_add(radius=0.0075, location=(0, yc, H * 0.68), segments=24, ring_count=12)
    hel = bpy.context.active_object; hel.name = f"{name}__helmet"; hel.data.materials.append(pmat("helmet", sp["acc2"], .25, coat=.8))
    for p_ in hel.data.polygons: p_.use_smooth = True
    link(hel, root)
    box(f"{name}__visor", (0.010, 0.002, 0.004), (0, yc + 0.0072, H * 0.70), dark, root)
    bpy.ops.mesh.primitive_torus_add(major_radius=0.012, minor_radius=0.0012, location=(0, yc + 0.004, H * 0.80),
                                     rotation=(0, 0, 0), major_segments=32, minor_segments=8)
    halo = bpy.context.active_object; halo.name = f"{name}__halo"; halo.data.materials.append(carbon); halo.scale = (0.85, 1.25, 1)
    link(halo, root)
    # wheels: open, outboard
    for sg in (-1, 1):
        for (sa, w_, r_) in ((ra, 0.0135, wr * 1.04), (fa, 0.0100, wr * 0.95)):
            y = -L / 2 + sa * L
            add_wheel(name, root, (sg * (W / 2 - w_ / 2), y, r_), r_, w_, sg, rubber, rimm, dark, spokes=6)
            # suspension arms
            box(f"{name}__arm", (W / 2 - w_ - 0.004, 0.0015, 0.0012), (sg * (W / 4 - w_ / 2 + 0.002), y, r_ * 1.1), carbon, root)
    return root

def P_(p): return p.P

# ---------------------------------------------------------------- API
def build_cars(scene, hide_old=True):
    if hide_old:
        for o in scene.objects:
            if o.name.endswith(" bil"): o.hide_render = True; o.hide_viewport = True
    out = []
    for i, sp in enumerate(SPECS):
        out.append((sp["name"], build_car(sp, (0.0, -3.0 - i * 0.4, 0.0))))
    return out
