package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.meanOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How many samples the pose stamped onto a frame is averaged over. */
private const val DEFAULT_POSE_WINDOW_SIZE = 20

/**
 * The slow-moving half of guided capture's state: which target the run is on,
 * and what it is walking.
 *
 * Separated from [AlignmentState] by how often it changes. This turns over at
 * capture rate — a few times a minute — so a screen can read it during
 * composition; the reticle's alignment turns over at the sensor's rate and is
 * handed back from [SphereCaptureCoordinator.onSample] instead, for the caller
 * to route somewhere that does not recompose.
 */
data class CaptureGuidance(
    /** The plan being walked, or null until the first fix lays one out. */
    val plan: SphereTargetPlan? = null,
    /** Index of the target the reticle is guiding toward. */
    val activeIndex: Int = 0,
    /** True from the moment the shutter is authorised until its frame lands. */
    val isCapturing: Boolean = false,
) {
    val totalTargets: Int get() = plan?.size ?: 0

    /** True once every target in the plan has been shot. */
    val isComplete: Boolean get() = totalTargets > 0 && activeIndex >= totalTargets

    /** Targets covered so far, never past the end of the plan. */
    val capturedTargets: Int get() = activeIndex.coerceAtMost(totalTargets)

    val completedRings: Int get() = plan?.completedRings(capturedTargets) ?: 0

    val ringCount: Int get() = plan?.ringCount ?: 0
}

/**
 * Identifies one shutter.
 *
 * A capture is authorised, the frame is written, and only then is the result
 * reported back — which takes long enough that the run can be restarted or
 * stepped back underneath it. The token is what lets
 * [SphereCaptureCoordinator.onCaptured] tell "the frame I authorised" from "a
 * frame belonging to a run that no longer exists", and discard the latter
 * rather than advancing a plan it was never aimed at.
 */
@JvmInline
value class CaptureToken internal constructor(private val epoch: Long)

/** What one orientation sample means for the capture screen. */
sealed interface CaptureDecision {

    /** Nothing to act on: no fix yet, or the run is busy elsewhere. */
    data object Ignore : CaptureDecision

    /** Keep guiding; [alignment] and [isHolding] are what the reticle should show. */
    data class Guide(
        val alignment: AlignmentState,
        val isHolding: Boolean,
    ) : CaptureDecision

    /**
     * The dwell completed. Shoot frame [index] at [pose], then report the
     * outcome with [token].
     */
    data class Capture(
        val index: Int,
        val pose: OrientationData,
        val token: CaptureToken,
        val alignment: AlignmentState,
    ) : CaptureDecision
}

/**
 * The capture loop's decision-making, with no camera and no Compose behind it.
 *
 * This is the rule that decides whether the shutter fires: a sample is turned
 * into an angular distance from the active target, run past the sensor's own
 * confidence, and handed to [AlignmentGate]; a completed dwell comes back as
 * [CaptureDecision.Capture] carrying the mean attitude over that dwell. The
 * caller owns the camera and does the shooting, then reports back through
 * [onCaptured] or [onCaptureFailed].
 *
 * **Why it is a class and not a `LaunchedEffect`.** Every mutation of the run's
 * position — a frame landing, an undo, a restart — is serialised through one
 * [Mutex], which is what makes them safe against each other. They are not
 * naturally exclusive: a capture takes hundreds of milliseconds to write, the
 * loop suspends across it, and an undo tapped during that window used to read
 * and write `activeIndex` in between the capture reading it and writing it
 * back. Whichever wrote last won, and the reticle was left pointing at a target
 * that had never been shot. Here an undo during a shutter is refused outright
 * (see [undo]) and a completion whose run has since moved is discarded (see
 * [CaptureToken]).
 *
 * All state changes are suspending because of that lock. Reads go through
 * [guidance], which is safe from anywhere.
 */
