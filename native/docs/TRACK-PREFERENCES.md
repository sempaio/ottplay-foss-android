# Persistent audio and subtitle preferences

[Русский](TRACK-PREFERENCES.ru.md) · [README](../README.md)

In the native Android player, open **More → Audio / Subtitles**. A confirmed selection is saved as its language and role, not its manifest index. A new channel can reorder the tracks without changing the requested language. A label is used only when the track has no language. Tracks without any language, label or role remain a choice for the current stream only.

- **Auto** removes the explicit preference and restores Media3 platform selection, including the device language. Confirming Auto also clears a preference whose language is absent from the current stream.
- **Off** keeps subtitles disabled across channels and restarts.
- If the requested audio is absent, Media3 chooses a playable fallback. The saved preference remains available for the next matching stream.
- If requested subtitles are absent, subtitles stay disabled. They return when a later stream contains the requested language and role.
- Explicit choices are included in settings backups. Backups created before this feature use Auto for both track types. Import validation rejects malformed preferences before writing the source vault or catalog.

```mermaid
flowchart LR
    A[Confirmed track selection] --> B[Language + role, optional label]
    B --> C[DataStore / settings backup]
    C --> D[New stream tracks]
    D --> E{Matching supported track?}
    E -->|yes| F[Select new track index]
    E -->|no audio| G[Platform fallback]
    E -->|no subtitles| H[Keep subtitles off]
```

The existing Android resource catalogs and Media3's localized track dialog supply the labels; this feature adds no English-only UI messages. The dialog applies the confirmed choice to the controller and emits an app action; the ViewModel writes it in its own scope, so closing the pane does not cancel persistence. The service applies saved preferences. Automatic decoder events never overwrite a saved preference.

An unlabelled track override takes precedence for its current media item, even when an older preference write or an unrelated subtitle change arrives later. Explicit Auto or another track selection removes that override. A media-item transition always expires it, including when the next stream advertises an equal track group. Releasing the player also cancels preference observation.

## Verification

Run `./gradlew :app:testDebugUnitTest --tests '*TrackPreferencesTest' --tests '*TrackPreferencePlayerTest' --tests '*SettingsBackupPolicyTest' :app:assembleDebug`.

Tests cover reordered tracks, commentary roles, missing-and-returning subtitles, Off/Auto, late preference acknowledgements, equal-group media transitions, player release, settings round trips and malformed imports. On a device, select an audio language and subtitles, move to a stream with reversed track order, restart the app, then try a stream without those tracks and choose Auto. This physical playback check remains necessary; unit tests do not prove a stream provider's metadata is correct.
