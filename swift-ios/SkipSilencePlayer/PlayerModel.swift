@preconcurrency import AVFoundation
import Combine
import Foundation
import MediaPlayer
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

struct PlaybackBookmark: Identifiable, Codable, Equatable {
    let id: UUID
    let position: Double
    let name: String

    init(id: UUID = UUID(), position: Double, name: String) {
        self.id = id
        self.position = position
        self.name = name
    }
}

struct ChapterMarker: Identifiable, Equatable {
    let id = UUID()
    let position: Double
    let name: String
}

enum ResumeMode: String, CaseIterable, Identifiable {
    case always
    case ask
    case never

    var id: String { rawValue }

    var label: String {
        switch self {
        case .always: return "Always resume"
        case .ask: return "Ask"
        case .never: return "Always restart"
        }
    }
}

enum VideoGravityMode: String, CaseIterable, Identifiable {
    case fit
    case fill
    case crop
    case width

    var id: String { rawValue }

    var label: String {
        switch self {
        case .fit: return "Fit"
        case .fill: return "Stretch"
        case .crop: return "Crop"
        case .width: return "Fit width"
        }
    }

    var gravity: AVLayerVideoGravity {
        switch self {
        case .fit, .width:
            return .resizeAspect
        case .fill:
            return .resize
        case .crop:
            return .resizeAspectFill
        }
    }
}

enum SubtitleTone: String, CaseIterable, Identifiable {
    case white
    case yellow
    case cyan

    var id: String { rawValue }

    var label: String {
        rawValue.capitalized
    }

    var color: UIColor {
        switch self {
        case .white: return .white
        case .yellow: return .yellow
        case .cyan: return .cyan
        }
    }
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

private struct PendingPlaylist {
    let urls: [URL]
    let names: [String]
    let startIndex: Int
    let resume: Double
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
    @Published private(set) var analysisSkippableTime = 0.0
    @Published private(set) var skippedTime = 0.0
    @Published private(set) var playbackError: String?

    @Published private(set) var silenceEnabled: Bool
    @Published private(set) var silenceThresholdDB: Double
    @Published private(set) var minimumSilence: Double
    @Published private(set) var edgePadding: Double
    @Published private(set) var playbackSpeed: Double
    @Published private(set) var doubleTapSeconds: Int

    @Published private(set) var subtitleOffset: Double
    @Published private(set) var subtitleFontSize: Double
    @Published private(set) var subtitleBottomFraction: Double
    @Published private(set) var subtitleBackgroundOpacity: Double
    @Published private(set) var subtitleTone: SubtitleTone
    @Published private(set) var videoGravityMode: VideoGravityMode
    @Published private(set) var videoZoom: Double
    @Published private(set) var audioOnly: Bool
    @Published private(set) var resumeMode: ResumeMode

    @Published private(set) var brightness = Double(UIScreen.main.brightness)
    @Published private(set) var volume = 1.0

    @Published private(set) var recentVideos: [RecentVideo]
    @Published private(set) var audioTrackNames: [String] = []
    @Published private(set) var subtitleTrackNames: [String] = []
    @Published private(set) var selectedAudioIndex: Int?
    @Published private(set) var selectedSubtitleIndex: Int?
    @Published private(set) var externalSubtitleName: String?
    @Published private(set) var externalSubtitleText = ""

    @Published private(set) var playlistIndex = 0
    @Published private(set) var playlistCount = 0
    @Published private(set) var pendingResumeSeconds: Double?
    @Published private(set) var chapters: [ChapterMarker] = []
    @Published private(set) var bookmarks: [PlaybackBookmark] = []

    @Published private(set) var sleepRemaining = 0.0
    @Published private(set) var abStart: Double?
    @Published private(set) var abEnd: Double?

    private let defaults = UserDefaults.standard
    private let notificationCenter = NotificationCenter.default
    private let remote = MPRemoteCommandCenter.shared()

