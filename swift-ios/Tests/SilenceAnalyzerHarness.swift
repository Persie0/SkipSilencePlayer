import AVFoundation
import Foundation

@main
struct SilenceAnalyzerHarness {
    static func main() async throws {
        guard CommandLine.arguments.count == 2 else {
            fputs("usage: SilenceAnalyzerHarness <wav>\n", stderr)
            exit(2)
        }

        let url = URL(fileURLWithPath: CommandLine.arguments[1])
        let result = try await SilenceAnalyzer().analyze(
            url: url,
            thresholdDB: -40,
            minimumSilence: 0.4,
            edgePadding: 0.05
        )

        guard result.hasAudio else {
            throw HarnessError("fixture unexpectedly has no audio")
        }
        guard result.ranges.count == 2 else {
            throw HarnessError("expected 2 silence ranges, got \(result.ranges.count)")
        }

        let first = result.ranges[0]
        let second = result.ranges[1]

        guard abs(first.start - 1.05) < 0.12,
              abs(first.end - 2.15) < 0.12 else {
            throw HarnessError("unexpected first range: \(first)")
        }

        guard abs(second.start - 3.25) < 0.12,
              abs(second.end - 4.45) < 0.12 else {
            throw HarnessError("unexpected second range: \(second)")
        }

        let total = result.ranges.reduce(0) { $0 + $1.duration }
        guard total > 2.0 && total < 2.4 else {
            throw HarnessError("unexpected total skippable time: \(total)")
        }

        print("SilenceAnalyzer real-media fixture passed: \(result.ranges)")
    }
}

struct HarnessError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) {
        self.description = description
    }
}
