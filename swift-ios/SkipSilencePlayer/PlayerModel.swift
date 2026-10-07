@preconcurrency import AVFoundation
import Combine
import Foundation
@preconcurrency import MediaPlayer
import UniformTypeIdentifiers
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

struct BookmarkPoint: Identifiable, Codable, Equatable {
    let id: UUID
    let seconds: Double
    let label: String
}

struct ChapterPoint: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let start: Double
    let duration: Double
}

struct PlaylistEntry: Identifiable, Equatable {
    let id: String
    let url: URL
    let name: String
}

enum ResumeMode: String, CaseIterable, Identifiable {
    case ask
    case always
    case never

    var id: String { rawValue }
    var label: String {
        switch self {
        case .ask: return "Ask"
        case .always: return "Always"
        case .never: return "Never"
        }
    }
}

enum VideoAspectMode: String, CaseIterable, Identifiable {
    case fit
    case fill
    case crop
    case original

    var id: String { rawValue }
    var label: String {
        rawValue.capitalized
    }

    var gravity: AVLayerVideoGravity {
        switch self {
        case .fill, .crop:
            return .resizeAspectFill
        case .fit, .original:
            return .resizeAspect
        }
    }
}

enum SubtitlePosition: String, CaseIterable, Identifiable {
    case top
    case center
    case bottom

    var id: String { rawValue }
    var label: String { rawValue.capitalized }
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

private struct PendingLoad {
    let entries: [PlaylistEntry]
    let index: Int
    let resume: Double
}

@MainActor
final class PlayerModel: NSObject, ObservableObject {
    let player = AVPlayer()

    @Published private(set) var fileName = "No video selected"
    @Published private(set) var fileInfo = ""
    @Published private(set) var currentTime = 0.0
    @Published private(set) var duration = 0.0
    @Published private(set) var isPlaying = false
    @Published private(set) var playbackError: String?
    @Published private(set) var analysisStatus = "Open a video to begin"
    @Published private(set) var silenceRanges: [SilenceRange] = []
    @Published private(set) var skippedTime = 0.0
    @Published private(set) var totalSkippableTime = 0.0
    @Published private(set) var estimatedWatchTime = 0.0

    @Published private(set) var silenceEnabled: Bool
    @Published private(set) var silenceThresholdDB: Double
    @Published private(set) var minimumSilence: Double
    @Published private(set) var edgePadding: Double
    @Published private(set) var playbackSpeed: Double
    @Published private(set) var doubleTapSeconds: Int

    @Published private(set) var brightness = Double(UIScreen.main.brightness)
    @Published private(set) var volume = 1.0

    @Published private(set) var recentVideos: [RecentVideo]
    @Published private(set) var playlist: [PlaylistEntry] = []
    @Published private(set) var playlistIndex = 0

    @Published private(set) var audioTrackNames: [String] = []
    @Published private(set) var subtitleTrackNames: [String] = []
    @Published private(set) var selectedAudioIndex: Int?
    @Published private(set) var selectedSubtitleIndex: Int?
    @Published private(set) var externalSubtitleName: String?
    @Published private(set) var externalSubtitleText = ""
    @Published private(set) var externalSubtitleCueCount = 0

    @Published private(set) var subtitleOffset: Double
    @Published private(set) var subtitleFontScale: Double
    @Published private(set) var subtitleBackgroundOpacity: Double
    @Published private(set) var subtitlePosition: SubtitlePosition

    @Published private(set) var aspectMode: VideoAspectMode
    @Published private(set) var videoZoom: Double
    @Published private(set) var audioOnly: Bool
    @Published private(set) var resumeMode: ResumeMode

    @Published private(set) var bookmarks: [BookmarkPoint] = []
    @Published private(set) var chapters: [ChapterPoint] = []

    @Published private(set) var abStart: Double?
    @Published private(set) var abEnd: Double?

    @Published private(set) var sleepDeadline: Date?
    @Published private(set) var sleepAtEnd = false

    @Published private(set) var resumePromptSeconds: Double?
    @Published private(set) var subtitleSearchStatus: String?

    private let defaults = UserDefaults.standard
    private var timeObserver: Any?
    private var analysisTask: Task<Void, Never>?
    private var securityURL: URL?
    private var hasSecurityScope = false
    private var playlistSecurityURL: URL?
    private var hasPlaylistSecurityScope = false
    private var selectedURL: URL?
    private var jumpingOverSilence = false
    private var persistTick = 0
    private var pendingLoad: PendingLoad?

    private var audioGroup: AVMediaSelectionGroup?
    private var subtitleGroup: AVMediaSelectionGroup?
    private var audioOptions: [AVMediaSelectionOption] = []
    private var subtitleOptions: [AVMediaSelectionOption] = []
    private var externalSubtitleCues: [SubtitleCue] = []

