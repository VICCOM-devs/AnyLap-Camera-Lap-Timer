import bpy, sys, json, math
sys.argv = ['x', sys.argv[-1]]
CAM = sys.argv[-1]
exec(open('/home/claude/blend/render_forms.py').read().split("log = open(")[0])   # defs only
sc = setup()
bpy.context.view_layer.update()
json.dump(meta(sc), open(f'{OUT}/{CAM}/meta.json', 'w'))
print(CAM, meta(sc)['line'], meta(sc)['metersPerProcPx'])
