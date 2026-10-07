@preconcurrency import AVFoundation
import Combine
import Foundation
import UIKit

struct RecentVideo: Identifiable, Codable, Equatable {
    let id: String
    let name: String
    let bookmarkBase64: String
}

struct SubtitleCue: Equatable, Sendable {
    let start: Double
    let end: Double
    let text: String
}

enum SilencePreset: String, CaseIterable, Identifiable {
    case conservative
    case balanced
    case aggressive

    var id: String { rawValue }

    var label: String {
        switch self {
        case .conservative: return "Conservative"
        case .balanced: return "Balanced"
        case .aggressive: return "Aggressive"
        }
    }

    var thresholdDB: Double {
        switch self {
        case .conservative: return -50
        case .balanced: return -42
        case .aggressive: return -35
        }
    }

    var minimumSilence: Double {
        switch self {
        case .conservative: return 0.8
        case .balanced: return 0.45
        case .aggressive: return 0.25
        }
    }

    var edgePadding: Double {
        switch self {
        case .conservative: return 0.15
        case .balanced: return 0.08
        case .aggressive: return 0.04
        }
    }
}

@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()

    @Published private(set) var fileName = "No video selected"
    @Published private(set) var fileInfo = ""
    @Published private(set) var currentTime = 0.0
    @Published private(set) var duration = 0.0
    @Published private(set) var isPlaying = false
    @Published private(set) var analysisStatus = "Open a video to begin"
    @Published private(set) var silenceRanges: [SilenceRange] = []
    @Published private(set) var skippedTime = 0.0

    @Published private(set) var silenceEnabled: Bool
    @Published private(set) var silenceThresholdDB: Double
    @Published private(set) var minimumSilence: Double
    @Published private(set) var edgePadding: Double
    @Published private(set) var playbackSpeed: Double
    @Published private(set) var doubleTapSeconds: Int

    @Published private(set) var brightness = Double(UIScreen.main.brightness)
    @Published private(set) var volume = 1.0

    @Published private(set) var recentVideos: [RecentVideo]
    @Published private(set) var audioTrackNames: [String] = []
    @Published private(set) var subtitleTrackNames: [String] = []
    @Published private(set) var selectedAudioIndex: Int?
    @Published private(set) var selectedSubtitleIndex: Int?
    @Published private(set) var externalSubtitleName: String?
    @Published private(set) var externalSubtitleText = ""

    private let defaults = UserDefaults.standard

    private var timeObserver: Any?
    private var analysisTask: Task<Void, Never>?
    private var securityURL: URL?
    private var selectedURL: URL?
    private var hasSecurityScope = false
    private var jumpingOverSilence = false
    private var persistTick = 0

    private var audioGroup: AVMediaSelectionGroup?
    private var subtitleGroup: AVMediaSelectionGroup?
    private var audioOptions: [AVMediaSelectionOption] = []
    private var subtitleOptions: [AVMediaSelectionOption] = []

    private var externalSubtitleCues: [SubtitleCue] = []

    init() {
        silenceEnabled =
            (UserDefaults.standard.object(forKey: "silenceEnabled") as? Bool) ?? true
        silenceThresholdDB = Self.savedDouble("silenceThresholdDB", fallback: -42)
        minimumSilence = Self.savedDouble("minimumSilence", fallback: 0.45)
        edgePadding = Self.savedDouble("edgePadding", fallback: 0.08)
        playbackSpeed = Self.savedDouble("playbackSpeed", fallback: 1.0)
        doubleTapSeconds =
            (UserDefaults.standard.object(forKey: "doubleTapSeconds") as? Int) ?? 10
        recentVideos = Self.loadRecentVideos()

        player.volume = 1.0
        player.defaultRate = Float(playbackSpeed)

        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .moviePlayback
            )
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

    var currentPreset: SilencePreset? {
        SilencePreset.allCases.first {
            abs($0.thresholdDB - silenceThresholdDB) < 0.01 &&
                abs($0.minimumSilence - minimumSilence) < 0.01 &&
                abs($0.edgePadding - edgePadding) < 0.01
        }
    }

    var selectedAudioName: String {
        guard let selectedAudioIndex, audioTrackNames.indices.contains(selectedAudioIndex) else {
            return audioTrackNames.isEmpty ? "None" : "Default"
        }
        return audioTrackNames[selectedAudioIndex]
    }

    var selectedSubtitleName: String {
        if let externalSubtitleName {
            return externalSubtitleName
        }
        guard
            let selectedSubtitleIndex,
            subtitleTrackNames.indices.contains(selectedSubtitleIndex)
        else {
            return "Off"
        }
        return subtitleTrackNames[selectedSubtitleIndex]
    }

    func load(url: URL) {
        saveCurrentVideoState()
        analysisTask?.cancel()

        if hasSecurityScope {
            securityURL?.stopAccessingSecurityScopedResource()
        }

        securityURL = url
        selectedURL = url
        hasSecurityScope = url.startAccessingSecurityScopedResource()

        let videoKey = Self.videoKey(url)
        silenceEnabled = savedVideoBool(
            key: videoKey,
            suffix: "skip",
            fallback: (defaults.object(forKey: "silenceEnabled") as? Bool) ?? true
        )
        silenceThresholdDB = savedVideoDouble(
            key: videoKey,
            suffix: "threshold",
            fallback: Self.savedDouble("silenceThresholdDB", fallback: -42)
        )
        minimumSilence = savedVideoDouble(
            key: videoKey,
            suffix: "minimum",
            fallback: Self.savedDouble("minimumSilence", fallback: 0.45)
        )
        edgePadding = savedVideoDouble(
            key: videoKey,
            suffix: "padding",
            fallback: Self.savedDouble("edgePadding", fallback: 0.08)
        )
        playbackSpeed = savedVideoDouble(
            key: videoKey,
            suffix: "speed",
            fallback: Self.savedDouble("playbackSpeed", fallback: 1.0)
        )

        fileName = url.lastPathComponent
        currentTime = 0
        duration = 0
        skippedTime = 0
        silenceRanges = []
        externalSubtitleText = ""
        jumpingOverSilence = false
        analysisStatus = "Analyzing audio for silence…"

        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        player.defaultRate = Float(playbackSpeed)
        configureMediaSelection(for: item)

        let resume = defaults.double(forKey: "\(videoKey).position")
        if resume > 0 {
            player.seek(
                to: CMTime(seconds: resume, preferredTimescale: 600),
                toleranceBefore: .zero,
                toleranceAfter: .zero
            )
            currentTime = resume
        }

        player.play()
        isPlaying = true

        restoreExternalSubtitle(for: videoKey)
        addRecentVideo(url)
        refreshFileInfo(url: url)
        startSilenceAnalysis(url: url)
    }

    func openRecent(_ recent: RecentVideo) {
        guard
            let data = Data(base64Encoded: recent.bookmarkBase64)
        else {
            return
        }

        var stale = false
        do {
            let url = try URL(
                resolvingBookmarkData: data,
                options: .withSecurityScope,
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            load(url: url)
        } catch {
            analysisStatus = "Could not reopen \(recent.name)"
        }
    }

    func togglePlayback() {
        if player.rate == 0 {
            player.defaultRate = Float(playbackSpeed)
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
        updateExternalSubtitle(at: target)
    }

    func setSilenceEnabled(_ enabled: Bool) {
        silenceEnabled = enabled
        defaults.set(enabled, forKey: "silenceEnabled")
        saveCurrentVideoState()
    }

    func setSilenceThreshold(_ value: Double) {
        silenceThresholdDB = min(max(value, -60.0), -20.0)
    }

    func setMinimumSilence(_ value: Double) {
        minimumSilence = min(max(value, 0.2), 2.0)
    }

    func setEdgePadding(_ value: Double) {
        edgePadding = min(max(value, 0.02), 0.20)
    }

    func applySilenceSettings() {
        silenceThresholdDB = silenceThresholdDB.rounded()
        minimumSilence = (minimumSilence * 20).rounded() / 20
        edgePadding = (edgePadding * 100).rounded() / 100

        defaults.set(silenceThresholdDB, forKey: "silenceThresholdDB")
        defaults.set(minimumSilence, forKey: "minimumSilence")
        defaults.set(edgePadding, forKey: "edgePadding")
        saveCurrentVideoState()

        guard let selectedURL else {
            return
        }
        startSilenceAnalysis(url: selectedURL)
    }

    func applyPreset(_ preset: SilencePreset) {
        silenceThresholdDB = preset.thresholdDB
        minimumSilence = preset.minimumSilence
        edgePadding = preset.edgePadding
        applySilenceSettings()
    }

    func setPlaybackSpeed(_ value: Double) {
        let rounded = min(max((value * 4).rounded() / 4, 0.5), 3.0)
        playbackSpeed = rounded
        player.defaultRate = Float(rounded)
        if isPlaying {
            player.rate = Float(rounded)
        }
    }

    func commitPlaybackSpeed() {
        defaults.set(playbackSpeed, forKey: "playbackSpeed")
        saveCurrentVideoState()
    }

    func setDoubleTapSeconds(_ seconds: Int) {
        let allowed = [5, 10, 15, 30]
        doubleTapSeconds = allowed.contains(seconds) ? seconds : 10
        defaults.set(doubleTapSeconds, forKey: "doubleTapSeconds")
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

    func selectAudio(index: Int) {
        guard
            let item = player.currentItem,
            let group = audioGroup,
            audioOptions.indices.contains(index)
        else {
            return
        }

        item.select(audioOptions[index], in: group)
        selectedAudioIndex = index
    }

    func selectSubtitle(index: Int?) {
        guard let item = player.currentItem, let group = subtitleGroup else {
            selectedSubtitleIndex = nil
            return
        }

        if let index, subtitleOptions.indices.contains(index) {
            item.select(subtitleOptions[index], in: group)
            selectedSubtitleIndex = index
            clearExternalSubtitle()
        } else {
            item.select(nil, in: group)
            selectedSubtitleIndex = nil
        }
    }

    func loadExternalSubtitle(url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let text = try String(contentsOf: url, encoding: .utf8)
            let cues = Self.parseSubtitleText(text, extension: url.pathExtension)
            guard !cues.isEmpty else {
                analysisStatus = "No subtitle cues found in \(url.lastPathComponent)"
                return
            }

            externalSubtitleCues = cues
            externalSubtitleName = url.lastPathComponent
            externalSubtitleText = ""
            if let group = subtitleGroup {
                player.currentItem?.select(nil, in: group)
                selectedSubtitleIndex = nil
            }

            if let selectedURL {
                let key = Self.videoKey(selectedURL)
                if let bookmark = try? url.bookmarkData(
                    options: .withSecurityScope,
                    includingResourceValuesForKeys: nil,
                    relativeTo: nil
                ) {
                    defaults.set(
                        bookmark.base64EncodedString(),
                        forKey: "\(key).externalSubtitle"
                    )
                    defaults.set(
                        url.lastPathComponent,
                        forKey: "\(key).externalSubtitleName"
                    )
                }
            }
            updateExternalSubtitle(at: currentTime)
        } catch {
            analysisStatus = "Could not read subtitle file"
        }
    }

    func clearExternalSubtitle() {
        externalSubtitleCues = []
        externalSubtitleName = nil
        externalSubtitleText = ""

        if let selectedURL {
            let key = Self.videoKey(selectedURL)
            defaults.removeObject(forKey: "\(key).externalSubtitle")
            defaults.removeObject(forKey: "\(key).externalSubtitleName")
        }
    }

    func resetSettings() {
        silenceEnabled = true
        silenceThresholdDB = -42
        minimumSilence = 0.45
        edgePadding = 0.08
        playbackSpeed = 1
        doubleTapSeconds = 10

        defaults.set(true, forKey: "silenceEnabled")
        defaults.set(-42.0, forKey: "silenceThresholdDB")
        defaults.set(0.45, forKey: "minimumSilence")
        defaults.set(0.08, forKey: "edgePadding")
        defaults.set(1.0, forKey: "playbackSpeed")
        defaults.set(10, forKey: "doubleTapSeconds")

        if let selectedURL {
            let key = Self.videoKey(selectedURL)
            [
                "skip",
                "threshold",
                "minimum",
                "padding",
                "speed"
            ].forEach {
                defaults.removeObject(forKey: "\(key).\($0)")
            }
        }

        player.defaultRate = 1
        if isPlaying {
            player.rate = 1
        }
        applySilenceSettings()
    }

    private func startSilenceAnalysis(url: URL) {
        analysisTask?.cancel()
        silenceRanges = []
        analysisStatus = String(
            format: "Analyzing at %.0f dB · %.2f s min · %.0f ms edge…",
            silenceThresholdDB,
            minimumSilence,
            edgePadding * 1000
        )

        let threshold = silenceThresholdDB
        let minimum = minimumSilence
        let padding = edgePadding
        let analyzer = SilenceAnalyzer()

        analysisTask = Task { [weak self] in
            do {
                let result = try await analyzer.analyze(
                    url: url,
                    thresholdDB: threshold,
                    minimumSilence: minimum,
                    edgePadding: padding
                )
                guard !Task.isCancelled, let self else {
                    return
                }

                duration = result.duration
                silenceRanges = result.ranges

                if !result.hasAudio {
                    analysisStatus = "This video has no audio track"
                } else if result.ranges.isEmpty {
                    analysisStatus = "No sustained silence found"
                } else {
                    let seconds = result.ranges.reduce(0.0) { $0 + $1.duration }
                    analysisStatus = String(
                        format: "%d silent ranges · %@ skippable",
                        result.ranges.count,
                        Self.format(seconds: seconds)
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

    private func configureMediaSelection(for item: AVPlayerItem) {
        let asset = item.asset

        audioGroup = asset.mediaSelectionGroup(forMediaCharacteristic: .audible)
        audioOptions = audioGroup?.options ?? []
        audioTrackNames = audioOptions.map(\.displayName)

        if
            let audioGroup,
            let selected = item.currentMediaSelection.selectedMediaOption(in: audioGroup)
        {
            selectedAudioIndex = audioOptions.firstIndex(of: selected)
        } else {
            selectedAudioIndex = audioOptions.isEmpty ? nil : 0
        }

        subtitleGroup = asset.mediaSelectionGroup(forMediaCharacteristic: .legible)
        subtitleOptions = subtitleGroup?.options ?? []
        subtitleTrackNames = subtitleOptions.map(\.displayName)

        if
            let subtitleGroup,
            let selected = item.currentMediaSelection.selectedMediaOption(in: subtitleGroup)
        {
            selectedSubtitleIndex = subtitleOptions.firstIndex(of: selected)
        } else {
            selectedSubtitleIndex = nil
        }
    }

    private func refreshFileInfo(url: URL) {
        let sizeText: String
        if
            let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
            size > 0
        {
            sizeText = Self.formatBytes(Int64(size))
        } else {
            sizeText = ""
        }

        fileInfo = sizeText

        Task { [weak self] in
            let asset = AVURLAsset(url: url)
            var parts: [String] = []
            if !sizeText.isEmpty {
                parts.append(sizeText)
            }

            if
                let tracks = try? await asset.loadTracks(withMediaType: .video),
                let track = tracks.first,
                let size = try? await track.load(.naturalSize),
                size.width > 0,
                size.height > 0
            {
                parts.append(
                    "\(Int(abs(size.width)))×\(Int(abs(size.height)))"
                )
            }

            if
                let durationTime = try? await asset.load(.duration)
            {
                let seconds = CMTimeGetSeconds(durationTime)
                if seconds.isFinite && seconds > 0 {
                    parts.append(Self.format(seconds: seconds))
                }
            }

            guard let self, selectedURL == url else {
                return
            }
            fileInfo = parts.joined(separator: " · ")
        }
    }

    private func addRecentVideo(_ url: URL) {
        guard
            let bookmark = try? url.bookmarkData(
                options: .withSecurityScope,
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
        else {
            return
        }

        let item = RecentVideo(
            id: Self.stableID(url.absoluteString),
            name: url.lastPathComponent,
            bookmarkBase64: bookmark.base64EncodedString()
        )

        recentVideos = ([item] + recentVideos.filter { $0.id != item.id })
            .prefix(8)
            .map { $0 }

        if let data = try? JSONEncoder().encode(recentVideos) {
            defaults.set(data, forKey: "recentVideos")
        }
    }

    private func restoreExternalSubtitle(for videoKey: String) {
        externalSubtitleCues = []
        externalSubtitleName = nil
        externalSubtitleText = ""

        guard
            let encoded = defaults.string(forKey: "\(videoKey).externalSubtitle"),
            let data = Data(base64Encoded: encoded)
        else {
            return
        }

        var stale = false
        guard
            let url = try? URL(
                resolvingBookmarkData: data,
                options: .withSecurityScope,
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
        else {
            return
        }

        loadExternalSubtitle(url: url)
    }

    private func saveCurrentVideoState() {
        guard let selectedURL else {
            return
        }

        let key = Self.videoKey(selectedURL)
        defaults.set(currentTime, forKey: "\(key).position")
        defaults.set(silenceEnabled, forKey: "\(key).skip")
        defaults.set(silenceThresholdDB, forKey: "\(key).threshold")
        defaults.set(minimumSilence, forKey: "\(key).minimum")
        defaults.set(edgePadding, forKey: "\(key).padding")
        defaults.set(playbackSpeed, forKey: "\(key).speed")
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
        updateExternalSubtitle(at: currentTime)

        persistTick += 1
        if persistTick >= 12 {
            persistTick = 0
            saveCurrentVideoState()
        }

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

        let skipped = max(0, range.end - currentTime)
        skippedTime += skipped
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

    private func updateExternalSubtitle(at time: Double) {
        let text = externalSubtitleCues.first {
            $0.start <= time && time < $0.end
        }?.text ?? ""

        if text != externalSubtitleText {
            externalSubtitleText = text
        }
    }

    private func savedVideoDouble(
        key: String,
        suffix: String,
        fallback: Double
    ) -> Double {
        guard defaults.object(forKey: "\(key).\(suffix)") != nil else {
            return fallback
        }
        return defaults.double(forKey: "\(key).\(suffix)")
    }

    private func savedVideoBool(
        key: String,
        suffix: String,
        fallback: Bool
    ) -> Bool {
        guard defaults.object(forKey: "\(key).\(suffix)") != nil else {
            return fallback
        }
        return defaults.bool(forKey: "\(key).\(suffix)")
    }

    private static func loadRecentVideos() -> [RecentVideo] {
        guard
            let data = UserDefaults.standard.data(forKey: "recentVideos"),
            let videos = try? JSONDecoder().decode([RecentVideo].self, from: data)
        else {
            return []
        }
        return Array(videos.prefix(8))
    }

    private static func savedDouble(_ key: String, fallback: Double) -> Double {
        guard UserDefaults.standard.object(forKey: key) != nil else {
            return fallback
        }
        return UserDefaults.standard.double(forKey: key)
    }

    private static func videoKey(_ url: URL) -> String {
        "video." + stableID(url.absoluteString)
    }

    private static func stableID(_ text: String) -> String {
        var hash: UInt64 = 1_469_598_103_934_665_603
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 1_099_511_628_211
        }
        return String(hash, radix: 16)
    }

    private static func parseSubtitleText(
        _ input: String,
        extension fileExtension: String
    ) -> [SubtitleCue] {
        let ext = fileExtension.lowercased()
        if ext == "ass" || ext == "ssa" {
            return parseASS(input)
        }
        return parseSRTOrVTT(input)
    }

    private static func parseSRTOrVTT(_ input: String) -> [SubtitleCue] {
        let normalized = input
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        let blocks = normalized.components(separatedBy: "\n\n")
        var cues: [SubtitleCue] = []

        for block in blocks {
            let lines = block
                .components(separatedBy: "\n")
                .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }

            guard
                let timingIndex = lines.firstIndex(where: { $0.contains("-->") })
            else {
                continue
            }

            let timing = lines[timingIndex].components(separatedBy: "-->")
            guard timing.count == 2 else {
                continue
            }

            let startToken = timing[0].trimmingCharacters(in: .whitespaces)
            let endToken = timing[1]
                .trimmingCharacters(in: .whitespaces)
                .components(separatedBy: .whitespaces)
                .first ?? ""

            guard
                let start = parseSubtitleTime(startToken),
                let end = parseSubtitleTime(endToken),
                end > start
            else {
                continue
            }

            let text = lines
                .dropFirst(timingIndex + 1)
                .joined(separator: "\n")
                .replacingOccurrences(
                    of: "<[^>]+>",
                    with: "",
                    options: .regularExpression
                )
                .trimmingCharacters(in: .whitespacesAndNewlines)

            if !text.isEmpty {
                cues.append(
                    SubtitleCue(start: start, end: end, text: text)
                )
            }
        }

        return cues.sorted { $0.start < $1.start }
    }

    private static func parseASS(_ input: String) -> [SubtitleCue] {
        var cues: [SubtitleCue] = []

        for line in input.components(separatedBy: .newlines) {
            guard line.lowercased().hasPrefix("dialogue:") else {
                continue
            }

            let payload = line.dropFirst("Dialogue:".count)
            let fields = payload.split(
                separator: ",",
                maxSplits: 9,
                omittingEmptySubsequences: false
            )
            guard
                fields.count >= 10,
                let start = parseSubtitleTime(String(fields[1])),
                let end = parseSubtitleTime(String(fields[2])),
                end > start
            else {
                continue
            }

            let text = String(fields[9])
                .replacingOccurrences(of: "\\N", with: "\n")
                .replacingOccurrences(
                    of: "\\{[^}]*\\}",
                    with: "",
                    options: .regularExpression
                )
                .trimmingCharacters(in: .whitespacesAndNewlines)

            if !text.isEmpty {
                cues.append(
                    SubtitleCue(start: start, end: end, text: text)
                )
            }
        }

        return cues.sorted { $0.start < $1.start }
    }

    private static func parseSubtitleTime(_ token: String) -> Double? {
        let clean = token
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")

        let parts = clean.split(separator: ":")
        guard parts.count == 2 || parts.count == 3 else {
            return nil
        }

        if parts.count == 3 {
            guard
                let hours = Double(parts[0]),
                let minutes = Double(parts[1]),
                let seconds = Double(parts[2])
            else {
                return nil
            }
            return hours * 3600 + minutes * 60 + seconds
        }

        guard
            let minutes = Double(parts[0]),
            let seconds = Double(parts[1])
        else {
            return nil
        }
        return minutes * 60 + seconds
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

    private static func formatBytes(_ bytes: Int64) -> String {
        guard bytes > 0 else {
            return ""
        }

        let megabytes = Double(bytes) / 1_048_576
        if megabytes >= 1024 {
            return String(format: "%.2f GB", megabytes / 1024)
        }
        return String(format: "%.1f MB", megabytes)
    }
}
