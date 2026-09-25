package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure rotation algebra for pose refinement.
 *
 * Rotations are row-major 3×3 matrices mapping *camera* axes to the *world*
 * frame — the same convention [CameraBasis.toRotationMatrix] produces, so the
 * two halves of the pipeline can exchange rotations without a frame conversion.
 * Everything here is plain double arithmetic with no OpenCV, which is what makes
 * the whole refinement testable on a JVM without the native library.
 *
 * Three pieces:
 *
 * 1. [estimateRotation] — RANSAC over matched unit bearings, using a two-point
 *    hypothesis built from orthonormal triples (a SVD-free stand-in for
 *    Orthogonal Procrustes that is exact for the pure-rotation case).
 * 2. [weightedMeanRotation] — the weighted Karcher mean on SO(3), implemented
 *    as quaternion averaging, valid because the corrections are all small.
 * 3. [averageRotations] — Gauss–Seidel rotation averaging over the pose graph,
 *    with the sensor poses as the starting guess and the anchor frame fixed.
 */
internal object RotationMath {

    const val SIZE = 9

    fun identity(): DoubleArray = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(SIZE)
        for (i in 0..2) {
            for (j in 0..2) {
                out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
            }
        }
        return out
    }

    fun transpose(matrix: DoubleArray): DoubleArray = doubleArrayOf(
        matrix[0], matrix[3], matrix[6],
        matrix[1], matrix[4], matrix[7],
        matrix[2], matrix[5], matrix[8],
    )

    fun apply(matrix: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        matrix[0] * v[0] + matrix[1] * v[1] + matrix[2] * v[2],
        matrix[3] * v[0] + matrix[4] * v[1] + matrix[5] * v[2],
        matrix[6] * v[0] + matrix[7] * v[1] + matrix[8] * v[2],
    )

    /** Geodesic distance between two rotations, in radians. */
    fun angle(a: DoubleArray, b: DoubleArray): Double {
        val relative = multiply(transpose(a), b)
        // The cosine from the trace, and the sine from the skew part of the
        // relative rotation. `acos` alone would amplify the last bits of
        // roundoff in the trace into a phantom angle when the rotations are
        // nearly identical (the trace sum is only exact to a few ulps, and
        // d(acos)/dx diverges at x = 1) — with atan2(sin, cos) an identical
        // pair reports exactly 0 and a close pair is exact to first order.
        val cos = ((relative[0] + relative[4] + relative[8] - 1.0) / 2.0).coerceIn(-1.0, 1.0)
        val s1 = relative[7] - relative[5]
        val s2 = relative[2] - relative[6]
        val s3 = relative[3] - relative[1]
        val sin = 0.5 * sqrt(s1 * s1 + s2 * s2 + s3 * s3)
        return atan2(sin, cos)
    }

    /** The rotation taking [from] to [to], both unit vectors. */
    fun rotationBetween(from: DoubleArray, to: DoubleArray): DoubleArray {
        val f = normalize3(from)
        val t = normalize3(to)
        val cosine = dot3(f, t).coerceIn(-1.0, 1.0)
        if (cosine > 1.0 - 1e-12) return identity()
        val axis = cross3(f, t)
        val axisNorm = norm3(axis)
        if (axisNorm < 1e-12) {
            // Anti-parallel: any 180° rotation about a perpendicular axis.
            val seed = if (abs(f[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0)
            else doubleArrayOf(0.0, 1.0, 0.0)
            return rotationAboutAxis(normalize3(cross3(f, seed)), Math.PI)
        }
        return rotationAboutAxis(scale3(axis, 1.0 / axisNorm), acos(cosine))
    }

    /** Rodrigues: the rotation of [angle] radians about the unit [axis]. */
    fun rotationAboutAxis(axis: DoubleArray, angle: Double): DoubleArray {
        val x = axis[0]
        val y = axis[1]
        val z = axis[2]
        val c = cos(angle)
        val s = sin(angle)
        val t = 1.0 - c
        return doubleArrayOf(
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c,
        )
    }

    /**
     * The rotation mapping one pair of unit bearings onto another.
     *
     * Two correspondences (a1→b1, a2→b2) determine a rotation exactly under the
     * pure-rotation model: build an orthonormal triple from each pair and align
     * the frames. Used as the RANSAC hypothesis — two samples instead of the
     * three a homography needs, which cuts the iterations required.
     */
    fun basisRotation(
        a1: DoubleArray, b1: DoubleArray,
        a2: DoubleArray, b2: DoubleArray,
    ): DoubleArray {
        val u1 = normalize3(a1)
        val u2 = normalize3(sub3(a2, scale3(u1, dot3(a2, u1))))
        val u3 = cross3(u1, u2)
        val v1 = normalize3(b1)
        val v2 = normalize3(sub3(b2, scale3(v1, dot3(b2, v1))))
        val v3 = cross3(v1, v2)

        val rotation = DoubleArray(SIZE)
        // R = B·A^T, the change of basis taking the a-triple onto the b-triple.
        // R[i][j] = sum_k B[i][k]·A[j][k] = sum_k v_k[i]·u_k[j].
        for (i in 0..2) {
            for (j in 0..2) {
                rotation[i * 3 + j] = v1[i] * u1[j] + v2[i] * u2[j] + v3[i] * u3[j]
            }
        }
        return rotation
    }

    /**
     * A rotation hypothesis and the correspondences that agreed with it.
     *
     * [residualRadians] is the median angular error over the inliers, a proxy for
     * the measurement noise that weights the edge in the global averaging.
     */
    data class RotationEstimate(
        val rotation: DoubleArray,
        val inliers: List<Int>,
        val residualRadians: Double,
    ) {
        val inlierCount: Int get() = inliers.size
    }

    /**
     * Finds the rotation with [to] ≈ rotation·[from] by RANSAC.
     *
     * [from] and [to] are matched unit bearings; a correspondence agrees with a
     * hypothesis when the rotated [from] vector is within [thresholdRadians] of
     * [to]. The winning inlier set is re-fit by averaging the per-correspondence
     * rotations, which averages out the feature-location noise.
     */
    fun estimateRotation(
        from: Array<DoubleArray>,
        to: Array<DoubleArray>,
        thresholdRadians: Double,
        maxIterations: Int,
        random: Random,
        minInliers: Int = 2,
    ): RotationEstimate? {
        if (from.size < 2) return null

        var best: RotationEstimate? = null
        repeat(maxIterations) {
            val first = random.nextInt(from.size)
            var second = random.nextInt(from.size)
            var guard = 0
            while (second == first && guard++ < 6) second = random.nextInt(from.size)
            if (second == first) return@repeat

            val candidate = basisRotation(from[first], to[first], from[second], to[second])
            val inliers = ArrayList<Int>(from.size / 2)
            for (index in from.indices) {
                val applied = apply(candidate, from[index])
                val err = acos((dot3(applied, to[index])).coerceIn(-1.0, 1.0))
                if (err <= thresholdRadians) inliers += index
            }
            if (inliers.size < minInliers) return@repeat
            if (best == null || inliers.size > best.inlierCount) {
                best = RotationEstimate(candidate, inliers, residual(inliers, candidate, from, to))
            }
        }

        val winner = best ?: return null
        if (winner.inlierCount < minInliers) return null
        // Refit on the consensus set by least squares — Orthogonal Procrustes,
        // which minimizes the squared angular residual over every inlier at
        // once. (Averaging the per-correspondence rotations would be wrong: a
        // single vector pair does not determine the rotation uniquely, because
        // any spin about the target vector leaves it pointing the same way.)
        val refit = procrustes(
            a = Array(winner.inliers.size) { from[winner.inliers[it]] },
            b = Array(winner.inliers.size) { to[winner.inliers[it]] },
        )
        val refitInliers = from.indices.filter {
            val applied = apply(refit, from[it])
            acos(dot3(applied, to[it]).coerceIn(-1.0, 1.0)) <= thresholdRadians
        }
        return RotationEstimate(refit, refitInliers, residual(refitInliers, refit, from, to))
    }

    private fun residual(
        inliers: List<Int>,
        rotation: DoubleArray,
        from: Array<DoubleArray>,
        to: Array<DoubleArray>,
    ): Double {
        val errors = inliers.map {
            val applied = apply(rotation, from[it])
            acos(dot3(applied, to[it]).coerceIn(-1.0, 1.0))
        }.sorted()
        return if (errors.isEmpty()) 0.0 else errors[errors.size / 2]
    }

    /**
     * Weighted mean of rotations, valid because the inputs are close.
     *
     * Quaternions are averaged (signs aligned to the first) and renormalised.
     * This is the Karcher mean to first order, which is plenty for the few
     * degrees of correction this pipeline hunts.
     */
    fun weightedMeanRotation(rotations: List<DoubleArray>, weights: List<Double>): DoubleArray {
        if (rotations.isEmpty()) return identity()
        val reference = matrixToQuaternion(rotations[0])
        var sum = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        for (index in rotations.indices) {
            var q = matrixToQuaternion(rotations[index])
            if (dot4(q, reference) < 0.0) q = scale4(q, -1.0)
            sum = add4(sum, scale4(q, weights[index]))
        }
        val magnitude = norm4(sum)
        if (magnitude < 1e-9) return rotations[0]
        return quaternionToMatrix(scale4(sum, 1.0 / magnitude))
    }

    /**
     * One measured rotation edge in the pose graph.
     *
     * [rotation] maps frame [from]'s camera axes to frame [to]'s: a matched
     * bearing [b] in [from]'s frame reappears as `rotation·b` in [to]'s frame.
     */
    data class RotationEdge(
        val from: Int,
        val to: Int,
        val rotation: DoubleArray,
        val weight: Double,
    )

    /**
     * Refines absolute rotations against the measured edges.
     *
     * Starts from [initial] (the sensor rotations) and runs Gauss–Seidel: every
     * frame except the anchor (index 0, held at its sensor value so the result
     * keeps the user's reference frame) is replaced by the weighted mean of the
     * estimates its neighbours' rotations imply. Converges to the least-squares
     * alignment because the corrections are small.
     */
    fun averageRotations(
        initial: List<DoubleArray>,
        edges: List<RotationEdge>,
        iterations: Int,
    ): List<DoubleArray> {
        val current = initial.map { it.copyOf() }.toMutableList()
        repeat(iterations) {
            for (i in 1 until current.size) {
                val estimates = ArrayList<DoubleArray>()
                val weights = ArrayList<Double>()
                for (edge in edges) {
                    when (i) {
                        edge.from -> {
                            estimates += multiply(current[edge.to], edge.rotation)
                            weights += edge.weight
                        }
                        edge.to -> {
                            estimates += multiply(current[edge.from], transpose(edge.rotation))
                            weights += edge.weight
                        }
                    }
                }
                if (estimates.isNotEmpty()) {
                    current[i] = weightedMeanRotation(estimates, weights)
                }
            }
        }
        return current
    }

    /**
     * One matched feature correspondence used to estimate focal corrections.
     *
     * The two unit bearings [transformedFrom] and [toBearing] should agree: the
     * first is the *from* frame's bearing rotated into the *to* frame by the
     * edge's measured rotation, so the residual is simply their difference. Each
     * carries the derivative of its bearing with respect to the log-scale of its
     * own frame's focal length — [transformedFromDerivative] is the derivative
     * of the already-rotated bearing — which is what turns the residual into a
     * linear least-squares problem in the per-frame corrections.
     */
    data class FocalCorrespondence(
        val from: Int,
        val to: Int,
        val transformedFrom: DoubleArray,
        val toBearing: DoubleArray,
        val transformedFromDerivative: DoubleArray,
        val toDerivative: DoubleArray,
    )

    /**
     * Least-squares per-frame focal corrections from matched correspondences.
     *
     * A focal-length error shows up as a systematic angular error between
     * matched bearings: the pixels were projected through the wrong focal
     * length, so the unit bearings recovered from them are pulled toward or
     * away from the optical axis. For a small log-scale correction `δ` per
     * frame, each correspondence's residual
     *
     *   r(δ_from, δ_to) = R·p_from(e^δ_from) − p_to(e^δ_to)
     *
     * is linearised with the derivative vectors the correspondence carries, and
     * the resulting normal equations are solved by Gauss–Seidel over the frames
     * — the same style [averageRotations] uses for the rotations.
     *
     * [anchorFrame] stays at zero. Rotation averaging already fixes that frame
     * so the result keeps the user's reference frame, and pinning its focal
     * length removes the one remaining gauge: the whole sphere may be scaled by
     * a constant without changing any alignment, so the absolute scale is only
     * defined relative to a frame that is held still. Corrections are clamped
     * to ±[maxCorrection] (about ±16% of the focal length) each sweep, so a
     * degenerate or poorly-conditioned solve can never bend a frame out of
     * shape; frames the correspondences never touch simply keep their reported
     * focal length.
     *
     * Returns one log-scale per frame (zero for the anchor); the caller applies
     * `e^δ` to the frame's focal lengths.
     */
    fun refineFocalLengths(
        correspondences: List<FocalCorrespondence>,
        frameCount: Int,
        iterations: Int = 8,
        maxCorrection: Double = 0.15,
        anchorFrame: Int = 0,
    ): DoubleArray {
        val delta = DoubleArray(frameCount)
        if (correspondences.isEmpty() || frameCount < 2) return delta

        val hessian = Array(frameCount) { DoubleArray(frameCount) }
        val gradient = DoubleArray(frameCount)
        for (correspondence in correspondences) {
            val from = correspondence.from
            val to = correspondence.to
            if (from !in 0 until frameCount || to !in 0 until frameCount) continue
            // r = r₀ + δ_from·d_from − δ_to·d_to, so the Hessian block for the
            // pair carries the cross term negative and the gradient of the
            // *to* frame changes sign against the *from* one.
            val residual = sub3(correspondence.transformedFrom, correspondence.toBearing)
            val dFrom = correspondence.transformedFromDerivative
            val dTo = correspondence.toDerivative
            hessian[from][from] += dot3(dFrom, dFrom)
            hessian[to][to] += dot3(dTo, dTo)
            hessian[from][to] -= dot3(dFrom, dTo)
            hessian[to][from] = hessian[from][to]
            gradient[from] -= dot3(dFrom, residual)
            gradient[to] += dot3(dTo, residual)
        }

        // Gauss–Seidel over the frames; the anchor is skipped so its delta
        // stays zero. The deltas carry over between sweeps, which is a warm
        // start rather than a bug: each pass refines the previous estimate.
        repeat(iterations) {
            for (frame in 0 until frameCount) {
                if (frame == anchorFrame) continue
                var estimate = gradient[frame]
                for (neighbour in 0 until frameCount) {
                    if (neighbour != frame) estimate -= hessian[frame][neighbour] * delta[neighbour]
                }
                val diagonal = hessian[frame][frame]
                delta[frame] = if (diagonal > 1e-9) {
                    (estimate / diagonal).coerceIn(-maxCorrection, maxCorrection)
                } else {
                    0.0
                }
            }
        }

        for (frame in 0 until frameCount) {
            if (!delta[frame].isFinite()) delta[frame] = 0.0
        }
        return delta
    }

    /**
     * Best rotation taking the unit bearings [a] onto [b] by least squares.
     *
     * Orthogonal Procrustes: build H = Σ b_i·a_i^T, take its SVD H = U S V^T,
     * and return R = U·V^T corrected to a proper rotation. This is the
     * maximum-likelihood rotation under isotropic noise, and the SVD is small
     * enough (3×3) to be done here with cyclic Jacobi rotations instead of
     * pulling in a linear-algebra library.
     */
    fun procrustes(a: Array<DoubleArray>, b: Array<DoubleArray>): DoubleArray {
        require(a.isNotEmpty() && a.size == b.size) { "procrustes needs matching, non-empty points" }
        val h = DoubleArray(SIZE)
        for (i in a.indices) {
            for (r in 0..2) {
                for (c in 0..2) h[r * 3 + c] += b[i][r] * a[i][c]
            }
        }
        // Right singular vectors from the eigen-decomposition of H^T·H.
        val mt = DoubleArray(SIZE)
        for (r in 0..2) {
            for (c in 0..2) {
                mt[r * 3 + c] = h[r] * h[c] + h[3 + r] * h[3 + c] + h[6 + r] * h[6 + c]
            }
        }
        val (eigenvalues, v) = symmetricEigen3x3(mt)

        // Left singular vectors: u_i = H·v_i / s_i, where v_i is the i-th column
        // of V and s_i its singular value. H is row-major, so row r is
        // h[r*3 .. r*3+2].
        val u = DoubleArray(SIZE)
        for (i in 0..2) {
            val singular = sqrt(max(eigenvalues[i], 0.0))
            if (singular < 1e-9) {
                // Degenerate direction: leave the column zero; the determinant
                // fix below still produces a valid rotation in practice.
                continue
            }
            for (r in 0..2) {
                u[r * 3 + i] = (h[r * 3] * v[i] + h[r * 3 + 1] * v[3 + i] + h[r * 3 + 2] * v[6 + i]) / singular
            }
        }

        var rotation = DoubleArray(SIZE)
        // R = U·V^T, so the j-th column of V is read by row index.
        for (i in 0..2) {
            for (j in 0..2) {
                rotation[i * 3 + j] =
                    u[i * 3] * v[3 * j] + u[i * 3 + 1] * v[3 * j + 1] + u[i * 3 + 2] * v[3 * j + 2]
            }
        }
        if (det3(rotation) < 0.0) {
            // Reflect to a proper rotation by flipping the least-significant
            // left singular vector.
            for (r in 0..2) u[r * 3 + 2] = -u[r * 3 + 2]
            rotation = DoubleArray(SIZE)
            for (i in 0..2) {
                for (j in 0..2) {
                    rotation[i * 3 + j] =
                        u[i * 3] * v[3 * j] + u[i * 3 + 1] * v[3 * j + 1] + u[i * 3 + 2] * v[3 * j + 2]
                }
            }
        }
        return rotation
    }

    /**
     * Eigen-decomposition of a symmetric 3×3 matrix by cyclic Jacobi rotations.
     *
     * Returns the eigenvalues in descending order and the eigenvectors as the
     * columns of the second array, in matching order. The implementation is the
     * textbook sweep: zero the largest off-diagonal element with a Givens
     * rotation, accumulate the rotations into the eigenvector matrix, repeat.
     */
    fun symmetricEigen3x3(matrix: DoubleArray): Pair<DoubleArray, DoubleArray> {
        var a = matrix.copyOf()
        var v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(32) {
            val off = doubleArrayOf(abs(a[1]), abs(a[2]), abs(a[5]))
            val max = maxOf(off[0], off[1], off[2])
            if (max < 1e-15) return@repeat
            val (p, q) = when {
                off[0] >= off[1] && off[0] >= off[2] -> 0 to 1
                off[1] >= off[2] -> 0 to 2
                else -> 1 to 2
            }
            val apq = a[p * 3 + q]
            if (abs(apq) < 1e-15) return@repeat
            val app = a[p * 3 + p]
            val aqq = a[q * 3 + q]
            val theta = 0.5 * atan2(2.0 * apq, aqq - app)
            val c = cos(theta)
            val s = sin(theta)
            val r = 3 - p - q

            val newPp = c * c * app - 2 * c * s * apq + s * s * aqq
            val newQq = s * s * app + 2 * c * s * apq + c * c * aqq
            val newPq = (c * c - s * s) * apq + c * s * (app - aqq)
            val apr = a[p * 3 + r]
            val aqr = a[q * 3 + r]
            val newPr = c * apr - s * aqr
            val newQr = s * apr + c * aqr

            a[p * 3 + p] = newPp
            a[q * 3 + q] = newQq
            a[p * 3 + q] = newPq
            a[q * 3 + p] = newPq
            a[p * 3 + r] = newPr
            a[r * 3 + p] = newPr
            a[q * 3 + r] = newQr
            a[r * 3 + q] = newQr

            // Accumulate the eigenvectors. The sweep is A' = J^T·A·J, so the
            // change of basis that diagonalises the original matrix accumulates
            // as V' = V·J — a rotation of columns p and q.
            for (i in 0..2) {
                val vip = v[i * 3 + p]
                val viq = v[i * 3 + q]
                v[i * 3 + p] = c * vip - s * viq
                v[i * 3 + q] = s * vip + c * viq
            }
        }

        val order = intArrayOf(0, 1, 2)
        val values = doubleArrayOf(a[0], a[4], a[8])
        for (i in 0..2) {
            for (j in i + 1..2) {
                if (values[order[j]] > values[order[i]]) {
                    val tmp = order[i]
                    order[i] = order[j]
                    order[j] = tmp
                }
            }
        }
        // Reorder so that returned column i is original column order[i]. `v` is
        // row-major, so a column lives at stride 3 and the interleave goes by
        // row index, not by column — laying the columns out back-to-back would
        // transpose the result.
        return Pair(
            doubleArrayOf(values[order[0]], values[order[1]], values[order[2]]),
            doubleArrayOf(
                v[order[0]], v[order[1]], v[order[2]],
                v[3 + order[0]], v[3 + order[1]], v[3 + order[2]],
                v[6 + order[0]], v[6 + order[1]], v[6 + order[2]],
            ),
        )
    }

    private fun det3(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    // ---- Vector helpers (3-vectors and quaternions) ----

    fun dot3(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross3(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun add3(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] + b[0], a[1] + b[1], a[2] + b[2],
    )

    fun sub3(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] - b[0], a[1] - b[1], a[2] - b[2],
    )

    fun scale3(a: DoubleArray, s: Double): DoubleArray = doubleArrayOf(
        a[0] * s, a[1] * s, a[2] * s,
    )

    fun norm3(a: DoubleArray): Double = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

    fun normalize3(a: DoubleArray): DoubleArray {
        val magnitude = norm3(a)
        return if (magnitude < 1e-12) doubleArrayOf(0.0, 0.0, 1.0) else scale3(a, 1.0 / magnitude)
    }

    private fun dot4(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]

    private fun add4(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3],
    )

    private fun scale4(a: DoubleArray, s: Double): DoubleArray = doubleArrayOf(
        a[0] * s, a[1] * s, a[2] * s, a[3] * s,
    )

    private fun norm4(a: DoubleArray): Double =
        sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3])

    fun quaternionToMatrix(q: DoubleArray): DoubleArray {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        val n = x * x + y * y + z * z + w * w
        if (n < 1e-12) return identity()
        val s = 2.0 / n
        return doubleArrayOf(
            1 - s * (y * y + z * z), s * (x * y - w * z), s * (x * z + w * y),
            s * (x * y + w * z), 1 - s * (x * x + z * z), s * (y * z - w * x),
            s * (x * z - w * y), s * (y * z + w * x), 1 - s * (x * x + y * y),
        )
    }

    /** Shepperd's method: numerically stable quaternion from a rotation matrix. */
    fun matrixToQuaternion(m: DoubleArray): DoubleArray {
        val trace = m[0] + m[4] + m[8]
        return if (trace > 0.0) {
            var s = sqrt(trace + 1.0)
            val w = 0.5 * s
            s = 0.5 / s
            doubleArrayOf(
                (m[7] - m[5]) * s,
                (m[2] - m[6]) * s,
                (m[3] - m[1]) * s,
                w,
            )
        } else if (m[0] > m[4] && m[0] > m[8]) {
            var s = sqrt(1.0 + m[0] - m[4] - m[8])
            val x = 0.5 * s
            s = 0.5 / s
            doubleArrayOf(
                x,
                (m[1] + m[3]) * s,
                (m[2] + m[6]) * s,
                (m[7] - m[5]) * s,
            )
        } else if (m[4] > m[8]) {
            var s = sqrt(1.0 + m[4] - m[0] - m[8])
            val y = 0.5 * s
            s = 0.5 / s
            doubleArrayOf(
                (m[1] + m[3]) * s,
                y,
                (m[5] + m[7]) * s,
                (m[2] - m[6]) * s,
            )
        } else {
            var s = sqrt(1.0 + m[8] - m[0] - m[4])
            val z = 0.5 * s
            s = 0.5 / s
            doubleArrayOf(
                (m[2] + m[6]) * s,
                (m[5] + m[7]) * s,
                z,
                (m[3] - m[1]) * s,
            )
        }
    }
}
