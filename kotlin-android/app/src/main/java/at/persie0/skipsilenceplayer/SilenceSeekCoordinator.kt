package at.persie0.skipsilenceplayer

import kotlin.math.abs

/**
 * Tracks exactly one automatic silence seek. Media3 may deliver its position
 * discontinuity synchronously from seekTo(), so call propose() before seekTo().
 *
 * Never credit a requested seek that failed, timed out or was superseded.
 * Manual seeks do not create proposals and therefore cannot inflate savings.
 */
internal class SilenceSeekCoordinator {
    private data class Pending(
        val mediaId: String,
        val startMs: Long,
        val targetMs: Long,
        val requestedAtMs: Long
    )

    private var pending: Pending? = null
    private var lastMediaId = ""
    private var lastTargetMs = -1L
    private var lastAttemptAtMs = Long.MIN_VALUE

    fun propose(
        mediaId: String,
        positionMs: Long,
        targetMs: Long,
        nowMs: Long
    ): Boolean {
        if (mediaId.isBlank() || positionMs < 0 || targetMs - positionMs < 65L) {
            return false
        }

        val existing = pending
        if (existing != null) {
            if (nowMs - existing.requestedAtMs < 1500L) {
                return false
            }
            pending = null
        }

        if (
            lastMediaId == mediaId &&
            lastTargetMs == targetMs &&
            nowMs >= lastAttemptAtMs &&
            nowMs - lastAttemptAtMs < 900L
        ) {
            return false
        }

        pending = Pending(mediaId, positionMs, targetMs, nowMs)
        lastMediaId = mediaId
        lastTargetMs = targetMs
        lastAttemptAtMs = nowMs
        return true
    }

    /**
     * An observed Media3 SEEK discontinuity completes the proposal.
     * A different transition, rejected target, or unrelated manual seek
     * never receives a saved-time credit.
     */
    fun onDiscontinuity(
        mediaId: String,
        oldPositionMs: Long,
        newPositionMs: Long,
        isSeek: Boolean
    ): Long {
        val request = pending ?: return 0L
        pending = null
        if (!isSeek || request.mediaId != mediaId) return 0L
        if (
            oldPositionMs < request.startMs - 200L ||
            oldPositionMs >= request.targetMs ||
            abs(newPositionMs - request.targetMs) > 250L
        ) {
            return 0L
        }
        return (newPositionMs - oldPositionMs).coerceAtLeast(0L)
    }

    fun cancel() {
        pending = null
    }

    fun reset() {
        pending = null
        lastMediaId = ""
        lastTargetMs = -1L
        lastAttemptAtMs = Long.MIN_VALUE
    }
}
