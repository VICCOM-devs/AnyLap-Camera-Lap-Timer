#!/usr/bin/env python3
"""Frame the raw Roborazzi captures into Play Store screenshots.

  ./gradlew :composeApp:testDebugUnitTest --tests '*ScreenshotTest*'   # raw captures
  python3 scripts/store-screenshots/render.py                         # -> store/screenshots/<lang>/*.png (all languages)
  python3 scripts/store-screenshots/render.py de-DE ja-JP             # just these

Texts: en/sv in shots.json, the other store locales in translations.json (see locales.py for fonts).

Needs: pip install playwright && playwright install chromium
"""
import base64, html, json, os, re, sys
import locales as L
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "composeApp" / "screenshots"
FONTS = ROOT / "composeApp" / "src" / "commonMain" / "composeResources" / "font"
OUT = ROOT / "store" / "screenshots"
CFG = L.SHOTS
W, H = CFG["size"]


def data_uri(p: Path, mime="image/png"):
    return f"data:{mime};base64," + base64.b64encode(p.read_bytes()).decode()


def font_face(name, file):
    return f"@font-face{{font-family:'{name}';src:url({data_uri(FONTS / file, 'font/ttf')});font-weight:100 1000}}"


CSS = (
    font_face("BarlowBlackItalic", "barlow_black_italic.ttf")
    + font_face("BarlowSemi", "barlow_semi_bold.ttf")
    + font_face("BarlowBold", "barlow_bold.ttf")
    + f"""
*{{box-sizing:border-box;margin:0}}
body{{width:{W}px;height:{H}px;overflow:hidden;background:#09090B;color:#F6F6F8;font-family:BarlowSemi;position:relative}}
.bg{{position:absolute;inset:0;background:
  radial-gradient(ellipse 900px 700px at 85% 8%, rgba(225,6,0,.42), transparent 70%),
  radial-gradient(ellipse 700px 600px at 0% 100%, rgba(122,10,14,.55), transparent 70%),
  repeating-linear-gradient(115deg, rgba(255,255,255,.025) 0 2px, transparent 2px 46px), #09090B}}
.cap{{position:absolute;left:72px;right:72px;top:96px;font-family:BarlowBlackItalic;font-size:104px;line-height:.98;letter-spacing:-1px;text-transform:uppercase}}
.cap em{{font-style:normal;color:#FF3B2F}}
.bar{{position:absolute;left:76px;top:332px;width:140px;height:8px;background:#E10600;border-radius:2px}}
.sub{{position:absolute;left:76px;right:90px;top:366px;font-size:38px;line-height:1.28;color:#C9C9D0}}
.phone{{position:absolute;background:#000;border:12px solid #1E1E23;box-shadow:0 0 0 2px #34343C, 0 40px 90px rgba(0,0,0,.65), 0 0 120px rgba(225,6,0,.18);overflow:hidden}}
.phone img{{display:block;width:100%}}
.chips{{position:absolute;left:72px;right:72px;display:flex;flex-wrap:nowrap;gap:12px}}
.chip{{font-family:BarlowBold;font-size:27px;padding:11px 20px;border-radius:40px;background:#18181C;border:2px solid #34343C;color:#F6F6F8}}
.chip:before{{content:'';display:inline-block;width:14px;height:14px;border-radius:7px;background:#E10600;margin-right:14px;vertical-align:middle;position:relative;top:-3px}}
.zoom{{position:absolute;border-radius:28px;overflow:hidden;border:3px solid #34343C;box-shadow:0 30px 70px rgba(0,0,0,.6)}}
.zoom img{{position:absolute;display:block}}
.cap .l{{display:inline-block;white-space:nowrap}}
.chip{{white-space:nowrap;flex:none}}
:lang(ja),:lang(zh),:lang(ko){{line-break:strict}}
:lang(ko) .sub{{word-break:keep-all}}
[dir=rtl] .bar{{left:auto;right:76px}}
[dir=rtl] .chip:before{{margin-right:0;margin-left:14px}}
"""
)


def lang_css(lang):
    extra, head, body, bold, lh = L.fonts(lang)
    if not head:
        return ""
    return extra + (
        f".cap{{font-family:BarlowBlackItalic,{head};font-weight:900;line-height:{0.98 * lh:.2f};letter-spacing:0}}"
        f"body,.sub{{font-family:BarlowSemi,{body};font-weight:600}}.sub{{line-height:{1.28 * lh:.2f}}}"
        f".chip,.stepn{{font-family:BarlowBold,{bold};font-weight:700}}"
        f".stepn{{font-family:BarlowBlackItalic,{head};font-weight:900}}"
    )


# Shrinks text that doesn't fit its box; returns a list of what still overflows (should be empty).
FIT_JS = """(subMax) => {
  const fs = e => parseFloat(getComputedStyle(e).fontSize);
  const shrink = (els, bad, min) => { let s = fs(els[0]); while (bad() && s > min) { s -= 1; els.forEach(e => e.style.fontSize = s + 'px'); } return !bad(); };
  const out = [];
  const cap = document.querySelector('.cap');
  const lines = [...cap.querySelectorAll('.l')];
  if (!shrink([cap], () => lines.some(l => l.getBoundingClientRect().width > cap.clientWidth + 1) || cap.getBoundingClientRect().bottom > 318, 56)) out.push('caption');
  const sub = document.querySelector('.sub');
  if (!shrink([sub], () => sub.getBoundingClientRect().bottom > subMax, 26)) out.push('sub');
  const chips = document.querySelector('.chips');
  if (chips) {
    chips.style.top = Math.max(448, sub.getBoundingClientRect().bottom + 16) + 'px';
    const cs = [...chips.children];
    if (!shrink(cs, () => chips.scrollWidth > chips.clientWidth + 1, 18)) out.push('chips');
    if (chips.getBoundingClientRect().bottom > 538) out.push('chips-height');
    window.__chip = fs(cs[0]);
  }
  const card = document.querySelector('.steps');
  if (card) {
    const ts = [...card.querySelectorAll('.stept')];
    if (!shrink(ts, () => card.getBoundingClientRect().bottom > 1860, 26)) out.push('steps');
  }
  return out;
}"""


