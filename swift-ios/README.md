# iOS

Native SwiftUI + AVFoundation implementation.

AVPlayer has no built-in skip-silence switch, so the app analyzes decoded linear PCM from the selected asset with AVAssetReader and records sustained low-RMS ranges. During playback it seeks over those ranges when skip-silence is enabled.

Open `SkipSilencePlayer.xcodeproj` in Xcode.
