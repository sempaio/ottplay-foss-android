#!/usr/bin/env bash
# Project existing screenshots without redrawing application content.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
command -v magick >/dev/null 2>&1 || { printf '%s\n' 'ImageMagick 7 (magick) is required.' >&2; exit 1; }
render_dir="$(mktemp -d "${TMPDIR:-/tmp}/ottplay-hero.XXXXXX")"
trap 'rm -rf -- "$render_dir"' EXIT
tv_image="$repo_root/store/screenshots/tv/02-library.png"
phone_image="$repo_root/store/screenshots/phone/03-library.png"
output_image="$repo_root/docs/screenshots/hero.png"
[[ "$(magick identify -format '%wx%h' "$tv_image")" == '1920x1080' ]] || { printf '%s\n' 'Review the TV projection: dimensions changed.' >&2; exit 1; }
[[ "$(magick identify -format '%wx%h' "$phone_image")" == '1080x1920' ]] || { printf '%s\n' 'Review the phone projection: dimensions changed.' >&2; exit 1; }
magick "$tv_image" -alpha on \
  \( -size 1920x1080 xc:none -fill white -draw 'roundrectangle 0,0 1919,1079 30,30' \) \
  -compose DstIn -composite -compose Over \
  -virtual-pixel transparent -filter Robidoux -define distort:viewport=1800x1250+0+0 \
  -distort Perspective '0,0 95,245 1919,0 1400,100 1919,1079 1450,850 0,1079 145,1000' \
  -strip "$render_dir/tv.png"
magick "$phone_image" -alpha on \
  \( -size 1080x1920 xc:none -fill white -draw 'roundrectangle 0,0 1079,1919 60,60' \) \
  -compose DstIn -composite -compose Over \
  -virtual-pixel transparent -filter Robidoux -define distort:viewport=1800x1250+0+0 \
  -distort Perspective '0,0 1210,370 1079,0 1670,280 1079,1919 1710,1080 0,1919 1260,1160' \
  -strip "$render_dir/phone.png"
for panel in tv phone; do
  magick "$render_dir/$panel.png" -fill '#111827' -colorize 100 \
    -channel A -evaluate multiply 0.27 +channel -blur 0x18 -strip "$render_dir/$panel-shadow.png"
done
magick -size 1800x1250 xc:'#edf1f3' \
  "$render_dir/tv-shadow.png" -geometry +0+22 -compose Over -composite \
  "$render_dir/tv.png" -geometry +0+0 -compose Over -composite \
  "$render_dir/phone-shadow.png" -geometry +0+22 -compose Over -composite \
  "$render_dir/phone.png" -geometry +0+0 -compose Over -composite \
  -strip -define png:exclude-chunk=date,time "PNG24:$render_dir/hero.png"
mv -- "$render_dir/hero.png" "$output_image"
printf 'Rendered %s\n' "$output_image"
