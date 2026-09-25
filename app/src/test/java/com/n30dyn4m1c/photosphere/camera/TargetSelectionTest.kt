package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetSelectionTest {

    /** One horizon ring at 0°, 90°, 180°, -90°. */
    private val ring = SphereTargetPlan.create(
        startYawDegrees = 0f,
        ringElevations = listOf(0f),
        equatorSpacingDegrees = 90f,
    )

    private fun aimAt(yaw: Float, pitch: Float = 0f) =
        OrientationData(yawDegrees = yaw, pitchDegrees = pitch, timestampNanos = 1L)

    @Test
    fun `the active target holds while the aim wanders between markers`() {
        // 40° off the active target and 50° off the next: nothing is claimed.
        assertEquals(0, TargetSelection.select(ring, aimAt(40f), emptySet(), active = 0))
    }

    @Test
    fun `aiming squarely at another uncaptured marker claims it`() {
        val claimed = TargetSelection.select(ring, aimAt(178f), emptySet(), active = 1)
        assertEquals(2, claimed)
    }

    @Test
    fun `a captured marker can never be claimed`() {
        assertEquals(1, TargetSelection.select(ring, aimAt(180f), setOf(0, 2), active = 1))
    }

    @Test
    fun `a captured active target hands over to the plan's next one`() {
        // Just shot target 0; its two neighbours are equally far away, and the
        // plan's own successor wins the tie.
        assertEquals(1, TargetSelection.select(ring, aimAt(0f), setOf(0), active = 0))
    }

    @Test
    fun `hand-over prefers a clearly nearer gap to the plan's successor`() {
        // Standing next to target 3 (-90°) after shooting 2: 3 is the successor
        // anyway. After shooting 0 while facing -80°, target 3 is far nearer
        // than the successor 1 (at +90°), and beats the bias.
        assertEquals(3, TargetSelection.afterCapture(ring, aimAt(-80f), setOf(0), previous = 0))
    }

    @Test
    fun `hand-over wraps to the start of the plan`() {
        assertEquals(0, TargetSelection.afterCapture(ring, aimAt(-90f), setOf(1, 2, 3), previous = 3))
    }

    @Test
    fun `nothing is selected once every target is captured`() {
        assertNull(TargetSelection.select(ring, aimAt(0f), setOf(0, 1, 2, 3), active = 3))
    }

    @Test
    fun `plan successor skips captured targets`() {
        assertEquals(3, TargetSelection.planSuccessor(4, setOf(0, 1, 2), previous = 0))
        assertNull(TargetSelection.planSuccessor(4, setOf(0, 1, 2, 3), previous = 0))
    }
}
