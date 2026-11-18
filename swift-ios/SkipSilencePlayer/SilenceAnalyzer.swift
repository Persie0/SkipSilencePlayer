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
    let effectiveThresholdDB: Double
    let noiseFloorDB: Double
    let complete: Bool
    let analyzedUntil: Double
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
        edgePadding: Double = 0.08,
        adaptive: Bool = true,
        onProgress: (@Sendable (SilenceAnalysis) async -> Void)? = nil
    ) async throws -> SilenceAnalysis {
        let asset = AVURLAsset(url: url)
        let durationTime = try await asset.load(.duration)
        let durationValue = CMTimeGetSeconds(durationTime)
        let duration = durationValue.isFinite ? max(0, durationValue) : 0
        let tracks = try await asset.loadTracks(withMediaType: .audio)

        guard let track = tracks.first else {
            return SilenceAnalysis(
                ranges: [],
                hasAudio: false,
                duration: duration,
                effectiveThresholdDB: thresholdDB,
                noiseFloorDB: -100,
                complete: true,
                analyzedUntil: duration
            )
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

        var windows: [PCMWindow] = []
        var lastPublishedAt = 0.0
        while let sampleBuffer = output.copyNextSampleBuffer() {
            if Task.isCancelled {
                reader.cancelReading()
                throw CancellationError()
            }

            let sampleWindows = Self.pcmWindows(of: sampleBuffer)
            if sampleWindows.isEmpty {
                // A decoded PCM buffer with samples but no accessible PCM data
                // is NOT silence. Fail closed instead of skipping speech.
                if CMSampleBufferGetNumSamples(sampleBuffer) > 0 {
                    reader.cancelReading()
                    throw AnalyzerError.cannotRead
                }
                continue
            }
            windows.append(contentsOf: sampleWindows)

            if let onProgress, let last = windows.last {
                let scannedUntil = last.end
                let shouldPublish =
                    (lastPublishedAt == 0 && scannedUntil >= 10) ||
                    (lastPublishedAt > 0 && scannedUntil - lastPublishedAt >= 90)
                if shouldPublish {
                    lastPublishedAt = scannedUntil
                    let partial = Self.detect(
                        windows: windows,
                        manualThresholdDB: thresholdDB,
                        minimumSilence: minimumSilence,
                        edgePadding: edgePadding,
                        adaptive: adaptive
                    )
                    await onProgress(
                        SilenceAnalysis(
                            ranges: partial.ranges,
                            hasAudio: true,
                            duration: duration,
                            effectiveThresholdDB: partial.threshold,
                            noiseFloorDB: partial.floor,
                            complete: false,
                            analyzedUntil: scannedUntil
                        )
                    )
                }
            }
        }

        if reader.status == .failed {
            throw reader.error ?? AnalyzerError.readerFailed
        }

        let detection = Self.detect(
            windows: windows,
            manualThresholdDB: thresholdDB,
            minimumSilence: minimumSilence,
            edgePadding: edgePadding,
            adaptive: adaptive
        )
        return SilenceAnalysis(
            ranges: detection.ranges,
            hasAudio: true,
            duration: duration,
            effectiveThresholdDB: detection.threshold,
            noiseFloorDB: detection.floor,
            complete: true,
            analyzedUntil: duration
        )
    }

    private struct Detection {
        let ranges: [SilenceRange]
        let threshold: Double
        let floor: Double
    }

    private static func detect(
        windows: [PCMWindow],
        manualThresholdDB: Double,
        minimumSilence: Double,
        edgePadding: Double,
        adaptive: Bool
    ) -> Detection {
        guard !windows.isEmpty else {
            return Detection(ranges: [], threshold: manualThresholdDB, floor: -100)
        }
        // Bounded 0.1 dB histogram avoids resorting every decoded window
        // on each progressive update of a long recording.
        var bins = [Int](repeating: 0, count: 1001)
        var digitalCount = 0
        var audibleCount = 0
        for window in windows {
            let db = window.db.isNaN
                ? 0.0 : min(0.0, max(-100.0, window.db))
            let bucket = min(1000, max(0, Int((db + 100) * 10)))
            bins[bucket] += 1
            if db <= -80 {
                digitalCount += 1
            } else {
                audibleCount += 1
            }
        }

        func percentile(
            _ fraction: Double,
            firstBucket: Int = 0
        ) -> Double {
            let total = firstBucket == 0 ? windows.count : audibleCount
            guard total > 0 else { return -100 }
            let rank = Int(Double(total - 1) * fraction)
            var seen = 0
            for bucket in firstBucket...1000 {
                seen += bins[bucket]
                if seen > rank {
                    return -100 + Double(bucket) / 10
                }
            }
            return 0
        }

        let digitalShare = Double(digitalCount) / Double(windows.count)
        let audibleFloor = audibleCount >= 10
            ? percentile(0.25, firstBucket: 201) : -100
        let floor = digitalShare > 0.10 && audibleCount >= 10
            ? audibleFloor : percentile(0.20)
        let upper = percentile(0.95)
        let speechCap = upper - floor >= 14
            ? upper - 12 : floor - 6
        let candidate = adaptive
            ? min(max(manualThresholdDB - 10, floor + 8), speechCap)
            : manualThresholdDB
        let threshold = min(-20.0, max(-60.0, candidate))

        var ranges: [SilenceRange] = []
        var quietStart: Double?
        var quietEnd = 0.0

        func appendQuietRange() {
            guard let start = quietStart else { return }
            let end = quietEnd
            if
                end - start >= max(0.1, minimumSilence),
                end - start > edgePadding * 2 + 0.08
            {
                ranges.append(
                    SilenceRange(
                        start: start + edgePadding,
                        end: end - edgePadding
                    )
                )
            }
            quietStart = nil
        }

        for window in windows {
            if window.start < 0 || window.end <= window.start {
                continue
            }
            if quietStart != nil && window.start > quietEnd + 0.06 {
                appendQuietRange()
            }
            if window.db <= threshold {
                if quietStart == nil {
                    quietStart = window.start
                }
                quietEnd = max(quietEnd, window.end)
            } else {
                appendQuietRange()
            }
        }
        appendQuietRange()
        return Detection(ranges: ranges, threshold: threshold, floor: floor)
    }

    private struct PCMWindow {
        let start: Double
        let end: Double
        let db: Double
    }

    private static func pcmWindows(
        of sampleBuffer: CMSampleBuffer,
        targetDuration: Double = 0.02
    ) -> [PCMWindow] {
        guard
            let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer),
            let description = CMSampleBufferGetFormatDescription(sampleBuffer),
            let stream =
                CMAudioFormatDescriptionGetStreamBasicDescription(description)
        else {
            return []
        }

        let sampleRate = stream.pointee.mSampleRate
        let channels = Int(stream.pointee.mChannelsPerFrame)
        guard sampleRate > 0, channels > 0 else {
            return []
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
            totalLength >= MemoryLayout<Int16>.size * channels
        else {
            return []
        }

        let reportedFrames = CMSampleBufferGetNumSamples(sampleBuffer)
        let availableSamples = totalLength / MemoryLayout<Int16>.size
        let availableFrames = availableSamples / channels
        let frameCount = min(reportedFrames, availableFrames)
        guard frameCount > 0 else {
            return []
        }

        let presentation = CMTimeGetSeconds(
            CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        )
        guard presentation.isFinite else {
            return []
        }

        let windowFrames = max(1, Int((sampleRate * targetDuration).rounded()))
        var result: [PCMWindow] = []

        dataPointer.withMemoryRebound(
            to: Int16.self,
            capacity: availableSamples
        ) { samples in
            var frameStart = 0

            while frameStart < frameCount {
                let frameEnd = min(frameStart + windowFrames, frameCount)
                let sampleStart = frameStart * channels
                let sampleEnd = frameEnd * channels
                var sumSquares = 0.0

                for index in sampleStart..<sampleEnd {
                    let normalized =
                        Double(samples[index]) / Double(Int16.max)
                    sumSquares += normalized * normalized
                }

                let count = sampleEnd - sampleStart
                let rms =
                    count > 0
                        ? sqrt(sumSquares / Double(count))
                        : 0
                let db =
                    rms > 0
                        ? 20.0 * log10(rms)
                        : -.infinity

                let start =
                    presentation + Double(frameStart) / sampleRate
                let end =
                    presentation + Double(frameEnd) / sampleRate

                result.append(
                    PCMWindow(
                        start: start,
                        end: end,
                        db: db
                    )
                )
                frameStart = frameEnd
            }
        }

        return result
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
