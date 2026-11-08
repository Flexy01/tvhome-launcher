import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
LAYOUTS = (
    ROOT / "launcher/app/src/main/res/layout/activity_main.xml",
    ROOT / "launcher/app/src/main/res/layout-iw/activity_main.xml",
)


def parse_xml(path):
    data = path.read_bytes()
    assert b"<!DOCTYPE" not in data.upper(), f"DOCTYPE is forbidden in {path}"
    return ET.fromstring(data)


def check():
    for path in LAYOUTS:
        root = parse_xml(path)
        hero = next(view for view in root.iter() if view.get(ANDROID + "id") == "@+id/hero")
        assert not any(view.get(ANDROID + "id") == "@+id/heroLogo" for view in hero.iter())
        assert any(view.get(ANDROID + "id") == "@+id/heroIdentity" for view in hero.iter())
        art = next(view for view in root.iter() if view.get(ANDROID + "id") == "@+id/heroArt")
        assert art.get(ANDROID + "background") == "@drawable/top_shelf_fallback"
        assert art.get(ANDROID + "visibility") == "visible"

    heroart = (ROOT / "launcher/app/src/main/java/com/gathin/tvhome/HeroArt.java").read_text(encoding="utf-8")
    from_body = heroart.split("static Bitmap from(", 1)[1]
    assert "Drawable banner" in from_body and "Drawable icon" in from_body
    assert "copy.draw(canvas)" not in heroart
    assert "setAlpha(" not in heroart
    assert "clipPath" not in heroart
    assert "drawBitmap" not in heroart
    assert "createScaledBitmap" not in heroart
    assert "inJustDecodeBounds" not in heroart
    assert "decode" not in heroart
    assert "RenderScript" not in heroart and "ScriptIntrinsicBlur" not in heroart

    fallback = parse_xml(ROOT / "launcher/app/src/main/res/drawable/top_shelf_fallback.xml")
    gradients = [view for view in fallback.iter() if view.tag == "gradient"]
    assert len(gradients) >= 2, "fallback needs a dark base and subtle depth layer"
    for gradient in gradients:
        colors = [gradient.get(ANDROID + name, "").lower() for name in ("startColor", "centerColor", "endColor")]
        colors = [color for color in colors if color]
        assert colors and all(color.startswith("#") for color in colors)

    main = (ROOT / "launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java").read_text(encoding="utf-8")
    adapter = (ROOT / "launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java").read_text(encoding="utf-8")
    assert "Drawable iconAt(int position)" in adapter
    hero_art = main.split("private void showHeroArt(", 1)[1].split("/** ", 1)[0]
    assert "if (heroArt.getVisibility() != View.VISIBLE) return;" in hero_art
    assert "HeroArt.from(null, null, color, rtl)" in hero_art
    assert "heroArt.setAlpha(HERO_ART_ALPHA);" in hero_art
    reload_apps = main.split("private void reloadApps(", 1)[1].split("/** ", 1)[0]
    assert "heroArtKey = null;" in reload_apps
    assert "heroArt.setImageBitmap(null);" in reload_apps
    hide_hero = main.split("private void hideHero()", 1)[1].split("/** ", 1)[0]
    assert "releaseHeroArt()" in hide_hero
    assert "private void releaseHeroArt()" in main
    assert "heroArt.animate().alpha(0f).setDuration(HERO_ART_FADE_MS)" in main
    assert "private static final int HERO_ART_FADE_MS = 180;" in main
    assert "AerialBackdrop.attach" not in main
    assert "aerial.resume()" not in main and "aerial.pause()" not in main
    assert "new MediaPlayer()" not in main
    assert "AerialBackdrop.attach" not in main
    assert "set-home-activity" not in main
    manifest = parse_xml(ROOT / "launcher/app/src/main/AndroidManifest.xml")
    assert any(view.tag == "activity" and view.get(ANDROID + "name") == ".MainActivity"
               for view in manifest.iter())
    install = (ROOT / "launcher/build-and-install.ps1").read_text(encoding="utf-8")
    assert "pm disable-user --user 0 $StockLauncher" in install
    assert 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity' in install
    assert "settings put secure" not in main
    for path in (ROOT / "launcher/app/src/main/res/layout/activity_main.xml",
                 ROOT / "launcher/app/src/main/res/layout-iw/activity_main.xml"):
        root = parse_xml(path)
        assert any(view.get(ANDROID + "id") == "@+id/heroName"
                   for view in root.iter() if view.tag == "TextView")

    config = parse_xml(ROOT / "launcher/app/src/main/res/values/config.xml")
    assert next(view for view in config.iter() if view.get("name") == "dock_title").text == "true"
    assert next(view for view in config.iter() if view.get("name") == "columns").text == "6"
    assert next(view for view in config.iter() if view.get("name") == "aerial_backdrop").text in (None, "")
    assert "north_america_aurora.mov" not in (ROOT / "launcher/app/src/main/res/values/config.xml").read_text(encoding="utf-8")


if __name__ == "__main__":
    check()
    print("Top Shelf layout checks passed")
