import SwiftUI
import UniformTypeIdentifiers
import UIKit

private enum DragControl {
    case brightness
    case volume
    case seek
}

struct ContentView: View {
    @StateObject private var model = PlayerModel()
    @StateObject private var pipManager = PictureInPictureManager()

    @State private var showingVideoImporter = false
    @State private var showingPlaylistImporter = false
    @State private var showingFolderImporter = false
    @State private var showingSubtitleImporter = false

    @State private var dragControl: DragControl?
    @State private var dragStartValue = 0.0
    @State private var dragStartTime = 0.0
    @State private var pendingSeekDelta = 0.0
    @State private var gestureHUD: String?
    @State private var controlsVisible = true
    @State private var advancedExpanded = false
    @State private var isFullscreen = false
    @State private var gestureLocked = false
    @State private var hideGeneration = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            videoArea

            if controlsVisible && !pipManager.isActive {
                controls
                    .frame(maxHeight: isFullscreen ? 400 : 600)
                    .frame(maxWidth: .infinity)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }

            if let error = model.playbackError, !pipManager.isActive {
                playbackErrorOverlay(error)
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(isFullscreen)
        .fileImporter(
            isPresented: $showingVideoImporter,
            allowedContentTypes: [.movie, .video, .audiovisualContent],
            allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let url = urls.first {
                model.requestOpen(urls: [url])
                showControls()
            }
        }
        .fileImporter(
            isPresented: $showingPlaylistImporter,
            allowedContentTypes: [.movie, .video, .audiovisualContent],
            allowsMultipleSelection: true
        ) { result in
            if case .success(let urls) = result, !urls.isEmpty {
                model.requestOpen(urls: urls)
                showControls()
            }
        }
        .fileImporter(
            isPresented: $showingFolderImporter,
            allowedContentTypes: [.folder],
            allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let url = urls.first {
                model.requestOpenFolder(url)
                showControls()
            }
        }
        .fileImporter(
            isPresented: $showingSubtitleImporter,
            allowedContentTypes: [.plainText, .text],
            allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let url = urls.first {
                model.loadExternalSubtitle(url: url)
                showControls()
            }
        }
        .onOpenURL { url in
            model.requestOpen(urls: [url])
            showControls()
        }
        .alert(
            "Resume playback?",
            isPresented: Binding(
                get: { model.pendingResumeSeconds != nil },
                set: { newValue in
                    if !newValue {
                        model.confirmPendingResume(false)
                    }
                }
            )
        ) {
            Button("Restart") {
                model.confirmPendingResume(false)
            }
            Button("Resume") {
                model.confirmPendingResume(true)
            }
        } message: {
            if let seconds = model.pendingResumeSeconds {
                Text("Continue from \(PlayerModel.format(seconds: seconds))?")
            }
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

    private var videoArea: some View {
        GeometryReader { geometry in
            ZStack {
                if model.audioOnly {
                    VStack(spacing: 12) {
                        Image(systemName: "waveform")
                            .font(.system(size: 54))
                        Text("Audio-only mode")
                            .font(.headline)
                        Text(model.fileName)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    .foregroundStyle(.white)
                } else {
                    VideoSurface(
                        player: model.player,
                        pictureInPictureManager: pipManager,
                        videoGravity: model.videoGravityMode.gravity,
                        zoomScale: model.videoZoom
                    )
                    .background(Color.black)
                }

                if model.fileName == "No video selected" {
                    VStack(spacing: 10) {
                        Button {
                            showingVideoImporter = true
                        } label: {
                            Label("Open video", systemImage: "folder")
                                .padding(.horizontal, 10)
                        }
                        .buttonStyle(.borderedProminent)

                        HStack {
                            Button("Open playlist") {
                                showingPlaylistImporter = true
                            }
                            .buttonStyle(.bordered)

                            Button("Open folder") {
                                showingFolderImporter = true
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }

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
                                guard !gestureLocked else {
                                    showControls()
                                    return
                                }

                                let seconds = Double(model.doubleTapSeconds)
                                if event.location.x < geometry.size.width / 2 {
                                    model.seek(by: -seconds)
                                    showHUD("−\(model.doubleTapSeconds) s")
                                } else {
                                    model.seek(by: seconds)
                                    showHUD("+\(model.doubleTapSeconds) s")
                                }
                                showControls()
                            }
                    )
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 12)
                            .onChanged { value in
                                guard !gestureLocked else {
                                    return
                                }

                                if dragControl == nil {
                                    let horizontal = abs(value.translation.width)
                                    let vertical = abs(value.translation.height)

                                    if horizontal > vertical {
                                        dragControl = .seek
                                        dragStartTime = model.currentTime
                                        pendingSeekDelta = 0
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
                                    let width = max(geometry.size.width, 1)
                                    pendingSeekDelta = min(
                                        max(value.translation.width / width * 120, -120),
                                        120
                                    )
                                    let sign = pendingSeekDelta < 0 ? "−" : "+"
                                    gestureHUD = "Seek \(sign)\(Int(abs(pendingSeekDelta))) s"

                                case .brightness:
                                    let height = max(geometry.size.height, 1)
                                    let delta = -value.translation.height / height
                                    let next = min(max(dragStartValue + delta, 0.02), 1)
                                    model.setBrightness(next)
                                    gestureHUD = "Brightness \(Int(next * 100))%"

                                case .volume:
                                    let height = max(geometry.size.height, 1)
                                    let delta = -value.translation.height / height
                                    let next = min(max(dragStartValue + delta, 0), 1)
                                    model.setVolume(next)
                                    gestureHUD = "Volume \(Int(next * 100))%"

                                case .none:
                                    break
                                }

                                showControls()
                            }
                            .onEnded { _ in
                                if dragControl == .seek {
                                    model.seek(to: dragStartTime + pendingSeekDelta)
                                }
                                dragControl = nil
                                pendingSeekDelta = 0
                                dismissHUDLater()
                            }
                    )

                if !model.externalSubtitleText.isEmpty && !model.audioOnly {
                    Text(model.externalSubtitleText)
                        .font(.system(size: model.subtitleFontSize, weight: .semibold))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(Color(model.subtitleTone.color))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(
                            .black.opacity(model.subtitleBackgroundOpacity),
                            in: RoundedRectangle(cornerRadius: 7)
                        )
                        .padding(.horizontal, 24)
                        .padding(
                            .bottom,
                            max(
                                24,
                                geometry.size.height * model.subtitleBottomFraction +
                                    (controlsVisible ? (isFullscreen ? 90 : 130) : 0)
                            )
                        )
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .allowsHitTesting(false)
                }

                if let gestureHUD {
                    Text(gestureHUD)
                        .font(.headline)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(
                            .black.opacity(0.72),
                            in: RoundedRectangle(cornerRadius: 12)
                        )
                }
            }
        }
    }

    private var controls: some View {
        ScrollView {
            VStack(spacing: 8) {
                topControls
                mediaInfo
                timeline
                transportControls
                speedControl
                silenceControl
                brightnessVolumeControls

                Button {
                    advancedExpanded.toggle()
                    showControls()
                } label: {
                    HStack {
                        Text(advancedExpanded ? "Hide advanced" : "Advanced")
                        Spacer()
                        Image(
                            systemName: advancedExpanded
                                ? "chevron.up"
                                : "chevron.down"
                        )
                    }
                }
                .buttonStyle(.plain)
                .padding(.vertical, 4)

                if advancedExpanded {
                    Divider()
                    advancedControls
                }

                Text(
                    gestureLocked
                        ? "Gestures locked · use the lock button to unlock"
                        : "Double-tap: ±\(model.doubleTapSeconds) s · horizontal swipe: seek · vertical swipe: brightness/volume"
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
            .padding(.bottom, 8)
        }
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.97)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var topControls: some View {
        HStack(spacing: 6) {
            Button {
                showingVideoImporter = true
            } label: {
                Text("Open")
            }
            .buttonStyle(.bordered)

            Menu("More") {
                Button("Open playlist") {
                    showingPlaylistImporter = true
                }
                Button("Open folder") {
                    showingFolderImporter = true
                }
            }
            .buttonStyle(.bordered)

            recentMenu

            Spacer()

            Button {
                gestureLocked.toggle()
                showControls()
            } label: {
                Image(systemName: gestureLocked ? "lock.fill" : "lock.open")
            }
            .buttonStyle(.bordered)

            if PictureInPictureManager.isSupported && !model.audioOnly {
                Button {
                    pipManager.toggle()
                    scheduleControlsHide()
                } label: {
                    Image(systemName: "pip.enter")
                }
                .buttonStyle(.bordered)
                .disabled(model.fileName == "No video selected")
            }

            Button {
                setFullscreen(!isFullscreen)
            } label: {
                Image(
                    systemName: isFullscreen
                        ? "arrow.down.right.and.arrow.up.left"
                        : "arrow.up.left.and.arrow.down.right"
                )
            }
            .buttonStyle(.bordered)
        }
    }

    private var mediaInfo: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(model.fileName)
                .lineLimit(1)
                .font(.caption)
                .foregroundStyle(.primary)

            if !model.fileInfo.isEmpty {
                Text(model.fileInfo)
                    .lineLimit(1)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Text(model.analysisStatus)
                .lineLimit(1)
                .font(.caption2)
                .foregroundStyle(.secondary)

            if let watch = model.estimatedWatchTime {
                HStack(spacing: 4) {
                    Text(
                        "Silence \(PlayerModel.format(seconds: model.analysisSkippableTime)) · estimated watch \(PlayerModel.format(seconds: watch))"
                    )
                    if let saved = model.estimatedTimeSaved, saved > 0 {
                        Text("· save \(PlayerModel.format(seconds: saved))")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var timeline: some View {
        VStack(spacing: 2) {
            Slider(
                value: Binding(
                    get: { min(model.currentTime, max(model.duration, 0)) },
                    set: {
                        model.seek(to: $0)
                        showControls()
                    }
                ),
                in: 0...max(model.duration, 1)
            )
            .disabled(model.duration <= 0)

            HStack {
                Text(PlayerModel.format(seconds: model.currentTime))
                    .monospacedDigit()
                    .font(.caption)

                Spacer()

                Text("Skipped " + PlayerModel.format(seconds: model.skippedTime))
                    .monospacedDigit()
                    .font(.caption)
                    .foregroundStyle(.secondary)

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
        }
    }

    private var transportControls: some View {
        HStack(spacing: 22) {
            Button {
                model.previousVideo()
                showControls()
            } label: {
                Image(systemName: "backward.end.fill")
            }
            .disabled(model.playlistIndex <= 0)

            Button {
                model.seek(by: -Double(model.doubleTapSeconds))
                showHUD("−\(model.doubleTapSeconds) s")
            } label: {
                Image(systemName: "gobackward")
                    .overlay(
                        Text("\(model.doubleTapSeconds)")
                            .font(.system(size: 9, weight: .bold))
                    )
            }

            Button {
                model.togglePlayback()
                showControls()
            } label: {
                Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 32))
                    .frame(width: 48, height: 44)
            }

            Button {
                model.seek(by: Double(model.doubleTapSeconds))
                showHUD("+\(model.doubleTapSeconds) s")
            } label: {
                Image(systemName: "goforward")
                    .overlay(
                        Text("\(model.doubleTapSeconds)")
                            .font(.system(size: 9, weight: .bold))
                    )
            }

            Button {
                model.nextVideo()
                showControls()
            } label: {
                Image(systemName: "forward.end.fill")
            }
            .disabled(model.playlistIndex + 1 >= model.playlistCount)
        }
        .font(.title2)
        .buttonStyle(.plain)
        .disabled(model.fileName == "No video selected")
    }

    private var speedControl: some View {
        HStack {
            Text("Speed " + String(format: "%.2f×", model.playbackSpeed))
                .font(.caption)
                .monospacedDigit()
                .frame(width: 100, alignment: .leading)

            Slider(
                value: Binding(
                    get: { model.playbackSpeed },
                    set: {
                        model.setPlaybackSpeed($0)
                        showControls()
                    }
                ),
                in: 0.5...3,
                step: 0.25,
                onEditingChanged: { editing in
                    if !editing {
                        model.commitPlaybackSpeed()
                    }
                }
            )
        }
    }

    private var silenceControl: some View {
        HStack {
            Text("Skip silence")
                .font(.subheadline)

            Toggle(
                "",
                isOn: Binding(
                    get: { model.silenceEnabled },
                    set: {
                        model.setSilenceEnabled($0)
                        showControls()
                    }
                )
            )
            .labelsHidden()

            Spacer()

            presetMenu
        }
    }

    private var brightnessVolumeControls: some View {
        Group {
            HStack {
                Image(systemName: "sun.max.fill")
                    .frame(width: 24)
                Slider(
                    value: Binding(
                        get: { model.brightness },
                        set: {
                            model.setBrightness($0)
                            showControls()
                        }
                    ),
                    in: 0.02...1
                )
            }

            HStack {
                Image(systemName: "speaker.wave.2.fill")
                    .frame(width: 24)
                Slider(
                    value: Binding(
                        get: { model.volume },
                        set: {
                            model.setVolume($0)
                            showControls()
                        }
                    ),
                    in: 0...1
                )
            }
        }
    }

    private var advancedControls: some View {
        VStack(spacing: 8) {
            silenceAdvanced
            playbackAdvanced
            subtitleAdvanced
            utilityAdvanced
            chapterBookmarkControls

            Button(role: .destructive) {
                model.resetSettings()
                showHUD("Defaults restored")
                showControls()
            } label: {
                Text("Reset player settings")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    private var silenceAdvanced: some View {
        Group {
            HStack {
                Text("Threshold \(Int(model.silenceThresholdDB.rounded())) dB")
                    .font(.caption)
                    .monospacedDigit()
                    .frame(width: 125, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.silenceThresholdDB },
                        set: {
                            model.setSilenceThreshold($0)
                            showControls()
                        }
                    ),
                    in: -60...(-20),
                    step: 1,
                    onEditingChanged: { editing in
                        if !editing {
                            model.applySilenceSettings()
                        }
                    }
                )
                .disabled(!model.silenceEnabled)
            }

            HStack {
                Text("Min " + String(format: "%.2f s", model.minimumSilence))
                    .font(.caption)
                    .monospacedDigit()
                    .frame(width: 125, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.minimumSilence },
                        set: {
                            model.setMinimumSilence($0)
                            showControls()
                        }
                    ),
                    in: 0.2...2,
                    step: 0.05,
                    onEditingChanged: { editing in
                        if !editing {
                            model.applySilenceSettings()
                        }
                    }
                )
                .disabled(!model.silenceEnabled)
            }

            HStack {
                Text("Edge \(Int((model.edgePadding * 1000).rounded())) ms")
                    .font(.caption)
                    .monospacedDigit()
                    .frame(width: 125, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.edgePadding },
                        set: {
                            model.setEdgePadding($0)
                            showControls()
                        }
                    ),
                    in: 0.02...0.20,
                    step: 0.01,
                    onEditingChanged: { editing in
                        if !editing {
                            model.applySilenceSettings()
                        }
                    }
                )
                .disabled(!model.silenceEnabled)
            }
        }
    }

    private var playbackAdvanced: some View {
        Group {
            HStack {
                doubleTapMenu
                Spacer()
                resumeMenu
            }

            HStack {
                Text("Audio-only")
                Toggle(
                    "",
                    isOn: Binding(
                        get: { model.audioOnly },
                        set: { model.setAudioOnly($0) }
                    )
                )
                .labelsHidden()

                Spacer()

                gravityMenu
            }

            HStack {
                Text("Zoom " + String(format: "%.1f×", model.videoZoom))
                    .font(.caption)
                    .frame(width: 100, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.videoZoom },
                        set: { model.setVideoZoom($0) }
                    ),
                    in: 1...3,
                    step: 0.1
                )
            }
        }
    }

    private var subtitleAdvanced: some View {
        Group {
            HStack {
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
                .disabled(model.fileName == "No video selected")

                if model.externalSubtitleName != nil {
                    Button(role: .destructive) {
                        model.clearExternalSubtitle()
                    } label: {
                        Text("Remove")
                    }
                    .buttonStyle(.bordered)
                }
            }

            audioTrackMenu
            subtitleTrackMenu

            HStack {
                Text(
                    "Subtitle offset " +
                        String(format: "%+.1f s", model.subtitleOffset)
                )
                .font(.caption)
                .monospacedDigit()
                .frame(width: 145, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.subtitleOffset },
                        set: { model.setSubtitleOffset($0) }
                    ),
                    in: -10...10,
                    step: 0.1
                )
            }

            HStack {
                Text("Subtitle size \(Int(model.subtitleFontSize.rounded()))")
                    .font(.caption)
                    .frame(width: 145, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.subtitleFontSize },
                        set: { model.setSubtitleFontSize($0) }
                    ),
                    in: 14...40,
                    step: 1
                )
            }

            HStack {
                Text("Subtitle height \(Int((model.subtitleBottomFraction * 100).rounded()))%")
                    .font(.caption)
                    .frame(width: 145, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.subtitleBottomFraction },
                        set: { model.setSubtitleBottomFraction($0) }
                    ),
                    in: 0.02...0.35,
                    step: 0.01
                )
            }

            HStack {
                Text("Subtitle bg \(Int((model.subtitleBackgroundOpacity * 100).rounded()))%")
                    .font(.caption)
                    .frame(width: 145, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.subtitleBackgroundOpacity },
                        set: { model.setSubtitleBackgroundOpacity($0) }
                    ),
                    in: 0...0.9,
                    step: 0.1
                )
            }

            Menu {
                ForEach(SubtitleTone.allCases) { tone in
                    Button(tone.label) {
                        model.setSubtitleTone(tone)
                    }
                }
            } label: {
                HStack {
                    Text("Subtitle color")
                    Spacer()
                    Text(model.subtitleTone.label)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Text(
                "External subtitle import supports SRT, WebVTT, SSA and ASS. A matching subtitle beside the video is loaded automatically when file access allows it."
            )
            .font(.caption2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var utilityAdvanced: some View {
        Group {
            Menu {
                ForEach([15, 30, 60, 90], id: \.self) { minutes in
                    Button("\(minutes) minutes") {
                        model.setSleepTimer(minutes: minutes)
                    }
                }
                Button("Off") {
                    model.setSleepTimer(minutes: nil)
                }
            } label: {
                HStack {
                    Text("Sleep timer")
                    Spacer()
                    Text(
                        model.sleepRemaining > 0
                            ? PlayerModel.format(seconds: model.sleepRemaining)
                            : "Off"
                    )
                    .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            HStack(spacing: 8) {
                Button {
                    model.setABStart()
                } label: {
                    Text(
                        model.abStart.map {
                            "A " + PlayerModel.format(seconds: $0)
                        } ?? "Set A"
                    )
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)

                Button {
                    model.setABEnd()
                } label: {
                    Text(
                        model.abEnd.map {
                            "B " + PlayerModel.format(seconds: $0)
                        } ?? "Set B"
                    )
                }
                .buttonStyle(.bordered)
                .disabled(model.abStart == nil)
                .frame(maxWidth: .infinity)

                Button("Clear") {
                    model.clearABRepeat()
                }
                .buttonStyle(.bordered)
                .disabled(model.abStart == nil && model.abEnd == nil)
            }
        }
    }

    private var chapterBookmarkControls: some View {
        Group {
            HStack {
                Button {
                    model.addBookmark()
                } label: {
                    Text("Bookmark " + PlayerModel.format(seconds: model.currentTime))
                }
                .buttonStyle(.bordered)
                .disabled(model.fileName == "No video selected")

                Spacer()

                bookmarkMenu
            }

            chapterMenu

            Text(
                "Bookmarks are custom chapter markers. Embedded chapters are shown when the video contains chapter metadata."
            )
            .font(.caption2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var recentMenu: some View {
        Menu {
            ForEach(model.recentVideos) { recent in
                Button(recent.name) {
                    model.openRecent(recent)
                    showControls()
                }
            }

            if !model.recentVideos.isEmpty {
                Divider()
                Button("Clear history", role: .destructive) {
                    model.clearHistory()
                }
            }
        } label: {
            Text("Recent")
        }
        .buttonStyle(.bordered)
        .disabled(model.recentVideos.isEmpty)
    }

    private var presetMenu: some View {
        Menu {
            ForEach(SilencePreset.allCases) { preset in
                Button(preset.label) {
                    model.applyPreset(preset)
                    showHUD(preset.label)
                    showControls()
                }
            }
        } label: {
            Text(model.currentPreset?.label ?? "Custom")
        }
        .buttonStyle(.bordered)
    }

    private var doubleTapMenu: some View {
        Menu {
            ForEach([5, 10, 15, 30], id: \.self) { seconds in
                Button("\(seconds) seconds") {
                    model.setDoubleTapSeconds(seconds)
                    showControls()
                }
            }
        } label: {
            Text("Double-tap: \(model.doubleTapSeconds) s")
        }
        .buttonStyle(.bordered)
    }

    private var resumeMenu: some View {
        Menu {
            ForEach(ResumeMode.allCases) { mode in
                Button(mode.label) {
                    model.setResumeMode(mode)
                }
            }
        } label: {
            Text(model.resumeMode.label)
        }
        .buttonStyle(.bordered)
    }

    private var gravityMenu: some View {
        Menu {
            ForEach(VideoGravityMode.allCases) { mode in
                Button(mode.label) {
                    model.setVideoGravityMode(mode)
                }
            }
        } label: {
            Text("View: \(model.videoGravityMode.label)")
        }
        .buttonStyle(.bordered)
    }

    private var audioTrackMenu: some View {
        Menu {
            ForEach(model.audioTrackNames.indices, id: \.self) { index in
                Button(model.audioTrackNames[index]) {
                    model.selectAudio(index: index)
                    showControls()
                }
            }
        } label: {
            HStack {
                Text("Audio")
                Spacer()
                Text(model.selectedAudioName)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(model.audioTrackNames.isEmpty)
    }

    private var subtitleTrackMenu: some View {
        Menu {
            Button("Off") {
                model.selectSubtitle(index: nil)
                showControls()
            }

            ForEach(model.subtitleTrackNames.indices, id: \.self) { index in
                Button(model.subtitleTrackNames[index]) {
                    model.selectSubtitle(index: index)
                    showControls()
                }
            }
        } label: {
            HStack {
                Text("Embedded subtitles")
                Spacer()
                Text(model.selectedSubtitleName)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(
            model.subtitleTrackNames.isEmpty &&
                model.externalSubtitleName == nil
        )
    }

    private var bookmarkMenu: some View {
        Menu {
            ForEach(model.bookmarks) { bookmark in
                Button(
                    "\(bookmark.name) · \(PlayerModel.format(seconds: bookmark.position))"
                ) {
                    model.jump(to: bookmark)
                }
            }

            if !model.bookmarks.isEmpty {
                Divider()
                Button("Clear bookmarks", role: .destructive) {
                    model.clearBookmarks()
                }
            }
        } label: {
            Text("Bookmarks (\(model.bookmarks.count))")
        }
        .buttonStyle(.bordered)
        .disabled(model.bookmarks.isEmpty)
    }

    private var chapterMenu: some View {
        Menu {
            ForEach(model.chapters) { chapter in
                Button(
                    "\(chapter.name) · \(PlayerModel.format(seconds: chapter.position))"
                ) {
                    model.jump(to: chapter)
                }
            }
        } label: {
            HStack {
                Text("Embedded chapters")
                Spacer()
                Text(model.chapters.isEmpty ? "None" : "\(model.chapters.count)")
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(model.chapters.isEmpty)
    }

    private func playbackErrorOverlay(_ error: String) -> some View {
        VStack(spacing: 10) {
            Text("Playback error")
                .font(.headline)
            Text(error)
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            HStack {
                Button("Dismiss") {
                    // Loading/seek actions clear or replace the error.
                    showingVideoImporter = false
                }
                .buttonStyle(.bordered)

                Button("Retry") {
                    model.retryPlayback()
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(18)
        .frame(maxWidth: 360)
        .background(
            .black.opacity(0.9),
            in: RoundedRectangle(cornerRadius: 14)
        )
        .padding()
    }

    private func setFullscreen(_ enabled: Bool) {
        isFullscreen = enabled
        showControls()

        guard
            let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first
        else {
            return
        }

        let orientations: UIInterfaceOrientationMask =
            enabled ? .landscape : .allButUpsideDown

        scene.requestGeometryUpdate(
            .iOS(interfaceOrientations: orientations)
        ) { _ in
            // The system may deny rotation while multitasking.
        }
    }

    private func showControls() {
        controlsVisible = true
        scheduleControlsHide()
    }

    private func scheduleControlsHide() {
        hideGeneration += 1
        let generation = hideGeneration

        guard model.isPlaying, !advancedExpanded else {
            return
        }

        let delay = isFullscreen ? 2.5 : 4.0
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            if generation == hideGeneration && model.isPlaying && !advancedExpanded {
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