    private var remoteCommandTokens: [(MPRemoteCommand, Any)] = []
    private var notificationTokens: [NSObjectProtocol] = []
    private var shouldResumeAfterInterruption = false

    override init() {
        silenceEnabled =
            (UserDefaults.standard.object(forKey: "silenceEnabled") as? Bool) ?? true
        silenceThresholdDB = Self.savedDouble("silenceThresholdDB", fallback: -42)
        minimumSilence = Self.savedDouble("minimumSilence", fallback: 0.45)
        edgePadding = Self.savedDouble("edgePadding", fallback: 0.08)
        playbackSpeed = Self.savedDouble("playbackSpeed", fallback: 1.0)
        doubleTapSeconds =
            (UserDefaults.standard.object(forKey: "doubleTapSeconds") as? Int) ?? 10

        subtitleOffset = Self.savedDouble("subtitleOffset", fallback: 0)
        subtitleFontScale = Self.savedDouble("subtitleFontScale", fallback: 1)
        subtitleBackgroundOpacity =
            Self.savedDouble("subtitleBackgroundOpacity", fallback: 0.72)
        subtitlePosition = SubtitlePosition(
            rawValue: UserDefaults.standard.string(forKey: "subtitlePosition") ?? ""
        ) ?? .bottom

        aspectMode = VideoAspectMode(
            rawValue: UserDefaults.standard.string(forKey: "aspectMode") ?? ""
        ) ?? .fit
        videoZoom = Self.savedDouble("videoZoom", fallback: 1)
        audioOnly =
            (UserDefaults.standard.object(forKey: "audioOnly") as? Bool) ?? false
        resumeMode = ResumeMode(
            rawValue: UserDefaults.standard.string(forKey: "resumeMode") ?? ""
        ) ?? .ask

        recentVideos = Self.loadRecentVideos()

        super.init()

        player.volume = 1
        player.defaultRate = Float(playbackSpeed)

        configureAudioSession()
        configureRemoteCommands()
        configureNotifications()

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

        for (command, token) in remoteCommandTokens {
            command.removeTarget(token)
        }

        for token in notificationTokens {
            NotificationCenter.default.removeObserver(token)
        }

        if hasSecurityScope {
            securityURL?.stopAccessingSecurityScopedResource()
        }
        if hasPlaylistSecurityScope {
            playlistSecurityURL?.stopAccessingSecurityScopedResource()
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    var currentPreset: SilencePreset? {
        SilencePreset.allCases.first {
            abs($0.thresholdDB - silenceThresholdDB) < 0.01 &&
                abs($0.minimumSilence - minimumSilence) < 0.01 &&
                abs($0.edgePadding - edgePadding) < 0.01
        }
    }

    var selectedAudioName: String {
        guard
            let selectedAudioIndex,
            audioTrackNames.indices.contains(selectedAudioIndex)
        else {
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

    var hasPrevious: Bool {
        playlistIndex > 0
    }

    var hasNext: Bool {
        playlistIndex + 1 < playlist.count
    }

    var sleepTimerLabel: String {
        if sleepAtEnd {
            return "End of video"
        }
        if let sleepDeadline {
            let seconds = max(0, sleepDeadline.timeIntervalSinceNow)
            return "Sleep " + Self.format(seconds: seconds)
        }
        return "Sleep timer"
    }

    func requestLoad(urls: [URL], startIndex: Int = 0) {
        releasePlaylistSecurityScope()
        let entries = urls.map {
            PlaylistEntry(
                id: Self.stableID($0.absoluteString),
                url: $0,
                name: $0.lastPathComponent
            )
        }
        requestLoad(entries: entries, startIndex: startIndex)
    }

    func requestLoad(entries: [PlaylistEntry], startIndex: Int = 0) {
        guard !entries.isEmpty else { return }

        let index = min(max(startIndex, 0), entries.count - 1)
        let key = Self.videoKey(entries[index].url)
        let saved = defaults.double(forKey: "\(key).position")

        switch resumeMode {
        case .always where saved >= 5:
            performLoad(entries: entries, index: index, position: saved)
        case .never:
            performLoad(entries: entries, index: index, position: 0)
        case .ask where saved >= 5:
            pendingLoad = PendingLoad(entries: entries, index: index, resume: saved)
            resumePromptSeconds = saved
        default:
            performLoad(entries: entries, index: index, position: 0)
        }
    }

    func acceptResume() {
        guard let pendingLoad else { return }
        self.pendingLoad = nil
        resumePromptSeconds = nil
        performLoad(
            entries: pendingLoad.entries,
            index: pendingLoad.index,
            position: pendingLoad.resume
        )
    }

    func rejectResume() {
        guard let pendingLoad else { return }
        self.pendingLoad = nil
        resumePromptSeconds = nil
        performLoad(
            entries: pendingLoad.entries,
            index: pendingLoad.index,
            position: 0
        )
    }

    func requestFolder(url: URL) {
        releasePlaylistSecurityScope()
        playlistSecurityURL = url
        hasPlaylistSecurityScope = url.startAccessingSecurityScopedResource()

        let videoExtensions = Set([
            "mp4", "m4v", "mov", "mkv", "webm", "avi",
            "ts", "mts", "m2ts", "mpg", "mpeg"
        ])

        let keys: [URLResourceKey] = [
            .isRegularFileKey,
            .contentTypeKey,
            .nameKey
        ]

        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else {
            playbackError = "Could not read the selected folder."
            return
        }

        var urls: [URL] = []
        for case let child as URL in enumerator {
            let values = try? child.resourceValues(forKeys: Set(keys))
            guard values?.isRegularFile == true else { continue }

            let isVideoType =
                values?.contentType?.conforms(to: .movie) == true ||
                values?.contentType?.conforms(to: .video) == true
            let isKnownExtension =
                videoExtensions.contains(child.pathExtension.lowercased())

            if isVideoType || isKnownExtension {
                urls.append(child)
            }
        }

        urls.sort {
            $0.lastPathComponent.localizedStandardCompare(
                $1.lastPathComponent
            ) == .orderedAscending
        }

        if urls.isEmpty {
            playbackError = "No supported video files were found in that folder."
            releasePlaylistSecurityScope()
        } else {
            let entries = urls.map {
                PlaylistEntry(
                    id: Self.stableID($0.absoluteString),
                    url: $0,
                    name: $0.lastPathComponent
                )
            }
            requestLoad(entries: entries)
        }
    }

    func openRecent(_ recent: RecentVideo) {
        guard let url = Self.resolveBookmark(recent.bookmarkBase64) else {
            playbackError = "Could not reopen \(recent.name). The file may have moved or access expired."
            return
        }
        requestLoad(urls: [url])
    }

    func openExternalURL(_ url: URL) {
        requestLoad(urls: [url])
    }

    func previous() {
        guard hasPrevious else { return }
        saveCurrentVideoState()
        performLoad(entries: playlist, index: playlistIndex - 1, position: 0)
    }

    func next() {
        guard hasNext else { return }
        saveCurrentVideoState()
        performLoad(entries: playlist, index: playlistIndex + 1, position: 0)
    }

    func togglePlayback() {
        if player.rate == 0 {
            play()
        } else {
            pause()
        }
    }

    func play() {
        player.defaultRate = Float(playbackSpeed)
        player.play()
        player.rate = Float(playbackSpeed)
        isPlaying = true
        updateNowPlaying()
    }

    func pause() {
        player.pause()
        isPlaying = false
        updateNowPlaying()
    }

    func retryPlayback() {
        playbackError = nil
        player.currentItem?.seek(
            to: .zero,
            completionHandler: nil
        )
        player.play()
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
        updateNowPlaying()
    }

    func setSilenceEnabled(_ enabled: Bool) {
        silenceEnabled = enabled
        defaults.set(enabled, forKey: "silenceEnabled")
        saveCurrentVideoState()
    }

    func setSilenceThreshold(_ value: Double) {
        silenceThresholdDB = min(max(value, -60), -20)
    }

    func setMinimumSilence(_ value: Double) {
        minimumSilence = min(max(value, 0.2), 2)
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

        guard let selectedURL else { return }
        startSilenceAnalysis(url: selectedURL)
    }

    func applyPreset(_ preset: SilencePreset) {
        silenceThresholdDB = preset.thresholdDB
        minimumSilence = preset.minimumSilence
        edgePadding = preset.edgePadding
        applySilenceSettings()
    }

    func setPlaybackSpeed(_ value: Double) {
        playbackSpeed = min(max((value * 4).rounded() / 4, 0.5), 3)
        player.defaultRate = Float(playbackSpeed)
        if isPlaying {
            player.rate = Float(playbackSpeed)
        }
        recalculateEstimatedWatchTime()
        updateNowPlaying()
    }

    func commitPlaybackSpeed() {
        defaults.set(playbackSpeed, forKey: "playbackSpeed")
        saveCurrentVideoState()
    }

    func setDoubleTapSeconds(_ seconds: Int) {
        let allowed = [5, 10, 15, 30]
        doubleTapSeconds = allowed.contains(seconds) ? seconds : 10
        defaults.set(doubleTapSeconds, forKey: "doubleTapSeconds")
        configureRemoteSkipIntervals()
    }

    func setBrightness(_ value: Double) {
        let clamped = min(max(value, 0.02), 1)
        brightness = clamped
        UIScreen.main.brightness = CGFloat(clamped)
    }

    func setVolume(_ value: Double) {
        let clamped = min(max(value, 0), 1)
        volume = clamped
        player.volume = Float(clamped)
    }

    func setAspectMode(_ mode: VideoAspectMode) {
        aspectMode = mode
        defaults.set(mode.rawValue, forKey: "aspectMode")
    }

    func setVideoZoom(_ value: Double) {
        videoZoom = min(max(value, 1), 3)
    }

    func commitVideoZoom() {
        defaults.set(videoZoom, forKey: "videoZoom")
    }

    func setAudioOnly(_ enabled: Bool) {
        audioOnly = enabled
        defaults.set(enabled, forKey: "audioOnly")
        applyAudioOnlyToCurrentItem()
    }

    func setResumeMode(_ mode: ResumeMode) {
        resumeMode = mode
        defaults.set(mode.rawValue, forKey: "resumeMode")
    }

    func setSubtitleOffset(_ seconds: Double) {
        subtitleOffset = min(max(seconds, -10), 10)
        defaults.set(subtitleOffset, forKey: "subtitleOffset")
        updateExternalSubtitle(at: currentTime)
    }

    func setSubtitleFontScale(_ value: Double) {
        subtitleFontScale = min(max(value, 0.7), 2)
        defaults.set(subtitleFontScale, forKey: "subtitleFontScale")
    }

    func setSubtitleBackgroundOpacity(_ value: Double) {
        subtitleBackgroundOpacity = min(max(value, 0), 1)
        defaults.set(subtitleBackgroundOpacity, forKey: "subtitleBackgroundOpacity")
    }

    func setSubtitlePosition(_ position: SubtitlePosition) {
        subtitlePosition = position
        defaults.set(position.rawValue, forKey: "subtitlePosition")
    }

    func selectAudio(index: Int) {
        guard
            let item = player.currentItem,
            let group = audioGroup,
            audioOptions.indices.contains(index)
        else { return }

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
                playbackError = "No subtitle cues were found in \(url.lastPathComponent)."
                return
            }

            externalSubtitleCues = cues
            externalSubtitleCueCount = cues.count
            externalSubtitleName = url.lastPathComponent
            externalSubtitleText = ""
            subtitleSearchStatus = nil

            if let group = subtitleGroup {
                player.currentItem?.select(nil, in: group)
                selectedSubtitleIndex = nil
            }

            if let selectedURL {
                let key = Self.videoKey(selectedURL)
                if let bookmark = try? url.bookmarkData(
                    options: [],
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
            playbackError = "Could not read subtitle file: \(error.localizedDescription)"
        }
    }

    func clearExternalSubtitle() {
        externalSubtitleCues = []
        externalSubtitleCueCount = 0
        externalSubtitleName = nil
        externalSubtitleText = ""
        subtitleSearchStatus = nil

        if let selectedURL {
            let key = Self.videoKey(selectedURL)
            defaults.removeObject(forKey: "\(key).externalSubtitle")
            defaults.removeObject(forKey: "\(key).externalSubtitleName")
        }
    }

    func searchSubtitle(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !externalSubtitleCues.isEmpty else {
            subtitleSearchStatus = "Import an external subtitle first"
            return
        }

        let subtitleClock = currentTime - subtitleOffset
        let match = externalSubtitleCues.first {
            $0.start > subtitleClock &&
                $0.text.localizedCaseInsensitiveContains(trimmed)
        } ?? externalSubtitleCues.first {
            $0.text.localizedCaseInsensitiveContains(trimmed)
        }

        if let match {
            seek(to: max(0, match.start + subtitleOffset))
            subtitleSearchStatus = "Found at " + Self.format(seconds: match.start)
        } else {
            subtitleSearchStatus = "No match"
        }
    }

    func addBookmark() {
        guard let selectedURL else { return }

        let point = BookmarkPoint(
            id: UUID(),
            seconds: currentTime,
            label: Self.format(seconds: currentTime)
        )
        bookmarks.append(point)
        bookmarks.sort { $0.seconds < $1.seconds }
        saveBookmarks(for: selectedURL)
    }

    func removeBookmark(_ bookmark: BookmarkPoint) {
        guard let selectedURL else { return }
        bookmarks.removeAll { $0.id == bookmark.id }
        saveBookmarks(for: selectedURL)
    }

    func clearBookmarks() {
        guard let selectedURL else { return }
        bookmarks = []
        saveBookmarks(for: selectedURL)
    }

    func setA() {
        abStart = currentTime
        if let abEnd, abEnd <= currentTime {
            self.abEnd = nil
        }
    }

    func setB() {
        guard let abStart, currentTime > abStart else { return }
        abEnd = currentTime
    }

    func clearAB() {
        abStart = nil
        abEnd = nil
    }

    func setSleepTimer(minutes: Double) {
        sleepAtEnd = false
        sleepDeadline = Date().addingTimeInterval(minutes * 60)
    }

    func setSleepAtEnd() {
        sleepDeadline = nil
        sleepAtEnd = true
    }

    func cancelSleepTimer() {
        sleepDeadline = nil
        sleepAtEnd = false
    }

    func clearHistory() {
        recentVideos = []
        defaults.removeObject(forKey: "recentVideos")

        for key in defaults.dictionaryRepresentation().keys
        where key.hasPrefix("video.") && key.hasSuffix(".position") {
            defaults.removeObject(forKey: key)
        }
    }

    func resetSettings() {
        silenceEnabled = true
        silenceThresholdDB = -42
        minimumSilence = 0.45
        edgePadding = 0.08
        playbackSpeed = 1
        doubleTapSeconds = 10

        subtitleOffset = 0
        subtitleFontScale = 1
        subtitleBackgroundOpacity = 0.72
        subtitlePosition = .bottom

        aspectMode = .fit
        videoZoom = 1
        audioOnly = false
        resumeMode = .ask

        defaults.set(true, forKey: "silenceEnabled")
        defaults.set(-42.0, forKey: "silenceThresholdDB")
        defaults.set(0.45, forKey: "minimumSilence")
        defaults.set(0.08, forKey: "edgePadding")
        defaults.set(1.0, forKey: "playbackSpeed")
        defaults.set(10, forKey: "doubleTapSeconds")
        defaults.set(0.0, forKey: "subtitleOffset")
        defaults.set(1.0, forKey: "subtitleFontScale")
        defaults.set(0.72, forKey: "subtitleBackgroundOpacity")
        defaults.set(SubtitlePosition.bottom.rawValue, forKey: "subtitlePosition")
        defaults.set(VideoAspectMode.fit.rawValue, forKey: "aspectMode")
        defaults.set(1.0, forKey: "videoZoom")
        defaults.set(false, forKey: "audioOnly")
        defaults.set(ResumeMode.ask.rawValue, forKey: "resumeMode")

        player.defaultRate = 1
        if isPlaying {
            player.rate = 1
        }
        applyAudioOnlyToCurrentItem()
        configureRemoteSkipIntervals()
        applySilenceSettings()
    }

    private func performLoad(
        entries: [PlaylistEntry],
        index: Int,
        position: Double
    ) {
        saveCurrentVideoState()
        analysisTask?.cancel()

        playlist = entries
        playlistIndex = min(max(index, 0), entries.count - 1)
        loadCurrentPlaylistEntry(position: position)
    }

    private func loadCurrentPlaylistEntry(position: Double) {
        guard playlist.indices.contains(playlistIndex) else { return }

        if hasSecurityScope {
            securityURL?.stopAccessingSecurityScopedResource()
        }

        let entry = playlist[playlistIndex]
        let url = entry.url

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
            fallback: Self.savedDouble("playbackSpeed", fallback: 1)
        )

        fileName = entry.name
        currentTime = position
        duration = 0
        skippedTime = 0
        totalSkippableTime = 0
        estimatedWatchTime = 0
        silenceRanges = []
        externalSubtitleText = ""
        playbackError = nil
        jumpingOverSilence = false
        analysisStatus = "Analyzing audio for silence…"
        abStart = nil
        abEnd = nil

        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        player.defaultRate = Float(playbackSpeed)

        if position > 0 {
            player.seek(
                to: CMTime(seconds: position, preferredTimescale: 600),
                toleranceBefore: .zero,
                toleranceAfter: .zero
            )
        }

        configureMediaSelection(for: item)
        applyAudioOnlyToCurrentItem()
        restoreExternalSubtitle(for: videoKey)
        loadBookmarks(for: url)
        addRecentVideo(url)
        refreshFileInfo(url: url)
        loadChapters(asset: item.asset)
        startSilenceAnalysis(url: url)

        play()
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .moviePlayback
            )
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            playbackError = "Audio session setup failed: \(error.localizedDescription)"
        }
    }

    private func configureNotifications() {
        let center = NotificationCenter.default

        notificationTokens.append(
            center.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: AVAudioSession.sharedInstance(),
                queue: .main
            ) { [weak self] note in
                Task { @MainActor in
                    self?.handleAudioInterruption(note)
                }
            }
        )

        notificationTokens.append(
            center.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: AVAudioSession.sharedInstance(),
                queue: .main
            ) { [weak self] note in
                Task { @MainActor in
                    self?.handleRouteChange(note)
                }
            }
        )

