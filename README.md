# Skip Silence Player

Native Android and iOS video players focused on shortening long videos by automatically skipping silent sections while keeping normal media-player features.

- `kotlin-android/` — Kotlin, Jetpack Compose, Media3/ExoPlayer.
- `swift-ios/` — SwiftUI, AVFoundation, AVKit and MediaPlayer.

## Playback

- Open local videos.
- Open multiple videos as a playlist.
- Open a folder and play its videos in natural filename order.
- Previous/next playlist controls with automatic next-item playback.
- Open videos directly with **Open with / Share to Skip Silence Player**.
- Playback speed: **0.5×–3×** in 0.25× steps.
- Timeline scrubbing.
- Configurable double-tap seek: **5 / 10 / 15 / 30 seconds**.
- Horizontal swipe seeking up to ±120 seconds.
- Left-side vertical swipe for brightness.
- Right-side vertical swipe for volume.
- Gesture lock.
- Fullscreen/landscape playback.
- Auto-hiding controls.
- Picture-in-Picture.
- Fit, stretch, crop and fit-width video modes.
- Additional **1×–3× zoom**.
- Audio-only/background playback.
- File size, resolution, duration and playlist position display.

## System media integration

### Android

Playback is hosted in a Media3 `MediaSessionService`:

- media notification,
- lock-screen controls,
- Bluetooth/headset media buttons,
- background playback,
- Android audio focus handling,
- automatic pause when an audio route becomes noisy/disconnected.

### iOS

The app integrates with:

- `MPNowPlayingInfoCenter`,
- `MPRemoteCommandCenter`,
- lock-screen / Control Center playback controls,
- Bluetooth/headset media buttons,
- background audio mode,
- AVAudioSession interruption handling,
- automatic pause when the previous audio route becomes unavailable.

## Silence skipping

- Enable/disable silence skipping.
- Threshold: **−60 dB to −20 dB**.
- Minimum silence duration: **0.2–2.0 seconds**.
- Edge padding: **20–200 ms**.
- Presets:
  - Conservative
  - Balanced
  - Aggressive
  - Custom
- Live skipped-time counter.
- Pre-playback/local analysis showing:
  - detected skippable silence,
  - estimated viewing time,
  - estimated time saved at the selected playback speed.

Balanced defaults:

| Setting | Default |
| --- | ---: |
| Silence threshold | −42 dB |
| Minimum silence | 0.45 s |
| Edge padding | 80 ms |
| Playback speed | 1.0× |
| Double-tap seek | 10 s |

Android uses Media3's configurable `SilenceSkippingAudioProcessor` during playback plus a local MediaCodec analysis pass for the preview estimate.

iOS analyzes decoded PCM locally with `AVAssetReader`, detects sustained low-RMS regions, then seeks across those regions during `AVPlayer` playback.

## Resume, history and navigation

- Remember playback position per video.
- Resume policy:
  - Always resume
  - Ask
  - Always restart
- Recent-video history.
- Clear history.
- Per-video playback speed and silence settings.
- User bookmarks/custom chapter markers.
- Embedded chapter discovery on iOS when chapter metadata is available.

## Audio and subtitles

- Embedded audio-track selector.
- Embedded subtitle-track selector.
- External subtitle import.
- Automatic local subtitle discovery when a matching subtitle file sits beside a video and the document provider grants folder access.
- Subtitle timing offset: **−10 s to +10 s** in 100 ms steps.
- Adjustable subtitle:
  - size,
  - vertical position,
  - background opacity,
  - text color.

External subtitle formats:

- SRT
- WebVTT
- SSA
- ASS
- Android additionally parses TTML/XML for its local subtitle overlay.

## Utility features

- Sleep timer: 15 / 30 / 60 / 90 minutes.
- A–B repeat.
- Clear playback errors with retry handling for unsupported, corrupt or inaccessible media.
- Reset player tuning to defaults.

## Requirements

Android:

- Android 7.0 / API 24+
- target / compile SDK 37
- JDK 17

iOS:

- iOS 17+
- Swift 5 language mode
- current Xcode

## Build

Android:

```bash
cd kotlin-android
./gradlew :app:assembleDebug
```

iOS:

```bash
cd swift-ios
xcodebuild \
  -project SkipSilencePlayer.xcodeproj \
  -scheme SkipSilencePlayer \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

All silence analysis and subtitle parsing are performed locally on the device.
