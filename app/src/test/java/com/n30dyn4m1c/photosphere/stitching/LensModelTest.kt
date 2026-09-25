package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * The radial lens model that keeps frame edges honest.
 */
class LensModelTest {

    // Realistic phone-lens barrel distortion, in the *normalized* space
    // `CameraCharacteristics.LENS_DISTORTION` uses: coordinates divided by the
    // focal length, exactly as OpenCV does it. A k1 of −0.02 pulls a ray 30°
    // off the axis in by roughly 0.7%.
    private val unitlessCoefficients = doubleArrayOf(-0.02, 1.0e-3, -5.0e-5)

    private val lens = RadialDistortion(unitlessCoefficients)

    /** A 1024×768 working frame across 66°, which is what the stitch decodes to. */
    private val workingFrame = FrameIntrinsics.forLens(
        widthPx = 1024,
        heightPx = 768,
        horizontalFovDegrees = 66f,
        verticalFovDegrees = 52f,
        distortion = lens,
    )

    private val smallCoefficients = workingFrame.radial!!

    private val centreX = workingFrame.centerXPx
    private val centreY = workingFrame.centerYPx

    @Test
    fun `the optical centre is unmoved by distortion`() {
        val distorted = distortPixel(centreX, centreY, centreX, centreY, smallCoefficients)
        assertEquals(centreX, distorted[0], 1e-9)
        assertEquals(centreY, distorted[1], 1e-9)
    }

    @Test
    fun `barrel distortion pulls the frame edge towards the centre`() {
        // An ideal pixel near the corner should land closer to the centre once
        // the negative k1 pushes it in, in proportion to its distance from it.
        val distorted = distortPixel(900.0, 700.0, centreX, centreY, smallCoefficients)
        val xOffset = 900.0 - centreX
        val yOffset = 700.0 - centreY
        val xShrink = xOffset - (distorted[0] - centreX)
        val yShrink = yOffset - (distorted[1] - centreY)
        assertTrue("x should shrink", xShrink > 0.0)
        assertTrue("y should shrink", yShrink > 0.0)
        // The pull is proportional to the offset from the optical centre.
        assertEquals(xShrink / yShrink, xOffset / yOffset, 1e-9)
    }

    @Test
    fun `undistort is the inverse of distort`() {
        for ((column, row) in listOf(
            512.0 to 384.0,
            900.0 to 700.0,
            100.0 to 50.0,
            811.0 to 24.0,
            64.0 to 740.0,
        )) {
            val distorted = distortPixel(column, row, centreX, centreY, smallCoefficients)
            val recovered =
                undistortPixel(distorted[0], distorted[1], centreX, centreY, smallCoefficients)
            assertEquals("column $column", column, recovered[0], 1e-3)
            assertEquals("row $row", row, recovered[1], 1e-3)
        }
    }

    @Test
    fun `no coefficients means no movement`() {
        val pinhole = distortPixel(900.0, 700.0, centreX, centreY, doubleArrayOf(0.0, 0.0, 0.0))
        assertEquals(900.0, pinhole[0], 1e-12)
        assertEquals(700.0, pinhole[1], 1e-12)
    }

    // -- The normalization LENS_DISTORTION actually uses ----------------------

    @Test
    fun `pixel coefficients divide by the focal length, not the frame edge`() {
        // This is the whole of the LENS_DISTORTION contract: `x_i = (x - c_x)/f`,
        // so a term of order 2n divides by f^(2n). Getting this wrong by using
        // the deprecated LENS_RADIAL_DISTORTION's "array edge = ±1" convention
        // overstates k1 by (f / halfEdge)^2 — more than double on a normal lens
        // — and k3 by the sixth power of the same.
        val focal = 1234.5
        val effective = lens.pixelCoefficientsFor(focal)
        assertNotNull(effective)
        assertEquals(unitlessCoefficients[0] / Math.pow(focal, 2.0), effective!![0], 1e-18)
        assertEquals(unitlessCoefficients[1] / Math.pow(focal, 4.0), effective[1], 1e-24)
        assertEquals(unitlessCoefficients[2] / Math.pow(focal, 6.0), effective[2], 1e-30)
    }

