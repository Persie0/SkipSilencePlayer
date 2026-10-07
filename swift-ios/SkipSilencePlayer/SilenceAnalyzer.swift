import AVFoundation
import CoreMedia
import Foundation

struct SilenceRange: Equatable, Sendable {
    let start: Double
    let end: Double

    var duration: Double {
        max(0, end - start)
    }
}

struct SilenceAnalysis: Sendable {
    let ranges: [SilenceRange]
    let hasAudio: Bool
    let duration: Double
}

actor SilenceAnalyzer {
    enum AnalyzerError: LocalizedError {
        case cannotRead
        case readerFailed

        var errorDescription: String? {
            switch self {
            case .cannotRead:
                return "Could not read the video's audio track."
            case .readerFailed:
                return "Audio analysis failed."
            }
        }
    }

    func analyze(
        url: URL,
        thresholdDB: Double = -42.0,
        minimumSilence: Double = 0.45,
        edgePadding: Double = 0.08
    ) async throws -> SilenceAnalysis {
        let asset = AVURLAsset(url: url)
        let durationTime = try await asset.load(.duration)
        let durationValue = CMTimeGetSeconds(durationTime)
        let duration = durationValue.isFinite ? max(0, durationValue) : 0
        let tracks = try await asset.loadTracks(withMediaType: .audio)

        guard let track = tracks.first else {
            return SilenceAnalysis(ranges: [], hasAudio: false, duration: duration)
        }

        let reader = try AVAssetReader(asset: asset)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]

        let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
        output.alwaysCopiesSampleData = false

        guard reader.canAdd(output) else {
            throw AnalyzerError.cannotRead
        }
        reader.add(output)

        guard reader.startReading() else {
            throw reader.error ?? AnalyzerError.cannotRead
        }

        var ranges: [SilenceRange] = []
        var silenceStart: Double?
        var lastEnd = 0.0

        while let sampleBuffer = output.copyNextSampleBuffer() {
            if Task.isCancelled {
                reader.cancelReading()
                throw CancellationError()
            }

            let start = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
            let sampleDuration = Self.durationSeconds(of: sampleBuffer)
            let end = max(start, start + sampleDuration)
            lastEnd = max(lastEnd, end)

            let db = Self.decibels(of: sampleBuffer)
            if db <= thresholdDB {
                if silenceStart == nil {
                    silenceStart = start
                }
            } else if let began = silenceStart {
                Self.appendRange(
                    from: began,
                    to: start,
                    minimumSilence: minimumSilence,
                    edgePadding: edgePadding,
                    into: &ranges
                )
                silenceStart = nil
            }
        }

        if let began = silenceStart {
            Self.appendRange(
                from: began,
                to: max(lastEnd, duration),
                minimumSilence: minimumSilence,
                edgePadding: edgePadding,
                into: &ranges
            )
        }

        if reader.status == .failed {
            throw reader.error ?? AnalyzerError.readerFailed
        }

        return SilenceAnalysis(
            ranges: Self.mergeAdjacent(ranges),
            hasAudio: true,
            duration: duration
        )
    }

    private static func durationSeconds(of sampleBuffer: CMSampleBuffer) -> Double {
        let explicit = CMSampleBufferGetDuration(sampleBuffer)
        if explicit.isValid {
            let seconds = CMTimeGetSeconds(explicit)
            if seconds.isFinite && seconds > 0 {
                return seconds
            }
        }

        let sampleCount = CMSampleBufferGetNumSamples(sampleBuffer)
        if
            let description = CMSampleBufferGetFormatDescription(sampleBuffer),
            let streamDescription = CMAudioFormatDescriptionGetStreamBasicDescription(description)
        {
            let rate = streamDescription.pointee.mSampleRate
            if rate > 0 {
                return Double(sampleCount) / rate
            }
        }

        return 0
    }

    private static func decibels(of sampleBuffer: CMSampleBuffer) -> Double {
        guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
            return -.infinity
        }

        var lengthAtOffset = 0
        var totalLength = 0
        var dataPointer: UnsafeMutablePointer<Int8>?

        let status = CMBlockBufferGetDataPointer(
            dataBuffer,
            atOffset: 0,
            lengthAtOffsetOut: &lengthAtOffset,
            totalLengthOut: &totalLength,
            dataPointerOut: &dataPointer
        )

        guard
            status == kCMBlockBufferNoErr,
            let dataPointer,
            totalLength >= MemoryLayout<Int16>.size
        else {
            return -.infinity
        }

        let count = totalLength / MemoryLayout<Int16>.size
        var sumSquares = 0.0

        dataPointer.withMemoryRebound(to: Int16.self, capacity: count) { samples in
            for index in 0..<count {
                let normalized = Double(samples[index]) / Double(Int16.max)
                sumSquares += normalized * normalized
            }
        }

        guard count > 0 else {
            return -.infinity
        }

        let rms = sqrt(sumSquares / Double(count))
        guard rms > 0 else {
            return -.infinity
        }

        return 20.0 * log10(rms)
    }

    private static func appendRange(
        from start: Double,
        to end: Double,
        minimumSilence: Double,
        edgePadding: Double,
        into ranges: inout [SilenceRange]
    ) {
        guard end - start >= minimumSilence else {
            return
        }

        let skipStart = start + edgePadding
        let skipEnd = end - edgePadding
        guard skipEnd > skipStart else {
            return
        }

        ranges.append(SilenceRange(start: skipStart, end: skipEnd))
    }

    private static func mergeAdjacent(_ input: [SilenceRange]) -> [SilenceRange] {
        guard var current = input.first else {
            return []
        }

        var merged: [SilenceRange] = []
        for range in input.dropFirst() {
            if range.start <= current.end + 0.03 {
                current = SilenceRange(
                    start: current.start,
                    end: max(current.end, range.end)
                )
            } else {
                merged.append(current)
                current = range
            }
        }
        merged.append(current)
        return merged
    }
}
