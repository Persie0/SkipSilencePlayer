# Skip Silence Player

Two native mobile video players that automatically skip silent parts of local videos.

- `kotlin-android/` — Kotlin + Jetpack Compose + Media3/ExoPlayer.
- `swift-ios/` — SwiftUI + AVFoundation.

## Features

- Open local video files with the platform document picker.
- Toggle automatic silence skipping.
- Double-tap the left/right side of the video to seek backward/forward 10 seconds.
- Vertical swipe on the left side adjusts screen brightness.
- Vertical swipe on the right side adjusts media volume.
- Play/pause, timeline scrubbing, elapsed/remaining time, and explicit brightness/volume sliders.
- Rotation/full-screen friendly native video surfaces.

### Silence skipping

Android uses Media3/ExoPlayer's native silence-skipping audio processor.

iOS analyzes the selected video's audio track locally using AVAssetReader, finds sustained low-RMS PCM ranges, and seeks across those ranges during AVPlayer playback. Analysis never uploads the video.

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

iOS: open `swift-ios/SkipSilencePlayer.xcodeproj`.