    private var timeObserver: Any?
    private var notificationTokens: [NSObjectProtocol] = []
    private var analysisTask: Task<Void, Never>?
    private var metadataTask: Task<Void, Never>?
    private var selectedURL: URL?
    private var securityURL: URL?
    private var hasSecurityScope = false
    private var playlistRootURL: URL?
    private var hasPlaylistRootScope = false
    private var playlistURLs: [URL] = []
    private var playlistNames: [String] = []
    private var pendingPlaylist: PendingPlaylist?
    private var jumpingOverSilence = false
    private var persistTick = 0
    private var sleepDeadline: Date?
    private var wasPlayingBeforeInterruption = false

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

        subtitleOffset = Self.savedDouble("subtitleOffset", fallback: 0)
        subtitleFontSize = Self.savedDouble("subtitleFontSize", fallback: 24)
        subtitleBottomFraction = Self.savedDouble("subtitleBottomFraction", fallback: 0.10)
        subtitleBackgroundOpacity = Self.savedDouble("subtitleBackgroundOpacity", fallback: 0.65)
        subtitleTone = SubtitleTone(
            rawValue: UserDefaults.standard.string(forKey: "subtitleTone") ?? ""
        ) ?? .white
        videoGravityMode = VideoGravityMode(
            rawValue: UserDefaults.standard.string(forKey: "videoGravityMode") ?? ""
        ) ?? .fit
        videoZoom = Self.savedDouble("videoZoom", fallback: 1.0)
        audioOnly =
            (UserDefaults.standard.object(forKey: "audioOnly") as? Bool) ?? false
        resumeMode = ResumeMode(
            rawValue: UserDefaults.standard.string(forKey: "resumeMode") ?? ""
        ) ?? .always

        recentVideos = Self.loadRecentVideos()

        player.volume = 1.0
        player.defaultRate = Float(playbackSpeed)

