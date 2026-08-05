package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture loop's rule, driven directly.
 *
 * The interesting cases are not the happy path — they are what happens when a
 * frame is still being written and something else moves the run underneath it,
 * which on a device is a few hundred milliseconds wide and effectively
 * impossible to reproduce by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SphereCaptureCoordinatorTest {

    /** A three-target ring, one every 90°, level with the horizon. */
    private val plan = SphereTargetPlan(
        targets = listOf(
            SphereTarget(yawDegrees = 0f, pitchDegrees = 0f),
            SphereTarget(yawDegrees = 90f, pitchDegrees = 0f),
            SphereTarget(yawDegrees = 180f, pitchDegrees = 0f),
        ),
    )

    private fun coordinator() = SphereCaptureCoordinator(
        gate = AlignmentGate(thresholdDegrees = 2f, dwellMillis = 300L),
    )

    private fun sample(
        yawDegrees: Float,
        pitchDegrees: Float = 0f,
        accuracy: OrientationAccuracy = OrientationAccuracy.High,
    ) = OrientationData(
        yawDegrees = yawDegrees,
        pitchDegrees = pitchDegrees,
        accuracy = accuracy,
        // Anything above zero counts as a fix.
        timestampNanos = 1L,
    )

    private suspend fun SphereCaptureCoordinator.feed(
        yawDegrees: Float,
        nowMillis: Long,
        accuracy: OrientationAccuracy = OrientationAccuracy.High,
        isStitching: Boolean = false,
    ): CaptureDecision = onSample(
        orientation = sample(yawDegrees, accuracy = accuracy),
        nowMillis = nowMillis,
        isStitching = isStitching,
        createPlan = { plan },
    )

    /** Holds the aim on the active target until the dwell trips the shutter. */
    private suspend fun SphereCaptureCoordinator.holdUntilCapture(
        yawDegrees: Float,
        startMillis: Long,
    ): CaptureDecision.Capture {
        feed(yawDegrees, startMillis)
        val decision = feed(yawDegrees, startMillis + 300L)
        return decision as CaptureDecision.Capture
    }

    @Test
    fun `a sample without a fix is ignored`() = runTest {
        val coordinator = coordinator()

        val decision = coordinator.onSample(
            orientation = OrientationData(),
            nowMillis = 0L,
            isStitching = false,
            createPlan = { plan },
        )

        assertEquals(CaptureDecision.Ignore, decision)
        // No plan is laid out from a sample that carries no bearing.
        assertNull(coordinator.guidance.value.plan)
    }

    @Test
    fun `the plan is laid out once, from the first bearing that lands`() = runTest {
        val coordinator = coordinator()
        var layouts = 0

        repeat(3) {
            coordinator.onSample(
                orientation = sample(yawDegrees = 42f),
                nowMillis = it * 10L,
                isStitching = false,
                createPlan = { layouts++; plan },
            )
        }

        assertEquals(1, layouts)
    }

    @Test
    fun `a held aim fires the shutter and advances only once it lands`() = runTest {
        val coordinator = coordinator()

        val capture = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)

        assertEquals(0, capture.index)
        // Still on the same target: the frame has not landed yet.
        assertEquals(0, coordinator.guidance.value.activeIndex)
        assertTrue(coordinator.guidance.value.isCapturing)

        assertTrue(coordinator.onCaptured(capture.token))
        assertEquals(1, coordinator.guidance.value.activeIndex)
        assertFalse(coordinator.guidance.value.isCapturing)
    }

    @Test
    fun `a failed capture stays on the same target`() = runTest {
        val coordinator = coordinator()

        val capture = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)
        assertTrue(coordinator.onCaptureFailed(capture.token))

        assertEquals(0, coordinator.guidance.value.activeIndex)
        assertFalse(coordinator.guidance.value.isCapturing)
    }

    @Test
    fun `an undo while a shutter is in flight is refused`() = runTest {
        val coordinator = coordinator()

        val capture = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)

        // The frame is not in the buffer yet, so this undo would drop the one
        // before it and then be overwritten by the capture's own advance.
        var bufferTouched = false
        val undone = coordinator.undo { bufferTouched = true; 0 }

        assertFalse(undone)
        assertFalse("the buffer must not be touched by a refused undo", bufferTouched)
        assertEquals(0, coordinator.guidance.value.activeIndex)

        // And the capture it refused for still completes normally.
        assertTrue(coordinator.onCaptured(capture.token))
        assertEquals(1, coordinator.guidance.value.activeIndex)
    }

    @Test
    fun `an undo steps the run back onto the dropped frame's target`() = runTest {
        val coordinator = coordinator()

        val first = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)
        coordinator.onCaptured(first.token)
        val second = coordinator.holdUntilCapture(yawDegrees = 90f, startMillis = 1_000L)
        coordinator.onCaptured(second.token)
        assertEquals(2, coordinator.guidance.value.activeIndex)

        assertTrue(coordinator.undo { 1 })
        assertEquals(1, coordinator.guidance.value.activeIndex)
    }

    @Test
    fun `an undo with nothing to drop leaves the run where it was`() = runTest {
        val coordinator = coordinator()

        val capture = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)
        coordinator.onCaptured(capture.token)

        assertFalse(coordinator.undo { null })
        assertEquals(1, coordinator.guidance.value.activeIndex)
    }

    /**
     * The race the coordinator exists for: the undo lands in the window between
     * the shutter being authorised and its frame being reported, which on a
     * device is the few hundred milliseconds a burst takes to write.
     */
    @Test
    fun `an undo interleaved with a capture cannot desync the run`() = runTest {
        val coordinator = coordinator()

        // Two frames already down; the run is on target 2.
        val first = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)
        coordinator.onCaptured(first.token)
        val second = coordinator.holdUntilCapture(yawDegrees = 90f, startMillis = 1_000L)
        coordinator.onCaptured(second.token)
        assertEquals(2, coordinator.guidance.value.activeIndex)

        // Target 2's shutter is authorised but its frame is still being written.
        val third = coordinator.holdUntilCapture(yawDegrees = 180f, startMillis = 2_000L)
        assertEquals(2, third.index)

        // The user taps undo mid-write. It is refused, so frame 1 survives.
        assertFalse(coordinator.undo { 1 })

        // The write finishes and reports back.
        assertTrue(coordinator.onCaptured(third.token))

        // Three frames shot, reticle on target 3 — not on 2 with a hole behind
        // it, which is what the unserialised version left behind.
        assertEquals(3, coordinator.guidance.value.activeIndex)
    }

    @Test
    fun `a capture that lands after a restart does not advance the new run`() = runTest {
        val coordinator = coordinator()

        val capture = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)

        // "Start over" while the frame is still being written. Its frames are
        // deleted with the session, so its completion must not count.
        coordinator.reset()
        assertEquals(0, coordinator.guidance.value.activeIndex)
        assertNull(coordinator.guidance.value.plan)

        assertFalse(coordinator.onCaptured(capture.token))
        assertEquals(0, coordinator.guidance.value.activeIndex)
    }

    @Test
    fun `a capture that lands after an undo does not advance the new run`() = runTest {
        val coordinator = coordinator()

        val first = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)
        coordinator.onCaptured(first.token)
        val second = coordinator.holdUntilCapture(yawDegrees = 90f, startMillis = 1_000L)

        // The undo is refused while the shutter is in flight, so force the
        // out-of-order case the token exists for: report the capture, undo, then
        // replay the stale completion.
        coordinator.onCaptured(second.token)
        assertTrue(coordinator.undo { 1 })
        assertEquals(1, coordinator.guidance.value.activeIndex)

        // A duplicate completion for a run that has since moved.
        assertFalse(coordinator.onCaptured(second.token))
        assertEquals(1, coordinator.guidance.value.activeIndex)
    }

    @Test
    fun `the shutter is held while a stitch owns the frames`() = runTest {
        val coordinator = coordinator()

        // A full dwell, but the frames belong to a stitch for its duration.
        assertEquals(CaptureDecision.Ignore, coordinator.feed(0f, 0L, isStitching = true))
        assertEquals(CaptureDecision.Ignore, coordinator.feed(0f, 300L, isStitching = true))
        assertEquals(0, coordinator.guidance.value.activeIndex)

        // And the dwell it interrupted does not carry over: the aim has to be
        // held again from scratch once the stitch releases the frames. A sample
        // 301 ms after the first would have fired had the dwell kept running.
        assertTrue(coordinator.feed(0f, 301L) is CaptureDecision.Guide)
        assertTrue(coordinator.feed(0f, 600L) is CaptureDecision.Guide)
        assertTrue(coordinator.feed(0f, 601L) is CaptureDecision.Capture)
    }

    @Test
    fun `no frame is taken while the sensor calls itself unreliable`() = runTest {
        val coordinator = coordinator()

        val held = coordinator.feed(0f, 0L, accuracy = OrientationAccuracy.Unreliable)
        val later = coordinator.feed(0f, 5_000L, accuracy = OrientationAccuracy.Unreliable)

        assertTrue(held is CaptureDecision.Guide)
        assertTrue(later is CaptureDecision.Guide)
        // The distance is still reported, so the reticle keeps tracking.
        assertEquals(0f, (later as CaptureDecision.Guide).alignment.distanceDegrees, 1e-3f)
        assertFalse(later.isHolding)
        assertEquals(0, coordinator.guidance.value.activeIndex)
    }

    @Test
    fun `merely uncalibrated still shoots`() = runTest {
        val coordinator = coordinator()

        coordinator.feed(0f, 0L, accuracy = OrientationAccuracy.Low)
        val decision = coordinator.feed(0f, 300L, accuracy = OrientationAccuracy.Low)

        assertTrue(decision is CaptureDecision.Capture)
    }

    @Test
    fun `a walked plan stops guiding`() = runTest {
        val coordinator = coordinator()

        var now = 0L
        repeat(plan.size) {
            val capture = coordinator.holdUntilCapture(
                yawDegrees = plan[it].yawDegrees,
                startMillis = now,
            )
            coordinator.onCaptured(capture.token)
            now += 1_000L
        }

        assertTrue(coordinator.guidance.value.isComplete)
        val decision = coordinator.feed(0f, now)
        assertTrue(decision is CaptureDecision.Guide)
        assertFalse((decision as CaptureDecision.Guide).isHolding)
        assertFalse(decision.alignment.hasDistance)
    }

    /**
     * The pose stamped onto a frame is the mean over its dwell, so a sample from
     * before the aim settled must not be in it.
     */
    @Test
    fun `the captured pose averages only the dwell's samples`() = runTest {
        val coordinator = coordinator()

        // Aimed well away from target 0, then settled onto it.
        coordinator.feed(yawDegrees = 40f, nowMillis = 0L)
        coordinator.feed(yawDegrees = 1f, nowMillis = 100L)
        val capture = coordinator.holdUntilCapture(yawDegrees = 1f, startMillis = 100L)

        // Every sample in the window sat at 1°; the 40° sample that preceded the
        // dwell was dropped when the gate failed it.
        assertEquals(1f, capture.pose.yawDegrees, 0.01f)
    }

    @Test
    fun `undo runs under the same lock as the sample loop`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()

        val capture = coordinator.holdUntilCapture(yawDegrees = 0f, startMillis = 0L)
        coordinator.onCaptured(capture.token)

        // An undo whose buffer work suspends. Nothing else may observe or change
        // the run's position while it is in there.
        val bufferWork = CompletableDeferred<Int>()
        val undo = launch { coordinator.undo { bufferWork.await() } }

        // The sample loop blocks on the lock rather than reading a half-applied
        // undo, so this cannot complete until the undo does.
        val sampled = launch { coordinator.feed(0f, 1_000L) }

        assertEquals(1, coordinator.guidance.value.activeIndex)
        bufferWork.complete(0)
        undo.join()
        sampled.join()

        assertEquals(0, coordinator.guidance.value.activeIndex)
    }
}
