import AVFoundation
@preconcurrency import AVKit
import Combine
import SwiftUI
import UIKit

@MainActor
final class PictureInPictureManager: NSObject, ObservableObject, @preconcurrency AVPictureInPictureControllerDelegate {
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
        guard let controller else { return }

        if controller.isPictureInPictureActive {
            controller.stopPictureInPicture()
        } else if controller.isPictureInPicturePossible {
            controller.startPictureInPicture()
        }
    }

    func pictureInPictureControllerDidStartPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        isActive = true
    }

    func pictureInPictureControllerDidStopPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        isActive = false
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
    let zoomScale: Double

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.backgroundColor = .black
        configure(view)
        return view
    }

    func updateUIView(_ uiView: PlayerLayerView, context: Context) {
        configure(uiView)
    }

    private func configure(_ view: PlayerLayerView) {
        view.playerLayer.player = player
        view.playerLayer.videoGravity = videoGravity
        view.playerLayer.setAffineTransform(
            CGAffineTransform(
                scaleX: CGFloat(zoomScale),
                y: CGFloat(zoomScale)
            )
        )
        pictureInPictureManager.attach(to: view.playerLayer)
    }
}
