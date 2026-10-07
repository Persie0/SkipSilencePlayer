# Skip Silence Player

Two native mobile video players that automatically skip silent parts of local videos.

- `kotlin-android/` — Kotlin + Jetpack Compose + Media3/ExoPlayer.
- `swift-ios/` — SwiftUI + AVFoundation.

## Core playback

- Open one or multiple local videos.
- Open a folder and create a naturally sorted playlist.
- Previous/next controls and automatic next-video playback.
- “Open with Skip Silence Player” integration.
- Playback speed from 0.5× to 3×.
- Configurable double-tap seek: 5, 10, 15, or 30 seconds.
- Horizontal swipe seeking.
- Left/right vertical swipes for brightness and volume.
- Gesture lock.
- Fit, fill, crop, original-size mode, and pinch zoom.
- Fullscreen/landscape playback.
- Picture-in-Picture.
- Audio-only/background playback.
- File size, resolution, and duration information.
- Clear playback errors with retry.

## System media integration

### Android

Playback lives in a Media3 `MediaSessionService`. This provides background playback, media notification and lock-screen/system media controls. ExoPlayer handles audio focus and automatically pauses when the active wired/Bluetooth audio route becomes noisy or disconnects.

### iOS

The app uses an `AVAudioSession` playback session, `MPNowPlayingInfoCenter`, and `MPRemoteCommandCenter` for lock-screen/Control Center/headset controls. Playback responds to audio-session interruptions and pauses when an output device is removed.

## Silence skipping

- Enable/disable silence skipping.
- Silence threshold from -60 dB to -20 dB.
- Minimum silence duration from 0.2 s to 2.0 s.
- Adjustable edge retention/padding.
- Conservative, Balanced, Aggressive, and Custom modes.
- Live time-saved counter.
- Estimated final viewing time accounting for silence skipping and playback speed.

Balanced defaults:

| Setting | Value |
| --- | ---: |
| Silence threshold | -42 dB |
| Minimum silence | 0.45 s |
| Edge padding | 80 ms |
| Playback speed | 1.0× |
| Double-tap seek | 10 s |

Android uses a configurable Media3 `SilenceSkippingAudioProcessor` during playback.

iOS analyzes decoded PCM locally with `AVAssetReader`, detects sustained low-RMS ranges, and seeks across those ranges during `AVPlayer` playback. The analysis stays on-device.

## Resume, history, and navigation

- Remember playback position per video.
- Resume behavior: Ask, Always, or Never.
- Recent videos.
- Persist per-video playback speed and silence settings.
- Bookmarks per video.
- Embedded chapter navigation when chapter metadata is available.
- Playback-history clearing.
- Completed videos are not resumed at their final frame.

## Subtitles and audio tracks

- Embedded audio-track selector.
- Embedded subtitle-track selector.
- External SRT, WebVTT, SSA/ASS, and TTML support where implemented by the platform parser.
- External subtitle timing offset from -10 s to +10 s.
- External subtitle font scaling, background opacity, and top/center/bottom placement.
- Search imported subtitle text and jump to the next match.
- Persist external subtitle choice when continuing document access is available.

Custom timing offset and positioning apply to imported external subtitles. Embedded subtitles continue to use the platform media renderer; Android additionally applies the player subtitle text-size setting.

## Playback tools

- A–B repeat.
- Sleep timer: 15, 30, 45, or 60 minutes, or end of video.
- Bookmarks.
- Chapters.
- Playlist/folder playback.
- Audio-only mode.
- Silence-skipped and estimated viewing-time statistics.
- Reset player settings.

## Requirements

Android:
- Android 7.0 / API 24+
- JDK 17
- Android SDK 37

iOS:
- iOS 17+
- Current Xcode
- Swift 5 language mode

## Build

Android:

```bash
cd kotlin-android
./gradlew :app:assembleDebug
```

iOS:

Open `swift-ios/SkipSilencePlayer.xcodeproj`, or build the `SkipSilencePlayer` scheme for an iOS Simulator.
