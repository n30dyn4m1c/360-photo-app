package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The rotation algebra behind pose refinement, exercised without OpenCV: this is
 * the part that decides how well the overlaps align, so it is pinned down here.
 */
class RotationMathTest {

    @Test
    fun `rotationBetween maps one unit vector exactly onto another`() {
        val from = doubleArrayOf(1.0, 0.0, 0.0)
        val to = normalized(doubleArrayOf(0.0, 1.0, 1.0))
        val rotation = RotationMath.rotationBetween(from, to)
        assertVector(to, RotationMath.apply(rotation, from))
    }

    @Test
    fun `anti-parallel vectors still produce a valid rotation`() {
        val from = doubleArrayOf(1.0, 0.0, 0.0)
        val to = doubleArrayOf(-1.0, 0.0, 0.0)
        val rotation = RotationMath.rotationBetween(from, to)
        assertVector(to, RotationMath.apply(rotation, from))
    }

    @Test
    fun `a rotation survives a trip through its quaternion`() {
        val axis = normalized(doubleArrayOf(1.0, -2.0, 0.5))
        for (angle in doubleArrayOf(0.1, 0.7, 1.3, 2.9)) {
            val rotation = RotationMath.rotationAboutAxis(axis, angle)
            val rebuilt = RotationMath.quaternionToMatrix(RotationMath.matrixToQuaternion(rotation))
            assertEquals("angle $angle", 0.0, RotationMath.angle(rotation, rebuilt), 1e-6)
        }
    }

