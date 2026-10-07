@preconcurrency import AVFoundation
import Combine
import Foundation
import UIKit

@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()

    @Published private(set) var fileName = "No video selected"
    @Published private(set) var currentTime = 0.0
    @Published private(set) var duration = 0.0
    @Published private(set) var isPlaying = false
    @Published private(set) var analysisStatus = "Open a video to begin"
    @Published private(set) var silenceRanges: [SilenceRange] = []

    @Published var silenceEnabled = true
    @Published private(set) var silenceThresholdDB: Double
    @Published private(set) var brightness = Double(UIScreen.main.brightness)
    @Published private(set) var volume = 1.0

    private var timeObserver: Any?
    private var analysisTask: Task<Void, Never>?
    private var securityURL: URL?
    private var selectedURL: URL?
    private var hasSecurityScope = false
    private var jumpingOverSilence = false

    init() {
        let savedThreshold = UserDefaults.standard.object(forKey: "silenceThresholdDB") as? Double
        silenceThresholdDB = min(max(savedThreshold ?? -42.0, -60.0), -20.0)
        player.volume = 1.0

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // Playback can still work if the shared audio session cannot be changed.
        }

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.08, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in
                self?.tick(time)
            }
        }
    }

    deinit {
        analysisTask?.cancel()
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        if hasSecurityScope {
            securityURL?.stopAccessingSecurityScopedResource()
        }
    }

    func load(url: URL) {
        analysisTask?.cancel()

        if hasSecurityScope {
            securityURL?.stopAccessingSecurityScopedResource()
        }

        securityURL = url
        selectedURL = url
        hasSecurityScope = url.startAccessingSecurityScopedResource()

        fileName = url.lastPathComponent
        currentTime = 0
        duration = 0
        silenceRanges = []
        analysisStatus = "Analyzing audio for silence…"
        jumpingOverSilence = false

        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        player.play()
        isPlaying = true

        startSilenceAnalysis(url: url)
    }

    func togglePlayback() {
        if player.rate == 0 {
            player.play()
        } else {
            player.pause()
        }
        isPlaying = player.rate != 0
    }

    func seek(by seconds: Double) {
        seek(to: currentTime + seconds)
    }

    func seek(to seconds: Double) {
        let upper = duration > 0 ? duration : max(currentTime, 0)
        let target = min(max(0, seconds), max(upper, 0))
        jumpingOverSilence = false
        player.seek(
            to: CMTime(seconds: target, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
        currentTime = target
    }

    func setSilenceThreshold(_ value: Double) {
        silenceThresholdDB = min(max(value, -60.0), -20.0)
    }

    func applySilenceThreshold() {
        let rounded = silenceThresholdDB.rounded()
        silenceThresholdDB = rounded
        UserDefaults.standard.set(rounded, forKey: "silenceThresholdDB")
        guard let selectedURL else {
            return
        }
        startSilenceAnalysis(url: selectedURL)
    }

    func setBrightness(_ value: Double) {
        let clamped = min(max(value, 0.02), 1.0)
        brightness = clamped
        UIScreen.main.brightness = CGFloat(clamped)
    }

    func setVolume(_ value: Double) {
        let clamped = min(max(value, 0.0), 1.0)
        volume = clamped
        player.volume = Float(clamped)
    }

    private func startSilenceAnalysis(url: URL) {
        analysisTask?.cancel()
        silenceRanges = []
        analysisStatus = "Analyzing audio at \(Int(silenceThresholdDB.rounded())) dB…"

        let threshold = silenceThresholdDB
        let analyzer = SilenceAnalyzer()
        analysisTask = Task { [weak self] in
            do {
                let result = try await analyzer.analyze(
                    url: url,
                    thresholdDB: threshold
                )
                guard !Task.isCancelled, let self else {
                    return
                }

                duration = result.duration
                silenceRanges = result.ranges

                if !result.hasAudio {
                    analysisStatus = "This video has no audio track"
                } else if result.ranges.isEmpty {
                    analysisStatus = "No sustained silence found at \(Int(threshold.rounded())) dB"
                } else {
                    let seconds = result.ranges.reduce(0.0) { $0 + $1.duration }
                    analysisStatus = String(
                        format: "%d silent ranges · %@ skippable · %.0f dB",
                        result.ranges.count,
                        Self.format(seconds: seconds),
                        threshold
                    )
                }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, let self else {
                    return
                }
                analysisStatus = error.localizedDescription
            }
        }
    }

    private func tick(_ time: CMTime) {
        let seconds = CMTimeGetSeconds(time)
        if seconds.isFinite {
            currentTime = max(0, seconds)
        }

        if
            duration <= 0,
            let itemDuration = player.currentItem?.duration,
            itemDuration.isValid
        {
            let value = CMTimeGetSeconds(itemDuration)
            if value.isFinite && value > 0 {
                duration = value
            }
        }

        isPlaying = player.rate != 0

        guard
            silenceEnabled,
            isPlaying,
            !jumpingOverSilence,
            let range = silenceRanges.first(where: {
                $0.start <= currentTime && currentTime < $0.end
            }),
            range.end - currentTime > 0.04
        else {
            return
        }

        jumpingOverSilence = true
        player.seek(
            to: CMTime(seconds: range.end, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        ) { [weak self] _ in
            Task { @MainActor in
                self?.jumpingOverSilence = false
            }
        }
    }

    static func format(seconds: Double) -> String {
        guard seconds.isFinite else {
            return "--:--"
        }

        let total = max(0, Int(seconds.rounded(.down)))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%d:%02d", minutes, secs)
    }
}
