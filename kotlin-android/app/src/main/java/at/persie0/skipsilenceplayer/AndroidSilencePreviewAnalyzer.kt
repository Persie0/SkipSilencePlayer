package at.persie0.skipsilenceplayer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

internal data class SilencePreview(
    val durationMs: Long,
    val skippableMs: Long
)

internal object AndroidSilencePreviewAnalyzer {
    suspend fun analyze(
        context: Context,
        uri: Uri,
        thresholdDb: Float,
        minimumSilenceSeconds: Float,
        edgePaddingSeconds: Float
    ): SilencePreview? = withContext(Dispatchers.IO) {
        runCatching {
            analyzeBlocking(
                context,
                uri,
                thresholdDb,
                minimumSilenceSeconds,
                edgePaddingSeconds
            )
        }.getOrNull()
    }

    private fun analyzeBlocking(
        context: Context,
        uri: Uri,
        thresholdDb: Float,
        minimumSilenceSeconds: Float,
        edgePaddingSeconds: Float
    ): SilencePreview? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) {
                    extractor.setDataSource(
                        afd.fileDescriptor,
                        afd.startOffset,
                        afd.length
                    )
                } else {
                    extractor.setDataSource(afd.fileDescriptor)
                }
            } ?: return null

            var audioTrack = -1
            var inputFormat: MediaFormat? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = format
                    break
                }
            }

            if (audioTrack < 0 || inputFormat == null) {
                return null
            }

            extractor.selectTrack(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sourceDurationUs = if (
                inputFormat.containsKey(MediaFormat.KEY_DURATION)
            ) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val selectedFormat = inputFormat ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(selectedFormat, null, null, 0)
            decoder.start()

            var outputFormat: MediaFormat = selectedFormat
            var inputEnded = false
            var outputEnded = false

            val info = MediaCodec.BufferInfo()
            var silenceStartUs: Long? = null
            var skippableUs = 0L
            var lastEndUs = 0L

            val minimumUs =
                (minimumSilenceSeconds.coerceIn(0.2f, 2f) * 1_000_000L).toLong()
            val paddingUs =
                (edgePaddingSeconds.coerceIn(0.02f, 0.20f) * 1_000_000L).toLong()

            fun closeSilence(endUs: Long) {
                val startUs = silenceStartUs ?: return
                val total = endUs - startUs
                if (total >= minimumUs) {
                    skippableUs +=
                        (total - paddingUs * 2L).coerceAtLeast(0L)
                }
                silenceStartUs = null
            }

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        val size = if (buffer != null) {
                            extractor.readSampleData(buffer, 0)
                        } else {
                            -1
                        }

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
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val sampleRate = outputFormat
                                .getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                .coerceAtLeast(1)
                            val channels = outputFormat
                                .getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceAtLeast(1)
                            val pcmEncoding = if (
                                outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }

                            val db = pcmDb(buffer, pcmEncoding)
                            val bytesPerSample =
                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames =
                                info.size.toLong() / (bytesPerSample * channels).coerceAtLeast(1)
                            val bufferDurationUs =
                                frames * 1_000_000L / sampleRate
                            val startUs = info.presentationTimeUs.coerceAtLeast(0L)
                            val endUs = startUs + bufferDurationUs
                            lastEndUs = maxOf(lastEndUs, endUs)

                            if (db <= thresholdDb) {
                                if (silenceStartUs == null) {
                                    silenceStartUs = startUs
                                }
                            } else {
                                closeSilence(startUs)
                            }
                        }

                        outputEnded =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            closeSilence(maxOf(lastEndUs, sourceDurationUs))

            val durationUs = maxOf(sourceDurationUs, lastEndUs)
            SilencePreview(
                durationMs = durationUs / 1000L,
                skippableMs = skippableUs / 1000L
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pcmDb(
        source: java.nio.ByteBuffer,
        encoding: Int
    ): Double {
        val buffer = source.slice().order(ByteOrder.nativeOrder())

        var sumSquares = 0.0
        var count = 0

        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            while (buffer.remaining() >= 4) {
                val sample = buffer.float.toDouble().coerceIn(-1.0, 1.0)
                sumSquares += sample * sample
                count++
            }
        } else {
            while (buffer.remaining() >= 2) {
                val sample = buffer.short.toDouble() / Short.MAX_VALUE.toDouble()
                sumSquares += sample * sample
                count++
            }
        }

        if (count == 0) return Double.NEGATIVE_INFINITY
        val rms = sqrt(sumSquares / count.toDouble())
        if (rms <= 0.0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(rms)
    }
}