def caption_html(lines):
    out = []
    for ln in lines:
        ln = html.escape(ln)
        out.append('<span class="l">' + re.sub(r"\*(.+?)\*", r"<em>\1</em>", ln) + "</span>")
    return "<br>".join(out)


def img_size(p: Path):
    from PIL import Image
    with Image.open(p) as im:
        return im.size


def page(shot, lang):
    cap_lines, sub_text, chip_list = L.shot_text(shot, lang)
    cap = caption_html(cap_lines)
    sub = html.escape(sub_text)
    src = RAW / shot["src"]
    sw, sh = img_size(src)
    body = [f'<div class="bg"></div><div class="cap">{cap}</div><div class="bar"></div><div class="sub">{sub}</div>']
    lay = shot["layout"]
    if lay == "portrait":
        pw = 800  # outer phone width
        x = (W - pw) // 2
        y = 560
        inner = pw - 24
        scale = inner / sw
        top = shot.get("top", 0)
        body.append(
            f'<div class="phone" style="left:{x}px;top:{y}px;width:{pw}px;height:{H - y + 80}px;border-radius:64px">'
            f'<img src="{data_uri(src)}" style="margin-top:{-top * scale}px"></div>'
        )
    elif lay in ("landscape", "landscape-hero"):
        pw = W - 2 * 44
        inner = pw - 24
        ph = int(inner * sh / sw) + 24
        y = 560 if lay == "landscape" else 548
        body.append(
            f'<div class="phone" style="left:44px;top:{y}px;width:{pw}px;height:{ph}px;border-radius:52px">'
            f'<img src="{data_uri(src)}"></div>'
        )
        below = y + ph + 44
        if lay == "landscape-hero":
            # Feature chips just under the subtitle, then two enlarged call-outs of the live screen.
            if chip_list:
                chips = "".join(f'<span class="chip">{html.escape(c)}</span>' for c in chip_list)
                body.append(f'<div class="chips" style="top:448px">{chips}</div>')
            z = shot["zoom"]
            gap = 30
            if len(z) == 1:
                boxes = [(44, below, W - 88, H - below - 60)]
            else:
                h1 = shot.get("zoom_h1", 250)
                h2 = H - below - h1 - gap - 60
                boxes = [(44, below, W - 88, h1), (44, below + h1 + gap, W - 88, h2)]
            for (zx, zy, zw, zh), zc in zip(boxes, z):
                cx, cy, cw, ch = zc["crop"]
                zs = RAW / zc["src"]
                zsw, zsh = img_size(zs)
                s = zw / (cw * zsw)  # fit the crop's width; the box height shows as many rows as fit
                ox = cx * zsw * s
                oy = cy * zsh * s
                body.append(
                    f'<div class="zoom" style="left:{zx}px;top:{zy}px;width:{zw}px;height:{zh}px">'
                    f'<img src="{data_uri(zs)}" style="width:{zsw * s}px;left:{-ox}px;top:{-oy}px"></div>'
                )
        else:
            # Under a landscape shot: a big tip card with the three setup steps.
            steps = L.steps(lang)
            items = "".join(
                f'<div style="display:flex;gap:28px;align-items:center;margin-bottom:34px">'
                f'<span class="stepn" style="font-family:BarlowBlackItalic;font-size:72px;color:#FF3B2F;width:70px;flex:none">{i + 1}</span>'
                f'<span class="stept" style="font-size:40px;line-height:1.2">{html.escape(t)}</span></div>'
                for i, t in enumerate(steps)
            )
            body.append(
                f'<div class="steps" style="position:absolute;left:44px;right:44px;top:{below + 10}px;padding:48px 52px 20px;border-radius:40px;'
                f'background:#131316;border:2px solid #26262C">{items}</div>'
            )
    d = ' dir="rtl"' if L.rtl(lang) else ""
    return (f"<!doctype html><html lang='{L.html_lang(lang)}'{d}><head><meta charset='utf-8'><style>{CSS}{lang_css(lang)}</style></head>"
            f"<body>{''.join(body)}</body></html>")


def main():
    from playwright.sync_api import sync_playwright
    from PIL import Image
    langs = sys.argv[1:] or L.LANGS
    problems = []
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": W, "height": H})
        for lang in langs:
            d = OUT / lang
            d.mkdir(parents=True, exist_ok=True)
            for shot in CFG["shots"]:
                pg.set_content(page(shot, lang))
                pg.evaluate("document.fonts.ready")
                pg.wait_for_timeout(150)
                sub_max = 466 if shot["layout"] == "landscape-hero" else 520
                bad = pg.evaluate(FIT_JS, sub_max)
                if shot["id"] == "01_hero":
                    print(f"  chip font {lang}: {pg.evaluate('window.__chip')}px")
                if bad:
                    problems.append(f"{lang}/{shot['id']}: {', '.join(bad)}")
                out = d / f"{shot['id']}.png"
                pg.screenshot(path=str(out))
                Image.open(out).convert("RGB").save(out)  # Play wants 24-bit, no alpha
                print(out.relative_to(ROOT))
        b.close()
    if problems:
        print("STILL OVERFLOWING:\n  " + "\n  ".join(problems))
        sys.exit(1)


if __name__ == "__main__":
    main()
