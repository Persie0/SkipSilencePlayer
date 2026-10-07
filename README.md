# Skip Silence Player

Two native mobile video players that automatically skip silent parts of local videos.

- `kotlin-android/` — Kotlin + Jetpack Compose + Media3/ExoPlayer.
- `swift-ios/` — SwiftUI + AVFoundation.

## Features

### Playback

- Open local video files with the platform document picker.
- Playback speed from 0.5× to 3× in 0.25× steps.
- Play/pause and timeline scrubbing.
- Configurable double-tap seek: 5, 10, 15, or 30 seconds.
- Vertical swipe on the left side controls brightness.
- Vertical swipe on the right side controls volume.
- Fullscreen/landscape mode and auto-hiding playback controls.
- Picture-in-Picture on supported Android and iOS devices.
- File information including duration, file size, and resolution when available.

### Silence skipping

- Toggle automatic silence skipping.
- Silence threshold from -60 dB to -20 dB.
- Minimum silence duration from 0.2 s to 2.0 s.
- Adjustable edge padding to protect speech transitions.
- Presets:
  - Conservative
  - Balanced
  - Aggressive
  - Custom
- Live counter showing how much silence has been skipped.
- Reset all player tuning to defaults.

Balanced defaults:

| Setting | Value |
| --- | ---: |
| Silence threshold | -42 dB |
| Minimum silence | 0.45 s |
| Edge padding | 80 ms |
| Playback speed | 1.0× |
| Double-tap seek | 10 s |

Android uses a configurable Media3 `SilenceSkippingAudioProcessor`.

iOS analyzes the selected video's audio locally with `AVAssetReader`, detects sustained low-RMS PCM regions, and seeks over those regions during `AVPlayer` playback. Analysis stays on-device.

### Resume and history

- Remembers playback position per video.
- Recent-video menu.
- Remembers per-video playback speed and silence settings.
- Remembers whether silence skipping is enabled.
- Persists external subtitle selection where the platform grants continuing document access.

### Audio and subtitles

- Embedded audio-track selector.
- Embedded subtitle-track selector.
- External subtitle files.
- Android uses Media3 subtitle support for SRT, WebVTT, SSA/ASS, and TTML when supported by the selected media pipeline.
- iOS includes local SRT, WebVTT, SSA, and ASS parsing with an in-player subtitle overlay.

## Requirements

Android:
- Android 7.0 / API 24+
- JDK 17
- Android SDK 37

iOS:
- iOS 17+
- Current Xcode (Swift 5 language mode, iOS 17+)

## Build

Android:

```bash
cd kotlin-android
./gradlew :app:assembleDebug
```

iOS:

Open `swift-ios/SkipSilencePlayer.xcodeproj`, or build the `SkipSilencePlayer` scheme for an iOS Simulator.
