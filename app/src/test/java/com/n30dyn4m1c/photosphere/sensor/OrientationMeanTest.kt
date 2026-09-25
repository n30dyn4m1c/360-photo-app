package com.n30dyn4m1c.photosphere.sensor

import com.n30dyn4m1c.photosphere.stitching.CameraBasis
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.RotationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private fun DoubleArray.toFloatArrayCompat(): FloatArray = FloatArray(size) { this[it].toFloat() }

private fun FloatArray.toDoubleArrayCompat(): DoubleArray = DoubleArray(size) { this[it].toDouble() }

/**
 * The dwell-window pose averaging that quiets the sensor for the frame it
 * stamps onto the sphere.
 */
class OrientationMeanTest {

    @Test
    fun `a plain set of angles averages as expected`() {
        assertEquals(5f, meanAngleDegrees(listOf(0f, 10f)), 1e-3f)
        assertEquals(-5f, meanAngleDegrees(listOf(-10f, 0f)), 1e-3f)
    }

    @Test
    fun `a crossing of plus or minus one eighty unwraps instead of smearing`() {
        // 179° and -179° are 2° apart, and the short arc between them passes
        // through the date line, so their mean is ±180° — not the 0° a naive
        // average would claim.
        assertEquals(180f, abs(meanAngleDegrees(listOf(179f, -179f))), 1e-3f)
        assertEquals(180f, abs(meanAngleDegrees(listOf(-179f, 179f))), 1e-3f)
    }

    @Test
    fun `the mean orientation of a window is the mean of its angles`() {
        val mean = meanOrientation(
            listOf(
                OrientationData(yawDegrees = 10f, pitchDegrees = 1f, rollDegrees = -2f, timestampNanos = 1L),
                OrientationData(yawDegrees = 12f, pitchDegrees = 3f, rollDegrees = 0f, timestampNanos = 2L),
            )
        )

        assertEquals(11f, mean.yawDegrees, 1e-3f)
        assertEquals(2f, mean.pitchDegrees, 1e-3f)
        assertEquals(-1f, mean.rollDegrees, 1e-3f)
    }

    @Test
    fun `the mean carries the freshest accuracy and timestamp`() {
        val mean = meanOrientation(
            listOf(
                OrientationData(yawDegrees = 0f, accuracy = OrientationAccuracy.Low, timestampNanos = 1L),
                OrientationData(yawDegrees = 1f, accuracy = OrientationAccuracy.Medium, timestampNanos = 2L),
            )
        )

        assertEquals(OrientationAccuracy.Medium, mean.accuracy)
        assertEquals(2L, mean.timestampNanos)
    }

    @Test
    fun `samples without a fix are ignored`() {
        val mean = meanOrientation(
            listOf(
                OrientationData(),
                OrientationData(yawDegrees = 20f, timestampNanos = 5L),
            )
        )
        assertEquals(20f, mean.yawDegrees, 1e-3f)
        assertEquals(5L, mean.timestampNanos)
    }

    @Test
    fun `a zenith dwell averages as a rotation, not as scattered angles`() {
        // Two physically nearby orientations of a camera aimed near the zenith,
        // whose Euler representations scatter: at pitch -89° yaw and roll trade
        // against each other, so the components alone look wildly different and
        // averaging them would reconstruct an orientation rolled far away about
        // the pole axis. The rotation mean must land where both samples agree.
        val first = CameraBasis.of(CameraPose(yawDegrees = 10f, pitchDegrees = -89f, rollDegrees = 5f))
        val second = CameraBasis.of(CameraPose(yawDegrees = 170f, pitchDegrees = -89f, rollDegrees = -175f))
        val firstMatrix = first.toRotationMatrix()
        val secondMatrix = second.toRotationMatrix()

        val mean = meanOrientation(
            listOf(
                OrientationData(
                    yawDegrees = 10f, pitchDegrees = -89f, rollDegrees = 5f,
                    timestampNanos = 1L, cameraBasis = firstMatrix.toFloatArrayCompat(),
                ),
                OrientationData(
                    yawDegrees = 170f, pitchDegrees = -89f, rollDegrees = -175f,
                    timestampNanos = 2L, cameraBasis = secondMatrix.toFloatArrayCompat(),
                ),
            )
        )

        // The mean carries a basis, and it sits between the two samples.
        assertNotNull(mean.cameraBasis)
        val meanMatrix = CameraBasis.fromRotationMatrix(mean.cameraBasis!!.toDoubleArrayCompat())
            .toRotationMatrix()
        val fromFirst = Math.toDegrees(RotationMath.angle(meanMatrix, firstMatrix))
        val fromSecond = Math.toDegrees(RotationMath.angle(meanMatrix, secondMatrix))
        assertTrue("mean $fromFirst° from the first sample", fromFirst < 12.0)
        assertTrue("mean $fromSecond° from the second sample", fromSecond < 12.0)

        // The angles the mean reports reconstruct the same orientation the
        // matrix describes: the two halves of the pose agree.
        val reconstructed = CameraBasis.of(
            CameraPose(mean.yawDegrees, mean.pitchDegrees, mean.rollDegrees)
        )
        assertEquals(
            0.0,
            Math.toDegrees(RotationMath.angle(reconstructed.toRotationMatrix(), meanMatrix)),
            1.0,
        )
    }

    @Test
    fun `an identical zenith dwell reproduces its orientation exactly`() {
        // A still phone aimed at the zenith reports a different arbitrary
        // (yaw, roll) split on every sample — the two Euler triples below are
        // the same camera. The basis is identical on both, and the rotation
        // mean must come back as exactly that basis rather than a smeared
        // average of the scattered angles.
        val basis = CameraBasis.of(CameraPose(yawDegrees = 0f, pitchDegrees = -90f, rollDegrees = 0f))
            .toRotationMatrix()
            .toFloatArrayCompat()

        val mean = meanOrientation(
            listOf(
                OrientationData(
                    yawDegrees = 0f, pitchDegrees = -90f, rollDegrees = 0f,
                    timestampNanos = 1L, cameraBasis = basis,
                ),
                OrientationData(
                    yawDegrees = 120f, pitchDegrees = -90f, rollDegrees = -120f,
                    timestampNanos = 2L, cameraBasis = basis,
                ),
            )
        )

        val expected = CameraBasis.fromRotationMatrix(basis.toDoubleArrayCompat()).toRotationMatrix()
        val meanMatrix = CameraBasis.fromRotationMatrix(mean.cameraBasis!!.toDoubleArrayCompat())
            .toRotationMatrix()
        assertEquals(0.0, RotationMath.angle(meanMatrix, expected), 1e-4)

        // And the reported angles are consistent with the basis.
        val reconstructed = CameraBasis.of(
            CameraPose(mean.yawDegrees, mean.pitchDegrees, mean.rollDegrees)
        )
        assertEquals(0.0, RotationMath.angle(reconstructed.toRotationMatrix(), meanMatrix), 1e-3)
    }
}
