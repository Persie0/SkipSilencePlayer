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
- Fit, fill/stretch, crop, and pinch zoom.
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
- **Smooth (default):** avoid abrupt timeline seeks. Android uses Media3's
  silence-shortening PCM processor with edge retention and its audio ramp;
  iOS gradually accelerates video through analyzed quiet intervals (up to 2×),
  restoring normal playback speed before speech resumes.
- **Jump (optional):** seek across analyzed quiet intervals for more aggressive
  time saving. Jump may visibly interrupt video frames/rebuffer.
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

Android keeps a configurable Media3 `SilenceSkippingAudioProcessor` active in
Smooth mode, even after background analysis completes. Only Jump mode uses
hard `seekTo()` for analyzed quiet ranges; outside safe decoded coverage it
falls back to the native processor. Adaptive noise-floor detection controls
the Jump mode's timeline map; Smooth's audio processor uses the selected
threshold/minimum/padding directly.

iOS analyzes decoded PCM locally with `AVAssetReader`, detects sustained
low-RMS ranges, and accelerates `AVPlayer` through those quiet ranges in
Smooth mode. Jump mode retains confirmed timeline seeks. The analysis remains
on-device and normal playback speed is restored on pause/seek/media changes.

## Playback-state follow-up (November 2026)

- iOS silence-range lookup now uses binary search rather than scanning every
  detected interval on each playback tick. Android external subtitle display
  also uses binary search over ordered cues.
- Automatic iOS skip completions are generation-checked against the current
  media item and the actual reached playback timestamp; superseded or
  unsuccessful seeks do not inflate the saved-time counter.
- Manual seeking and timeline scrubbing temporarily block conflicting
  automatic silence and A–B loop seeks. Rapid scrubbing uses a separate
  completion generation so stale callbacks cannot change the current state.
- A–B repeat no longer repeatedly seeks when iOS playback is paused.
- A completed iOS video persists a zero resume position even if a periodic
  persistence tick occurs after the end-of-item event. Pressing Play at the
  end restarts it from the beginning.
- Android estimated watch time now treats skip-silence being disabled as
  zero future skip savings, while historical actual saved time remains
  separately available.

These improvements are covered by Android JUnit boundary tests and Swift
real-media-harness checks for silence lookup and completed resume behavior.
Audio session interruptions, hardware decoder quirks and long-duration
real-world playback remain device-QA requirements.

## Playback reliability audit: seeks, long media and alternate audio

- Android confirms silence-related position discontinuities before crediting saved
  time. Failed, superseded, or manual seeks are not counted as automatic savings.
- A pending automatic seek blocks duplicate seeks while Media3 processes it.
  Analyzed ranges are used only within their verified decoded coverage.
- Android Smooth always uses native Media3 silence shortening. Jump uses the
  analyzed timeline where verified and falls back to native processing where
  the progressive analysis map has not yet reached.
- The Android and iOS loudness calibration uses a bounded 0.1 dB histogram
  instead of sorting the entire recording at every progress update.
- Audio analysis adapts its PCM window size for long recordings to constrain
  memory usage. Recordings beyond the analysis cap report a terminal partial
  map rather than claiming analysis is still continuing.
- When several audio tracks exist, Android matches the selected track by
  language to the decoded audio stream and reruns analysis after track changes.
  iOS also reanalyzes the selected language. If an alternate stream cannot be
  matched unambiguously, silence-map seeking is disabled for that stream
  rather than risking skipped dialogue. Android can still use native fallback.
- The test suite covers seek acknowledgment, duplicate suppression,
  manual-seek isolation, analyzed coverage boundaries, audio-language matching,
  and duration-aware analysis windows. Real-media audio regression tests still
  run for digital silence, noisy pauses and quiet speech.

## Adaptive silence skipping and diagnostics

Silence skipping now works from decoded **RMS audio windows** rather than
assuming the peak amplitude of individual PCM samples represents perceived
silence. The default **Adaptive noise floor** setting calibrates a conservative
threshold for each recording and protects quiet speech when the voice/noise
distributions overlap. Switch Adaptive off to enforce the manual dB threshold.

- The silent regions on the seek bar visualize locally decoded audio ranges.
  They guide Jump mode on both platforms and smooth acceleration on iOS; on
  Android Smooth uses its independent real-time PCM silence detector.
- Both platforms publish provisional ranges after initial audio decoding and
  refine the map as analysis continues. The displayed watch-time estimate is
  provisional until analysis completes.
- Android owns silence processing and the optional Jump seek loop in the
  media service, so background playback does not depend on a Compose UI.
- Smooth mode never issues automatic timeline seeks. Only Jump mode may seek,
  and never while native silence shortening is active for that portion of the
  analyzed timeline. Changing modes does not recreate the player.
