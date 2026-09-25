package com.n30dyn4m1c.photosphere.sensor

import com.n30dyn4m1c.photosphere.stitching.RotationMath

/**
 * The mean of a run of orientation samples, for a steadier pose at the shutter.
 *
 * The alignment gate only fires after the aim has held on a target for a few
 * hundred milliseconds, which means the pose that finally gets stamped onto the
 * frame is the *last* sensor sample of a deliberately still dwell. Averaging the
 * dwell's samples instead takes out the sample-to-sample sensor jitter, which is
 * exactly the noise that shows up as softness in the stitch overlaps. The mean
 * is equal-weight because the whole window is a single deliberate stop.
 *
 * When every sample carries its full camera basis ([OrientationData.cameraBasis]),
 * the mean is taken over the **rotations** themselves — a quaternion mean — and
 * the reported angles are re-derived from the result. Averaging the Euler
 * components instead would smear a pose aimed near the zenith: there yaw and
 * roll collapse into each other, each sample splits them differently, and the
 * averaged components land the frame rotated about its own axis. Samples
 * without a basis fall back to the component mean, which is exact away from
 * the zenith.
 */
internal fun meanOrientation(samples: List<OrientationData>): OrientationData {
    val valid = samples.filter { it.hasFix }
    val reference = valid.lastOrNull() ?: samples.lastOrNull() ?: OrientationData()
    if (valid.isEmpty()) return reference

    // Every sample of the dwell carries the camera basis: average the
    // rotations and re-derive the angles from the result, so the pose the
    // stitcher reconstructs and the pose reported here agree exactly.
    val bases = valid.mapNotNull { it.cameraBasis }
    if (bases.size == valid.size) {
        val basis = meanCameraBasis(bases)
        val angles = anglesFromCameraBasis(basis, FloatArray(3))
        return OrientationData(
            yawDegrees = angles[0],
            pitchDegrees = angles[1],
            rollDegrees = angles[2],
            accuracy = reference.accuracy,
            timestampNanos = reference.timestampNanos,
            cameraBasis = basis,
        )
    }

    return OrientationData(
        yawDegrees = normalizeDegrees(meanAngleDegrees(valid.map { it.yawDegrees })),
        pitchDegrees = meanAngleDegrees(valid.map { it.pitchDegrees }),
        rollDegrees = normalizeDegrees(meanAngleDegrees(valid.map { it.rollDegrees })),
        accuracy = reference.accuracy,
        timestampNanos = reference.timestampNanos,
    )
}

/**
 * The mean rotation of a dwell's camera bases, in the [cameraBasisMatrix]
 * layout.
 *
 * The bases are close — the gate only fires on a deliberate stop — so the
 * linear quaternion mean in [RotationMath.weightedMeanRotation] (signs aligned
 * to the first sample, then a renormalised sum) is the Karcher mean to first
 * order: exactly what a few degrees of sensor jitter need, and well-defined
 * where the Euler components are not.
 */
private fun meanCameraBasis(bases: List<FloatArray>): FloatArray {
    val matrices = bases.map { basis -> DoubleArray(9) { basis[it].toDouble() } }
    val mean = RotationMath.weightedMeanRotation(matrices, List(matrices.size) { 1.0 })
    return FloatArray(9) { mean[it].toFloat() }
}

/**
 * The arithmetic mean of angles, unwrapped so a crossing of ±180° does not smear
 * -179° and +179° into 0°.
 *
 * Angles are measured as deviations from the first value, which works for any
 * range — yaw and roll wrap at ±180°, pitch is bounded at ±90° and never wraps.
 */
internal fun meanAngleDegrees(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val reference = values.first()
    var sum = 0.0
    values.forEach { value ->
        var deviation = value - reference
        while (deviation > 180f) deviation -= 360f
        while (deviation < -180f) deviation += 360f
        sum += deviation
    }
    return reference + (sum / values.size).toFloat()
}