        configureAudioSession()
        configureRemoteCommands()
        configureNotifications()

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.10, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in
                self?.tick(time)
            }
        }

        updateRemoteIntervals()
        updateNowPlaying()
    }

    deinit {
        analysisTask?.cancel()
        metadataTask?.cancel()

        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }

        notificationTokens.forEach {
            notificationCenter.removeObserver($0)
        }

        if hasSecurityScope {
            securityURL?.stopAccessingSecurityScopedResource()
        }
        if hasPlaylistRootScope {
            playlistRootURL?.stopAccessingSecurityScopedResource()
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

    var estimatedWatchTime: Double? {
        guard duration > 0 else { return nil }
        let content = max(0, duration - analysisSkippableTime)
        return content / max(playbackSpeed, 0.01)
    }

    var estimatedTimeSaved: Double? {
        guard let estimatedWatchTime else { return nil }
        return max(0, duration - estimatedWatchTime)
    }

    func requestOpen(urls: [URL], startIndex: Int = 0) {
        let valid = urls.filter { !$0.hasDirectoryPath }
        guard !valid.isEmpty else { return }

        let names = valid.map(\.lastPathComponent)
        let safeIndex = min(max(startIndex, 0), valid.count - 1)
        let key = Self.videoKey(valid[safeIndex])
        let resume = defaults.double(forKey: "\(key).position")

        switch resumeMode {
        case .always:
            loadPlaylist(
                urls: valid,
                names: names,
                startIndex: safeIndex,
                resume: resume
            )
        case .never:
            loadPlaylist(
                urls: valid,
                names: names,
                startIndex: safeIndex,
                resume: 0
            )
        case .ask:
            if resume > 5 {
                pendingPlaylist = PendingPlaylist(
                    urls: valid,
                    names: names,
                    startIndex: safeIndex,
                    resume: resume
                )
                pendingResumeSeconds = resume
            } else {
                loadPlaylist(
                    urls: valid,
                    names: names,
                    startIndex: safeIndex,
                    resume: 0
                )
            }
        }
    }

    func requestOpenFolder(_ folderURL: URL) {
        if hasPlaylistRootScope {
            playlistRootURL?.stopAccessingSecurityScopedResource()
        }

        playlistRootURL = folderURL
        hasPlaylistRootScope = folderURL.startAccessingSecurityScopedResource()

        let urls = Self.scanVideoFolder(folderURL)
        guard !urls.isEmpty else {
            playbackError = "No supported video files were found in this folder."
            return
        }

        requestOpen(urls: urls)
    }

    func confirmPendingResume(_ resume: Bool) {
        guard let pendingPlaylist else {
            pendingResumeSeconds = nil
            return
        }

        loadPlaylist(
            urls: pendingPlaylist.urls,
            names: pendingPlaylist.names,
            startIndex: pendingPlaylist.startIndex,
            resume: resume ? pendingPlaylist.resume : 0
        )

        self.pendingPlaylist = nil
        pendingResumeSeconds = nil
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
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            requestOpen(urls: [url])
        } catch {
            playbackError = "Could not reopen \(recent.name)."
        }
    }

    func clearHistory() {
        recentVideos = []
        defaults.removeObject(forKey: "recentVideos")
    }

    func nextVideo() {
        guard playlistIndex + 1 < playlistURLs.count else { return }
        saveCurrentVideoState()
        playlistIndex += 1
        loadPlaylistItem(at: playlistIndex, resume: savedResume(for: playlistURLs[playlistIndex]))
    }

    func previousVideo() {
        guard playlistIndex > 0 else { return }
        saveCurrentVideoState()
        playlistIndex -= 1
        loadPlaylistItem(at: playlistIndex, resume: savedResume(for: playlistURLs[playlistIndex]))
    }

    func retryPlayback() {
        playbackError = nil
        player.currentItem?.seek(to: CMTime(seconds: currentTime, preferredTimescale: 600))
        player.play()
    }

    func togglePlayback() {
        if player.rate == 0 {
            player.defaultRate = Float(playbackSpeed)
            player.play()
        } else {
            player.pause()
        }
        isPlaying = player.rate != 0
        updateNowPlaying()
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

        if let selectedURL {
            startSilenceAnalysis(url: selectedURL)
        }
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
        updateRemoteIntervals()
    }

    func setSubtitleOffset(_ value: Double) {
        subtitleOffset = min(max((value * 10).rounded() / 10, -10), 10)
        defaults.set(subtitleOffset, forKey: "subtitleOffset")
        updateExternalSubtitle(at: currentTime)
    }

    func setSubtitleFontSize(_ value: Double) {
        subtitleFontSize = min(max(value, 14), 40)
        defaults.set(subtitleFontSize, forKey: "subtitleFontSize")
    }

    func setSubtitleBottomFraction(_ value: Double) {
        subtitleBottomFraction = min(max(value, 0.02), 0.35)
        defaults.set(subtitleBottomFraction, forKey: "subtitleBottomFraction")
    }

    func setSubtitleBackgroundOpacity(_ value: Double) {
        subtitleBackgroundOpacity = min(max(value, 0), 0.9)
        defaults.set(subtitleBackgroundOpacity, forKey: "subtitleBackgroundOpacity")
    }

    func setSubtitleTone(_ value: SubtitleTone) {
        subtitleTone = value
        defaults.set(value.rawValue, forKey: "subtitleTone")
    }

    func setVideoGravityMode(_ mode: VideoGravityMode) {
        videoGravityMode = mode
        defaults.set(mode.rawValue, forKey: "videoGravityMode")
    }

    func setVideoZoom(_ value: Double) {
        videoZoom = min(max(value, 1), 3)
        defaults.set(videoZoom, forKey: "videoZoom")
    }

    func setAudioOnly(_ enabled: Bool) {
        audioOnly = enabled
        defaults.set(enabled, forKey: "audioOnly")
    }

    func setResumeMode(_ mode: ResumeMode) {
        resumeMode = mode
        defaults.set(mode.rawValue, forKey: "resumeMode")
    }

    func setBrightness(_ value: Double) {
        let clamped = min(max(value, 0.02), 1.0)
        brightness = clamped
        UIScreen.main.brightness = CGFloat(clamped)
    }

    func setVolume(_ value: Double) {
        let clamped = min(max(value, 0), 1)
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
                playbackError = "No subtitle cues were found in \(url.lastPathComponent)."
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
        externalSubtitleName = nil
        externalSubtitleText = ""

        if let selectedURL {
            let key = Self.videoKey(selectedURL)
            defaults.removeObject(forKey: "\(key).externalSubtitle")
            defaults.removeObject(forKey: "\(key).externalSubtitleName")
        }
    }

    func setSleepTimer(minutes: Int?) {
        guard let minutes, minutes > 0 else {
            sleepDeadline = nil
            sleepRemaining = 0
            return
        }

        sleepDeadline = Date().addingTimeInterval(Double(minutes) * 60)
        sleepRemaining = Double(minutes) * 60
    }

    func setABStart() {
        abStart = currentTime
        if let abEnd, abEnd <= currentTime {
            self.abEnd = nil
        }
    }

    func setABEnd() {
        guard let abStart, currentTime > abStart else { return }
        abEnd = currentTime
    }

    func clearABRepeat() {
        abStart = nil
        abEnd = nil
    }

    func addBookmark() {
        guard let selectedURL else { return }
        let new = PlaybackBookmark(
            position: currentTime,
            name: "Bookmark \(bookmarks.count + 1)"
        )
        bookmarks.append(new)
        bookmarks.sort { $0.position < $1.position }
        Self.saveBookmarks(bookmarks, key: Self.videoKey(selectedURL))
    }

    func clearBookmarks() {
        guard let selectedURL else { return }
        bookmarks = []
        Self.saveBookmarks([], key: Self.videoKey(selectedURL))
    }

    func jump(to bookmark: PlaybackBookmark) {
        seek(to: bookmark.position)
    }

    func jump(to chapter: ChapterMarker) {
        seek(to: chapter.position)
    }

    func resetSettings() {
        silenceEnabled = true
        silenceThresholdDB = -42
        minimumSilence = 0.45
        edgePadding = 0.08
        playbackSpeed = 1
        doubleTapSeconds = 10
        subtitleOffset = 0
        subtitleFontSize = 24
        subtitleBottomFraction = 0.10
        subtitleBackgroundOpacity = 0.65
        subtitleTone = .white
        videoGravityMode = .fit
        videoZoom = 1
        audioOnly = false
        resumeMode = .always

        defaults.set(true, forKey: "silenceEnabled")
        defaults.set(-42.0, forKey: "silenceThresholdDB")
        defaults.set(0.45, forKey: "minimumSilence")
        defaults.set(0.08, forKey: "edgePadding")
        defaults.set(1.0, forKey: "playbackSpeed")
        defaults.set(10, forKey: "doubleTapSeconds")
        defaults.set(0.0, forKey: "subtitleOffset")
        defaults.set(24.0, forKey: "subtitleFontSize")
        defaults.set(0.10, forKey: "subtitleBottomFraction")
        defaults.set(0.65, forKey: "subtitleBackgroundOpacity")
        defaults.set(SubtitleTone.white.rawValue, forKey: "subtitleTone")
        defaults.set(VideoGravityMode.fit.rawValue, forKey: "videoGravityMode")
        defaults.set(1.0, forKey: "videoZoom")
        defaults.set(false, forKey: "audioOnly")
        defaults.set(ResumeMode.always.rawValue, forKey: "resumeMode")

        player.defaultRate = 1
        if isPlaying {
            player.rate = 1
        }

        updateRemoteIntervals()
        applySilenceSettings()
    }

    private func loadPlaylist(
        urls: [URL],
        names: [String],
        startIndex: Int,
        resume: Double
    ) {
        saveCurrentVideoState()

        playlistURLs = urls
        playlistNames = names
        playlistCount = urls.count
        playlistIndex = min(max(startIndex, 0), urls.count - 1)
        loadPlaylistItem(at: playlistIndex, resume: resume)
    }

    private func loadPlaylistItem(at index: Int, resume: Double) {
        guard playlistURLs.indices.contains(index) else { return }

        analysisTask?.cancel()
        metadataTask?.cancel()

        if hasSecurityScope {
            securityURL?.stopAccessingSecurityScopedResource()
        }

        let url = playlistURLs[index]
        let name = playlistNames.indices.contains(index)
            ? playlistNames[index]
            : url.lastPathComponent

        securityURL = url
        selectedURL = url
        hasSecurityScope = url.startAccessingSecurityScopedResource()

        loadPerVideoSettings(url)
        playlistIndex = index
        fileName = name
        fileInfo = ""
        currentTime = 0
        duration = 0
        skippedTime = 0
        analysisSkippableTime = 0
        silenceRanges = []
        externalSubtitleText = ""
        playbackError = nil
        jumpingOverSilence = false
        analysisStatus = "Analyzing audio for silence…"
        chapters = []
        bookmarks = Self.loadBookmarks(key: Self.videoKey(url))

        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        player.defaultRate = Float(playbackSpeed)
        configureMediaSelection(for: item)
        observePlayerItem(item)

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

        restoreExternalSubtitle(for: Self.videoKey(url))
        if externalSubtitleName == nil {
            autoLoadSiblingSubtitle(for: url)
        }

        addRecentVideo(url)
        refreshFileInfo(url: url)
        loadChapters(url: url)
        startSilenceAnalysis(url: url)
        updateNowPlaying()
    }

    private func loadPerVideoSettings(_ url: URL) {
        let key = Self.videoKey(url)

        silenceEnabled = savedVideoBool(
            key: key,
            suffix: "skip",
            fallback: (defaults.object(forKey: "silenceEnabled") as? Bool) ?? true
        )
        silenceThresholdDB = savedVideoDouble(
            key: key,
            suffix: "threshold",
            fallback: Self.savedDouble("silenceThresholdDB", fallback: -42)
        )
        minimumSilence = savedVideoDouble(
            key: key,
            suffix: "minimum",
            fallback: Self.savedDouble("minimumSilence", fallback: 0.45)
        )
        edgePadding = savedVideoDouble(
            key: key,
            suffix: "padding",
            fallback: Self.savedDouble("edgePadding", fallback: 0.08)
        )
        playbackSpeed = savedVideoDouble(
            key: key,
            suffix: "speed",
            fallback: Self.savedDouble("playbackSpeed", fallback: 1)
        )
    }

    private func startSilenceAnalysis(url: URL) {
        analysisTask?.cancel()
        silenceRanges = []
        analysisSkippableTime = 0

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

                guard !Task.isCancelled, let self, selectedURL == url else {
                    return
                }

                duration = result.duration
                silenceRanges = result.ranges
                analysisSkippableTime = result.ranges.reduce(0) { $0 + $1.duration }

                if !result.hasAudio {
                    analysisStatus = "This video has no audio track"
                } else if result.ranges.isEmpty {
                    analysisStatus = "No sustained silence found"
                } else {
                    analysisStatus = String(
                        format: "%d silent ranges · %@ skippable",
                        result.ranges.count,
                        Self.format(seconds: analysisSkippableTime)
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

    private func configureRemoteCommands() {
        remote.playCommand.isEnabled = true
        remote.pauseCommand.isEnabled = true
        remote.togglePlayPauseCommand.isEnabled = true
        remote.skipForwardCommand.isEnabled = true
        remote.skipBackwardCommand.isEnabled = true
        remote.changePlaybackPositionCommand.isEnabled = true
        remote.nextTrackCommand.isEnabled = true
        remote.previousTrackCommand.isEnabled = true
        remote.changePlaybackRateCommand.isEnabled = true
        remote.changePlaybackRateCommand.supportedPlaybackRates = [
            0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0
        ].map(NSNumber.init(value:))

        remote.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                player.play()
                isPlaying = true
                updateNowPlaying()
            }
            return .success
        }

        remote.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                player.pause()
                isPlaying = false
                updateNowPlaying()
            }
            return .success
        }

        remote.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                self?.togglePlayback()
            }
            return .success
        }

        remote.skipForwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                seek(by: Double(doubleTapSeconds))
            }
            return .success
        }

        remote.skipBackwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                seek(by: -Double(doubleTapSeconds))
            }
            return .success
        }

        remote.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }

            Task { @MainActor in
                self?.seek(to: positionEvent.positionTime)
            }
            return .success
        }

        remote.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                self?.nextVideo()
            }
            return .success
        }

        remote.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                self?.previousVideo()
            }
            return .success
        }

        remote.changePlaybackRateCommand.addTarget { [weak self] event in
            guard let rateEvent = event as? MPChangePlaybackRateCommandEvent else {
                return .commandFailed
            }

            Task { @MainActor in
                self?.setPlaybackSpeed(Double(rateEvent.playbackRate))
                self?.commitPlaybackSpeed()
            }
            return .success
        }
    }

    private func updateRemoteIntervals() {
        remote.skipForwardCommand.preferredIntervals = [NSNumber(value: doubleTapSeconds)]
        remote.skipBackwardCommand.preferredIntervals = [NSNumber(value: doubleTapSeconds)]
    }

    private func configureNotifications() {
        let interruption = notificationCenter.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            Task { @MainActor in
                self?.handleInterruption(note)
            }
        }

        let route = notificationCenter.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            Task { @MainActor in
                self?.handleRouteChange(note)
            }
        }

        let ended = notificationCenter.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main
        ) { [weak self] note in
            Task { @MainActor in
                guard
                    let self,
                    let item = note.object as? AVPlayerItem,
                    item === player.currentItem
                else {
                    return
                }

                if playlistIndex + 1 < playlistURLs.count {
                    nextVideo()
                }
            }
        }

        notificationTokens = [interruption, route, ended]
    }

    private func observePlayerItem(_ item: AVPlayerItem) {
        let failed = notificationCenter.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] note in
            Task { @MainActor in
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                self?.playbackError =
                    error?.localizedDescription ?? "The video could not finish playback."
            }
        }

        let stalled = notificationCenter.addObserver(
            forName: .AVPlayerItemPlaybackStalled,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.playbackError = "Playback stalled. Retry or check the media file."
            }
        }

        notificationTokens.append(contentsOf: [failed, stalled])
    }

    private func handleInterruption(_ note: Notification) {
        guard
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: raw)
        else {
            return
        }

        switch type {
        case .began:
            wasPlayingBeforeInterruption = isPlaying
            player.pause()
            isPlaying = false
            updateNowPlaying()

        case .ended:
            let optionsRaw =
                note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)

            if wasPlayingBeforeInterruption && options.contains(.shouldResume) {
                do {
                    try AVAudioSession.sharedInstance().setActive(true)
                    player.play()
                    isPlaying = true
                } catch {
                    playbackError = "Audio could not resume: \(error.localizedDescription)"
                }
            }
            wasPlayingBeforeInterruption = false
            updateNowPlaying()

        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ note: Notification) {
        guard
            let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: raw)
        else {
            return
        }

        if reason == .oldDeviceUnavailable {
            player.pause()
            isPlaying = false
            updateNowPlaying()
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

    private func loadChapters(url: URL) {
        metadataTask?.cancel()

        metadataTask = Task { [weak self] in
            let asset = AVURLAsset(url: url)

            do {
                let locales = try await asset.load(.availableChapterLocales)
                guard
                    !Task.isCancelled,
                    let locale = locales.first
                else {
                    return
                }

                let groups = try await asset.loadChapterMetadataGroups(
                    withTitleLocale: locale,
                    containingItemsWithCommonKeys: [AVMetadataKey.commonKeyTitle]
                )

                var loaded: [ChapterMarker] = []

                for (index, group) in groups.enumerated() {
                    let position = CMTimeGetSeconds(group.timeRange.start)
                    guard position.isFinite && position >= 0 else { continue }

                    let title =
                        group.items.first(where: {
                            $0.commonKey?.rawValue == AVMetadataKey.commonKeyTitle.rawValue
                        })?.stringValue
                        ?? "Chapter \(index + 1)"

                    loaded.append(
                        ChapterMarker(position: position, name: title)
                    )
                }

                guard !Task.isCancelled, let self, selectedURL == url else {
                    return
                }
                chapters = loaded
            } catch {
                guard let self, selectedURL == url else { return }
                chapters = []
            }
        }
    }

    private func addRecentVideo(_ url: URL) {
        guard
            let bookmark = try? url.bookmarkData(
                options: [],
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

        recentVideos = Array(
            ([item] + recentVideos.filter { $0.id != item.id }).prefix(12)
        )

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
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
        else {
            return
        }

        loadExternalSubtitle(url: url)
    }

    private func autoLoadSiblingSubtitle(for videoURL: URL) {
        let base = videoURL.deletingPathExtension().lastPathComponent.lowercased()
        let parent = videoURL.deletingLastPathComponent()
        let supported = ["srt", "vtt", "ass", "ssa"]

        guard
            let files = try? FileManager.default.contentsOfDirectory(
                at: parent,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            )
        else {
            return
        }

        if let subtitle = files.first(where: {
            supported.contains($0.pathExtension.lowercased()) &&
                $0.deletingPathExtension().lastPathComponent.lowercased() == base
        }) {
            loadExternalSubtitle(url: subtitle)
        }
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

    private func savedResume(for url: URL) -> Double {
        switch resumeMode {
        case .always:
            return defaults.double(forKey: "\(Self.videoKey(url)).position")
        case .ask, .never:
            return 0
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
        updateExternalSubtitle(at: currentTime)

        if player.currentItem?.status == .failed, playbackError == nil {
            playbackError =
                player.currentItem?.error?.localizedDescription
                ?? "Unsupported or damaged media file."
        }

        if let abStart, let abEnd, abEnd > abStart, currentTime >= abEnd {
            seek(to: abStart)
        }

        if let sleepDeadline {
            sleepRemaining = max(0, sleepDeadline.timeIntervalSinceNow)
            if sleepRemaining <= 0 {
                player.pause()
                isPlaying = false
                self.sleepDeadline = nil
                sleepRemaining = 0
            }
        }

        persistTick += 1
        if persistTick >= 10 {
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
        let adjusted = time - subtitleOffset
        let text = externalSubtitleCues.first {
            $0.start <= adjusted && adjusted < $0.end
        }?.text ?? ""

        if text != externalSubtitleText {
            externalSubtitleText = text
        }
    }

    private func updateNowPlaying() {
        guard fileName != "No video selected" else {
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

        if playlistCount > 1 {
            info[MPNowPlayingInfoPropertyExternalContentIdentifier] =
                "\(playlistIndex + 1)/\(playlistCount)"
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
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

    private static func scanVideoFolder(_ folder: URL) -> [URL] {
        guard
            let enumerator = FileManager.default.enumerator(
                at: folder,
                includingPropertiesForKeys: [.isRegularFileKey],
                options: [.skipsHiddenFiles, .skipsPackageDescendants]
            )
        else {
            return []
        }

        let supported = Set([
            "mp4", "m4v", "mov", "mkv", "webm",
            "avi", "3gp", "ts", "mts", "m2ts"
        ])

        return enumerator.compactMap { item -> URL? in
            guard let url = item as? URL else { return nil }
            return supported.contains(url.pathExtension.lowercased()) ? url : nil
        }
        .sorted {
            $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent)
                == .orderedAscending
        }
    }

    private static func loadRecentVideos() -> [RecentVideo] {
        guard
            let data = UserDefaults.standard.data(forKey: "recentVideos"),
            let videos = try? JSONDecoder().decode([RecentVideo].self, from: data)
        else {
            return []
        }
        return Array(videos.prefix(12))
    }

    private static func loadBookmarks(key: String) -> [PlaybackBookmark] {
        guard
            let data = UserDefaults.standard.data(forKey: "\(key).bookmarks"),
            let bookmarks = try? JSONDecoder().decode([PlaybackBookmark].self, from: data)
        else {
            return []
        }
        return bookmarks.sorted { $0.position < $1.position }
    }

    private static func saveBookmarks(_ bookmarks: [PlaybackBookmark], key: String) {
        guard let data = try? JSONEncoder().encode(bookmarks) else { return }
        UserDefaults.standard.set(data, forKey: "\(key).bookmarks")
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
