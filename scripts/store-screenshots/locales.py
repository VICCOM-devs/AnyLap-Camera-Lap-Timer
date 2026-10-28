"""Per-language text and type for the store art (screenshots + feature graphic).

Barlow covers Latin incl. Polish, Turkish and Vietnamese. Other scripts fall back to:
  CJK        -> Noto Sans CJK SC/TC/JP/KR (system font: apt install fonts-noto-cjk)
  Devanagari -> fonts/NotoSansDevanagari.ttf
  Thai       -> fonts/Kanit-*.ttf (sporty Thai with a real black italic, closest to Barlow)
  Cyrillic   -> fonts/FiraSansExtraCondensed-*.ttf
  Arabic     -> fonts/Cairo.ttf, laid out right-to-left
All bundled fonts are OFL (Google Fonts).
"""
import base64, json
from pathlib import Path

HERE = Path(__file__).resolve().parent
FONTS = HERE / "fonts"
TR = json.loads((HERE / "translations.json").read_text())
SHOTS = json.loads((HERE / "shots.json").read_text())

LANGS = [k for k in TR if not k.startswith("_")]  # en, sv + the 21 store locales

CJK = {"zh-CN": "Noto Sans CJK SC", "zh-TW": "Noto Sans CJK TC", "ja-JP": "Noto Sans CJK JP", "ko-KR": "Noto Sans CJK KR"}


def _uri(p: Path):
    return "data:font/ttf;base64," + base64.b64encode(p.read_bytes()).decode()


def _face(name, file, weight="100 1000"):
    return f"@font-face{{font-family:'{name}';src:url({_uri(FONTS / file)});font-weight:{weight}}}"


def rtl(lang):
    return lang == "ar"


def html_lang(lang):
    return {"no-NO": "nb"}.get(lang, lang)


def fonts(lang):
    """(extra @font-face css, headline stack, body stack, bold stack, script line-height factor)"""
    if lang in CJK:
        f = f"'{CJK[lang]}'"
        return "", f, f, f, 1.12
    if lang == "hi-IN":
        return _face("Deva", "NotoSansDevanagari.ttf"), "'Deva'", "'Deva'", "'Deva'", 1.3
    if lang == "th":
        css = _face("KanitBI", "Kanit-BlackItalic.ttf") + _face("KanitS", "Kanit-SemiBold.ttf") + _face("KanitB", "Kanit-Bold.ttf")
        return css, "'KanitBI'", "'KanitS'", "'KanitB'", 1.3
    if lang == "ru-RU":  # Barlow has no Cyrillic
        css = _face("FiraBI", "FiraSansExtraCondensed-BlackItalic.ttf") + _face("FiraS", "FiraSansExtraCondensed-SemiBold.ttf") + _face("FiraB", "FiraSansExtraCondensed-Bold.ttf")
        return css, "'FiraBI'", "'FiraS'", "'FiraB'", 1.0
    if lang == "ar":
        css = _face("Cairo", "Cairo.ttf")
        return css, "'Cairo'", "'Cairo'", "'Cairo'", 1.35
    if lang == "vi":  # stacked diacritics on capitals need room between caption lines
        return "", "BarlowBlackItalic", "BarlowSemi", "BarlowBold", 1.16
    return "", "", "", "", 1.0


def shot_text(shot, lang):
    """caption lines, sub, chips (or None) for one screenshot."""
    if lang in shot["caption"]:
        return shot["caption"][lang], shot["sub"][lang], shot.get("chips", {}).get(lang)
    t = TR[lang]["shots"][shot["id"]]
    return t["caption"], t["sub"], t.get("chips")


def steps(lang):
    return TR[lang]["steps"]


def feature(lang):
    return TR[lang]["feature"]
