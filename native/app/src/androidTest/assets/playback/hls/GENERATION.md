This eight-second HLS fixture is derived only from the project's original synthetic
`app/src/main/res/raw/demo.mp4`. It contains a generated colour pattern and silent AAC
audio, no external stream or copyrighted media. It is packaged in the test APK only.

Four two-second MPEG-TS segments contain H.264 baseline video at 640x360 / 25 fps and
AAC audio. The master playlist references the generated VOD media playlist. The
instrumentation test serves the same bytes under two item-specific authenticated paths.

Regenerate from the project root with FFmpeg (tested with the locally installed build):

```sh
ffmpeg -hide_banner -loglevel error -y -i app/src/main/res/raw/demo.mp4 \
  -map 0:v:0 -map 0:a:0 -map_metadata -1 -fflags +bitexact \
  -flags:v +bitexact -flags:a +bitexact -c:v libx264 -profile:v baseline \
  -preset veryfast -b:v 450k -maxrate 450k -bufsize 900k -pix_fmt yuv420p \
  -g 50 -keyint_min 50 -sc_threshold 0 -c:a aac -b:a 48k -ac 2 -ar 48000 \
  -hls_time 2 -hls_playlist_type vod \
  -hls_segment_filename app/src/androidTest/assets/playback/hls/segment%02d.ts \
  -f hls app/src/androidTest/assets/playback/hls/index.m3u8
```
