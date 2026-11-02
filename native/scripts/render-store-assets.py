#!/usr/bin/env python3
"""Render store artwork from the launcher identity; screenshots must come from the app."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--font", required=True, help="Path to a licensed bold TrueType font, used for rendering only")
args = parser.parse_args()
font = Path(args.font).resolve()
if not font.is_file():
    parser.error("Font file does not exist")

def render(path, width, height, drawings):
    path.parent.mkdir(parents=True, exist_ok=True)
    command = ["magick", "-size", f"{width}x{height}", "xc:#2563EB", "-font", str(font)]
    for options in drawings:
        command.extend(options)
    subprocess.run(command + ["-strip", "-define", "png:color-type=2", str(path)], check=True)

assets = ROOT / "store" / "assets"
assets.mkdir(parents=True, exist_ok=True)
# Match drawable/ic_launcher.xml and ic_launcher_foreground.xml exactly:
# an actual demo frame, a 40% black scrim, and the original 108-unit Play mark.
subprocess.run([
    "magick", str(ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_background.png"),
    "-resize", "512x512!", "-fill", "rgba(0,0,0,0.4)", "-draw", "rectangle 0,0 512,512",
    "-fill", "white", "-draw", "polygon 208.5926,151.7037 208.5926,360.2963 369.7778,256",
    "-strip", "-define", "png:color-type=2", str(assets / "icon-512.png"),
], check=True)
render(ROOT / "app/src/main/res/drawable-xhdpi/tv_banner.png", 320, 180, [
    ["-fill", "white", "-draw", "polygon 142,28 142,89 189,58"],
    ["-fill", "white", "-pointsize", "29", "-gravity", "South", "-annotate", "+0+41", "OTT-play"],
    ["-pointsize", "16", "-annotate", "+0+20", "Native"],
])
render(assets / "tv-banner-1280x720.png", 1280, 720, [
    ["-fill", "white", "-draw", "polygon 568,112 568,356 756,232"],
    ["-fill", "white", "-pointsize", "116", "-gravity", "South", "-annotate", "+0+164", "OTT-play"],
    ["-pointsize", "64", "-annotate", "+0+80", "Native"],
])
render(assets / "feature-graphic-1024x500.png", 1024, 500, [
    ["-fill", "white", "-draw", "polygon 122,167 122,333 250,250"],
    ["-fill", "white", "-pointsize", "65", "-gravity", "NorthWest", "-annotate", "+337+158", "OTT-play Native"],
    ["-pointsize", "26", "-annotate", "+342+253", "Your streams. Your player."],
])
print("Rendered launcher banner and store artwork. No screenshots were generated or altered.")
