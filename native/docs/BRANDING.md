# Android icon

The launcher icon uses the original synthetic test video bundled in this repository,
`app/src/main/res/raw/demo.mp4`. The background shows the entire 640-by-360 frame
at 1.8 seconds, centered in a 640-by-640 square. The frame fills the icon width
without cropping or stretching; 140-pixel black bars fill the top and bottom.
It has no dependency on another repository or a hosted video.

Regenerate the background from the repository root with FFmpeg:

```sh
ffmpeg -hide_banner -loglevel error -y -ss 1.8 \
  -i app/src/main/res/raw/demo.mp4 \
  -vf 'pad=640:640:0:140:color=black' -frames:v 1 -update 1 \
  app/src/main/res/drawable-nodpi/ic_launcher_background.png
```

`drawable/ic_launcher.xml` layers this letterboxed background underneath
`drawable/ic_launcher_foreground.xml`. The foreground keeps the white Play mark
and adds a 40% black scrim so it remains legible over the bright test pattern.
The complete source frame is preserved without a baked-in scrim or Play mark,
allowing other branding assets to reuse it. Store icons should resize the entire
square background, including its black bars, to retain the same composition.
