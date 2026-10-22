# Self-owned ClearKey fixture

The input is this project's FFmpeg-generated `app/src/main/res/raw/demo.mp4` test
pattern. There is no third-party video, license, account or production key.
The intentionally public synthetic key and KID below belong only to this test.

Generate from the repository root with FFmpeg 8.1:

```sh
ffmpeg -hide_banner -loglevel error -y \
  -i app/src/main/res/raw/demo.mp4 -map 0:v:0 -an -c:v copy \
  -encryption_scheme cenc-aes-ctr \
  -encryption_key 00112233445566778899aabbccddeeff \
  -encryption_kid 11223344556677889900aabbccddeeff \
  -movflags +dash+global_sidx -frag_duration 2000000 \
  app/src/androidTest/assets/playback/clearkey/encrypted.mp4
```

The static MPD uses the resulting `moov` initialization bytes 0–882 and `sidx`
bytes 883–970. Regeneration with a different FFmpeg version may require updating
these byte ranges. There are four encrypted fragments, eight seconds of video,
and no audio track. The local test server requires different synthetic stream
and license Authorization headers and answers the native ClearKey challenge with
a JSON Web Key set. The test checks the requested KID and actual video playback.

Host sanity check (all frames must decode with the key):

```sh
ffmpeg -hide_banner -loglevel error \
  -decryption_key 00112233445566778899aabbccddeeff \
  -i app/src/androidTest/assets/playback/clearkey/encrypted.mp4 -an -f null -
```

This covers CENC ClearKey and the native Android path. It does not validate
Widevine provisioning, PlayReady, hardware security levels, HDCP, a provider's
license entitlement or physical-device compatibility.
