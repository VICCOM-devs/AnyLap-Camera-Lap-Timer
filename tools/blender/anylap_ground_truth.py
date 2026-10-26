"""
AnyLap – ground truth for Blender test clips.

Run inside Blender (Scripting tab → Open → Run, or `blender scene.blend -b -P anylap_ground_truth.py`).
Writes <blend name>_truth.csv next to the .blend with one row per frame and car, plus a
<blend name>_crossings.csv with the exact (fractional) frame where each car's centre crosses the
finish line, in the direction it crossed.

Conventions:
- Cars are objects named car_01, car_02 … (any object whose name starts with "car_").
- The finish line is an object named finish_line (a thin plane/strip, or an empty). Its direction is
  its longest horizontal side (for an empty: its local X axis); crossing is measured as the car's
  centre passing the vertical plane that contains the line. Only position and rotation matter.
- Car origins should be at the middle of the car (Object → Set Origin → Origin to Geometry): the
  engine times the centre of the blob, so the truth must be the centre too.
- The camera is the scene camera. Speeds are world-space, in m/s, assuming 1 Blender unit = 1 m.
- Frames are the scene's frame range at the scene frame rate.

The matching engine test reads the crossings file: frame, car, direction (+1/-1), speed_mps.
"""

import bpy
import csv
import os
from mathutils import Vector

scene = bpy.context.scene
fps = scene.render.fps / scene.render.fps_base
cars = sorted([o for o in scene.objects if o.name.startswith("car_")], key=lambda o: o.name)
line = scene.objects.get("finish_line")
if line is None:
    raise RuntimeError("No object named finish_line in the scene")
if not cars:
    raise RuntimeError("No objects named car_… in the scene")

blend = bpy.data.filepath or "untitled.blend"
base = os.path.splitext(blend)[0]

# Line direction = the object's longest horizontal side in world space (local X or Y), so a strip
# modelled either way works. Empties have no size → local X.
m3 = line.matrix_world.to_3x3()
dims = line.dimensions
axis = Vector((1.0, 0.0, 0.0)) if dims.x >= dims.y else Vector((0.0, 1.0, 0.0))
line_dir = m3 @ axis
line_dir.z = 0.0
line_dir.normalize()
up = Vector((0.0, 0.0, 1.0))
normal = up.cross(line_dir).normalized()
origin = line.matrix_world.translation.copy()


def side(p):
    """Signed distance of a world point from the line plane (m)."""
    return (p - origin).dot(normal)


rows = []
crossings = []
prev = {}
f0, f1 = scene.frame_start, scene.frame_end
for f in range(f0, f1 + 1):
    scene.frame_set(f)
    for car in cars:
        p = car.matrix_world.translation.copy()
        s = side(p)
        speed = 0.0
        if car.name in prev:
            pp, ps = prev[car.name]
            speed = (p - pp).length * fps
            if (ps < 0 <= s) or (ps > 0 >= s):
                # linear interpolation of the exact crossing frame within [f-1, f]
                frac = ps / (ps - s) if ps != s else 0.0
                crossings.append({
                    "frame": round(f - 1 + frac, 3),
                    "time_s": round((f - 1 + frac - f0) / fps, 4),
                    "car": car.name,
                    "direction": 1 if s > ps else -1,
                    "speed_mps": round(speed, 3),
                })
        rows.append({
            "frame": f,
            "time_s": round((f - f0) / fps, 4),
            "car": car.name,
            "x": round(p.x, 4), "y": round(p.y, 4), "z": round(p.z, 4),
            "side_m": round(s, 4),
            "speed_mps": round(speed, 3),
        })
        prev[car.name] = (p, s)

with open(base + "_truth.csv", "w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=["frame", "time_s", "car", "x", "y", "z", "side_m", "speed_mps"])
    w.writeheader()
    w.writerows(rows)
with open(base + "_crossings.csv", "w", newline="") as fh:
    w = csv.DictWriter(fh, fieldnames=["frame", "time_s", "car", "direction", "speed_mps"])
    w.writeheader()
    w.writerows(crossings)

cam = scene.camera
info = [
    f"fps {fps:g}, frames {f0}-{f1}, resolution {scene.render.resolution_x}x{scene.render.resolution_y} @ {scene.render.resolution_percentage}%",
    f"motion blur {'on' if scene.render.use_motion_blur else 'off'}, shutter {getattr(scene.render, 'motion_blur_shutter', '?')} frames"
    f" (= 1/{fps / scene.render.motion_blur_shutter:.0f} s)" if scene.render.use_motion_blur and scene.render.motion_blur_shutter else "",
    f"camera {cam.name}: {cam.data.lens:g} mm on {cam.data.sensor_width:g} mm sensor, {(cam.matrix_world.translation - origin).length:.2f} m from the line"
    if cam else "no scene camera",
    f"{len(cars)} cars, {len(crossings)} crossings → {base}_crossings.csv",
]
print("\n".join(i for i in info if i))
