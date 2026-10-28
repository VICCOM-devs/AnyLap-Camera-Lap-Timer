#!/usr/bin/env python3
"""Export the Play Store icon (512x512) and feature graphic (1024x500).

  python3 scripts/store-art/render.py   # -> store/art/anylap-icon-512.png, store/art/<lang>/anylap-feature-1024x500.png
  python3 scripts/store-art/render.py de-DE ja-JP   # feature graphic for just these languages

Feature texts per language come from scripts/store-screenshots/translations.json (key "feature").

feature.html inlines the icon, so after editing anylap-icon.svg run with --sync to copy it in.
Needs: pip install playwright pillow && playwright install chromium
"""
import html, re, sys
from pathlib import Path
from PIL import Image
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "store" / "art"
sys.path.insert(0, str(HERE.parent / "store-screenshots"))
import locales as L  # noqa: E402

LOCALIZE_JS = """([tag, lap, fastest, css, rtl]) => {
  const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
  const t = document.querySelector('.tag');
  t.innerHTML = tag;
  if (rtl) { t.dir = 'rtl'; t.style.textAlign = 'right'; t.style.width = '360px'; }
  document.querySelector('.meta').innerHTML = '<b>RED</b> ' + lap + ' <span class="fl">' + fastest + '</span>';
  let s = 30;
  while ((t.scrollWidth > 370 || t.getBoundingClientRect().bottom > 470) && s > 20) { s -= 1; t.style.fontSize = s + 'px'; }
  const card = document.querySelector('.card');
  return { tag: s, cardLeft: Math.round(card.getBoundingClientRect().left) };
}"""


def lang_css(lang):
    extra, head, body, bold, lh = L.fonts(lang)
    css = ""
    if head:
        css = extra + (f".tag{{font-family:S,{body};font-weight:600;line-height:{1.25 * lh:.2f}}}"
                       f".meta,.fl{{font-family:B,{bold};font-weight:700}}")
    return css

def sync_icon():
    icon = (HERE / "anylap-icon.svg").read_text().replace("<svg ", '<svg width="128" height="128" ', 1)
    page = (HERE / "feature.html").read_text()
    page = re.sub(r'<div class="ic">.*?</svg></div>', lambda _: f'<div class="ic">{icon}</div>', page, count=1, flags=re.S)
    (HERE / "feature.html").write_text(page)

def main():
    if "--sync" in sys.argv:
        sync_icon()
    OUT.mkdir(parents=True, exist_ok=True)
    langs = [a for a in sys.argv[1:] if not a.startswith("--")] or L.LANGS
    jobs = [("anylap-icon.svg", 512, 512, "anylap-icon-512.png", None)]
    jobs += [("feature.html", 1024, 500, f"{lang}/anylap-feature-1024x500.png", lang) for lang in langs]
    with sync_playwright() as p:
        b = p.chromium.launch()
        for src, w, h, name, lang in jobs:
            pg = b.new_page(viewport={"width": w, "height": h})
            pg.goto((HERE / src).as_uri())
            if lang:
                f = L.feature(lang)
                tag = "<br>".join(html.escape(x) for x in f["tag"])
                pg.evaluate(f"document.documentElement.lang = '{L.html_lang(lang)}'")
                info = pg.evaluate(LOCALIZE_JS, [tag, html.escape(f["lap"]), html.escape(f["fastest"]), lang_css(lang), L.rtl(lang)])
                print(f"  {lang}: tag {info['tag']}px, card left edge {info['cardLeft']}px")
            pg.evaluate("document.fonts.ready")
            pg.wait_for_timeout(600)
            out = OUT / name
            out.parent.mkdir(parents=True, exist_ok=True)
            pg.screenshot(path=str(out))
            Image.open(out).convert("RGB").save(out)  # Play wants 24-bit, no alpha
            print(out)
            pg.close()
        b.close()

if __name__ == "__main__":
    main()
