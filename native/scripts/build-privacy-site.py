#!/usr/bin/env python3
"""Render the checked-in privacy document as a standalone, script-free public page."""
from html import escape
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
markdown = (root / "docs/privacy-policy.md").read_text()
assert "alvit.work@gmail.com" in markdown and "play.ott.foss.nativeapp" in markdown
assert not re.search(r"\b(TODO|TBD|PLACEHOLDER)\b", markdown)

def inline(text):
    text = escape(text)
    text = re.sub(r"\[([^\]]+)\]\((https://[^\s)]+|mailto:[^\s)]+)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    return text

blocks = []
for paragraph in markdown.strip().split("\n\n"):
    if paragraph.startswith("# "):
        blocks.append("<h1>" + inline(paragraph[2:]) + "</h1>")
    elif paragraph.startswith("## "):
        blocks.append("<h2>" + inline(paragraph[3:]) + "</h2>")
    elif paragraph.startswith("- "):
        blocks.append("<ul>" + "".join("<li>" + inline(line[2:]) + "</li>" for line in paragraph.splitlines()) + "</ul>")
    else:
        blocks.append("<p>" + inline(paragraph.replace("\n", " ")) + "</p>")

page = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Privacy Policy — OTT-play Native for Android</title>
<meta name="description" content="How OTT-play Native handles source credentials, playback, programme guides and local data. Published by alvit.">
<style>
:root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;background:#fff;color:#17202d;font:17px/1.7 system-ui,sans-serif}main{max-width:820px;margin:auto;padding:44px 24px 80px}h1{font-size:32px;line-height:1.2;letter-spacing:-.02em;margin:0 0 24px}h2{font-size:23px;line-height:1.3;margin:38px 0 12px}p{margin:14px 0}a{color:#205dc7;text-underline-offset:3px;overflow-wrap:anywhere}code{font-size:.87em;overflow-wrap:anywhere}ul{padding-left:24px}a:focus-visible{outline:3px solid #205dc7;outline-offset:4px}@media(prefers-color-scheme:dark){body{background:#14181f;color:#e9edf4}a{color:#8db5ff}}@media(max-width:500px){main{padding:28px 20px 56px}h1{font-size:27px}}@media print{body{color:#000;background:#fff}main{max-width:none;padding:0}a{color:inherit}}
</style></head><body><main>
""" + "\n".join(blocks) + "\n</main></body></html>\n"
destination = root / "docs/site/index.html"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(page)
print(f"Rendered {destination.relative_to(root)} ({len(page.encode())} bytes)")