- Conservative minimum duration and edge padding are designed to avoid
  clipping speech. A noise floor that cannot be reliably distinguished from
  quiet dialogue is intentionally left unskipped.
- Audio without clear quiet sections, music playing behind speech, or heavily
  compressed/limited audio might not have detectable silence. Automatic
  analysis is a loudness classifier, **not speech recognition or voice
  activity detection**.
- Changing silence settings reanalyzes the current file; old results are
  cancelled and cannot overwrite a newer file's ranges.
- Reopening completed videos starts at the beginning. Each video retains its
  independent resume position and silence settings.

## Additional reliability improvements

- Android's per-video save uses the outgoing video's last known position
  during automatic playlist transitions.
- A–B repeat limits automatic silence seeks; loop boundaries reset when
  switching videos.
- Retry on iOS recreates failed AVPlayer items. Dismissed codec/media errors
  do not immediately reappear unless a new error occurs.
- Subtitle imports from Android document providers use the actual display name
  or MIME information rather than opaque URIs. Folder subtitle matching is
  confined to the video's own directory.
- External subtitles support UTF-8/UTF-16 and common legacy encodings; large
  subtitle input is bounded.
- TTML document parsing blocks external entities and DTD loading.
- iOS subtitle lookup uses binary search instead of scanning every cue on
  every playback timer tick.

### Tests

The public Playground validation runs Android unit tests and an Android debug
build; the iOS job compiles the simulator app and analyzes real generated WAV
files containing digital silence, nonzero noisy pauses, and low-volume speech.
The tests verify both fixed and adaptive thresholds and guard against
false-positive skipping of quiet speech.

**Device QA is still necessary:** Bluetooth/headset interruptions,
vendor-specific hardware decoders, Android SAF providers, iOS external-file
bookmarks, PiP, and real-world dialogue/background-music content cannot be
fully validated by these CI builds alone.

## Playback navigation and device-adaptive UI

- **Library** combines Continue Watching, file and folder opening, recent-video
  search, per-video resume timestamps, and recent-folder shortcuts. Folder
  selections are remembered using platform-supported persistent document
  permissions (Android) or bookmarks (iOS); inaccessible folders report errors.
- **Up Next** lists the current playlist and lets the user jump between videos.
- **Markers** holds A–B repeat, bookmarks and chapters. **Tools** holds
  contextual playback controls and the sleep timer; **Settings** owns aspect
  ratio, double-tap seeking, resume behavior, audio-only mode, and destructive
  data actions.
- Compact landscape phones show a reduced overlay with seek timeline, transport,
  silence skipping, and tools; portrait retains the richer control layout.
- Minimum touch targets have been improved for recently opened items, the iOS
  chapter and bookmark controls, and the unlock affordance.
- All these surfaces remain native Compose or SwiftUI.

**Still required:** physical Android/iOS QA for rotated short screens,
subtitle overlap, screen readers, large system fonts, security-scoped folders,
and gesture conflicts; compilation alone does not validate their appearance.

## Playback interface

- The player has a dedicated Library view with open-video, open-folder,
  recent videos, and Continue watching actions. Returning to Library pauses
  playback but retains the active playlist and resume position.
- Android's system Back exits fullscreen first, then navigates to Library;
  dialogs and sheets retain their own Back behavior.
- Swipe-seeking previews its target and performs one seek on gesture release
  on both platforms, while the timeline supports accessible seeking.
- Subtitle overlays adapt to the measured playback-control area rather than
  relying on a fixed portrait-only offset.
- Silence analysis progress and availability are visible alongside the primary
  Skip silence control. Detailed analysis remains available in the settings sheet.
- Subtitle timing, styling, placement and text search are shown as advanced
  options only when an external subtitle file is loaded.

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

## Production hardening

- Restores the last local queue/playlist and selected item across app restarts without unexpectedly auto-playing.
- Skips stale Android queue entries that can no longer be opened; iOS restores only entries whose file bookmarks still resolve.
- Shows codec/media diagnostics such as video/audio codec, resolution, frame rate, channel count, and sample rate when available.
- Reports device decode/playability problems instead of only surfacing a generic playback error.
- Draws detected silent regions directly on the seek bar.
- Android folder playback automatically pairs same-name subtitles, including language variants such as `movie.en.srt`.
- Keyboard playback controls are available for external keyboards: Space/K play-pause, J/Left seek back, L/Right seek forward, F fullscreen, and M mute.
- Accessibility labels and seek-position semantics are provided for core playback controls.
- Android unit tests cover queue persistence, subtitle-name matching, and silence-range rules.
- iOS CI includes a generated PCM WAV fixture with known tone/silence intervals and verifies the real AVFoundation decoder/analyzer result.

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
