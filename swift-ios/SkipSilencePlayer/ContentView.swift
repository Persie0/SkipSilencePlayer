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
    @State private var showingFolderImporter = false
    @State private var showingSubtitleImporter = false
    @State private var dragControl: DragControl?
    @State private var dragStartValue = 0.0
    @State private var dragStartTime = 0.0
    @State private var gestureHUD: String?
    @State private var controlsVisible = true
    @State private var advancedExpanded = false
    @State private var isFullscreen = false
    @State private var gesturesLocked = false
    @State private var hideGeneration = 0
    @State private var subtitleSearch = ""
    @State private var pinchStartZoom = 1.0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            videoArea

            if controlsVisible && !gesturesLocked {
                controls
                    .frame(maxHeight: isFullscreen ? 390 : 620)
                    .frame(maxWidth: .infinity)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }

            if gesturesLocked {
                Button("Unlock gestures") {
                    gesturesLocked = false
                    showControls()
                }
                .buttonStyle(.borderedProminent)
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.top, 18)
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

                if model.fileName == "No video selected" {
                    Button {
                        showingVideoImporter = true
                    } label: {
                        Label("Open video", systemImage: "folder")
                    }
                    .buttonStyle(.borderedProminent)
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
                        .font(.headline)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(.black.opacity(0.76), in: RoundedRectangle(cornerRadius: 12))
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
            .padding(.vertical, controlsVisible ? 90 : 28)
            .allowsHitTesting(false)
        }
    }

    private var controls: some View {
        ScrollView {
            VStack(spacing: 8) {
                HStack {
                    Button {
                        showingVideoImporter = true
                    } label: {
                        Label("Open", systemImage: "folder")
                    }
                    .buttonStyle(.bordered)

                    Button("Folder") {
                        showingFolderImporter = true
                    }
                    .buttonStyle(.bordered)

                    recentMenu

                    Spacer()

                    if PictureInPictureManager.isSupported {
                        Button {
                            pipManager.toggle()
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

                VStack(alignment: .leading, spacing: 2) {
                    Text(model.fileName)
                        .lineLimit(1)
                        .font(.caption)

                    if !model.fileInfo.isEmpty {
                        Text(model.fileInfo)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    Text(model.analysisStatus)
                        .lineLimit(1)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { min(model.currentTime, max(model.duration, 0)) },
                        set: { model.seek(to: $0) }
                    ),
                    in: 0...max(model.duration, 1)
                )
                .disabled(model.duration <= 0)

                HStack {
                    Text(PlayerModel.format(seconds: model.currentTime))
                        .monospacedDigit()
                    Spacer()
                    Text(
                        "Saved " + PlayerModel.format(seconds: model.skippedTime) +
                        " · est. " + PlayerModel.format(seconds: model.estimatedWatchTime) +
                        " watch"
                    )
                    .font(.caption2)
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
                }
                .font(.caption)

                HStack(spacing: 18) {
                    Button("Prev") { model.previous() }
                        .disabled(!model.hasPrevious)

                    Button {
                        model.seek(by: -Double(model.doubleTapSeconds))
                        showHUD("−\(model.doubleTapSeconds) s")
                    } label: {
                        Image(systemName: "gobackward")
                    }

                    Button {
                        model.togglePlayback()
                        showControls()
                    } label: {
                        Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 30))
                    }

                    Button {
                        model.seek(by: Double(model.doubleTapSeconds))
                        showHUD("+\(model.doubleTapSeconds) s")
                    } label: {
                        Image(systemName: "goforward")
                    }

                    Button("Next") { model.next() }
                        .disabled(!model.hasNext)
                }
                .buttonStyle(.plain)

                HStack {
                    Text(String(format: "Speed %.2f×", model.playbackSpeed))
                        .font(.caption)
                        .frame(width: 100, alignment: .leading)

                    Slider(
                        value: Binding(
                            get: { model.playbackSpeed },
                            set: { model.setPlaybackSpeed($0) }
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

                HStack {
                    Text("Skip silence")
                    Toggle(
                        "",
                        isOn: Binding(
                            get: { model.silenceEnabled },
                            set: { model.setSilenceEnabled($0) }
                        )
                    )
                    .labelsHidden()

                    Spacer()
                    presetMenu
                }

                HStack {
                    Image(systemName: "sun.max.fill")
                    Slider(
                        value: Binding(
                            get: { model.brightness },
                            set: { model.setBrightness($0) }
                        ),
                        in: 0.02...1
                    )
                }

                HStack {
                    Image(systemName: "speaker.wave.2.fill")
                    Slider(
                        value: Binding(
                            get: { model.volume },
                            set: { model.setVolume($0) }
                        ),
                        in: 0...1
                    )
                }

                Button {
                    advancedExpanded.toggle()
                    showControls()
                } label: {
                    HStack {
                        Text(advancedExpanded ? "Hide advanced" : "Advanced")
                        Spacer()
                        Image(systemName: advancedExpanded ? "chevron.up" : "chevron.down")
                    }
                }
                .buttonStyle(.plain)

                if advancedExpanded {
                    Divider()

                    silenceControls
                    viewingControls
                    subtitleControls
                    playbackTools

                    Button(role: .destructive) {
                        model.resetSettings()
                        showHUD("Defaults restored")
                    } label: {
                        Text("Reset player settings")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }

                Text(
                    "Double-tap ±\(model.doubleTapSeconds)s · horizontal drag seeks · vertical drag controls brightness/volume · pinch zoom"
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 8)
        }
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.96)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var silenceControls: some View {
        Group {
            HStack {
                Text("Threshold \(Int(model.silenceThresholdDB.rounded())) dB")
                    .font(.caption)
                    .frame(width: 125, alignment: .leading)
                Slider(
                    value: Binding(
                        get: { model.silenceThresholdDB },
                        set: { model.setSilenceThreshold($0) }
                    ),
                    in: -60...(-20),
                    step: 1,
                    onEditingChanged: { if !$0 { model.applySilenceSettings() } }
                )
                .disabled(!model.silenceEnabled)
            }

            HStack {
                Text(String(format: "Min %.2f s", model.minimumSilence))
                    .font(.caption)
                    .frame(width: 125, alignment: .leading)
                Slider(
                    value: Binding(
                        get: { model.minimumSilence },
                        set: { model.setMinimumSilence($0) }
                    ),
                    in: 0.2...2,
                    step: 0.05,
                    onEditingChanged: { if !$0 { model.applySilenceSettings() } }
                )
                .disabled(!model.silenceEnabled)
            }

            HStack {
                Text("Edge \(Int((model.edgePadding * 1000).rounded())) ms")
                    .font(.caption)
                    .frame(width: 125, alignment: .leading)
                Slider(
                    value: Binding(
                        get: { model.edgePadding },
                        set: { model.setEdgePadding($0) }
                    ),
                    in: 0.02...0.20,
                    step: 0.01,
                    onEditingChanged: { if !$0 { model.applySilenceSettings() } }
                )
                .disabled(!model.silenceEnabled)
            }
        }
    }

    private var viewingControls: some View {
        Group {
            HStack {
                aspectMenu
                doubleTapMenu
                resumeMenu
            }

            HStack {
                Text(String(format: "Zoom %.1f×", model.videoZoom))
                    .font(.caption)
                    .frame(width: 100, alignment: .leading)
                Slider(
                    value: Binding(
                        get: { model.videoZoom },
                        set: { model.setVideoZoom($0) }
                    ),
                    in: 1...3,
                    onEditingChanged: { if !$0 { model.commitVideoZoom() } }
                )
            }

            HStack {
                Text("Audio only")
                Toggle(
                    "",
                    isOn: Binding(
                        get: { model.audioOnly },
                        set: { model.setAudioOnly($0) }
                    )
                )
                .labelsHidden()

                Spacer()

                Button("Lock gestures") {
                    gesturesLocked = true
                    controlsVisible = false
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var subtitleControls: some View {
        Group {
            Divider()
            Text("Subtitles")
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)

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

                if model.externalSubtitleName != nil {
                    Button("Remove", role: .destructive) {
                        model.clearExternalSubtitle()
                    }
                    .buttonStyle(.bordered)
                }
            }

            audioTrackMenu
            subtitleTrackMenu

            HStack {
                Text(
                    String(
                        format: "Sync %+.2f s",
                        model.subtitleOffset
                    )
                )
                .font(.caption)
                .frame(width: 125, alignment: .leading)

                Slider(
                    value: Binding(
                        get: { model.subtitleOffset },
                        set: { model.setSubtitleOffset($0) }
                    ),
                    in: -10...10,
                    step: 0.05
                )
            }

            HStack {
                Text(String(format: "Text %.1f×", model.subtitleFontScale))
                    .font(.caption)
                    .frame(width: 125, alignment: .leading)
                Slider(
                    value: Binding(
                        get: { model.subtitleFontScale },
                        set: { model.setSubtitleFontScale($0) }
                    ),
                    in: 0.7...2
                )
            }

            HStack {
                subtitlePositionMenu
                Text(
                    "BG \(Int((model.subtitleBackgroundOpacity * 100).rounded()))%"
                )
                .font(.caption)
                Slider(
                    value: Binding(
                        get: { model.subtitleBackgroundOpacity },
                        set: { model.setSubtitleBackgroundOpacity($0) }
                    ),
                    in: 0...1
                )
            }

            HStack {
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
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var playbackTools: some View {
        Group {
            Divider()
            Text("Playback tools")
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack {
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

                Button("Clear A–B") {
                    model.clearAB()
                }
                .buttonStyle(.bordered)
                .disabled(model.abStart == nil && model.abEnd == nil)
            }

            HStack {
                Button("Add bookmark") {
                    model.addBookmark()
                }
                .buttonStyle(.bordered)

                bookmarkMenu
            }

            chapterMenu
            sleepMenu

            HStack {
                Button("Clear history", role: .destructive) {
                    model.clearHistory()
                }
                .buttonStyle(.bordered)

                Spacer()

                Text(
                    model.playlist.count > 1
                        ? "Playlist \(model.playlistIndex + 1)/\(model.playlist.count)"
                        : ""
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
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
                }
            }
        } label: {
            Text(model.currentPreset?.label ?? "Custom")
        }
        .buttonStyle(.bordered)
    }

    private var aspectMenu: some View {
        Menu {
            ForEach(VideoAspectMode.allCases) { mode in
                Button(mode.label) {
                    model.setAspectMode(mode)
                }
            }
        } label: {
            Text(model.aspectMode.label)
        }
        .buttonStyle(.bordered)
    }

    private var doubleTapMenu: some View {
        Menu {
            ForEach([5, 10, 15, 30], id: \.self) { seconds in
                Button("\(seconds) s") {
                    model.setDoubleTapSeconds(seconds)
                }
            }
        } label: {
            Text("Tap \(model.doubleTapSeconds)s")
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
            Text("Resume: " + model.resumeMode.label)
        }
        .buttonStyle(.bordered)
    }

    private var subtitlePositionMenu: some View {
        Menu {
            ForEach(SubtitlePosition.allCases) { position in
                Button(position.label) {
                    model.setSubtitlePosition(position)
                }
            }
        } label: {
            Text(model.subtitlePosition.label)
        }
        .buttonStyle(.bordered)
    }

    private var audioTrackMenu: some View {
        Menu {
            ForEach(model.audioTrackNames.indices, id: \.self) { index in
                Button(model.audioTrackNames[index]) {
                    model.selectAudio(index: index)
                }
            }
        } label: {
            HStack {
                Text("Audio")
                Spacer()
                Text(model.selectedAudioName)
                    .foregroundStyle(.secondary)
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
            }

            ForEach(model.subtitleTrackNames.indices, id: \.self) { index in
                Button(model.subtitleTrackNames[index]) {
                    model.selectSubtitle(index: index)
                }
            }
        } label: {
            HStack {
                Text("Embedded subtitles")
                Spacer()
                Text(model.selectedSubtitleName)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }

    private var bookmarkMenu: some View {
        Menu {
            ForEach(model.bookmarks) { bookmark in
                Button(bookmark.label) {
                    model.seek(to: bookmark.seconds)
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
                    PlayerModel.format(seconds: chapter.start) +
                        " · " + chapter.title
                ) {
                    model.seek(to: chapter.start)
                }
            }
        } label: {
            Text(
                model.chapters.isEmpty
                    ? "Chapters: none detected"
                    : "Chapters (\(model.chapters.count))"
            )
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(model.chapters.isEmpty)
    }

    private var sleepMenu: some View {
        Menu {
            ForEach([15, 30, 45, 60], id: \.self) { minutes in
                Button("\(minutes) minutes") {
                    model.setSleepTimer(minutes: Double(minutes))
                }
            }
            Button("At end of video") {
                model.setSleepAtEnd()
            }
            if model.sleepDeadline != nil || model.sleepAtEnd {
                Divider()
                Button("Cancel timer", role: .destructive) {
                    model.cancelSleepTimer()
                }
            }
        } label: {
            Text(model.sleepTimerLabel)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
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
