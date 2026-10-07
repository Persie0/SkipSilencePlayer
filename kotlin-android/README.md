# Android

Native Android implementation using Kotlin, Jetpack Compose and Media3/ExoPlayer.

## Architecture

Playback is owned by `PlaybackService : MediaSessionService`, while the activity connects through a `MediaController`. This keeps media playback and system controls alive independently of the UI.

The player configures media audio attributes with audio-focus handling enabled and `setHandleAudioBecomingNoisy(true)`. Media3 therefore handles transient/permanent audio-focus changes and wired/Bluetooth route removal.

Silence skipping uses a configurable `SilenceSkippingAudioProcessor` and can be tuned by threshold, minimum-silence duration, and retained edge padding.

## Player features

Includes multi-video and folder playlists, automatic next playback, notification/lock-screen controls, Open With integration, PiP, fullscreen, aspect modes, pinch zoom, horizontal seeking, gesture lock, audio-only playback, resume modes, bookmarks, chapters, A-B repeat, sleep timer, history, subtitle import/search/sync/style controls, track selection, error handling, and time-saved estimates.

External subtitle parsing supports SRT, WebVTT, SSA/ASS, and TTML. Embedded subtitles are rendered by Media3.

## Build

```bash
./gradlew :app:assembleDebug
```
