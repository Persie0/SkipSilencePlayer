import AVFoundation
@preconcurrency import AVKit
import Combine
import SwiftUI
import UIKit

@MainActor
final class PictureInPictureManager: NSObject, ObservableObject, AVPictureInPictureControllerDelegate {
    static var isSupported: Bool {
        AVPictureInPictureController.isPictureInPictureSupported()
    }

    @Published private(set) var isActive = false

    private var controller: AVPictureInPictureController?
    private weak var attachedLayer: AVPlayerLayer?

    func attach(to playerLayer: AVPlayerLayer) {
        guard Self.isSupported else {
            controller = nil
            attachedLayer = nil
            return
        }

        if attachedLayer === playerLayer, controller != nil {
            return
        }

        attachedLayer = playerLayer
        controller = AVPictureInPictureController(playerLayer: playerLayer)
        controller?.delegate = self
        controller?.canStartPictureInPictureAutomaticallyFromInline = true
    }

    func toggle() {
        guard let controller else {
            return
        }

        if controller.isPictureInPictureActive {
            controller.stopPictureInPicture()
        } else if controller.isPictureInPicturePossible {
            controller.startPictureInPicture()
        }
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        Task { @MainActor [weak self] in
            self?.isActive = true
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        Task { @MainActor [weak self] in
            self?.isActive = false
        }
    }
}

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass {
        AVPlayerLayer.self
    }

    var playerLayer: AVPlayerLayer {
        layer as! AVPlayerLayer
    }
}

struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    let pictureInPictureManager: PictureInPictureManager
    let videoGravity: AVLayerVideoGravity

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = videoGravity
        view.playerLayer.player = player
        pictureInPictureManager.attach(to: view.playerLayer)
        return view
    }

    func updateUIView(_ uiView: PlayerLayerView, context: Context) {
        uiView.playerLayer.player = player
        uiView.playerLayer.videoGravity = videoGravity
        pictureInPictureManager.attach(to: uiView.playerLayer)
    }
}
