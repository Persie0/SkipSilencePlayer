package at.persie0.skipsilenceplayer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

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
    fun analyze(
        context: Context,
        uri: Uri,
        thresholdDb: Float,
        minimumSilenceSeconds: Float,
        edgePaddingSeconds: Float
    ): List<SilenceRangeMs> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) {
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } else {
                    extractor.setDataSource(afd.fileDescriptor)
                }
            } ?: return emptyList()

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = format
                    break
                }
            }
            if (audioTrack < 0 || inputFormat == null) return emptyList()

            extractor.selectTrack(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var outputFormat = inputFormat
            var inputEnded = false
            var outputEnded = false
            var lastEndMs = 0L
            val info = MediaCodec.BufferInfo()
            val accumulator = SilenceAccumulator(
                thresholdDb = thresholdDb,
                minimumSilenceMs = (minimumSilenceSeconds * 1000f).toLong(),
                paddingMs = (edgePaddingSeconds * 1000f).toLong()
            )

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        val size = if (input != null) extractor.readSampleData(input, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                .coerceAtLeast(1)
                            val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceAtLeast(1)
                            val encoding = if (
                                outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            val bytesPerSample =
                                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size.toLong() /
                                (bytesPerSample * channels).coerceAtLeast(1)
                            val durationMs = frames * 1000L / rate
                            val startMs = info.presentationTimeUs.coerceAtLeast(0L) / 1000L
                            val endMs = startMs + durationMs
                            lastEndMs = maxOf(lastEndMs, endMs)
                            accumulator.accept(startMs, endMs, pcmDb(buffer, encoding))
                        }

                        outputEnded =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            return accumulator.finish(lastEndMs)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pcmDb(source: ByteBuffer, encoding: Int): Double {
        val buffer = source.slice().order(ByteOrder.nativeOrder())
        var squares = 0.0
        var count = 0

        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            while (buffer.remaining() >= 4) {
                val sample = buffer.float.toDouble().coerceIn(-1.0, 1.0)
                squares += sample * sample
                count++
            }
        } else {
            while (buffer.remaining() >= 2) {
                val sample = buffer.short.toDouble() / Short.MAX_VALUE.toDouble()
                squares += sample * sample
                count++
            }
        }

        if (count == 0) return Double.NEGATIVE_INFINITY
        val rms = sqrt(squares / count)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }
}
