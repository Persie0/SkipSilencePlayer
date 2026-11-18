package at.persie0.skipsilenceplayer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ensureActive

data class SilenceRangeMs(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

class SilenceAccumulator(
    private val thresholdDb: Float,
    minimumSilenceMs: Long,
    paddingMs: Long
) {
    private val minimumMs = minimumSilenceMs.coerceAtLeast(1L)
    private val padding = paddingMs.coerceAtLeast(0L)
    private var startMs: Long? = null
    private val output = mutableListOf<SilenceRangeMs>()

    fun accept(start: Long, end: Long, db: Double) {
        if (db <= thresholdDb) {
            if (startMs == null) startMs = start
        } else {
            close(start)
        }
    }

    fun finish(end: Long): List<SilenceRangeMs> {
        close(end)
        return output.toList()
    }

    private fun close(end: Long) {
        val start = startMs ?: return
        val rawDuration = end - start
        if (rawDuration >= minimumMs) {
            val clippedStart = start + padding
            val clippedEnd = end - padding
            if (clippedEnd > clippedStart) {
                output += SilenceRangeMs(clippedStart, clippedEnd)
            }
        }
        startMs = null
    }
}

object AndroidSilenceAnalyzer {
    private const val MAX_WINDOWS = 900_000 // Roughly five hours of 20 ms samples.

    suspend fun analyze(
        context: Context,
        uri: Uri,
        thresholdDb: Float,
        minimumSilenceSeconds: Float,
        edgePaddingSeconds: Float,
        adaptive: Boolean = true
    ): List<SilenceRangeMs> {
        return analyzeDetailed(
            context, uri, thresholdDb, minimumSilenceSeconds,
            edgePaddingSeconds, adaptive
        ).ranges
    }

    suspend fun analyzeDetailed(
        context: Context,
        uri: Uri,
        thresholdDb: Float,
        minimumSilenceSeconds: Float,
        edgePaddingSeconds: Float,
        adaptive: Boolean,
        onProgress: (suspend (SilenceDetectionResult, Long) -> Unit)? = null
    ): SilenceDetectionResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val windows = ArrayList<AudioLoudnessWindow>()
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) {
                    extractor.setDataSource(
                        afd.fileDescriptor, afd.startOffset, afd.length
                    )
                } else {
                    extractor.setDataSource(afd.fileDescriptor)
                }
            } ?: throw java.io.FileNotFoundException("Media is no longer accessible.")

            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    extractor.selectTrack(index)
                    audioFormat = format
                    break
                }
            }

            val format = audioFormat
                ?: return SilenceDetectionResult(emptyList(), thresholdDb, -100f, adaptive)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Audio track has no decoder MIME type")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var outputFormat = format
            var inputEnded = false
            var outputEnded = false
            val bufferInfo = MediaCodec.BufferInfo()
            var stalled = 0
            var lastPublishedMs = 0L

            while (!outputEnded && windows.size < MAX_WINDOWS) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                        input?.clear()
                        val size =
                            if (input == null) -1 else extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, size,
                                extractor.sampleTime.coerceAtLeast(0L), 0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        stalled = 0
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        stalled++
                        if (inputEnded && stalled > 150) {
                            throw java.io.IOException("Audio decoder stopped producing output")
                        }
                    }

                    else -> if (outputIndex >= 0) {
                        stalled = 0
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && bufferInfo.size > 0) {
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            windows.addAll(
                                pcmWindows(
                                    source = output.slice().order(ByteOrder.nativeOrder()),
                                    timestampUs = bufferInfo.presentationTimeUs,
                                    outputFormat = outputFormat
                                )
                            )
                        }
                        outputEnded =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)

                        val analyzedUntil = windows.lastOrNull()?.endMs ?: 0L
                        val isFirstPreview =
                            lastPublishedMs == 0L && analyzedUntil >= 10_000L
                        val isNextPreview =
                            lastPublishedMs > 0L &&
                                analyzedUntil - lastPublishedMs >= 90_000L
                        if (onProgress != null && (isFirstPreview || isNextPreview)) {
                            lastPublishedMs = analyzedUntil
                            val partial = AdaptiveSilenceDetector.detect(
                                windows = windows,
                                manualThresholdDb = thresholdDb,
                                minimumSilenceMs =
                                    (minimumSilenceSeconds.coerceIn(0.2f, 2f) * 1000).toLong(),
                                paddingMs =
                                    (edgePaddingSeconds.coerceIn(0.02f, 0.20f) * 1000).toLong(),
                                adaptive = adaptive
                            ).copy(
                                complete = false,
                                analyzedUntilMs = analyzedUntil
                            )
                            onProgress(partial, analyzedUntil)
                        }
                    }
                }
            }

            return AdaptiveSilenceDetector.detect(
                windows = windows,
                manualThresholdDb = thresholdDb,
                minimumSilenceMs =
                    (minimumSilenceSeconds.coerceIn(0.2f, 2f) * 1000).toLong(),
                paddingMs =
                    (edgePaddingSeconds.coerceIn(0.02f, 0.20f) * 1000).toLong(),
                adaptive = adaptive
            ).copy(
                complete = outputEnded,
                analyzedUntilMs = windows.lastOrNull()?.endMs ?: 0L
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun pcmWindows(
        source: ByteBuffer,
        timestampUs: Long,
        outputFormat: MediaFormat
    ): List<AudioLoudnessWindow> {
        val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            .coerceAtLeast(1)
        val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            .coerceAtLeast(1)
        val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        if (
            encoding != AudioFormat.ENCODING_PCM_16BIT &&
            encoding != AudioFormat.ENCODING_PCM_FLOAT
        ) {
            throw IllegalArgumentException("Unsupported decoded PCM format: $encoding")
        }

        val bytesPerSample =
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = channels * bytesPerSample
        val totalFrames = source.remaining() / bytesPerFrame
        val framesPerWindow = (rate / 50).coerceAtLeast(1)
        val baseUs = timestampUs.coerceAtLeast(0L)
        val result = ArrayList<AudioLoudnessWindow>(
            (totalFrames / framesPerWindow) + 1
        )

        var processedFrames = 0
        while (processedFrames < totalFrames) {
            val currentFrames = minOf(
                framesPerWindow, totalFrames - processedFrames
            )
            var sumSquares = 0.0
            for (index in 0 until currentFrames * channels) {
                val value = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    source.float.toDouble().takeIf { it.isFinite() }
                        ?.coerceIn(-1.0, 1.0) ?: 0.0
                } else {
                    source.short.toDouble() / Short.MAX_VALUE.toDouble()
                }
                sumSquares += value * value
            }

            val samples = (currentFrames * channels).coerceAtLeast(1)
            val rms = kotlin.math.sqrt(sumSquares / samples)
            val db = if (rms > 0) 20.0 * kotlin.math.log10(rms) else -100.0
            val start = (baseUs + processedFrames * 1_000_000L / rate) / 1000L
            val end = (
                baseUs + (processedFrames + currentFrames) * 1_000_000L / rate
            ) / 1000L

            if (end > start) {
                result.add(AudioLoudnessWindow(start, end, db))
            }
            processedFrames += currentFrames
        }
        return result
    }
}