    @Test
    fun `the working frame is normalized against its own focal length`() {
        // 1024 px across 66° is a focal length of 1024/2/tan(33°) ≈ 788 px. The
        // vertical axis lands within a pixel of the same number, which is what
        // "square pixels" means and why one focal length can carry the radial
        // polynomial for both.
        val expectedFocal = 512.0 / tan(Math.toRadians(33.0))
        assertEquals(expectedFocal, workingFrame.focalXPx, 1e-9)
        assertEquals(expectedFocal, workingFrame.focalPx, 1.0)

        val focal = workingFrame.focalPx
        assertEquals(unitlessCoefficients[0] / (focal * focal), smallCoefficients[0], 1e-18)
    }

    @Test
    fun `a distortion this camera did not report is nothing to correct`() {
        assertNull(RadialDistortion(doubleArrayOf()).pixelCoefficientsFor(1000.0))
        assertNull(RadialDistortion(doubleArrayOf(-1.0, 0.0)).pixelCoefficientsFor(1000.0))
        assertNull(lens.pixelCoefficientsFor(0.0))
        assertNull(lens.pixelCoefficientsFor(Double.NaN))
    }

    @Test
    fun `an all-zero calibration is a pinhole and is skipped entirely`() {
        val pinhole = RadialDistortion(doubleArrayOf(0.0, 0.0, 0.0))
        assertFalse(pinhole.isSignificant)
        assertNull(pinhole.pixelCoefficientsFor(1500.0))
        // And a frame built for it carries no polynomial into the inner loop.
        assertNull(
            FrameIntrinsics.forLens(1024, 768, 66f, 52f, pinhole).radial
        )
    }

    @Test
    fun `scaling the focal length re-normalises the radial coefficients`() {
        // A focal correction of s means the frame really spans s× the focal
        // length it was described with, so the pixel-space polynomial has to be
        // re-derived against the new focal: each term divides by s^(2·order).
        val scale = 1.2
        val scaled = workingFrame.scaledBy(scale)
        assertEquals(workingFrame.focalXPx * scale, scaled.focalXPx, 1e-9)
        assertEquals(workingFrame.focalYPx * scale, scaled.focalYPx, 1e-9)

        // Scaling is a lens change, so the scaled frame must be identical to
        // re-deriving the same lens at the new focal length from the unitless
        // coefficients — that is the whole point of carrying them through.
        val scaledRadial = scaled.radial!!
        val reDerived = lens.pixelCoefficientsFor(workingFrame.focalPx * scale)!!
        assertEquals(reDerived[0], scaledRadial[0], 1e-15)
        assertEquals(reDerived[1], scaledRadial[1], 1e-20)
        assertEquals(reDerived[2], scaledRadial[2], 1e-25)
    }

    @Test
    fun `a unit focal scale is the identity`() {
        val same = workingFrame.scaledBy(1.0)
        assertTrue("scaledBy(1.0) should hand back the same instance", same === workingFrame)
    }

    @Test
    fun `the two resolutions describe the same lens`() {
        // Two decodes of one capture: the same physical ray must land on the
        // same *fraction* of the frame however coarsely it was decoded, which is
        // what makes the correction independent of the subsampling factor.
        val full = FrameIntrinsics.forLens(4000, 3000, 66f, 52f, lens)
        val small = FrameIntrinsics.forLens(1000, 750, 66f, 52f, lens)

        val atFull = distortPixel(
            4000.0, full.centerYPx, full.centerXPx, full.centerYPx, full.radial!!,
        )[0]
        val atSmall = distortPixel(
            1000.0, small.centerYPx, small.centerXPx, small.centerYPx, small.radial!!,
        )[0]
        assertEquals(atFull / 4.0, atSmall, 1e-6)
    }

    @Test
    fun `a realistic lens moves a frame corner by a believable amount`() {
        // The guard against a normalization that is off by a power: a phone
        // lens's own corner should move by a couple of percent, not by a third
        // of the frame (which pushes every source pixel out of bounds and makes
        // the stitch cover nothing) and not by a hundredth of a pixel.
        val corner = distortPixel(
            workingFrame.widthPx - 1.0,
            workingFrame.heightPx - 1.0,
            centreX,
            centreY,
            smallCoefficients,
        )
        val before = Math.hypot(workingFrame.widthPx - 1.0 - centreX, workingFrame.heightPx - 1.0 - centreY)
        val after = Math.hypot(corner[0] - centreX, corner[1] - centreY)
        val shift = (before - after) / before
        assertTrue("corner moved by ${shift * 100}%, expected 0.1%..10%", shift in 0.001..0.10)
    }
}
