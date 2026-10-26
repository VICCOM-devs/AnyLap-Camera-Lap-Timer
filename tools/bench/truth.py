import bpy, csv
from mathutils import Vector
bpy.ops.wm.open_mainfile(filepath='/home/claude/blend/AnyLapBlender.blend')
sc = bpy.context.scene
b = bpy.data.objects['Bana']
mw = b.matrix_world
cy = [p for p in b.data.polygons if p.material_index == 3][0]
vs = [mw @ b.data.vertices[i].co for i in cy.vertices]
print("stripe verts", [tuple(round(c,3) for c in v) for v in vs])
line_y = sum(v.y for v in vs) / len(vs)
cars = [o for o in sc.objects if o.name.endswith(' bil')]
fps = sc.render.fps / sc.render.fps_base
def centre(o):
    # bounding-box centre in world space (independent of where the origin is)
    pts = [o.matrix_world @ Vector(c) for c in o.bound_box]
    return sum(pts, Vector()) / 8
rows = []; cross = []; prev = {}
sc.frame_set(1)
for o in cars:
    c = centre(o); print(f"{o.name}: origin {tuple(round(v,3) for v in o.matrix_world.translation)} bbox centre {tuple(round(v,3) for v in c)}")
for f in range(sc.frame_start, sc.frame_end + 1):
    sc.frame_set(f)
    for o in cars:
        c = centre(o); s = c.y - line_y
        spd = 0.0
        if o.name in prev:
            pc, ps = prev[o.name]; spd = (c - pc).length * fps
            if (ps < 0 <= s) or (ps > 0 >= s):
                fr = ps / (ps - s)
                cross.append((round(f - 1 + fr, 3), o.name, 1 if s > ps else -1, round(spd, 3), round(c.x, 3)))
        rows.append((f, o.name, round(c.x,4), round(c.y,4), round(s,4), round(spd,3)))
        prev[o.name] = (c, s)
with open('/home/claude/blend/truth.csv','w',newline='') as fh:
    w=csv.writer(fh); w.writerow(['frame','car','x','y','side_m','speed_mps']); w.writerows(rows)
with open('/home/claude/blend/crossings.csv','w',newline='') as fh:
    w=csv.writer(fh); w.writerow(['frame','car','direction','speed_mps','x_lane']); w.writerows(sorted(cross))
print("line y", round(line_y,4))
for c in sorted(cross): print("cross", c)
# per car speed summary
from collections import defaultdict
sp = defaultdict(list)
for r in rows:
    if r[5] > 0: sp[r[1]].append(r[5])
for k,v in sp.items(): print(k, "speed min/max m/s", min(v), max(v), "first/last y", [r[3] for r in rows if r[1]==k][0], [r[3] for r in rows if r[1]==k][-1])
# other moving things
for o in sc.objects:
    if o.animation_data and o.animation_data.action and not o.name.endswith(' bil'):
        a = o.animation_data.action
        print("animated", o.name, "frames", [round(x,1) for x in a.frame_range])