        notificationTokens.append(
            center.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: nil,
                queue: .main
            ) { [weak self] note in
                Task { @MainActor in
                    guard
                        let self,
                        note.object as? AVPlayerItem === self.player.currentItem
                    else { return }

                    self.markCurrentCompleted()
                    if self.sleepAtEnd {
                        self.cancelSleepTimer()
                        self.pause()
                    } else if self.hasNext {
                        self.next()
                    } else {
                        self.pause()
                    }
                }
            }
        )
    }

    private func handleAudioInterruption(_ notification: Notification) {
        guard
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: raw)
        else { return }

        switch type {
        case .began:
            shouldResumeAfterInterruption = isPlaying
            pause()
        case .ended:
            let rawOptions =
                notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: rawOptions)
            if shouldResumeAfterInterruption && options.contains(.shouldResume) {
                configureAudioSession()
                play()
            }
            shouldResumeAfterInterruption = false
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ notification: Notification) {
        guard
            let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: raw)
        else { return }

        if reason == .oldDeviceUnavailable {
            pause()
        }
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.togglePlayPauseCommand.isEnabled = true
        center.nextTrackCommand.isEnabled = true
        center.previousTrackCommand.isEnabled = true
        center.changePlaybackPositionCommand.isEnabled = true
        center.skipForwardCommand.isEnabled = true
        center.skipBackwardCommand.isEnabled = true

        addRemoteTarget(center.playCommand) { [weak self] _ in
            self?.play()
            return .success
        }
        addRemoteTarget(center.pauseCommand) { [weak self] _ in
            self?.pause()
            return .success
        }
        addRemoteTarget(center.togglePlayPauseCommand) { [weak self] _ in
            self?.togglePlayback()
            return .success
        }
        addRemoteTarget(center.nextTrackCommand) { [weak self] _ in
            guard let self, self.hasNext else { return .noSuchContent }
            self.next()
            return .success
        }
        addRemoteTarget(center.previousTrackCommand) { [weak self] _ in
            guard let self, self.hasPrevious else { return .noSuchContent }
            self.previous()
            return .success
        }
        addRemoteTarget(center.skipForwardCommand) { [weak self] _ in
            guard let self else { return .commandFailed }
            self.seek(by: Double(self.doubleTapSeconds))
            return .success
        }
        addRemoteTarget(center.skipBackwardCommand) { [weak self] _ in
            guard let self else { return .commandFailed }
            self.seek(by: -Double(self.doubleTapSeconds))
            return .success
        }
        addRemoteTarget(center.changePlaybackPositionCommand) { [weak self] event in
            guard
                let self,
                let positionEvent = event as? MPChangePlaybackPositionCommandEvent
            else { return .commandFailed }
            self.seek(to: positionEvent.positionTime)
            return .success
        }

        configureRemoteSkipIntervals()
    }

    private func addRemoteTarget(
        _ command: MPRemoteCommand,
        handler: @escaping @MainActor (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus
    ) {
        let token = command.addTarget { event in
            if Thread.isMainThread {
                return MainActor.assumeIsolated {
                    handler(event)
                }
            }

            var result = MPRemoteCommandHandlerStatus.commandFailed
            DispatchQueue.main.sync {
                result = MainActor.assumeIsolated {
                    handler(event)
                }
            }
            return result
        }
        remoteCommandTokens.append((command, token))
    }

    private func configureRemoteSkipIntervals() {
        let center = MPRemoteCommandCenter.shared()
        center.skipForwardCommand.preferredIntervals = [NSNumber(value: doubleTapSeconds)]
        center.skipBackwardCommand.preferredIntervals = [NSNumber(value: doubleTapSeconds)]
    }

    private func updateNowPlaying() {
        guard selectedURL != nil else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: fileName,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? playbackSpeed : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: playbackSpeed
        ]

        if duration > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
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

                guard !Task.isCancelled, let self, self.selectedURL == url else {
                    return
                }

                duration = result.duration
                silenceRanges = result.ranges
                totalSkippableTime = result.ranges.reduce(0) { $0 + $1.duration }
                recalculateEstimatedWatchTime()

                if !result.hasAudio {
                    analysisStatus = "This video has no audio track"
                } else if result.ranges.isEmpty {
                    analysisStatus = "No sustained silence found"
                } else {
                    analysisStatus = String(
                        format: "%d silent ranges · %@ skippable · est. %@ watch",
                        result.ranges.count,
                        Self.format(seconds: totalSkippableTime),
                        Self.format(seconds: estimatedWatchTime)
                    )
                }

                updateNowPlaying()
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, let self else { return }
                analysisStatus = error.localizedDescription
            }
        }
    }

    private func recalculateEstimatedWatchTime() {
        let mediaSeconds = duration > 0 ? duration : 0
        let skip = silenceEnabled ? totalSkippableTime : 0
        estimatedWatchTime =
            max(0, mediaSeconds - skip) / max(playbackSpeed, 0.5)
    }

    private func configureMediaSelection(for item: AVPlayerItem) {
        let asset = item.asset

        Task { [weak self] in
            let audio = try? await asset.loadMediaSelectionGroup(for: .audible)
            let subtitles = try? await asset.loadMediaSelectionGroup(for: .legible)

            guard let self, self.player.currentItem === item else { return }

            audioGroup = audio
            audioOptions = audio?.options ?? []
            audioTrackNames = audioOptions.map(\.displayName)

            if
                let audio,
                let selected = item.currentMediaSelection.selectedMediaOption(in: audio)
            {
                selectedAudioIndex = audioOptions.firstIndex(of: selected)
            } else {
                selectedAudioIndex = audioOptions.isEmpty ? nil : 0
            }

            subtitleGroup = subtitles
            subtitleOptions = subtitles?.options ?? []
            subtitleTrackNames = subtitleOptions.map(\.displayName)

            if
                let subtitles,
                let selected = item.currentMediaSelection.selectedMediaOption(in: subtitles)
            {
                selectedSubtitleIndex = subtitleOptions.firstIndex(of: selected)
            } else {
                selectedSubtitleIndex = nil
            }
        }
    }

    private func applyAudioOnlyToCurrentItem() {
        guard let item = player.currentItem else { return }

        for track in item.tracks {
            if track.assetTrack?.mediaType == .video {
                track.isEnabled = !audioOnly
            }
        }
    }

    private func loadChapters(asset: AVAsset) {
        chapters = []

        Task { [weak self] in
            let locales = (try? await asset.load(.availableChapterLocales)) ?? []
            guard let locale = locales.first else { return }

            let groups = (try? await asset.loadChapterMetadataGroups(
                withTitleLocale: locale,
                containingItemsWithCommonKeys: []
            )) ?? []

            var loaded: [ChapterPoint] = []
            for (index, group) in groups.enumerated() {
                let start = CMTimeGetSeconds(group.timeRange.start)
                let chapterDuration = CMTimeGetSeconds(group.timeRange.duration)

                var title = "Chapter \(index + 1)"
                if let item = group.items.first,
                   let value = try? await item.load(.stringValue),
                   !value.isEmpty {
                    title = value
                }

                loaded.append(
                    ChapterPoint(
                        title: title,
                        start: start.isFinite ? max(0, start) : 0,
                        duration: chapterDuration.isFinite ? max(0, chapterDuration) : 0
                    )
                )
            }

            guard let self, self.player.currentItem?.asset === asset else { return }
            chapters = loaded
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
                parts.append("\(Int(abs(size.width)))×\(Int(abs(size.height)))")
            }

            if let durationTime = try? await asset.load(.duration) {
                let seconds = CMTimeGetSeconds(durationTime)
                if seconds.isFinite && seconds > 0 {
                    parts.append(Self.format(seconds: seconds))
                }
            }

            guard let self, selectedURL == url else { return }
            fileInfo = parts.joined(separator: " · ")
        }
    }

    private func addRecentVideo(_ url: URL) {
        guard
            let bookmark = try? url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
        else { return }

        let item = RecentVideo(
            id: Self.stableID(url.absoluteString),
            name: url.lastPathComponent,
            bookmarkBase64: bookmark.base64EncodedString()
        )

        recentVideos = ([item] + recentVideos.filter { $0.id != item.id })
            .prefix(12)
            .map { $0 }

        if let data = try? JSONEncoder().encode(recentVideos) {
            defaults.set(data, forKey: "recentVideos")
        }
    }

    private func restoreExternalSubtitle(for videoKey: String) {
        clearExternalSubtitle()

        guard
            let encoded = defaults.string(forKey: "\(videoKey).externalSubtitle"),
            let url = Self.resolveBookmark(encoded)
        else { return }

        loadExternalSubtitle(url: url)
    }

    private func loadBookmarks(for url: URL) {
        let key = Self.videoKey(url)
        guard
            let data = defaults.data(forKey: "\(key).bookmarks"),
            let values = try? JSONDecoder().decode([BookmarkPoint].self, from: data)
        else {
            bookmarks = []
            return
        }
        bookmarks = values.sorted { $0.seconds < $1.seconds }
    }

    private func saveBookmarks(for url: URL) {
        let key = Self.videoKey(url)
        if let data = try? JSONEncoder().encode(bookmarks) {
            defaults.set(data, forKey: "\(key).bookmarks")
        }
    }

    private func releasePlaylistSecurityScope() {
        if hasPlaylistSecurityScope {
            playlistSecurityURL?.stopAccessingSecurityScopedResource()
        }
        playlistSecurityURL = nil
        hasPlaylistSecurityScope = false
    }

    private func markCurrentCompleted() {
        guard let selectedURL else { return }
        defaults.set(0.0, forKey: "\(Self.videoKey(selectedURL)).position")
    }

    private func saveCurrentVideoState() {
        guard let selectedURL else { return }

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
                recalculateEstimatedWatchTime()
            }
        }

        isPlaying = player.rate != 0
        playbackError = player.currentItem?.error?.localizedDescription
        updateExternalSubtitle(at: currentTime)

        if let abStart, let abEnd, abEnd > abStart, currentTime >= abEnd {
            seek(to: abStart)
        }

        if let sleepDeadline, Date() >= sleepDeadline {
            self.sleepDeadline = nil
            pause()
        }

        persistTick += 1
        if persistTick >= 12 {
            persistTick = 0
            saveCurrentVideoState()
            updateNowPlaying()
        }

        guard
            silenceEnabled,
            isPlaying,
            !jumpingOverSilence,
            let range = silenceRanges.first(where: {
                $0.start <= currentTime && currentTime < $0.end
            }),
            range.end - currentTime > 0.04
        else { return }

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
        let subtitleTime = time - subtitleOffset
        let text = externalSubtitleCues.first {
            $0.start <= subtitleTime && subtitleTime < $0.end
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
        else { return [] }

        return Array(videos.prefix(12))
    }

    private static func savedDouble(_ key: String, fallback: Double) -> Double {
        guard UserDefaults.standard.object(forKey: key) != nil else {
            return fallback
        }
        return UserDefaults.standard.double(forKey: key)
    }

    private static func resolveBookmark(_ base64: String) -> URL? {
        guard let data = Data(base64Encoded: base64) else { return nil }
        var stale = false
        return try? URL(
            resolvingBookmarkData: data,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &stale
        )
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
            else { continue }

            let timing = lines[timingIndex].components(separatedBy: "-->")
            guard timing.count == 2 else { continue }

            let startToken = timing[0].trimmingCharacters(in: .whitespaces)
            let endToken = timing[1]
                .trimmingCharacters(in: .whitespaces)
                .components(separatedBy: .whitespaces)
                .first ?? ""

            guard
                let start = parseSubtitleTime(startToken),
                let end = parseSubtitleTime(endToken),
                end > start
            else { continue }

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
                cues.append(SubtitleCue(start: start, end: end, text: text))
            }
        }

        return cues.sorted { $0.start < $1.start }
    }

    private static func parseASS(_ input: String) -> [SubtitleCue] {
        var cues: [SubtitleCue] = []

        for line in input.components(separatedBy: .newlines) {
            guard line.lowercased().hasPrefix("dialogue:") else { continue }

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
            else { continue }

            let text = String(fields[9])
                .replacingOccurrences(of: "\\N", with: "\n")
                .replacingOccurrences(
                    of: "\\{[^}]*\\}",
                    with: "",
                    options: .regularExpression
                )
                .trimmingCharacters(in: .whitespacesAndNewlines)

            if !text.isEmpty {
                cues.append(SubtitleCue(start: start, end: end, text: text))
            }
        }

        return cues.sorted { $0.start < $1.start }
    }

    private static func parseSubtitleTime(_ token: String) -> Double? {
        let clean = token
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")

        let parts = clean.split(separator: ":")
        guard parts.count == 2 || parts.count == 3 else { return nil }

        if parts.count == 3 {
            guard
                let hours = Double(parts[0]),
                let minutes = Double(parts[1]),
                let seconds = Double(parts[2])
            else { return nil }

            return hours * 3600 + minutes * 60 + seconds
        }

        guard
            let minutes = Double(parts[0]),
            let seconds = Double(parts[1])
        else { return nil }

        return minutes * 60 + seconds
    }

    static func format(seconds: Double) -> String {
        guard seconds.isFinite else { return "--:--" }

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
        guard bytes > 0 else { return "" }

        let megabytes = Double(bytes) / 1_048_576
        if megabytes >= 1024 {
            return String(format: "%.2f GB", megabytes / 1024)
        }
        return String(format: "%.1f MB", megabytes)
    }
}