    @Test
    fun `procrustes recovers a rotation from noisy correspondences`() {
        val truth = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.5, 0.2, 0.9)), 0.8)
        val from = Array(60) { randomUnit() }
        val to = Array(60) { perturb(RotationMath.apply(truth, from[it]), Math.toRadians(0.3)) }

        val estimate = RotationMath.procrustes(from, to)
        assertTrue(
            "procrustes off by ${Math.toDegrees(RotationMath.angle(estimate, truth))}°",
            RotationMath.angle(estimate, truth) < Math.toRadians(0.2),
        )
    }

    @Test
    fun `a symmetric matrix returns its eigenvalues and vectors in order`() {
        // A symmetric matrix with known eigenvalues 3, 2, 1 along the axes.
        val matrix = doubleArrayOf(
            3.0, 0.0, 0.0,
            0.0, 2.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val (values, vectors) = RotationMath.symmetricEigen3x3(matrix)
        assertEquals(3.0, values[0], 1e-9)
        assertEquals(2.0, values[1], 1e-9)
        assertEquals(1.0, values[2], 1e-9)
        // Eigenvectors are unit and mutually orthogonal.
        assertEquals(1.0, norm(vectors.copyOfRange(0, 3)), 1e-9)
        assertEquals(1.0, norm(vectors.copyOfRange(3, 6)), 1e-9)
        assertEquals(1.0, norm(vectors.copyOfRange(6, 9)), 1e-9)
    }

    @Test
    fun `basisRotation is exact for a pure rotation`() {
        val truth = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.3, 1.0, 0.0)), 0.7)
        val a1 = normalized(doubleArrayOf(1.0, 0.2, 0.1))
        val a2 = normalized(doubleArrayOf(0.1, 1.0, -0.3))
        val b1 = RotationMath.apply(truth, a1)
        val b2 = RotationMath.apply(truth, a2)
        val estimate = RotationMath.basisRotation(a1, b1, a2, b2)
        assertEquals(0.0, RotationMath.angle(estimate, truth), 1e-9)
    }

    @Test
    fun `RANSAC recovers a rotation despite a third of the matches lying`() {
        val truth = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.2, 0.9, 0.3)), 0.5)
        val from = Array(300) { randomUnit() }
        val to = Array(300) { index ->
            if (index % 3 == 0) {
                // Outlier: a completely wrong correspondence.
                randomUnit()
            } else {
                // Inlier with a degree of measurement noise.
                perturb(RotationMath.apply(truth, from[index]), Math.toRadians(0.4))
            }
        }

        val estimate = RotationMath.estimateRotation(
            from = from,
            to = to,
            thresholdRadians = Math.toRadians(1.5),
            maxIterations = 400,
            random = Random(7),
            minInliers = 100,
        )

        assertNotNull("RANSAC should find a consensus", estimate)
        assertTrue("inlier count ${estimate!!.inlierCount}", estimate.inlierCount >= 150)
        assertTrue(
            "recovered rotation off by ${Math.toDegrees(RotationMath.angle(estimate.rotation, truth))}°",
            RotationMath.angle(estimate.rotation, truth) < Math.toRadians(0.6),
        )
    }

    @Test
    fun `rotation averaging recovers the absolute rotations from measured edges`() {
        val count = 8
        val truth = List(count) { index ->
            RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.1, 1.0, 0.2)), index * 0.5)
        }

        // A ring of relative rotations with small measurement noise — the
        // ~0.2° residual a RANSAC rotation fit leaves behind. The initial poses
        // carry the larger ~1° sensor error the averaging is meant to remove.
        val edges = ArrayList<RotationMath.RotationEdge>()
        for (i in 0 until count) {
            val j = (i + 1) % count
            val trueRelative = RotationMath.multiply(RotationMath.transpose(truth[j]), truth[i])
            val noisy = RotationMath.multiply(
                RotationMath.rotationAboutAxis(randomUnit(), 0.003),
                trueRelative,
            )
            edges += RotationMath.RotationEdge(i, j, noisy, 1.0)
        }

        // The anchor is exact; every other frame is given a sensor-style error.
        val initial = List(count) { index ->
            if (index == 0) truth[0]
            else RotationMath.multiply(
                RotationMath.rotationAboutAxis(randomUnit(), 0.01),
                truth[index],
            )
        }

        val refined = RotationMath.averageRotations(initial, edges, 20)
        for (i in 0 until count) {
            val error = RotationMath.angle(refined[i], truth[i])
            assertTrue("frame $i off by ${Math.toDegrees(error)}°", error < Math.toRadians(0.5))
        }
    }

    @Test
    fun `the weighted mean of identical rotations is that rotation`() {
        val rotation = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(1.0, 0.0, 0.0)), 1.0)
        val mean = RotationMath.weightedMeanRotation(
            listOf(rotation, rotation, rotation),
            listOf(1.0, 2.0, 0.5),
        )
        assertEquals(0.0, RotationMath.angle(mean, rotation), 1e-6)
    }

    @Test
    fun `the focal-warp derivative matches a numeric difference`() {
        // The solver's derivative model must describe the actual physics of a
        // focal-length error: a correction of δ divides the tangent of every
        // measured bearing by e^δ, pulling it radially toward or away from the
        // optical axis. Check the closed form against a central difference of
        // exactly that model — the same way PoseRefiner evaluates it numerically.
        val bearing = normalized(doubleArrayOf(0.35, -0.6, 0.8))
        val h = 1e-5
        val plus = correctedBearing(bearing, h)
        val minus = correctedBearing(bearing, -h)
        val numeric = doubleArrayOf(
            (plus[0] - minus[0]) / (2 * h),
            (plus[1] - minus[1]) / (2 * h),
            (plus[2] - minus[2]) / (2 * h),
        )
        val analytic = focalDerivative(bearing)
        assertEquals(numeric[0], analytic[0], 1e-5)
        assertEquals(numeric[1], analytic[1], 1e-5)
        assertEquals(numeric[2], analytic[2], 1e-5)
    }

    @Test
    fun `per-frame focal corrections are recovered from matched bearings`() {
        // A sweep of cameras, each recording its bearings through a per-frame
        // focal-length error. The solver should put each camera back onto its
        // true focal length, with the anchor (frame 0) held at its reported one.
        val frameCount = 6
        val focalScales = doubleArrayOf(1.0, 1.04, 0.97, 1.08, 1.02, 0.95)
        val correspondences = focalCorrespondences(frameCount, focalScales, Random(7))
        assertTrue("wanted rich overlaps, got ${correspondences.size}", correspondences.size >= 400)

        val delta = RotationMath.refineFocalLengths(correspondences, frameCount)

        assertEquals("the anchor keeps its reported focal length", 0.0, delta[0], 1e-12)
        for (frame in 1 until frameCount) {
            val expected = Math.log(focalScales[frame])
            assertEquals(
                "frame $frame wants log(${focalScales[frame]}) = $expected",
                expected, delta[frame], 0.02,
            )
        }
    }

    @Test
    fun `correctly reported focal lengths draw no correction`() {
        val frameCount = 5
        val correspondences = focalCorrespondences(
            frameCount, DoubleArray(frameCount) { 1.0 }, Random(11),
        )
        val delta = RotationMath.refineFocalLengths(correspondences, frameCount)
        for (frame in 0 until frameCount) {
            assertEquals("frame $frame", 0.0, delta[frame], 1e-3)
        }
    }

    @Test
    fun `a degenerate solve is clamped and leaves untouched frames alone`() {
        // One correspondence whose derivative is a thousandth of its residual
        // would demand a thousandfold focal change; the clamp stops it at 15%.
        val correspondence = RotationMath.FocalCorrespondence(
            from = 0,
            to = 1,
            transformedFrom = doubleArrayOf(1.0, 0.0, 0.0),
            toBearing = doubleArrayOf(0.0, 1.0, 0.0),
            transformedFromDerivative = doubleArrayOf(0.0, 0.0, 0.0),
            toDerivative = doubleArrayOf(0.001, 0.0, 0.0),
        )
        val delta = RotationMath.refineFocalLengths(listOf(correspondence), 4)
        assertEquals(0.0, delta[0], 1e-12)
        assertEquals(0.15, delta[1], 1e-12)
        // Frames the correspondences never touched keep their reported focal.
        assertEquals(0.0, delta[2], 1e-12)
        assertEquals(0.0, delta[3], 1e-12)
    }

    /**
     * The correspondences a ring of [frameCount] level cameras would measure
     * for shared scene points, with each camera's bearings recorded through a
     * focal-length error of `focalScales[frame]`.
     *
     * The ground truth is exact: bearing of a scene direction in camera i is
     * `R_i^T·d`, the measured bearing is that one pulled through the focal
     * warp, and the relative rotation the correspondence carries is the true
     * `R_j^T·R_i`. So if the solver recovers each `ln(scale)`, every residual
     * vanishes — that is the yardstick the recovery test measures against.
     */
    private fun focalCorrespondences(
        frameCount: Int,
        focalScales: DoubleArray,
        random: Random,
    ): List<RotationMath.FocalCorrespondence> {
        // A sweep at 30° steps rather than a closed ring: cameras that face
        // each other share no front-hemisphere points, and the solver needs
        // plenty of shared points to separate focal error from noise.
        val rotations = List(frameCount) { frame ->
            val yaw = frame * (Math.PI / 6.0)
            CameraBasis.of(
                CameraPose(Math.toDegrees(yaw).toFloat(), 0f, 0f)
            ).toRotationMatrix()
        }
        val directions = List(1200) {
            normalized(
                doubleArrayOf(
                    random.nextDouble() - 0.5,
                    random.nextDouble() - 0.5,
                    0.5 + random.nextDouble() * 0.5,
                )
            )
        }
        val correspondences = ArrayList<RotationMath.FocalCorrespondence>()
        for (first in 0 until frameCount) {
            for (second in first + 1 until frameCount) {
                val relative = RotationMath.multiply(
                    RotationMath.transpose(rotations[second]),
                    rotations[first],
                )
                for (direction in directions) {
                    val fromBearing = RotationMath.apply(
                        RotationMath.transpose(rotations[first]), direction,
                    )
                    val toBearing = RotationMath.apply(
                        RotationMath.transpose(rotations[second]), direction,
                    )
                    if (fromBearing[2] < 0.35 || toBearing[2] < 0.35) continue
                    val measuredFrom = focalWarp(fromBearing, focalScales[first])
                    val measuredTo = focalWarp(toBearing, focalScales[second])
                    correspondences += RotationMath.FocalCorrespondence(
                        from = first,
                        to = second,
                        transformedFrom = RotationMath.apply(relative, measuredFrom),
                        toBearing = measuredTo,
                        transformedFromDerivative =
                            RotationMath.apply(relative, focalDerivative(measuredFrom)),
                        toDerivative = focalDerivative(measuredTo),
                    )
                }
            }
        }
        return correspondences
    }

    /** The bearing a pixel records when the focal length is [scale]× reported. */
    private fun focalWarp(bearing: DoubleArray, scale: Double): DoubleArray =
        normalized(doubleArrayOf(bearing[0] * scale, bearing[1] * scale, bearing[2]))

    /**
     * The corrected bearing after a log-scale focal correction of [delta]: the
     * tangent is divided by `e^delta`. This is exactly the model the solver
     * linearises, so its central difference is the yardstick for [focalDerivative].
     */
    private fun correctedBearing(bearing: DoubleArray, delta: Double): DoubleArray =
        normalized(doubleArrayOf(bearing[0] * Math.exp(-delta), bearing[1] * Math.exp(-delta), bearing[2]))

    /**
     * `d(bearing)/d(ln scale)` for the correction above: `(−x, −y, 0) + (x²+y²)·p`.
     */
    private fun focalDerivative(bearing: DoubleArray): DoubleArray {
        val x = bearing[0]
        val y = bearing[1]
        val r2 = x * x + y * y
        return doubleArrayOf(-x + r2 * x, -y + r2 * y, r2 * bearing[2])
    }

    private fun randomUnit(): DoubleArray {
        var candidate: DoubleArray
        do {
            candidate = doubleArrayOf(Random.nextDouble() - 0.5, Random.nextDouble() - 0.5, Random.nextDouble() - 0.5)
        } while (norm(candidate) < 1e-6)
        return normalized(candidate)
    }

    /** Rotates [v] by up to [angleRad] about a random axis. */
    private fun perturb(v: DoubleArray, angleRad: Double): DoubleArray =
        RotationMath.apply(RotationMath.rotationAboutAxis(randomUnit(), angleRad), v)

    private fun normalized(v: DoubleArray): DoubleArray {
        val magnitude = norm(v)
        return doubleArrayOf(v[0] / magnitude, v[1] / magnitude, v[2] / magnitude)
    }

    private fun norm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun assertVector(expected: DoubleArray, actual: DoubleArray) {
        val dot = expected[0] * actual[0] + expected[1] * actual[1] + expected[2] * actual[2]
        assertEquals("angle between them", 0.0, acos(dot.coerceIn(-1.0, 1.0)), 1e-9)
        // Also check it is not the reflection.
        assertEquals(expected[0], actual[0], 1e-6)
        assertEquals(expected[1], actual[1], 1e-6)
        assertEquals(expected[2], actual[2], 1e-6)
    }
}
