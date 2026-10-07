import SwiftUI
import UniformTypeIdentifiers
import UIKit

private enum DragControl {
    case brightness
    case volume
}

struct ContentView: View {
    @StateObject private var model = PlayerModel()
    @StateObject private var pipManager = PictureInPictureManager()

    @State private var showingVideoImporter = false
    @State private var showingSubtitleImporter = false
    @State private var dragControl: DragControl?
    @State private var dragStartValue = 0.0
    @State private var gestureHUD: String?
    @State private var controlsVisible = true
    @State private var advancedExpanded = false
    @State private var isFullscreen = false
    @State private var hideGeneration = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            videoArea

            if controlsVisible {
                controls
                    .frame(maxHeight: isFullscreen ? 340 : 520)
                    .frame(maxWidth: .infinity)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(isFullscreen)
        .fileImporter(
            isPresented: $showingVideoImporter,
            allowedContentTypes: [.movie, .video, .audiovisualContent],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    model.load(url: url)
                    showControls()
                }
            case .failure:
                break
            }
        }
        .fileImporter(
            isPresented: $showingSubtitleImporter,
            allowedContentTypes: [.plainText, .text],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    model.loadExternalSubtitle(url: url)
                    showControls()
                }
            case .failure:
                break
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
                VideoSurface(
                    player: model.player,
                    pictureInPictureManager: pipManager
                )
                .background(Color.black)

                if model.fileName == "No video selected" {
                    Button {
                        showingVideoImporter = true
                    } label: {
                        Label("Open video", systemImage: "folder")
                            .padding(.horizontal, 10)
                    }
                    .buttonStyle(.borderedProminent)
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
                                if dragControl == nil {
                                    if value.startLocation.x < geometry.size.width / 2 {
                                        dragControl = .brightness
                                        dragStartValue = model.brightness
                                    } else {
                                        dragControl = .volume
                                        dragStartValue = model.volume
                                    }
                                }

                                let height = max(geometry.size.height, 1)
                                let delta = -value.translation.height / height
                                let next = min(max(dragStartValue + delta, 0), 1)

                                switch dragControl {
                                case .brightness:
                                    model.setBrightness(next)
                                    gestureHUD = "Brightness " + String(Int(next * 100)) + "%"
                                case .volume:
                                    model.setVolume(next)
                                    gestureHUD = "Volume " + String(Int(next * 100)) + "%"
                                case .none:
                                    break
                                }

                                showControls()
                            }
                            .onEnded { _ in
                                dragControl = nil
                                dismissHUDLater()
                            }
                    )

                if !model.externalSubtitleText.isEmpty {
                    Text(model.externalSubtitleText)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 7))
                        .padding(.horizontal, 24)
                        .padding(
                            .bottom,
                            controlsVisible ? (isFullscreen ? 150 : 260) : 32
                        )
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .allowsHitTesting(false)
                }

                if let gestureHUD {
                    Text(gestureHUD)
                        .font(.headline)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 12))
                }
            }
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

                    recentMenu

                    Spacer()

                    if PictureInPictureManager.isSupported {
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
                }
                .frame(maxWidth: .infinity, alignment: .leading)

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

                HStack(spacing: 26) {
                    Button {
                        let seconds = Double(model.doubleTapSeconds)
                        model.seek(by: -seconds)
                        showHUD("−\(model.doubleTapSeconds) s")
                    } label: {
                        Label(
                            "\(model.doubleTapSeconds)",
                            systemImage: "gobackward"
                        )
                        .labelStyle(.iconOnly)
                        .overlay(
                            Text("\(model.doubleTapSeconds)")
                                .font(.system(size: 9, weight: .bold))
                        )
                        .font(.title2)
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
                        let seconds = Double(model.doubleTapSeconds)
                        model.seek(by: seconds)
                        showHUD("+\(model.doubleTapSeconds) s")
                    } label: {
                        Label(
                            "\(model.doubleTapSeconds)",
                            systemImage: "goforward"
                        )
                        .labelStyle(.iconOnly)
                        .overlay(
                            Text("\(model.doubleTapSeconds)")
                                .font(.system(size: 9, weight: .bold))
                        )
                        .font(.title2)
                    }
                }
                .buttonStyle(.plain)
                .disabled(model.fileName == "No video selected")

                HStack {
                    Text(
                        "Speed " + String(format: "%.2f×", model.playbackSpeed)
                    )
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
                        Text(
                            "Min " + String(format: "%.2f s", model.minimumSilence)
                        )
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
                        Text(
                            "Edge \(Int((model.edgePadding * 1000).rounded())) ms"
                        )
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

                    HStack {
                        doubleTapMenu

                        Spacer()

                        Button {
                            showingSubtitleImporter = true
                        } label: {
                            Label(
                                model.externalSubtitleName ?? "External subtitle",
                                systemImage: "captions.bubble"
                            )
                            .lineLimit(1)
                        }
                        .buttonStyle(.bordered)
                        .disabled(model.fileName == "No video selected")
                    }

                    audioTrackMenu
                    subtitleTrackMenu

                    if model.externalSubtitleName != nil {
                        Button(role: .destructive) {
                            model.clearExternalSubtitle()
                            showControls()
                        } label: {
                            Text("Remove external subtitle")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }

                    Text(
                        "External subtitles support SRT, WebVTT, SSA and ASS. Embedded audio and subtitle tracks can be selected above."
                    )
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

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

                Text(
                    "Double-tap: ±\(model.doubleTapSeconds) s · Swipe left: brightness · Swipe right: volume"
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
                Text("Subtitles")
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
            // Rotation requests can be denied by system multitasking state.
        }
    }

    private func showControls() {
        controlsVisible = true
        scheduleControlsHide()
    }

    private func scheduleControlsHide() {
        hideGeneration += 1
        let generation = hideGeneration

        guard model.isPlaying else {
            return
        }

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
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
            if gestureHUD == expected {
                gestureHUD = nil
            }
        }
    }
}
