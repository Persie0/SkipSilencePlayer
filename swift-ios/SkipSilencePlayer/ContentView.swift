import SwiftUI
import UniformTypeIdentifiers
import UIKit

private enum DragControl {
    case brightness
    case volume
    case seek
}

private enum PlayerColors {
    static let accent = Color(red: 63 / 255, green: 216 / 255, blue: 199 / 255)
    static let accentDark = Color(red: 4 / 255, green: 33 / 255, blue: 28 / 255)
    static let sheet = Color(red: 23 / 255, green: 27 / 255, blue: 34 / 255)
}

struct ContentView: View {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var subtitleBaseFontSize: CGFloat = 18
    @StateObject private var model = PlayerModel()
    @StateObject private var pipManager = PictureInPictureManager()

    @State private var showingVideoImporter = false
    @State private var showingFolderImporter = false
    @State private var showingSubtitleImporter = false
    @State private var dragControl: DragControl?
    @State private var dragStartValue = 0.0
    @State private var dragStartTime = 0.0
    @State private var gestureSeekPreview: Double?
    @State private var gestureHUD: String?
    @State private var controlsVisible = true
    @State private var isFullscreen = false
    @State private var gesturesLocked = false
    @State private var hideGeneration = 0
    @State private var subtitleSearch = ""
    @State private var pinchStartZoom = 1.0
    @FocusState private var playerKeyboardFocus: Bool

    @State private var showSpeedSheet = false
    @State private var showSilenceSheet = false
    @State private var showSubtitleSheet = false
    @State private var showAudioSheet = false
    @State private var showToolsSheet = false
    @State private var showSettingsPage = false
    @State private var showMarkersSheet = false
    @State private var toolsDestinationAfterDismiss: String?
    @State private var locateFileAfterRecentDismiss = false
    @State private var showPlaylistSheet = false
    @State private var showRecentSheet = false
    @State private var showHelpSheet = false
    @State private var showAdvancedSilenceSettings = false
    @State private var showAdvancedSubtitleSettings = false
    @State private var showingLibrary = false
    @State private var librarySearch = ""
    @State private var bottomControlsHeight: CGFloat = 0
    @State private var playerViewportWidth: CGFloat = 800
    @State private var pendingDestructiveAction: String?
    @State private var showingDestructiveConfirmation = false
    @State private var confirmationQueuedAfterSheetDismiss = false

    private var hasVideo: Bool {
        model.hasLoadedMedia
    }

    private var playbackOverlayOpen: Bool {
        showingLibrary || showingVideoImporter || showingFolderImporter ||
            showingSubtitleImporter || showSpeedSheet || showSilenceSheet ||
            showSubtitleSheet || showAudioSheet || showToolsSheet ||
            showMarkersSheet || showSettingsPage || showPlaylistSheet ||
            showRecentSheet || showHelpSheet || showingDestructiveConfirmation ||
            model.resumePromptSeconds != nil
    }

    private var compactLandscape: Bool {
        verticalSizeClass == .compact
    }

    private var narrowCompactLandscape: Bool {
        compactLandscape &&
            (playerViewportWidth < 620 || dynamicTypeSize.isAccessibilitySize)
    }

    private var compactPortraitActions: Bool {
        !compactLandscape &&
            (playerViewportWidth < 380 || dynamicTypeSize.isAccessibilitySize)
    }

    private var filteredRecentVideos: [RecentVideo] {
        guard !librarySearch.isEmpty else { return model.recentVideos }
        return model.recentVideos.filter {
            $0.name.localizedCaseInsensitiveContains(librarySearch)
        }
    }

    private var silenceStatusSummary: String {
        if !model.silenceEnabled { return "Skipping off" }
        if model.silencePlaybackMode == .smooth {
            return String(
                format: "Smooth · up to %g×",
                model.availableSilenceAccelerationSpeed
            )
        }
        let status = model.analysisStatus
        if status.hasPrefix("Analysis complete") { return "Analysis ready" }
        if status.hasPrefix("Analysis capped") { return "Partially analyzed" }
        if status.localizedCaseInsensitiveContains("unavailable") {
            return "Analysis unavailable"
        }
        if status.contains("no audio track") { return "No audio track" }
        return "Analyzing audio…"
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            videoArea

            if !hasVideo || showingLibrary {
                ZStack {
                    Color.black.ignoresSafeArea()
                    homeScreen
                }
            } else if controlsVisible && !gesturesLocked {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    bottomCluster
                        .background(
                            GeometryReader { proxy in
                                Color.clear.preference(
                                    key: PlayerBottomControlsHeightKey.self,
                                    value: proxy.size.height
                                )
                            }
                        )
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onPreferenceChange(PlayerBottomControlsHeightKey.self) {
                    bottomControlsHeight = $0
                }
            }

            if gesturesLocked && !showingLibrary {
                Button {
                    gesturesLocked = false
                    showControls()
                } label: {
                    Label("Unlock", systemImage: "lock.open")
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .background(Capsule().fill(Color.black.opacity(0.72)))
                        .overlay(
                            Capsule().strokeBorder(PlayerColors.accent.opacity(0.6))
                        )
                }
                .buttonStyle(.plain)
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.top, 14)
            }