class SphereCaptureCoordinator(
    private val gate: AlignmentGate = AlignmentGate(),
    private val poseWindowSize: Int = DEFAULT_POSE_WINDOW_SIZE,
) {

    private val mutex = Mutex()

    /**
     * The samples the next frame's pose will be averaged over.
     *
     * The gate confirms an aim held still for a dwell, so this window is all
     * deliberate stops and its mean is a quieter estimate of where the camera
     * was than the single sample that happened to trip the trigger. Anything
     * that ends a dwell empties it, so a mean never spans two different aims.
     */
    private val poseWindow = ArrayDeque<OrientationData>()

    /**
     * Bumped by every change to the run's position. Outstanding captures carry
     * the value they were authorised under and are discarded if it has moved.
     */
    private var epoch = 0L

    private var isCapturing = false

    private val _guidance = MutableStateFlow(CaptureGuidance())

    /** Where the run currently is. Safe to read from composition. */
    val guidance: StateFlow<CaptureGuidance> = _guidance.asStateFlow()

    /**
     * Feeds one attitude sample to the loop.
     *
     * @param nowMillis a monotonic clock, e.g. `SystemClock.elapsedRealtime()`.
     *   Supplied by the caller so the dwell can be driven directly in tests.
     * @param isStitching true while the frames are owned by a stitch, which
     *   holds the shutter: a frame landing halfway through would not be in the
     *   set being stitched and would be deleted with it.
     * @param createPlan lays out the plan around the bearing the user is
     *   already facing, called once on the first sample that carries a fix.
     */
    suspend fun onSample(
        orientation: OrientationData,
        nowMillis: Long,
        isStitching: Boolean,
        createPlan: (startYawDegrees: Float) -> SphereTargetPlan,
    ): CaptureDecision = mutex.withLock {
        if (!orientation.hasFix) return@withLock CaptureDecision.Ignore

        // A shutter is already in flight. The caller's loop suspends across a
        // capture and so cannot reach here, but the rule belongs with the state
        // it protects rather than with the one caller that happens to respect
        // it: a second capture authorised against the same index would write
        // two frames over one target.
        if (isCapturing || isStitching) {
            endDwell()
            return@withLock CaptureDecision.Ignore
        }

        val plan = _guidance.value.plan
            ?: createPlan(orientation.yawDegrees).also { created ->
                _guidance.value = _guidance.value.copy(plan = created)
            }

        val target = plan.getOrNull(_guidance.value.activeIndex)
        if (target == null) {
            // The sphere is walked; stop guiding until a new run starts.
            endDwell()
            return@withLock CaptureDecision.Guide(AlignmentState(), isHolding = false)
        }

        val distance = SphereProjection.angularDistanceDegrees(orientation, target)

        // Careful shooting: no frame is taken while the fused sensor says its
        // own output is not to be believed. An unreliable magnetometer drifts
        // the reported aim by degrees even when the phone is still, and a frame
        // placed by that aim lands off its target no matter how long it is
        // held. Merely *uncalibrated* is fine — see OrientationAccuracy.
        if (!orientation.accuracy.allowsCapture) {
            endDwell()
            return@withLock CaptureDecision.Guide(
                alignment = AlignmentState(distanceDegrees = distance),
                isHolding = false,
            )
        }

        poseWindow.addLast(orientation)
        while (poseWindow.size > poseWindowSize) poseWindow.removeFirst()

        val reading = gate.update(distance, nowMillis)
        val alignment = AlignmentState(
            distanceDegrees = distance,
            dwellProgress = reading.dwellProgress,
            isAligned = reading.isAligned,
        )

        // The mean is only trustworthy if every sample in it came from this
        // deliberate stop, so a sample that fails the gate empties the window.
        // When the shutter fires, the window is exactly the dwell.
        if (!reading.isAligned) poseWindow.clear()

        if (!reading.isTriggered) {
            return@withLock CaptureDecision.Guide(alignment, isHolding = reading.isAligned)
        }

        val pose = meanOrientation(poseWindow.toList())
        poseWindow.clear()
        isCapturing = true
        _guidance.value = _guidance.value.copy(isCapturing = true)

        CaptureDecision.Capture(
            index = _guidance.value.activeIndex,
            pose = pose,
            token = CaptureToken(epoch),
            alignment = alignment.copy(isCapturing = true),
        )
    }

    /**
     * Reports that the frame authorised by [token] landed, advancing to the
     * next target.
     *
     * A token from a run that has since been reset or stepped back is ignored:
     * its frame belongs to a set that no longer exists, and advancing on it
     * would put the reticle past a target nothing was ever shot at.
     *
     * @return true when the run advanced.
     */
    suspend fun onCaptured(token: CaptureToken): Boolean = mutex.withLock {
        if (!isCurrent(token)) return@withLock false
        isCapturing = false
        // Read the index back out rather than trusting the one handed to the
        // caller: the token has already established that it has not moved.
        _guidance.value = _guidance.value.copy(
            activeIndex = _guidance.value.activeIndex + 1,
            isCapturing = false,
        )
        endDwell()
        true
    }

    /**
     * Reports that the frame authorised by [token] failed to land.
     *
     * The run stays on the same target, so a failed capture is retried rather
     * than silently skipped.
     *
     * @return true when the run was still waiting on this capture.
     */
    suspend fun onCaptureFailed(token: CaptureToken): Boolean = mutex.withLock {
        if (!isCurrent(token)) return@withLock false
        isCapturing = false
        _guidance.value = _guidance.value.copy(isCapturing = false)
        endDwell()
        true
    }

    /**
     * Steps the run back onto the target of the frame [dropLastFrame] removes.
     *
     * [dropLastFrame] does the removing and returns the plan index it dropped,
     * or null when there was nothing to drop. It runs under the coordinator's
     * lock, so the buffer and the run's position move together — an undo can
     * neither interleave with a capture completing nor with another undo.
     *
     * Refused outright while a shutter is in flight: that frame is not in the
     * buffer yet, so the undo would drop the *previous* one and then be
     * overwritten by the capture's own advance, leaving the reticle past a
     * target that was never shot. The caller disables the control for the same
     * window; this is the backstop that makes the disable unnecessary to trust.
     *
     * @return true when a frame was dropped.
     */
    suspend fun undo(dropLastFrame: suspend () -> Int?): Boolean = mutex.withLock {
        if (isCapturing) return@withLock false
        val index = dropLastFrame() ?: return@withLock false
        epoch++
        endDwell()
        _guidance.value = _guidance.value.copy(activeIndex = index)
        true
    }

    /**
     * Throws the run away: no plan, no progress, no half-completed dwell.
     *
     * Used both when the user starts over and when the optics or the capture
     * scope change, since a plan laid out against either is invalid once they
     * move. Any capture still in flight is orphaned by the epoch bump and will
     * not advance the run that replaces it.
     */
    suspend fun reset() = mutex.withLock {
        epoch++
        isCapturing = false
        endDwell()
        _guidance.value = CaptureGuidance()
    }

    /** Drops the part-completed dwell and the samples it had collected. */
    private fun endDwell() {
        gate.reset()
        poseWindow.clear()
    }

    private fun isCurrent(token: CaptureToken): Boolean = token == CaptureToken(epoch)
}
