#!/usr/bin/env python3
"""Export the Play Store icon (512x512) and feature graphic (1024x500).

  python3 scripts/store-art/render.py   # -> store/art/anylap-icon-512.png, store/art/anylap-feature-1024x500.png

feature.html inlines the icon, so after editing anylap-icon.svg run with --sync to copy it in.
Needs: pip install playwright pillow && playwright install chromium
"""
import re, sys
from pathlib import Path
from PIL import Image
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "store" / "art"

def sync_icon():
    icon = (HERE / "anylap-icon.svg").read_text().replace("<svg ", '<svg width="128" height="128" ', 1)
    page = (HERE / "feature.html").read_text()
    page = re.sub(r'<div class="ic">.*?</svg></div>', lambda _: f'<div class="ic">{icon}</div>', page, count=1, flags=re.S)
    (HERE / "feature.html").write_text(page)

def main():
    if "--sync" in sys.argv:
        sync_icon()
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [("anylap-icon.svg", 512, 512, "anylap-icon-512.png"), ("feature.html", 1024, 500, "anylap-feature-1024x500.png")]
    with sync_playwright() as p:
        b = p.chromium.launch()
        for src, w, h, name in jobs:
            pg = b.new_page(viewport={"width": w, "height": h})
            pg.goto((HERE / src).as_uri())
            pg.wait_for_timeout(600)
            out = OUT / name
            pg.screenshot(path=str(out))
            Image.open(out).convert("RGB").save(out)  # Play wants 24-bit, no alpha
            print(out)
            pg.close()
        b.close()

if __name__ == "__main__":
    main()
