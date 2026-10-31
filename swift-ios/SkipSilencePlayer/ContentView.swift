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
    @StateObject private var model = PlayerModel()
    @StateObject private var pipManager = PictureInPictureManager()

    @State private var showingVideoImporter = false
    @State private var showingFolderImporter = false
    @State private var showingSubtitleImporter = false
    @State private var dragControl: DragControl?
    @State private var dragStartValue = 0.0
    @State private var dragStartTime = 0.0
    @State private var gestureHUD: String?
    @State private var controlsVisible = true
    @State private var isFullscreen = false
    @State private var gesturesLocked = false
    @State private var hideGeneration = 0
    @State private var subtitleSearch = ""
    @State private var pinchStartZoom = 1.0

    @State private var showSpeedSheet = false
    @State private var showSilenceSheet = false
    @State private var showSubtitleSheet = false
    @State private var showAudioSheet = false
    @State private var showToolsSheet = false
    @State private var showPlaylistSheet = false
    @State private var showRecentSheet = false

    private var hasVideo: Bool {
        model.fileName != "No video selected"
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            videoArea

            if !hasVideo {
                homeScreen
            } else if controlsVisible && !gesturesLocked {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    bottomCluster
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if gesturesLocked {
                Button {
                    gesturesLocked = false
                    showControls()
                } label: {
                    Label("Unlock", systemImage: "lock.open")
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
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
                VStack(spacing: 10) {
                    Text("Playback error")
                        .font(.headline)
                    Text(error)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                    HStack {
                        Button("Dismiss") {
                            model.dismissPlaybackError()
                        }
                        .buttonStyle(.bordered)

                        Button("Retry") {
                            model.retryPlayback()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .padding(18)
                .background(.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 14))
                .padding(24)
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(isFullscreen)
        .onOpenURL { url in
            model.openExternalURL(url)
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
                    model.requestFolder(url: url)
                    showControls()
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
        .sheet(isPresented: $showToolsSheet) {
            toolsSheet
        }
        .sheet(isPresented: $showPlaylistSheet) {
            playlistSheet
        }
        .sheet(isPresented: $showRecentSheet) {
            recentSheet
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
        .onChange(of: model.isPlaying) { _, playing in
            if playing {
                scheduleControlsHide()
            } else {
                controlsVisible = true
                hideGeneration += 1
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

                if !gesturesLocked {
                    Color.clear
                        .contentShape(Rectangle())
                        .simultaneousGesture(
                            SpatialTapGesture(count: 1)
                                .onEnded { _ in
                                    if controlsVisible {
                                        controlsVisible = false
                                        hideGeneration += 1
                                    } else {
                                        showControls()
                                    }
                                }
                        )
                        .simultaneousGesture(
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
                                        let target = max(0, dragStartTime + delta)
                                        gestureHUD =
                                            (delta >= 0 ? "Seek +" : "Seek ") +
                                            String(Int(delta.rounded())) + " s"
                                        model.seek(to: target)
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

                subtitleOverlay

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
    private var subtitleOverlay: some View {
        if !model.externalSubtitleText.isEmpty {
            VStack {
                if model.subtitlePosition == .center {
                    Spacer()
                } else if model.subtitlePosition == .bottom {
                    Spacer()
                    Spacer()
                }

                Text(model.externalSubtitleText)
                    .font(.system(size: 18 * model.subtitleFontScale, weight: .semibold))
                    .multilineTextAlignment(.center)
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
            .padding(.vertical, controlsVisible ? 170 : 28)
            .allowsHitTesting(false)
        }
    }

    // MARK: - Home screen

    private var homeScreen: some View {
        VStack(spacing: 0) {
            Spacer()

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

            Text("Plays only what matters")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.top, 3)

            HStack(spacing: 12) {
                HomeCard(label: "Open video", systemImage: "play.rectangle") {
                    showingVideoImporter = true
                }
                HomeCard(label: "Open folder", systemImage: "folder") {
                    showingFolderImporter = true
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 30)

            if !model.recentVideos.isEmpty {
                HStack {
                    Text("Recent")
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

                VStack(spacing: 2) {
                    ForEach(model.recentVideos.prefix(4)) { recent in
                        Button {
                            model.openRecent(recent)
                            showControls()
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "clock.arrow.circlepath")
                                    .foregroundStyle(.tertiary)
                                Text(recent.name)
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                Spacer()
                            }
                            .padding(.horizontal, 26)
                            .padding(.vertical, 9)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Text(
                "Double-tap to seek · swipe for brightness and volume"
            )
            .font(.caption2)
            .foregroundStyle(.tertiary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 40)
            .padding(.top, 26)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.fileName)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)

                HStack(spacing: 6) {
                    if !model.fileInfo.isEmpty {
                        Text(model.fileInfo)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Text(model.analysisStatus)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 12)

            HStack(spacing: 10) {
                playerIconButton("lock.fill") {
                    gesturesLocked = true
                    controlsVisible = false
                }

                if PictureInPictureManager.isSupported {
                    playerIconButton("pip.enter") {
                        pipManager.toggle()
                    }
                    .disabled(!hasVideo)
                }

                playerIconButton(
                    isFullscreen
                        ? "arrow.down.right.and.arrow.up.left"
                        : "arrow.up.left.and.arrow.down.right"
                ) {
                    setFullscreen(!isFullscreen)
                }

                Menu {
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
                    Divider()
                    Menu {
                        ForEach(VideoAspectMode.allCases) { mode in
                            Button(mode.label) {
                                model.setAspectMode(mode)
                            }
                        }
                    } label: {
                        Text("Aspect: " + model.aspectMode.label)
                    }
                    Menu {
                        ForEach([5, 10, 15, 30], id: \.self) { seconds in
                            Button("\(seconds) s") {
                                model.setDoubleTapSeconds(seconds)
                            }
                        }
                    } label: {
                        Text("Double-tap seek: \(model.doubleTapSeconds) s")
                    }
                    Menu {
                        ForEach(ResumeMode.allCases) { mode in
                            Button(mode.label) {
                                model.setResumeMode(mode)
                            }
                        }
                    } label: {
                        Text("Resume: " + model.resumeMode.label)
                    }
                    Toggle(
                        isOn: Binding(
                            get: { model.audioOnly },
                            set: { model.setAudioOnly($0) }
                        )
                    ) {
                        Label("Audio only", systemImage: "waveform")
                    }
                    Divider()
                    Button("Clear history", role: .destructive) {
                        model.clearHistory()
                    }
                    Button("Reset settings", role: .destructive) {
                        model.resetSettings()
                        showHUD("Defaults restored")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 15, weight: .medium))
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.white.opacity(0.12)))
                }
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
                if model.silenceEnabled {
                    Text(
                        "Saved " + PlayerModel.format(seconds: model.skippedTime) +
                            " · est. " +
                            PlayerModel.format(seconds: model.estimatedWatchTime) +
                            " watch"
                    )
                    .font(.caption2)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 3)
                    .background(
                        Capsule().fill(PlayerColors.accent.opacity(0.16))
                    )
                    .overlay(
                        Capsule().strokeBorder(PlayerColors.accent.opacity(0.35))
                    )
                    .foregroundStyle(PlayerColors.accent)
                    .lineLimit(1)
                }
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
                bookmarkSeconds: model.bookmarks.map(\.seconds)
            ) { target in
                model.seek(to: target)
            }
            .padding(.horizontal, 20)

            HStack(spacing: 36) {
                Button {
                    model.previous()
                } label: {
                    Image(systemName: "backward.end.fill")
                        .font(.title3)
                }
                .disabled(!model.hasPrevious)
                .foregroundStyle(.white)

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
                }
                .foregroundStyle(.white)

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
                }
                .foregroundStyle(.white)

                Button {
                    model.next()
                } label: {
                    Image(systemName: "forward.end.fill")
                        .font(.title3)
                }
                .disabled(!model.hasNext)
                .foregroundStyle(.white)
            }
            .buttonStyle(.plain)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    PlayerChip(
                        label: String(format: "%g×", model.playbackSpeed),
                        systemImage: "gauge",
                        selected: abs(model.playbackSpeed - 1) > 0.01
                    ) {
                        showSpeedSheet = true
                    }

                    PlayerChip(
                        label: model.silenceEnabled ? "Silence on" : "Silence off",
                        systemImage: "waveform",
                        selected: model.silenceEnabled
                    ) {
                        let next = !model.silenceEnabled
                        model.setSilenceEnabled(next)
                        showHUD(next ? "Silence on" : "Silence off")
                    }

                    PlayerChip(
                        label: "Subtitles",
                        systemImage: "captions.bubble",
                        selected: model.selectedSubtitleIndex != nil ||
                            model.externalSubtitleName != nil
                    ) {
                        showSubtitleSheet = true
                    }

                    PlayerChip(
                        label: model.selectedAudioName,
                        systemImage: "speaker.wave.2",
                        selected: model.selectedAudioIndex != nil
                    ) {
                        showAudioSheet = true
                    }

                    PlayerChip(
                        label: "Tools",
                        systemImage: "wrench.and.screwdriver"
                    ) {
                        showToolsSheet = true
                    }

                    if model.playlist.count > 1 {
                        PlayerChip(
                            label: "\(model.playlistIndex + 1)/\(model.playlist.count)",
                            systemImage: "list.number"
                        ) {
                            showPlaylistSheet = true
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
        }
        .padding(.top, 26)
        .padding(.bottom, 16)
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.9)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .bottom)
        )
    }

    // MARK: - Sheets

    private var speedSheet: some View {
        SheetCard(title: "Speed") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(
                        [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0],
                        id: \.self
                    ) { speed in
                        PlayerChip(
                            label: String(format: "%g×", speed),
                            selected: abs(model.playbackSpeed - speed) < 0.01
                        ) {
                            model.setPlaybackSpeed(speed)
                            model.commitPlaybackSpeed()
                        }
                    }
                }
            }

            SheetSliderRow(
                label: "Speed",
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

            SectionLabel(text: "Preset")

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SilencePreset.allCases) { preset in
                        PlayerChip(
                            label: preset.label,
                            selected: model.currentPreset == preset
                        ) {
                            model.applyPreset(preset)
                            showHUD(preset.label)
                        }
                    }
                    PlayerChip(
                        label: "Custom",
                        selected: model.currentPreset == nil
                    ) {
                        showControls()
                    }
                    .disabled(true)
                }
            }

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

            Text(model.analysisStatus)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
    }

    private var subtitleSheet: some View {
        SheetCard(title: "Subtitles") {
            HStack(spacing: 10) {
                Button {
                    showingSubtitleImporter = true
                } label: {
                    Label(
                        model.externalSubtitleName ?? "Import subtitle",
                        systemImage: "captions.bubble"
                    )
                    .lineLimit(1)
                }
                .buttonStyle(.bordered)
                .tint(PlayerColors.accent)

                if model.externalSubtitleName != nil {
                    Button("Remove", role: .destructive) {
                        model.clearExternalSubtitle()
                    }
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

            SectionLabel(text: "Sync & style")

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

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SubtitlePosition.allCases) { position in
                        PlayerChip(
                            label: position.label,
                            selected: model.subtitlePosition == position
                        ) {
                            model.setSubtitlePosition(position)
                        }
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
        SheetCard(title: "Tools") {
            SectionLabel(text: "A–B repeat")

            HStack(spacing: 10) {
                Button(
                    model.abStart.map { "A " + PlayerModel.format(seconds: $0) } ?? "Set A"
                ) {
                    model.setA()
                }
                .buttonStyle(.bordered)

                Button(
                    model.abEnd.map { "B " + PlayerModel.format(seconds: $0) } ?? "Set B"
                ) {
                    model.setB()
                }
                .buttonStyle(.bordered)
                .disabled(model.abStart == nil)

                Button("Clear A–B", role: .destructive) {
                    model.clearAB()
                }
                .buttonStyle(.bordered)
                .disabled(model.abStart == nil && model.abEnd == nil)
            }

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
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.vertical, 6)
                }

                Button("Clear bookmarks", role: .destructive) {
                    model.clearBookmarks()
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
                        showControls()
                    } label: {
                        HStack(spacing: 10) {
                            Text(PlayerModel.format(seconds: chapter.start))
                                .monospacedDigit()
                                .font(.caption)
                                .foregroundStyle(PlayerColors.accent)
                            Text(chapter.title)
                                .foregroundStyle(.white)
                                .lineLimit(1)
                            Spacer()
                        }
                        .padding(.vertical, 7)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            SectionLabel(text: "Sleep timer")

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach([15, 30, 45, 60], id: \.self) { minutes in
                        PlayerChip(
                            label: "\(minutes) min",
                            selected: model.sleepDeadline != nil
                        ) {
                            model.setSleepTimer(minutes: Double(minutes))
                        }
                    }
                    PlayerChip(
                        label: "At end of video",
                        selected: model.sleepAtEnd
                    ) {
                        model.setSleepAtEnd()
                    }
                    if model.sleepDeadline != nil || model.sleepAtEnd {
                        PlayerChip(label: model.sleepTimerLabel) {
                            model.cancelSleepTimer()
                        }
                    }
                }
            }

            SectionLabel(text: "Data")

            HStack(spacing: 10) {
                Button("Clear history", role: .destructive) {
                    model.clearHistory()
                }
                .buttonStyle(.bordered)

                Button("Reset settings", role: .destructive) {
                    model.resetSettings()
                    showHUD("Defaults restored")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var playlistSheet: some View {
        SheetCard(title: "Playlist") {
            if model.playlist.isEmpty {
                Text("No videos loaded")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.playlist.indices, id: \.self) { index in
                    let entry = model.playlist[index]
                    Button {
                        model.jumpToPlaylistIndex(index)
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
                                .lineLimit(1)
                            Spacer()
                            if index == model.playlistIndex {
                                Text("Now")
                                    .font(.caption2)
                                    .foregroundStyle(PlayerColors.accent)
                            }
                        }
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var recentSheet: some View {
        SheetCard(title: "Recent") {
            if model.recentVideos.isEmpty {
                Text("No recent videos")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.recentVideos) { recent in
                    Button {
                        model.openRecent(recent)
                        showControls()
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "clock.arrow.circlepath")
                                .foregroundStyle(.tertiary)
                            Text(recent.name)
                                .foregroundStyle(.white)
                                .lineLimit(1)
                            Spacer()
                        }
                        .padding(.vertical, 9)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                Button("Clear history", role: .destructive) {
                    model.clearHistory()
                }
                .buttonStyle(.bordered)
                .padding(.top, 8)
            }
        }
    }

    // MARK: - Helpers

    private func playerIconButton(
        _ systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .medium))
                .frame(width: 34, height: 34)
                .background(Circle().fill(Color.white.opacity(0.12)))
        }
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

        guard model.isPlaying, !gesturesLocked else { return }

        let delay = isFullscreen ? 2.5 : 4.0
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            if generation == hideGeneration && model.isPlaying {
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

private struct HomeCard: View {
    let label: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.title2)
                    .foregroundStyle(PlayerColors.accent)
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(.white)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
            .background(
                RoundedRectangle(cornerRadius: 18)
                    .fill(Color.white.opacity(0.08))
            )
        }
        .buttonStyle(.plain)
    }
}

private struct PlayerChip: View {
    let label: String
    var systemImage: String? = nil
    var selected: Bool = false
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
                    .lineLimit(1)
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 8)
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
    }
}

private struct SeekBar: View {
    let position: Double
    let duration: Double
    let bookmarkSeconds: [Double]
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

                Capsule()
                    .fill(PlayerColors.accent)
                    .frame(width: max(width * fraction, 4), height: 4)

                ForEach(bookmarkSeconds.indices, id: \.self) { index in
                    let marker = min(max(bookmarkSeconds[index] / max(duration, 1), 0), 1)
                    Circle()
                        .fill(Color.white.opacity(0.9))
                        .frame(width: 5, height: 5)
                        .offset(x: width * marker - 2.5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Circle()
                    .fill(PlayerColors.accent)
                    .frame(width: 12, height: 12)
                    .overlay(
                        Circle().fill(Color.white)
                            .frame(width: 4, height: 4)
                    )
                    .offset(x: width * fraction - 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard duration > 0 else { return }
                        let next = min(max(value.location.x / width, 0), 1)
                        scrubFraction = next
                        onSeek(next * duration)
                    }
                    .onEnded { _ in
                        scrubFraction = nil
                    }
            )
        }
        .frame(height: 26)
    }
}

private struct SheetCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    init(
        _ title: String,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.content = content
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(title)
                    .font(.headline)
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
                    .lineLimit(1)
                Spacer()
            }
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
            Slider(
                value: $value,
                in: range,
                step: step,
                onEditingChanged: { editing in
                    if !editing {
                        onCommit()
                    }
                }
            )
            .disabled(disabled)
        }
        .padding(.vertical, 2)
    }
}
