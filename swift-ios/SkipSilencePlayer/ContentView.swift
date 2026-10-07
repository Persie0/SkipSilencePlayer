import SwiftUI
import UniformTypeIdentifiers

private enum DragControl {
    case brightness
    case volume
}

struct ContentView: View {
    @StateObject private var model = PlayerModel()
    @State private var showingImporter = false
    @State private var dragControl: DragControl?
    @State private var dragStartValue = 0.0
    @State private var gestureHUD: String?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                videoArea
                    .frame(maxHeight: .infinity)

                controls
            }
        }
        .preferredColorScheme(.dark)
        .fileImporter(
            isPresented: $showingImporter,
            allowedContentTypes: [.movie, .video, .audiovisualContent],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    model.load(url: url)
                }
            case .failure:
                break
            }
        }
    }

    private var videoArea: some View {
        GeometryReader { geometry in
            ZStack {
                VideoSurface(player: model.player)
                    .background(Color.black)

                if model.fileName == "No video selected" {
                    Button {
                        showingImporter = true
                    } label: {
                        Label("Open video", systemImage: "folder")
                            .padding(.horizontal, 10)
                    }
                    .buttonStyle(.borderedProminent)
                }

                Color.clear
                    .contentShape(Rectangle())
                    .simultaneousGesture(
                        SpatialTapGesture(count: 2)
                            .onEnded { event in
                                if event.location.x < geometry.size.width / 2 {
                                    model.seek(by: -10)
                                    showHUD("−10 s")
                                } else {
                                    model.seek(by: 10)
                                    showHUD("+10 s")
                                }
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
                            }
                            .onEnded { _ in
                                dragControl = nil
                                dismissHUDLater()
                            }
                    )

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
        VStack(spacing: 8) {
            HStack {
                Button {
                    showingImporter = true
                } label: {
                    Label("Open", systemImage: "folder")
                }
                .buttonStyle(.bordered)

                Spacer()

                Text("Skip silence")
                    .font(.subheadline)

                Toggle("", isOn: $model.silenceEnabled)
                    .labelsHidden()
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(model.fileName)
                    .lineLimit(1)
                    .font(.caption)
                    .foregroundStyle(.secondary)

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
                    .font(.caption)
                Spacer()
                Text(
                    model.duration > 0
                        ? "−" + PlayerModel.format(seconds: max(0, model.duration - model.currentTime))
                        : "--:--"
                )
                .monospacedDigit()
                .font(.caption)
            }

            HStack(spacing: 26) {
                Button {
                    model.seek(by: -10)
                    showHUD("−10 s")
                } label: {
                    Image(systemName: "gobackward.10")
                        .font(.title2)
                }

                Button {
                    model.togglePlayback()
                } label: {
                    Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 32))
                        .frame(width: 48, height: 44)
                }

                Button {
                    model.seek(by: 10)
                    showHUD("+10 s")
                } label: {
                    Image(systemName: "goforward.10")
                        .font(.title2)
                }
            }
            .buttonStyle(.plain)

            HStack {
                Image(systemName: "sun.max.fill")
                    .frame(width: 24)
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
                    .frame(width: 24)
                Slider(
                    value: Binding(
                        get: { model.volume },
                        set: { model.setVolume($0) }
                    ),
                    in: 0...1
                )
            }

            Text("Double-tap left/right: ±10 s · Swipe left: brightness · Swipe right: volume")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(.black.opacity(0.94))
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
