package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of non-visual guidance: beep cadence, dwell haptics and the
 * spoken direction of a target. All of it is arithmetic over the same numbers
 * the visual loop already computes, so it runs without a device.
 */
class GuidanceProfileTest {

    // --- Aim beeps ---------------------------------------------------------

    @Test
    fun `the beep rate rises as the aim closes on the threshold`() {
        val near = GuidanceProfile.beepIntervalMillis(distanceDegrees = 3f)!!
        val far = GuidanceProfile.beepIntervalMillis(distanceDegrees = 30f)!!
        assertTrue("expected a faster cadence when closer", near < far)
    }

    @Test
    fun `no beep is due beyond the guidance cone`() {
        assertNull(GuidanceProfile.beepIntervalMillis(distanceDegrees = 41f))
        assertNull(GuidanceProfile.beepIntervalMillis(distanceDegrees = 180f))
    }

    @Test
    fun `no fix means silence`() {
        assertNull(GuidanceProfile.beepIntervalMillis(Float.NaN))
    }

    @Test
    fun `the cadence is bounded`() {
        val fastest = GuidanceProfile.beepIntervalMillis(distanceDegrees = 2f)!!
        val slowest = GuidanceProfile.beepIntervalMillis(distanceDegrees = 40f)!!
        assertTrue(fastest in 1L..200L)
        assertTrue(slowest in 200L..2000L)
    }

    // --- Dwell haptics -----------------------------------------------------

    @Test
    fun `the halfway crossing of the dwell is the felt cue`() {
        assertTrue(GuidanceProfile.crossedDwellMilestone(previousProgress = 0.3f, currentProgress = 0.5f))
        assertTrue(GuidanceProfile.crossedDwellMilestone(previousProgress = 0.49f, currentProgress = 0.51f))
    }

    @Test
    fun `no tick before halfway or on a reset`() {
        assertFalse(GuidanceProfile.crossedDwellMilestone(previousProgress = 0f, currentProgress = 0.2f))
        assertFalse(GuidanceProfile.crossedDwellMilestone(previousProgress = 0.5f, currentProgress = 0.4f))
        assertFalse(GuidanceProfile.crossedDwellMilestone(previousProgress = 0f, currentProgress = 0f))
    }

    // --- Spoken direction --------------------------------------------------

    private fun orientation(yaw: Float, pitch: Float, roll: Float = 0f) =
        OrientationData(yawDegrees = yaw, pitchDegrees = pitch, rollDegrees = roll)

    @Test
    fun `a target the camera faces is level and centre`() {
        val relation = GuidanceProfile.targetRelation(
            orientation(yaw = 30f, pitch = -12f),
            SphereTarget(yawDegrees = 30f, pitchDegrees = -12f),
        )
        assertFalse(relation.isBehind)
        assertEquals(TargetRelation.Vertical.Level, relation.vertical)
        assertEquals(TargetRelation.Horizontal.Centre, relation.horizontal)
    }

    @Test
    fun `a target up and to the left announces as such`() {
        val relation = GuidanceProfile.targetRelation(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = -20f, pitchDegrees = -25f),
        )
        assertFalse(relation.isBehind)
        assertEquals(TargetRelation.Vertical.Above, relation.vertical)
        assertEquals(TargetRelation.Horizontal.Left, relation.horizontal)
    }

    @Test
    fun `a target behind the camera announces as behind`() {
        val relation = GuidanceProfile.targetRelation(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = 170f, pitchDegrees = 0f),
        )
        assertTrue(relation.isBehind)
    }

    @Test
    fun `a target below and to the right announces as such`() {
        val relation = GuidanceProfile.targetRelation(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = 20f, pitchDegrees = 25f),
        )
        assertFalse(relation.isBehind)
        assertEquals(TargetRelation.Vertical.Below, relation.vertical)
        assertEquals(TargetRelation.Horizontal.Right, relation.horizontal)
    }

    @Test
    fun `a near-centred aim is announced level even a little off`() {
        // The announcement thresholds are deliberately wider than the aim
        // tolerance: its job is to get the user facing the right way.
        val relation = GuidanceProfile.targetRelation(
            orientation(yaw = 0f, pitch = 0f),
            SphereTarget(yawDegrees = 3f, pitchDegrees = -3f),
        )
        assertEquals(TargetRelation.Vertical.Level, relation.vertical)
        assertEquals(TargetRelation.Horizontal.Centre, relation.horizontal)
    }
}
