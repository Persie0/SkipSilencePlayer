# iOS

Native SwiftUI + AVFoundation implementation.

## Architecture

`AVPlayer` handles playback. The app configures an `AVAudioSession` for movie playback, reacts to interruptions and audio-route changes, and publishes playback state through `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter` for Control Center, lock-screen, Bluetooth, and headset controls.

AVPlayer has no native skip-silence switch, so the app analyzes decoded linear PCM from the selected asset with `AVAssetReader`. Sustained low-RMS regions are detected locally and playback seeks across those ranges.

## Player features

Includes multi-video and folder playlists, automatic next playback, Open With document integration, PiP, fullscreen, fit/fill/crop viewing, pinch zoom, horizontal seeking, gesture lock, audio-only/background playback, Ask/Always/Never resume behavior, bookmarks, embedded chapters, A-B repeat, sleep timer, playback-history clearing, embedded audio/subtitle selection, external subtitle search/sync/style controls, error handling, and exact post-analysis time-saved/estimated-watch-time statistics.

External subtitle parsing supports SRT, WebVTT, SSA, and ASS. Imported subtitles support timing offset, font scaling, background opacity, positioning, and text search. Embedded subtitles remain rendered by AVFoundation.

## Build

Open `SkipSilencePlayer.xcodeproj` in Xcode, or build the `SkipSilencePlayer` scheme for an iOS Simulator.
