# Documentation screenshots

The README hero combines the repository's existing store captures:

- [Android TV library, 1920 × 1080](../../store/screenshots/tv/02-library.png).
- [Phone library, 1080 × 1920](../../store/screenshots/phone/03-library.png).

Both show the native Android app with the offline synthetic demonstration
catalogue, in Russian. [Capture provenance](../../store/capture-provenance.json) records version
0.2.1, build 3, captured on 13 September 2026 with a local test certificate.
These predate the current preview. They illustrate the TV and touch layouts,
not current release acceptance or a commercial programme catalogue.
The original pixels, status bar and navigation bar remain in the source images.
See the [store asset guide](../../store/README.md) for capture context.

`hero.png` applies perspective, rounded outer corners, a neutral background and
shadows to those real captures. No application text or controls are generated
or redrawn. Keep the original, flat screenshots for detailed instructions.

## Regenerate

Install ImageMagick 7 and run:

```sh
./scripts/render-docs-hero.sh
```

The renderer checks both source dimensions before projecting the images.
Review the composition and update the caption when replacing either source.
For a future capture, use an isolated emulator with the offline demo only;
exclude account credentials, playlist URLs, personal media and notifications.