            if let error = model.playbackError {
                VStack(spacing: 12) {
                    Text("Playback error")
                        .font(.headline)
                    Text(error)
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Try a different file if this video is missing or unsupported.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)

                    Button {
                        model.dismissPlaybackError()
                        showingVideoImporter = true
                    } label: {
                        Label(
                            error.hasPrefix("Could not reopen")
                                ? "Locate file"
                                : "Open another video",
                            systemImage: "folder"
                        )
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(PlayerColors.accent)

                    HStack {
                        Button("Dismiss") {
                            model.dismissPlaybackError()
                        }
                        .buttonStyle(.bordered)

                        if !error.hasPrefix("Could not reopen") {
                            Button("Retry") {
                                model.retryPlayback()
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
                .padding(18)
                .frame(maxWidth: 360)
                .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 14))
                .padding(24)
            }
        }
        .preferredColorScheme(.dark)
        .background(
            GeometryReader { proxy in
                Color.clear.preference(
                    key: PlayerViewportWidthKey.self,
                    value: proxy.size.width
                )
            }
        )
        .onPreferenceChange(PlayerViewportWidthKey.self) {
            playerViewportWidth = $0
        }
        .statusBarHidden(isFullscreen)
        .focusable()
        .focused($playerKeyboardFocus)
        .onAppear {
            playerKeyboardFocus = true
        }
        .onKeyPress(.space) {
            guard hasVideo && !showingLibrary else { return .ignored }
            model.togglePlayback()
            showControls()
            return .handled
        }
        .onKeyPress(.leftArrow) {
            guard hasVideo && !showingLibrary else { return .ignored }
            model.seek(by: -Double(model.doubleTapSeconds))
            showHUD("−\(model.doubleTapSeconds) s")
            return .handled
        }
        .onKeyPress(.rightArrow) {
            guard hasVideo && !showingLibrary else { return .ignored }
            model.seek(by: Double(model.doubleTapSeconds))
            showHUD("+\(model.doubleTapSeconds) s")
            return .handled
        }
        .onKeyPress("j") {
            guard hasVideo && !showingLibrary else { return .ignored }
            model.seek(by: -Double(model.doubleTapSeconds))
            return .handled
        }
        .onKeyPress("k") {
            guard hasVideo && !showingLibrary else { return .ignored }
            model.togglePlayback()
            return .handled
        }
        .onKeyPress("l") {
            guard hasVideo && !showingLibrary else { return .ignored }
            model.seek(by: Double(model.doubleTapSeconds))
            return .handled
        }
        .onKeyPress("f") {
            guard hasVideo && !showingLibrary else { return .ignored }
            setFullscreen(!isFullscreen)
            return .handled
        }
        .onKeyPress("m") {
            guard hasVideo && !showingLibrary else { return .ignored }
            model.toggleMute()
            return .handled
        }
        .onOpenURL { url in
            model.openExternalURL(url)
            showingLibrary = false
            showControls()
        }
        .fileImporter(
            isPresented: $showingVideoImporter,
            allowedContentTypes: [.movie, .video, .audiovisualContent],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls):
                if !urls.isEmpty {
                    model.requestLoad(urls: urls)
                    showingLibrary = false
                    showControls()
                }
            case .failure(let error):
                gestureHUD = error.localizedDescription
            }
        }
        .fileImporter(
            isPresented: $showingFolderImporter,
            allowedContentTypes: [.folder],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    if model.requestFolder(url: url) {
                        showingLibrary = false
                        showControls()
                    }
                }
            case .failure(let error):
                gestureHUD = error.localizedDescription
            }
        }
        .fileImporter(
            isPresented: $showingSubtitleImporter,
            allowedContentTypes: [.plainText, .text, .data],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    model.loadExternalSubtitle(url: url)
                    showControls()
                }
            case .failure(let error):
                gestureHUD = error.localizedDescription
            }
        }
        .sheet(isPresented: $showSpeedSheet) {
            speedSheet
        }
        .sheet(isPresented: $showSilenceSheet) {
            silenceSheet
        }
        .sheet(isPresented: $showSubtitleSheet) {
            subtitleSheet
        }
        .sheet(isPresented: $showAudioSheet) {
            audioSheet
        }
        .sheet(isPresented: $showToolsSheet, onDismiss: handleToolsDismiss) {
            toolsSheet
        }
        .sheet(isPresented: $showMarkersSheet, onDismiss: presentQueuedConfirmation) {
            markersSheet
        }
        .sheet(isPresented: $showSettingsPage, onDismiss: presentQueuedConfirmation) {
            settingsPage
        }
        .sheet(isPresented: $showPlaylistSheet) {
            playlistSheet
        }
        .sheet(isPresented: $showRecentSheet, onDismiss: handleRecentDismiss) {
            recentSheet
        }
        .sheet(isPresented: $showHelpSheet) {
            helpSheet
        }
        .alert(
            "Resume playback?",
            isPresented: Binding(
                get: { model.resumePromptSeconds != nil },
                set: { if !$0 { model.rejectResume() } }
            )
        ) {
            Button("Start over", role: .destructive) {
                model.rejectResume()
            }
            Button("Resume") {
                model.acceptResume()
            }
        } message: {
            Text(
                "Continue from " +
                    PlayerModel.format(seconds: model.resumePromptSeconds ?? 0) +
                    "?"
            )
        }
        .confirmationDialog(
            pendingDestructiveAction == "history" ? "Clear playback history?" :
                pendingDestructiveAction == "bookmarks" ? "Clear all bookmarks?" :
                "Reset player settings?",
            isPresented: $showingDestructiveConfirmation,
            titleVisibility: .visible
        ) {
            Button("Cancel", role: .cancel) {
                pendingDestructiveAction = nil
            }
            Button(
                pendingDestructiveAction == "settings" ? "Reset settings" : "Clear",
                role: .destructive
            ) {
                let action = pendingDestructiveAction
                pendingDestructiveAction = nil
                switch action {
                case "history": model.clearHistory()
                case "bookmarks": model.clearBookmarks()
                case "settings":
                    model.resetSettings()
                    showHUD("Defaults restored")
                default: break
                }
            }
        } message: {
            Text("This action cannot be undone.")
        }
        .onChange(of: model.isPlaying) { _, playing in
            if playing {
                scheduleControlsHide()
            } else {
                controlsVisible = true
                hideGeneration += 1
            }
        }
        .onChange(of: playbackOverlayOpen) { _, open in
            if open {
                hideGeneration += 1
                if hasVideo { controlsVisible = true }
            } else if hasVideo && !gesturesLocked {
                // Dismissing a sheet should reveal the player's controls,
                // with a fresh hide countdown rather than an expired timer.
                showControls()
            }
        }
    }

    // MARK: - Video area with gestures (unchanged behavior)

    private var videoArea: some View {
        GeometryReader { geometry in
            ZStack {
                if !model.audioOnly {
                    VideoSurface(
                        player: model.player,
                        pictureInPictureManager: pipManager,
                        videoGravity: model.aspectMode.gravity
                    )
                    .scaleEffect(model.videoZoom)
                    .background(Color.black)
                } else {
                    ZStack {
                        Color.black
                        VStack(spacing: 8) {
                            Image(systemName: "waveform")
                                .font(.system(size: 44))
                            Text("Audio-only playback")
                                .font(.headline)
                        }
                        .foregroundStyle(.secondary)
                    }
                }

                if !gesturesLocked && !showingLibrary && hasVideo {
                    Color.clear
                        .contentShape(Rectangle())
                        .gesture(
                            SpatialTapGesture(count: 2)
                                .onEnded { event in
                                    let seconds = Double(model.doubleTapSeconds)
                                    if event.location.x < geometry.size.width / 2 {
                                        model.seek(by: -seconds)
                                        showHUD("−\(model.doubleTapSeconds) s")
                                    } else {
                                        model.seek(by: seconds)
                                        showHUD("+\(model.doubleTapSeconds) s")
                                    }
                                }
                                .exclusively(
                                    before: SpatialTapGesture(count: 1)
                                        .onEnded { _ in
                                            if controlsVisible {
                                                controlsVisible = false
                                                hideGeneration += 1
                                            } else {
                                                showControls()
                                            }
                                        }
                                )
                        )
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 12)
                                .onChanged { value in
                                    if dragControl == nil {
                                        let dx = abs(value.translation.width)
                                        let dy = abs(value.translation.height)
                                        if dx > dy {
                                            dragControl = .seek
                                            dragStartTime = model.currentTime
                                        } else if value.startLocation.x < geometry.size.width / 2 {
                                            dragControl = .brightness
                                            dragStartValue = model.brightness
                                        } else {
                                            dragControl = .volume
                                            dragStartValue = model.volume
                                        }
                                    }

                                    switch dragControl {
                                    case .seek:
                                        let delta =
                                            value.translation.width /
                                            max(geometry.size.width, 1) * 120
                                        let target = min(
                                            max(0, dragStartTime + delta),
                                            model.duration > 0 ? model.duration : .infinity
                                        )
                                        gestureSeekPreview = target
                                        gestureHUD = "Seek to " +
                                            PlayerModel.format(seconds: target)
                                    case .brightness:
                                        let delta =
                                            -value.translation.height /
                                            max(geometry.size.height, 1)
                                        let next = min(max(dragStartValue + delta, 0.02), 1)
                                        model.setBrightness(next)
                                        gestureHUD =
                                            "Brightness " + String(Int(next * 100)) + "%"
                                    case .volume:
                                        let delta =
                                            -value.translation.height /
                                            max(geometry.size.height, 1)
                                        let next = min(max(dragStartValue + delta, 0), 1)
                                        model.setVolume(next)
                                        gestureHUD =
                                            "Volume " + String(Int(next * 100)) + "%"
                                    case .none:
                                        break
                                    }
                                }
                                .onEnded { _ in
                                    if dragControl == .seek, let target = gestureSeekPreview {
                                        model.seek(to: target)
                                    }
                                    gestureSeekPreview = nil
                                    dragControl = nil
                                    dismissHUDLater()
                                    showControls()
                                }
                        )
                        .simultaneousGesture(
                            MagnificationGesture()
                                .onChanged { scale in
                                    model.setVideoZoom(
                                        min(max(pinchStartZoom * scale, 1), 3)
                                    )
                                    gestureHUD = String(
                                        format: "Zoom %.1f×",
                                        model.videoZoom
                                    )
                                }
                                .onEnded { _ in
                                    pinchStartZoom = model.videoZoom
                                    model.commitVideoZoom()
                                    dismissHUDLater()
                                }
                        )
                }

                subtitleOverlay(availableHeight: geometry.size.height)

                if let gestureHUD {
                    Text(gestureHUD)
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .foregroundStyle(.white)
                        .background(
                            Capsule().fill(Color.black.opacity(0.78))
                        )
                        .overlay(
                            Capsule().strokeBorder(PlayerColors.accent.opacity(0.55))
                        )
                }
            }
        }
    }

    @ViewBuilder
    private func subtitleOverlay(availableHeight: CGFloat) -> some View {
        if !model.externalSubtitleText.isEmpty {
            let bottomInset: CGFloat = controlsVisible && !gesturesLocked
                ? min(bottomControlsHeight + 12, max(28, availableHeight * 0.55))
                : 28
            VStack {
                if model.subtitlePosition == .center {
                    Spacer()
                } else if model.subtitlePosition == .bottom {
                    Spacer()
                    Spacer()
                }

                Text(model.externalSubtitleText)
                    .font(.system(
                        size: subtitleBaseFontSize * model.subtitleFontScale,
                        weight: .semibold
                    ))
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                    .minimumScaleFactor(0.75)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(
                        .black.opacity(model.subtitleBackgroundOpacity),
                        in: RoundedRectangle(cornerRadius: 7)
                    )
                    .padding(.horizontal, 24)

                if model.subtitlePosition == .top {
                    Spacer()
                    Spacer()
                } else if model.subtitlePosition == .center {
                    Spacer()
                }
            }
            .padding(.top, 24)
            .padding(.bottom, bottomInset)
            .allowsHitTesting(false)
        }
    }

    // MARK: - Home screen

    private var homeScreen: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 24)

            if hasVideo {
                HStack {
                    Text("Library")
                        .font(.largeTitle.weight(.semibold))
                    Spacer()
                    Button {
                        showSettingsPage = true
                    } label: {
                        Image(systemName: "gearshape")
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Settings")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 22)
                        .fill(PlayerColors.accent.opacity(0.16))
                    Image(systemName: "waveform")
                        .font(.system(size: 36, weight: .semibold))
                        .foregroundStyle(PlayerColors.accent)
                }
                .frame(width: 76, height: 76)
    
                Text("Skip Silence Player")
                    .font(.title3.weight(.semibold))
                    .padding(.top, 14)
    
                Text("Skip quiet pauses in your videos")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.top, 3)
            }

            if hasVideo {
                Button {
                    showingLibrary = false
                    model.play()
                    showControls()
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "play.circle.fill")
                            .font(.title2)
                            .foregroundStyle(PlayerColors.accent)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Continue watching")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(model.fileName)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(.white)
                                .lineLimit(1)
                            Text(
                                PlayerModel.format(seconds: model.currentTime) +
                                    " of " + PlayerModel.format(seconds: model.duration)
                            )
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .foregroundStyle(.secondary)
                    }
                    .padding(14)
                    .background(Color.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 22)
                .padding(.top, 22)
            }

            HStack {
                Text("Browse files")
                    .font(.headline)
                Spacer()
                if !hasVideo {
                    Button {
                        showSettingsPage = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                    .font(.subheadline)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 26)

            VStack(spacing: 10) {
                Button {
                    showingVideoImporter = true
                } label: {
                    Label("Open video", systemImage: "play.rectangle.fill")
                        .font(.headline)
                        .foregroundStyle(PlayerColors.accentDark)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(
                            PlayerColors.accent,
                            in: RoundedRectangle(cornerRadius: 14)
                        )
                        .contentShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)

                Button {
                    showingFolderImporter = true
                } label: {
                    Label("Open folder", systemImage: "folder")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(
                            Color.white.opacity(0.09),
                            in: RoundedRectangle(cornerRadius: 14)
                        )
                        .contentShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 22)
            .padding(.top, 12)

            if !model.recentFolders.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Recent folders")
                        .font(.headline)
                        .padding(.bottom, 4)

                    ForEach(model.recentFolders.prefix(4)) { folder in
                        Button {
                            if model.openRecentFolder(folder) {
                                showingLibrary = false
                                showControls()
                            }
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "folder")
                                    .foregroundStyle(PlayerColors.accent)
                                Text(folder.name)
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundStyle(.secondary)
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.top, 20)
            }

            if model.playlist.count > 1 {
                Button {
                    showPlaylistSheet = true
                } label: {
                    HStack {
                        Image(systemName: "list.number")
                        Text("Up Next")
                        Spacer()
                        Text("\(model.playlist.count) videos")
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                    }
                    .frame(minHeight: 44)
                    .padding(.horizontal, 12)
                }
                .buttonStyle(.bordered)
                .padding(.horizontal, 22)
                .padding(.top, 12)
            }

            if !model.recentVideos.isEmpty {
                HStack {
                    Text("Recent videos")
                        .font(.headline)
                    Spacer()
                    Button("See all") {
                        showRecentSheet = true
                    }
                    .foregroundStyle(PlayerColors.accent)
                }
                .padding(.horizontal, 26)
                .padding(.top, 28)
                .padding(.bottom, 6)

                if model.recentVideos.count > 4 {
                    TextField("Search recent videos", text: $librarySearch)
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 8)
                }

                VStack(spacing: 2) {
                    ForEach(filteredRecentVideos.prefix(8)) { recent in
                        Button {
                            if model.openRecent(recent) {
                                showingLibrary = false
                                showControls()
                            }
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "clock.arrow.circlepath")
                                    .foregroundStyle(.tertiary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(recent.name)
                                        .foregroundStyle(.white)
                                        .lineLimit(1)
                                    if let resume = model.resumeTimeLabel(for: recent) {
                                        Text(resume)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                            }
                            .padding(.horizontal, 26)
                            .frame(minHeight: 44)
                            .padding(.vertical, 5)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    if filteredRecentVideos.isEmpty {
                        Text("No matching recent videos")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 16)
                    }
                }
            }

            Button {
                showHelpSheet = true
            } label: {
                Label("Gestures & player guide", systemImage: "questionmark.circle")
                    .font(.subheadline)
                    .foregroundStyle(PlayerColors.accent)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .padding(.top, 18)

                Spacer(minLength: 24)
                }
                .frame(maxWidth: 700)
                .frame(maxWidth: .infinity)
                .frame(
                    minHeight: (!hasVideo && model.recentVideos.isEmpty &&
                        model.recentFolders.isEmpty) ? geometry.size.height : 0
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(
            LinearGradient(
                colors: [Color(red: 16 / 255, green: 23 / 255, blue: 32 / 255), .black],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack(alignment: .center, spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.fileName)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)

                HStack(spacing: 6) {
                    if !compactLandscape && !model.fileInfo.isEmpty {
                        Text(model.fileInfo)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer(minLength: 4)

            HStack(spacing: 4) {
                playerIconButton("chevron.left", label: "Library") {
                    model.pause()
                    if isFullscreen { setFullscreen(false) }
                    showingLibrary = true
                    controlsVisible = false
                    hideGeneration += 1
                }

                if model.playlist.count > 1 {
                    playerIconButton("list.number", label: "Up Next") {
                        showPlaylistSheet = true
                    }
                }

                playerIconButton(
                    isFullscreen
                        ? "arrow.down.right.and.arrow.up.left"
                        : "arrow.up.left.and.arrow.down.right",
                    label: "Toggle fullscreen"
                ) {
                    setFullscreen(!isFullscreen)
                }

                Menu {
                    Button {
                        gesturesLocked = true
                        controlsVisible = false
                    } label: {
                        Label("Lock controls", systemImage: "lock.fill")
                    }
                    Divider()
                    Button {
                        showingVideoImporter = true
                    } label: {
                        Label("Open video…", systemImage: "play.rectangle")
                    }
                    Button {
                        showingFolderImporter = true
                    } label: {
                        Label("Open folder…", systemImage: "folder")
                    }
                    Button {
                        showRecentSheet = true
                    } label: {
                        Label("Recent…", systemImage: "clock.arrow.circlepath")
                    }
                    Button {
                        showHelpSheet = true
                    } label: {
                        Label("Player guide", systemImage: "questionmark.circle")
                    }
                    if PictureInPictureManager.isSupported {
                        Button {
                            pipManager.toggle()
                        } label: {
                            Label("Picture in Picture", systemImage: "pip.enter")
                        }
                    }
                    Button {
                        showAudioSheet = true
                    } label: {
                        Label("Audio tracks", systemImage: "speaker.wave.2")
                    }
                    Divider()
                    Button {
                        showSettingsPage = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 15, weight: .medium))
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(Color.white.opacity(0.12)))
                }
                .accessibilityLabel("More options")
                .foregroundStyle(.white)
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 10)
        .padding(.bottom, 30)
        .background(
            LinearGradient(
                colors: [.black.opacity(0.82), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .top)
        )
    }

    // MARK: - Bottom cluster

    private var bottomCluster: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Text(PlayerModel.format(seconds: model.currentTime))
                    .monospacedDigit()
                    .font(.caption)
                Spacer()
                Text(
                    model.duration > 0
                        ? "−" + PlayerModel.format(
                            seconds: max(0, model.duration - model.currentTime)
                        )
                        : "--:--"
                )
                .monospacedDigit()
                .font(.caption)
            }
            .padding(.horizontal, 20)

            SeekBar(
                position: model.currentTime,
                duration: model.duration,
                bookmarkSeconds: model.bookmarks.map(\.seconds),
                silenceRanges: model.silenceRanges
            ) { target in
                model.seek(to: target)
            }
            .padding(.horizontal, 20)

            if compactLandscape {
                compactTransportControls
            } else {
            HStack(spacing: 0) {
                if model.playlist.count > 1 {
                Button {
                    model.previous()
                } label: {
                    Image(systemName: "backward.end.fill")
                        .font(.title3)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .disabled(!model.hasPrevious)
                .foregroundStyle(.white)
                .accessibilityLabel("Previous video")
                }

                Button {
                    model.seek(by: -Double(model.doubleTapSeconds))
                    showHUD("−\(model.doubleTapSeconds) s")
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: "gobackward")
                            .font(.title2)
                        Text("\(model.doubleTapSeconds) s")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .frame(minWidth: 44, minHeight: 44)
                }
                .foregroundStyle(.white)
                .accessibilityLabel("Seek backward \(model.doubleTapSeconds) seconds")

                Button {
                    model.togglePlayback()
                    showControls()
                } label: {
                    Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(PlayerColors.accentDark)
                        .frame(width: 58, height: 58)
                        .background(Circle().fill(PlayerColors.accent))
                }
                .accessibilityLabel(model.isPlaying ? "Pause" : "Play")

                Button {
                    model.seek(by: Double(model.doubleTapSeconds))
                    showHUD("+\(model.doubleTapSeconds) s")
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: "goforward")
                            .font(.title2)
                        Text("\(model.doubleTapSeconds) s")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .frame(minWidth: 44, minHeight: 44)
                }
                .foregroundStyle(.white)
                .accessibilityLabel("Seek forward \(model.doubleTapSeconds) seconds")

                if model.playlist.count > 1 {
                Button {
                    model.next()
                } label: {
                    Image(systemName: "forward.end.fill")
                        .font(.title3)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .disabled(!model.hasNext)
                .foregroundStyle(.white)
                .accessibilityLabel("Next video")
                }
            }
            .buttonStyle(.plain)

            HStack(spacing: 6) {
                Button {
                    showSilenceSheet = true
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Skip silence")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(model.silenceEnabled ? PlayerColors.accent : .white)
                            .lineLimit(1)
                        Text(model.silenceEnabled
                            ? (model.silencePlaybackMode == .smooth
                                ? String(
                                    format: "Smooth up to %g× · saved %@",
                                    model.availableSilenceAccelerationSpeed,
                                    PlayerModel.format(seconds: model.skippedTime)
                                )
                                : "Jump · saved " + PlayerModel.format(seconds: model.skippedTime))
                            : "Off")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
                .accessibilityLabel("Silence skipping settings")

                Toggle("Skip silence", isOn: Binding(
                    get: { model.silenceEnabled },
                    set: { model.setSilenceEnabled($0) }
                ))
                .labelsHidden()
                .tint(PlayerColors.accent)
                .accessibilityLabel("Skip silence")

                if !compactPortraitActions {
                    Button {
                        showSpeedSheet = true
                    } label: {
                        Text(String(format: "%g×", model.playbackSpeed))
                            .font(.subheadline.weight(.medium))
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel("Playback speed")

                    Button {
                        showSubtitleSheet = true
                    } label: {
                        Image(systemName: "captions.bubble")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel("Subtitles")
                }

                Button {
                    showToolsSheet = true
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel("Playback tools")
            }
            .foregroundStyle(.white)
            .buttonStyle(.plain)
            .padding(.horizontal, 20)
            }
        }
        .padding(.top, compactLandscape ? 5 : 26)
        .padding(.bottom, compactLandscape ? 5 : 16)
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.9)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .bottom)
        )
    }

    private var compactTransportControls: some View {
        HStack(spacing: 12) {
            if !narrowCompactLandscape {
                Button {
                    model.seek(by: -Double(model.doubleTapSeconds))
                } label: {
                    Image(systemName: "gobackward")
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel("Seek backward \(model.doubleTapSeconds) seconds")
            }

            Button {
                model.togglePlayback()
                showControls()
            } label: {
                Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                    .foregroundStyle(PlayerColors.accentDark)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(PlayerColors.accent))
            }
            .accessibilityLabel(model.isPlaying ? "Pause" : "Play")

            if !narrowCompactLandscape {
                Button {
                    model.seek(by: Double(model.doubleTapSeconds))
                } label: {
                    Image(systemName: "goforward")
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel("Seek forward \(model.doubleTapSeconds) seconds")
            }

            Spacer(minLength: 2)

            Button {
                showSilenceSheet = true
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Skip silence")
                        .font(.caption.weight(.semibold))
                    if !narrowCompactLandscape {
                        Text(silenceStatusSummary)
                            .font(.caption2)
                            .lineLimit(1)
                    }
                }
                .frame(minHeight: 44)
            }
            .accessibilityLabel("Silence skipping settings")
            .accessibilityValue(silenceStatusSummary)
            .accessibilityHint("Adjust silence skipping")

            Toggle("Skip silence", isOn: Binding(
                get: { model.silenceEnabled },
                set: { model.setSilenceEnabled($0) }
            ))
            .labelsHidden()
            .tint(PlayerColors.accent)
            .accessibilityLabel("Skip silence")

            Button {
                showToolsSheet = true
            } label: {
                Image(systemName: "ellipsis")
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel("Playback tools")
        }
        .foregroundStyle(.white)
        .buttonStyle(.plain)
        .padding(.horizontal, 20)
    }

    // MARK: - Sheets

    private var speedSheet: some View {
        SheetCard(title: "Playback speed") {
            Text(
                "Controls speech and normal playback. Quiet-part acceleration " +
                "is configured separately under Silence skipping."
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)

            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 68), spacing: 8)],
                spacing: 8
            ) {
                ForEach(
                    [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0],
                    id: \.self
                ) { speed in
                    PlayerChip(
                        label: String(format: "%g×", speed),
                        selected: abs(model.playbackSpeed - speed) < 0.01,
                        stretch: true
                    ) {
                        model.setPlaybackSpeed(speed)
                        model.commitPlaybackSpeed()
                    }
                }
            }

            SheetSliderRow(
                label: "Normal playback speed",
                valueText: String(format: "%.2g×", model.playbackSpeed),
                value: Binding(
                    get: { model.playbackSpeed },
                    set: { model.setPlaybackSpeed($0) }
                ),
                range: 0.5...3,
                step: 0.25,
                onCommit: { model.commitPlaybackSpeed() }
            )
        }
    }

    private var silenceSheet: some View {
        SheetCard(title: "Silence skipping") {
            Toggle(
                "Skip silence",
                isOn: Binding(
                    get: { model.silenceEnabled },
                    set: { model.setSilenceEnabled($0) }
                )
            )
            .tint(PlayerColors.accent)

            Picker("Silence behavior", selection: Binding(
                get: { model.silencePlaybackMode },
                set: { model.setSilencePlaybackMode($0) }
            )) {
                ForEach(SilencePlaybackMode.allCases) { mode in
                    Text(mode.label).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .disabled(!model.silenceEnabled)

            Text(model.silencePlaybackMode == .smooth
                ? "Smooth: quiet parts play faster, with continuous video and no jumping."
                : "Jump: cuts analyzed quiet sections immediately. May interrupt video decoding.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if model.silencePlaybackMode == .smooth {
                SectionLabel(text: "Quiet-part speed")

                HStack {
                    Text("Accelerate silence")
                        .font(.subheadline)
                    Spacer()
                    Text(String(format: "%g×", model.silenceAccelerationSpeed))
                        .monospacedDigit()
                        .foregroundStyle(PlayerColors.accent)
                        .font(.subheadline.weight(.semibold))
                }

                Slider(
                    value: Binding(
                        get: { model.silenceAccelerationSpeed },
                        set: { model.setSilenceAccelerationSpeed($0) }
                    ),
                    in: 2...8,
                    step: 0.5
                )
                .tint(PlayerColors.accent)
                .disabled(!model.silenceEnabled)
                .accessibilityLabel("Smooth silence acceleration speed")
                .accessibilityValue(
                    String(format: "%g times", model.silenceAccelerationSpeed)
                )

                HStack {
                    Text("2×")
                    Spacer()
                    Text("8×")
                }
                .font(.caption)
                .foregroundStyle(.secondary)

                if model.silenceAccelerationSpeed > 2 {
                    Text(
                        !model.hasLoadedMedia
                            ? "Open a video to check whether high-speed playback is supported."
                            : (model.highSpeedSilenceAvailable
                                ? "This video supports higher playback speeds. Acceleration slows near speech boundaries; very high rates may drop frames."
                                : "This video's decoder supports up to 2×. Your higher-speed preference is saved for compatible videos.")
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                }
            }

            Toggle(
                "Adaptive noise floor (recommended)",
                isOn: Binding(
                    get: { model.adaptiveSilenceEnabled },
                    set: { model.setAdaptiveSilenceEnabled($0) }
                )
            )
            .tint(PlayerColors.accent)
            .disabled(!model.silenceEnabled)

            Text("Adaptive mode calibrates for each video and protects quiet speech. Aggressive presets may skip more content.")
                .font(.caption)
                .foregroundStyle(.secondary)

            SectionLabel(text: "Preset")

            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 112), spacing: 8)],
                spacing: 8
            ) {
                ForEach(SilencePreset.allCases) { preset in
                    PlayerChip(
                        label: preset.label,
                        selected: model.currentPreset == preset,
                        stretch: true
                    ) {
                        model.applyPreset(preset)
                        showHUD(preset.label)
                    }
                }
            }
            if model.currentPreset == nil {
                Text("Custom silence settings")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            DisclosureGroup(isExpanded: $showAdvancedSilenceSettings) {
                SheetSliderRow(
                    label: "Threshold",
                    valueText: "\(Int(model.silenceThresholdDB.rounded())) dB",
                    value: Binding(
                        get: { model.silenceThresholdDB },
                        set: { model.setSilenceThreshold($0) }
                    ),
                    range: -60...(-20),
                    step: 1,
                    disabled: !model.silenceEnabled,
                    onCommit: { model.applySilenceSettings() }
                )
    
                SheetSliderRow(
                    label: "Min silence",
                    valueText: String(format: "%.2f s", model.minimumSilence),
                    value: Binding(
                        get: { model.minimumSilence },
                        set: { model.setMinimumSilence($0) }
                    ),
                    range: 0.2...2,
                    step: 0.05,
                    disabled: !model.silenceEnabled,
                    onCommit: { model.applySilenceSettings() }
                )
    
                SheetSliderRow(
                    label: "Edge padding",
                    valueText: "\(Int((model.edgePadding * 1000).rounded())) ms",
                    value: Binding(
                        get: { model.edgePadding },
                        set: { model.setEdgePadding($0) }
                    ),
                    range: 0.02...0.20,
                    step: 0.01,
                    disabled: !model.silenceEnabled,
                    onCommit: { model.applySilenceSettings() }
                )
            } label: {
                Text("Advanced tuning")
                    .font(.subheadline.weight(.medium))
            }
            .tint(PlayerColors.accent)
            .disabled(!model.silenceEnabled)

            Text(model.analysisStatus)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
    }

    private var subtitleSheet: some View {
        SheetCard(title: "Subtitles") {
            VStack(alignment: .leading, spacing: 8) {
                Button {
                    showingSubtitleImporter = true
                } label: {
                    Label(
                        model.externalSubtitleName ?? "Import subtitle",
                        systemImage: "captions.bubble"
                    )
                    .lineLimit(2)
                }
                .frame(minHeight: 44)
                .buttonStyle(.bordered)
                .tint(PlayerColors.accent)

                if model.externalSubtitleName != nil {
                    Button("Remove subtitle", role: .destructive) {
                        model.clearExternalSubtitle()
                    }
                    .frame(minHeight: 44)
                    .buttonStyle(.bordered)
                }
            }

            SectionLabel(text: "Embedded subtitles")

            SelectRow(
                label: "Off",
                selected: model.selectedSubtitleIndex == nil &&
                    model.externalSubtitleName == nil
            ) {
                model.selectSubtitle(index: nil)
            }

            ForEach(model.subtitleTrackNames.indices, id: \.self) { index in
                SelectRow(
                    label: model.subtitleTrackNames[index],
                    selected: model.selectedSubtitleIndex == index
                ) {
                    model.selectSubtitle(index: index)
                }
            }

            if model.subtitleTrackNames.isEmpty {
                Text("No embedded subtitle tracks")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if model.externalSubtitleName != nil {
                DisclosureGroup(isExpanded: $showAdvancedSubtitleSettings) {
                SheetSliderRow(
                    label: "Sync",
                    valueText: String(format: "%+.1f s", model.subtitleOffset),
                    value: Binding(
                        get: { model.subtitleOffset },
                        set: { model.setSubtitleOffset($0) }
                    ),
                    range: -10...10,
                    step: 0.05,
                    onCommit: {}
                )
    
                SheetSliderRow(
                    label: "Text size",
                    valueText: String(format: "%.1f×", model.subtitleFontScale),
                    value: Binding(
                        get: { model.subtitleFontScale },
                        set: { model.setSubtitleFontScale($0) }
                    ),
                    range: 0.7...2,
                    onCommit: {}
                )
    
                SheetSliderRow(
                    label: "Background",
                    valueText: "\(Int((model.subtitleBackgroundOpacity * 100).rounded()))%",
                    value: Binding(
                        get: { model.subtitleBackgroundOpacity },
                        set: { model.setSubtitleBackgroundOpacity($0) }
                    ),
                    range: 0...1,
                    onCommit: {}
                )
    
                SectionLabel(text: "Position")
    
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 96), spacing: 8)],
                    spacing: 8
                ) {
                    ForEach(SubtitlePosition.allCases) { position in
                        PlayerChip(
                            label: position.label,
                            selected: model.subtitlePosition == position,
                            stretch: true
                        ) {
                            model.setSubtitlePosition(position)
                        }
                    }
                }
    
                SectionLabel(text: "Search")
    
                HStack(spacing: 10) {
                    TextField("Search subtitle text", text: $subtitleSearch)
                        .textFieldStyle(.roundedBorder)
                    Button("Next") {
                        model.searchSubtitle(subtitleSearch)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.externalSubtitleCueCount == 0)
                }
    
                if let status = model.subtitleSearchStatus {
                    Text(status)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                } label: {
                    Text("Subtitle sync, style & search")
                        .font(.subheadline.weight(.medium))
                }
                .tint(PlayerColors.accent)
            } else {
                Text("Import a subtitle file to adjust sync, text style, position, or search.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

        }
    }

    private var audioSheet: some View {
        SheetCard(title: "Audio") {
            if model.audioTrackNames.isEmpty {
                Text("No audio tracks detected")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.audioTrackNames.indices, id: \.self) { index in
                    SelectRow(
                        label: model.audioTrackNames[index],
                        selected: model.selectedAudioIndex == index
                    ) {
                        model.selectAudio(index: index)
                    }
                }
            }
        }
    }

    private var toolsSheet: some View {
        SheetCard(title: "Playback tools") {
            if compactLandscape || compactPortraitActions {
                HStack(spacing: 12) {
                    Button {
                        openFromTools("speed")
                    } label: {
                        Label("Speed", systemImage: "gauge")
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.bordered)

                    Button {
                        openFromTools("subtitles")
                    } label: {
                        Label("Subtitles", systemImage: "captions.bubble")
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                }

                if narrowCompactLandscape {
                    HStack(spacing: 12) {
                        Button("Seek back \(model.doubleTapSeconds) s") {
                            model.seek(by: -Double(model.doubleTapSeconds))
                            showToolsSheet = false
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .buttonStyle(.bordered)

                        Button("Seek forward \(model.doubleTapSeconds) s") {
                            model.seek(by: Double(model.doubleTapSeconds))
                            showToolsSheet = false
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .buttonStyle(.bordered)
                    }
                }
            }
            Button {
                openFromTools("markers")
            } label: {
                Label("Markers · Chapters, bookmarks and A–B", systemImage: "bookmark")
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)

            Button {
                openFromTools("audio")
            } label: {
                Label("Audio tracks", systemImage: "speaker.wave.2")
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)

            SectionLabel(text: "Sleep timer")

            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 100), spacing: 8)],
                spacing: 8
            ) {
                ForEach([15, 30, 45, 60], id: \.self) { minutes in
                    PlayerChip(
                        label: "\(minutes) min",
                        selected: model.sleepTimerMinutes == minutes,
                        stretch: true
                    ) {
                        model.setSleepTimer(minutes: Double(minutes))
                    }
                }
                PlayerChip(
                    label: "At end of video",
                    selected: model.sleepAtEnd,
                    stretch: true
                ) {
                    model.setSleepAtEnd()
                }
            }
            if model.sleepDeadline != nil || model.sleepAtEnd {
                Text(model.sleepTimerLabel)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Cancel sleep timer", role: .destructive) {
                    model.cancelSleepTimer()
                }
                .frame(minHeight: 44)
                .buttonStyle(.bordered)
            }

            SectionLabel(text: "Playback gestures")
            Text(
                "Double-tap left or right to seek. Swipe vertically on the left " +
                "for brightness, or the right for volume. Swipe horizontally " +
                "to scrub. Lock controls from More options."
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var markersSheet: some View {
        SheetCard(title: "Markers") {
            SectionLabel(text: "A–B repeat")

            HStack(spacing: 10) {
                Button(
                    model.abStart.map { "A " + PlayerModel.format(seconds: $0) } ?? "Set A"
                ) {
                    model.setA()
                }
                .frame(maxWidth: .infinity, minHeight: 44)
                .buttonStyle(.bordered)

                Button(
                    model.abEnd.map { "B " + PlayerModel.format(seconds: $0) } ?? "Set B"
                ) {
                    model.setB()
                }
                .frame(maxWidth: .infinity, minHeight: 44)
                .buttonStyle(.bordered)
                .disabled(model.abStart == nil)
            }
            Button("Clear A–B", role: .destructive) {
                model.clearAB()
            }
            .frame(minHeight: 44)
            .buttonStyle(.bordered)
            .disabled(model.abStart == nil && model.abEnd == nil)

            SectionLabel(text: "Bookmarks")

            Button {
                model.addBookmark()
            } label: {
                Label("Add bookmark", systemImage: "bookmark")
            }
            .buttonStyle(.bordered)

            if model.bookmarks.isEmpty {
                Text("No bookmarks yet")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.bookmarks) { bookmark in
                    HStack(spacing: 10) {
                        Button {
                            model.seek(to: bookmark.seconds)
                            showMarkersSheet = false
                            showControls()
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "bookmark")
                                    .font(.caption)
                                    .foregroundStyle(PlayerColors.accent)
                                Text(bookmark.label)
                                    .foregroundStyle(.white)
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        Button {
                            model.removeBookmark(bookmark)
                        } label: {
                            Image(systemName: "minus.circle")
                                .foregroundStyle(.secondary)
                                .frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Remove bookmark \(bookmark.label)")
                    }
                    .padding(.vertical, 6)
                }

                Button("Clear bookmarks", role: .destructive) {
                    requestConfirmationFromSheet("bookmarks", dismiss: "markers")
                }
                .buttonStyle(.bordered)
            }

            SectionLabel(text: "Chapters")

            if model.chapters.isEmpty {
                Text("No chapters detected")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.chapters) { chapter in
                    Button {
                        model.seek(to: chapter.start)
                        showMarkersSheet = false
                        showControls()
                    } label: {
                        HStack(spacing: 10) {
                            Text(PlayerModel.format(seconds: chapter.start))
                                .monospacedDigit()
                                .font(.caption)
                                .foregroundStyle(PlayerColors.accent)
                            Text(chapter.title)
                                .foregroundStyle(.white)
                                .lineLimit(2)
                            Spacer()
                        }
                        .frame(minHeight: 44)
                        .padding(.vertical, 5)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

        }
    }

    private var settingsPage: some View {
        NavigationStack {
            Form {
                Section("Video display") {
                    Picker("Aspect ratio", selection: Binding(
                        get: { model.aspectMode },
                        set: { model.setAspectMode($0) }
                    )) {
                        ForEach(VideoAspectMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    Toggle("Audio-only playback", isOn: Binding(
                        get: { model.audioOnly },
                        set: { model.setAudioOnly($0) }
                    ))
                }
                Section("Controls") {
                    Picker("Double-tap seek", selection: Binding(
                        get: { model.doubleTapSeconds },
                        set: { model.setDoubleTapSeconds($0) }
                    )) {
                        ForEach([5, 10, 15, 30], id: \.self) { seconds in
                            Text("\(seconds) seconds").tag(seconds)
                        }
                    }
                    Picker("Resume playback", selection: Binding(
                        get: { model.resumeMode },
                        set: { model.setResumeMode($0) }
                    )) {
                        ForEach(ResumeMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                }
                Section("Data and privacy") {
                    Button("Clear viewing history", role: .destructive) {
                        requestConfirmationFromSheet("history", dismiss: "settings")
                    }
                    Button("Reset player settings", role: .destructive) {
                        requestConfirmationFromSheet("settings", dismiss: "settings")
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { showSettingsPage = false }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var playlistSheet: some View {
        SheetCard(title: "Up Next") {
            if model.playlist.isEmpty {
                Text("No videos loaded")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.playlist.indices, id: \.self) { index in
                    let entry = model.playlist[index]
                    Button {
                        if index == model.playlistIndex {
                            model.play()
                        } else {
                            model.jumpToPlaylistIndex(index)
                        }
                        showingLibrary = false
                        showPlaylistSheet = false
                        showControls()
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: index == model.playlistIndex ? "play.fill" : "play")
                                .font(.caption)
                                .foregroundStyle(
                                    index == model.playlistIndex
                                        ? PlayerColors.accent
                                        : .secondary
                                )
                            Text(entry.name)
                                .foregroundStyle(
                                    index == model.playlistIndex
                                        ? PlayerColors.accent
                                        : .white
                                )
                                .lineLimit(2)
                            Spacer()
                            if index == model.playlistIndex {
                                Text("Now playing")
                                    .font(.caption)
                                    .foregroundStyle(PlayerColors.accent)
                            }
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(
                        index == model.playlistIndex ? .isSelected : []
                    )
                }
            }
        }
    }

    private var helpSheet: some View {
        SheetCard(title: "Player guide") {
            Text("Play local videos and skip quiet sections without uploading audio.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            SectionLabel(text: "Touch gestures")
            Label("Double-tap the left or right half to seek backward or forward.", systemImage: "hand.tap")
            Label("Swipe horizontally to scrub; swipe vertically on the left for brightness or right for volume.", systemImage: "hand.draw")
            Label("Pinch to zoom. Use Lock controls under More to prevent accidental gestures.", systemImage: "lock")

            SectionLabel(text: "Silence skipping")
            Text("Balanced with adaptive noise floor is a good starting point. Analysis finds sustained quiet passages, not individual spoken words.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            SectionLabel(text: "Playback tools")
            Text("Speed, Subtitles and Tools provide playback rate, captions, bookmarks, A–B repeat and the sleep timer.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var recentSheet: some View {
        SheetCard(title: "Recent") {
            if let error = model.playbackError, error.hasPrefix("Could not reopen") {
                Text(error)
                    .font(.subheadline)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Locate file…") {
                    locateFileAfterRecentDismiss = true
                    model.dismissPlaybackError()
                    showRecentSheet = false
                }
                .buttonStyle(.borderedProminent)
            }
            if model.recentVideos.isEmpty {
                Text("No recent videos")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.recentVideos) { recent in
                    Button {
                        if model.openRecent(recent) {
                            showingLibrary = false
                            showRecentSheet = false
                            showControls()
                        }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "clock.arrow.circlepath")
                                .foregroundStyle(.tertiary)
                            Text(recent.name)
                                .foregroundStyle(.white)
                                .lineLimit(2)
                            Spacer()
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                Button("Clear history", role: .destructive) {
                    requestConfirmationFromSheet("history", dismiss: "recent")
                }
                .buttonStyle(.bordered)
                .padding(.top, 8)
            }
        }
    }

    // MARK: - Helpers

    private func requestConfirmationFromSheet(_ action: String, dismiss sheet: String) {
        pendingDestructiveAction = action
        confirmationQueuedAfterSheetDismiss = true
        switch sheet {
        case "tools": showToolsSheet = false
        case "markers": showMarkersSheet = false
        case "settings": showSettingsPage = false
        default: showRecentSheet = false
        }
    }

    private func openFromTools(_ destination: String) {
        toolsDestinationAfterDismiss = destination
        showToolsSheet = false
    }

    private func handleRecentDismiss() {
        presentQueuedConfirmation()
        guard locateFileAfterRecentDismiss else { return }
        locateFileAfterRecentDismiss = false
        showingVideoImporter = true
    }

    private func handleToolsDismiss() {
        presentQueuedConfirmation()
        guard let destination = toolsDestinationAfterDismiss else { return }
        toolsDestinationAfterDismiss = nil
        switch destination {
        case "markers": showMarkersSheet = true
        case "queue": showPlaylistSheet = true
        case "audio": showAudioSheet = true
        case "speed": showSpeedSheet = true
        case "subtitles": showSubtitleSheet = true
        case "settings": showSettingsPage = true
        default: break
        }
    }

    private func presentQueuedConfirmation() {
        guard confirmationQueuedAfterSheetDismiss else { return }
        confirmationQueuedAfterSheetDismiss = false
        showingDestructiveConfirmation = true
    }

    private func playerIconButton(
        _ systemImage: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .medium))
                .frame(width: 44, height: 44)
                .background(Circle().fill(Color.white.opacity(0.12)))
        }
        .accessibilityLabel(label)
        .buttonStyle(.plain)
        .foregroundStyle(.white)
    }

    private func setFullscreen(_ enabled: Bool) {
        isFullscreen = enabled
        showControls()

        guard
            let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first
        else { return }

        let orientations: UIInterfaceOrientationMask =
            enabled ? .landscape : .allButUpsideDown

        scene.requestGeometryUpdate(
            .iOS(interfaceOrientations: orientations)
        ) { _ in }
    }

    private func showControls() {
        controlsVisible = true
        scheduleControlsHide()
    }

    private func scheduleControlsHide() {
        hideGeneration += 1
        let generation = hideGeneration

        guard model.isPlaying, !gesturesLocked, !playbackOverlayOpen else {
            return
        }

        let delay = isFullscreen ? 2.5 : 4.0
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            if generation == hideGeneration && model.isPlaying &&
                !playbackOverlayOpen && !gesturesLocked {
                controlsVisible = false
            }
        }
    }

    private func showHUD(_ text: String) {
        gestureHUD = text
        dismissHUDLater()
    }

    private func dismissHUDLater() {
        let expected = gestureHUD
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            if gestureHUD == expected {
                gestureHUD = nil
            }
        }
    }
}

// MARK: - Reusable components

private struct PlayerViewportWidthKey: PreferenceKey {
    static var defaultValue: CGFloat { 0 }

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct PlayerBottomControlsHeightKey: PreferenceKey {
    static var defaultValue: CGFloat { 0 }

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct PlayerChip: View {
    let label: String
    var systemImage: String? = nil
    var selected: Bool = false
    var stretch: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 13))
                }
                Text(label)
                    .font(.caption)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: stretch ? .infinity : nil, minHeight: 44)
            .padding(.horizontal, 8)
            .background(
                Capsule().fill(
                    selected ? PlayerColors.accent.opacity(0.22) : Color.white.opacity(0.12)
                )
            )
            .overlay(
                Capsule().strokeBorder(
                    selected ? PlayerColors.accent.opacity(0.7) : Color.white.opacity(0.06)
                )
            )
            .foregroundStyle(selected ? PlayerColors.accent : .white)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct SeekBar: View {
    let position: Double
    let duration: Double
    let bookmarkSeconds: [Double]
    let silenceRanges: [SilenceRange]
    let onSeek: (Double) -> Void

    @State private var scrubFraction: Double?

    private var fraction: Double {
        if let scrubFraction {
            return min(max(scrubFraction, 0), 1)
        }
        guard duration > 0 else { return 0 }
        return min(max(position / duration, 0), 1)
    }

    var body: some View {
        GeometryReader { geo in
            let width = max(geo.size.width, 1)
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.white.opacity(0.28))
                    .frame(height: 4)

                if duration > 0 {
                    // A long recording may have thousands of quiet sections.
                    // Drawing them in one Canvas avoids creating thousands of
                    // SwiftUI nodes on every playback-position update.
                    Canvas { context, _ in
                        var silencePath = Path()
                        for range in silenceRanges {
                            let start = min(max(range.start / duration, 0), 1)
                            let end = min(max(range.end / duration, start), 1)
                            guard end > start else { continue }
                            silencePath.addRoundedRect(
                                in: CGRect(
                                    x: width * start,
                                    y: 2,
                                    width: max(width * (end - start), 1),
                                    height: 8
                                ),
                                cornerSize: CGSize(width: 2, height: 2)
                            )
                        }
                        context.fill(
                            silencePath,
                            with: .color(.white.opacity(0.42))
                        )
                    }
                    .frame(height: 12)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }

                Capsule()
                    .fill(PlayerColors.accent)
                    .frame(width: max(width * fraction, 4), height: 4)

                ForEach(bookmarkSeconds.indices, id: \.self) { index in
                    let marker = min(max(bookmarkSeconds[index] / max(duration, 1), 0), 1)
                    Circle()
                        .fill(Color.white.opacity(0.9))
                        .frame(width: 5, height: 5)
                        .offset(x: min(max(width * marker - 2.5, 0), max(width - 5, 0)))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Circle()
                    .fill(PlayerColors.accent)
                    .frame(width: 12, height: 12)
                    .overlay(
                        Circle().fill(Color.white)
                            .frame(width: 4, height: 4)
                    )
                    .offset(x: min(max(width * fraction - 6, 0), max(width - 12, 0)))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard duration > 0 else { return }
                        scrubFraction = min(max(value.location.x / width, 0), 1)
                    }
                    .onEnded { value in
                        if duration > 0 {
                            let next = min(max(value.location.x / width, 0), 1)
                            onSeek(next * duration)
                        }
                        scrubFraction = nil
                    }
            )
        }
        .frame(height: 48)
        .accessibilityElement(children: .ignore)
        .accessibilityAdjustableAction { direction in
            let step = max(duration / 100, 5)
            switch direction {
            case .increment: onSeek(min(duration, position + step))
            case .decrement: onSeek(max(0, position - step))
            @unknown default: break
            }
        }
        .accessibilityLabel("Playback position and detected silence")
        .accessibilityValue(
            PlayerModel.format(seconds: position) +
                " of " + PlayerModel.format(seconds: duration)
        )
    }
}

private struct SheetCard<Content: View>: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    @ViewBuilder let content: () -> Content

    init(
        title: String,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.content = content
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    Text(title)
                        .font(.headline)
                    Spacer()
                    Button("Done") {
                        dismiss()
                    }
                    .frame(minHeight: 44)
                    .buttonStyle(.bordered)
                    .tint(PlayerColors.accent)
                }
                .padding(.top, 6)
                .padding(.bottom, 2)
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 22)
            .padding(.bottom, 30)
        }
        .background(PlayerColors.sheet)
        .presentationDetents([.medium, .large])
        .presentationBackground(PlayerColors.sheet)
    }
}

private struct SectionLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(PlayerColors.accent)
            .padding(.top, 10)
            .padding(.bottom, 2)
    }
}

private struct SelectRow: View {
    let label: String
    var selected: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 15))
                    .foregroundStyle(
                        selected ? PlayerColors.accent : Color.white.opacity(0.35)
                    )
                Text(label)
                    .foregroundStyle(selected ? PlayerColors.accent : .white)
                    .lineLimit(2)
                Spacer()
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct SheetSliderRow: View {
    let label: String
    let valueText: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double? = nil
    var disabled: Bool = false
    let onCommit: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.85))
                Spacer()
                Text(valueText)
                    .font(.caption)
                    .foregroundStyle(PlayerColors.accent)
                    .monospacedDigit()
            }
            if let step {
                Slider(
                    value: $value,
                    in: range,
                    step: step,
                    onEditingChanged: finishSliderEditing
                )
                .disabled(disabled)
            } else {
                Slider(
                    value: $value,
                    in: range,
                    onEditingChanged: finishSliderEditing
                )
                .disabled(disabled)
            }
        }
        .padding(.vertical, 2)
    }

    private func finishSliderEditing(_ editing: Bool) {
        if !editing {
            onCommit()
        }
    }
}
