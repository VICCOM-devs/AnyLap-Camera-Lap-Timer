# minis10 – ten Mini-Z-style race cars (Oct 27 2026)

GT3, LMP, Formula, rally hatch, touring, drift, Group 5, supercar, stock car, hypercar. 15.2–19.5 cm long,
8.4–9.6 cm wide, 3.5–5.8 cm tall. Lofted bodies with fenders, arches, cabins, wings; painted liveries with
invented sponsors, numbers, panel lines; wear (dents, scratches, rubber marks, torn decals, rally dirt) on
White 33, Black 88, Purple 27 (bruised) and Blue 14, Green 51, Orange 4 (used).

- `minis10.py` – generator, same API as `cars10.py` (`build_cars(scene)`), needs Pillow + numpy in the bpy venv.
- `save_minis_blend.py` – builds them into `AnyLapBlender.blend` → `blend/AnyLap_minis10.blend` (textures packed).
- `render_minis10.py` – calibration + race passes for the bench (same rig as `render_cars10.py`).
- `minis_studio.py per|lineup`, `render_minis_topdown.py W H samples out.png` – previews.
