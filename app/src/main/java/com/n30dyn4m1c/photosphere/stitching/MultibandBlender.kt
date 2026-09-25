package com.n30dyn4m1c.photosphere.stitching

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.min

/**
 * Burt–Adelson multi-band (Laplacian pyramid) blending, in the streaming form
 * the renderer's banded canvas requires.
 *
 * **Why not the feathered mean.** A cross-fade resolves an overlap by fading
 * detail across the whole width of it, so a seam that is even a pixel off
 * shows up twice: the transition is so wide that both copies of an edge are
 * visible inside it. Multi-band blending splits every frame into frequency
 * bands and blends those bands with *progressively wider* masks — a narrow
 * mask for the fine bands, a wide one for the coarse bands. Fine detail is
 * faded across the few pixels the frames genuinely disagree on, while broad
 * illumination is faded across the whole overlap. A seam disappears because at
 * every scale the transition is narrower than the detail it carries.
 *
 * **The maths.** Each frame is decomposed into a Laplacian pyramid
 * `L_l = G_l − EXPAND(G_{l+1})` ending in a coarse Gaussian `G_L`, and its
 * feather mask into a Gaussian pyramid `M_l`. Every band is blended by the
 * normalised weighted mean, and the bands are added back from coarse to fine:
 *
 * ```
 * acc_L = Σ G_i,L·M_i,L / Σ M_i,L
 * acc_l = EXPAND(acc_{l+1}) + Σ L_i,l·M_i,l / Σ M_i,l
 * ```
 *
 * `acc_0` is the finished blend. For a single frame the recurrence collapses
 * to the pyramid reconstruction identity `G_0 = G_L + Σ EXPAND(L_l)` — the
 * frame itself — so a lone frame comes through untouched; in an overlap each
 * band is the feathered mean *restricted to one band*, which is exactly the
 * property that makes multi-band work.
 *
 * **What this file owns.** The pyramid arithmetic: how many levels a canvas
 * warrants, the EXPAND step, the normalised division, and [reconstructBand],
 * the core recurrence evaluated over one horizontal band. It never touches a
 * frame's pixels — the renderer accumulates each level's weighted colour and
 * weight sums, hands them here to be rebuilt, and paints the result. The
 * reconstruction is banded too, so the peak memory of a blend is a few
 * band-sized buffers plus the half-resolution accumulators; the coarse levels
 * that carry the low-frequency cross-fade live in mats a small fraction of the
 * canvas.
 */
internal object MultibandBlender {

    /**
     * Most pyramid levels a canvas is split into. Each extra level is another
     * full pass over the frames at a quarter of the size of the last, so
     * beyond ~6 levels the wide cross-fade gains nothing while the passes keep
     * costing. Six levels over a 2048-tall canvas leave a 64-row coarse
     * Gaussian, which is already deep into "broad illumination" territory.
     */
    const val MAX_LEVELS = 6

    /**
     * Added to the weight sum before the normalised division, so an uncovered
     * pixel divides 0 by ε rather than 0 by 0. Small enough not to move real
     * pixels, whose weights are orders of magnitude larger.
     */
    const val WEIGHT_EPSILON = 1e-6

    /**
     * One level's weighted colour sum and its mask sum — the two things
     * [reconstructBand] needs to rebuild that level's band.
     */
    class PyramidLevel(val color: Mat, val weight: Mat) {
        /** Releases both mats. Safe to call again; releasing is idempotent. */
        fun release() {
            color.release()
            weight.release()
        }
    }

    /**
     * How many bands a [canvasHeight]-tall canvas splits into (levels `0..n-1`).
     *
     * The coarsest band should still be a meaningful Gaussian of the frames —
     * about 8 rows tall — so the count is `log2(height / 8)` capped at
     * [MAX_LEVELS]. A canvas shorter than 8 rows keeps a single level, which
     * degrades to the feathered mean the renderer fell back to.
     */
    fun levelCountFor(canvasHeight: Int): Int {
        if (canvasHeight <= 0) return 1
        var height = canvasHeight
        var levels = 1
        while (height > 8 && levels < MAX_LEVELS) {
            height /= 2
            levels++
        }
        return levels
    }

    /** Width and height of pyramid level [level] of a [width]×[height] canvas. */
    fun levelSize(width: Int, height: Int, level: Int): Pair<Int, Int> {
        val shift = level.coerceAtLeast(0)
        return (width shr shift).coerceAtLeast(1) to (height shr shift).coerceAtLeast(1)
    }

    /**
     * The rectangles one band's reconstruction touches, so the Mat work stays
     * in bounds.
     *
     * A band of canvas rows `[bandTop, bandTop + bandRows)` is rebuilt from
     * the coarser level's rows that cover it, `[coarseTop, coarseTop + coarseRows)`,
     * upsampled to [upRows] rows. [localTop] is where the band starts inside
     * the upsampled region.
     *
     * The upsampled region is sized to *exactly* [localTop] + [bandRows] rows,
     * not the naive `2 × coarseRows`: a canvas height that is not a power of
     * two gives odd level heights, where the last band's rows reach further
     * than a clean ×2 of its coarse rows (the topmost fine rows all read the
     * last coarse row, clamped). Growing the region keeps every crop in bounds
     * and matches the clamped full-level upsample at the edge — a too-small
     * region throws a native `CvException` inside `submat`.
     *
     * Returns null when the band is empty or reaches nothing in the coarser
     * level.
     */
    fun bandGeometry(
        bandTop: Int,
        bandHeight: Int,
        canvasHeight: Int,
        coarseHeight: Int,
    ): BandGeometry? {
        val bandRows = min(bandHeight, canvasHeight - bandTop)
        if (bandRows <= 0) return null
        val coarseTop = (bandTop / 2 - 1).coerceIn(0, coarseHeight)
        val coarseBottom = ((bandTop + bandRows) / 2 + 1).coerceIn(coarseTop, coarseHeight)
        val coarseRows = coarseBottom - coarseTop
        if (coarseRows <= 0) return null
        val localTop = bandTop - coarseTop * 2
        return BandGeometry(
            bandRows = bandRows,
            coarseTop = coarseTop,
            coarseRows = coarseRows,
            localTop = localTop,
            upRows = localTop + bandRows,
        )
    }

    /**
     * The layout one band's reconstruction uses. All rects derived from it are
     * in bounds by construction: `coarseTop + coarseRows ≤ coarseHeight` and
     * `localTop + bandRows = upRows`.
     */
    class BandGeometry(
        val bandRows: Int,
        val coarseTop: Int,
        val coarseRows: Int,
        val localTop: Int,
        val upRows: Int,
    )

    /**
     * The normalised image `color / (weight + ε)`, with the single-channel
     * weight broadcast to the colour's three channels.
     *
     * This is the whole of the coarsest level's blend, and the final step of
     * every other level's recurrence. The ε guards the uncovered pixels.
     */
    fun normalizeColor(color: Mat, weight: Mat): Mat {
        val weightWithEpsilon = Mat()
        val weight3 = Mat()
        val out = Mat()
        try {
            Core.add(weight, Scalar(WEIGHT_EPSILON), weightWithEpsilon)
            Core.merge(listOf(weightWithEpsilon, weightWithEpsilon, weightWithEpsilon), weight3)
            Core.divide(color, weight3, out)
        } finally {
            weightWithEpsilon.release()
            weight3.release()
        }
        return out
    }

    /**
     * Reconstructs one horizontal band of a pyramid level.
     *
     * [colorBand] and [weightBand] are this level's weighted sums over the
     * band's rows: a band-height Mat for the finest level, or a submat of the
     * full level elsewhere. [coarseAcc], [coarseColor] and [coarseWeight] are
     * the next-coarser level's *reconstructed* image, colour sum and weight
     * sum, all full-canvas at that level. The band occupies canvas rows
     * `[bandTop, bandTop + bandHeight)`.
     *
     * The recurrence, in the streaming form the memory budget demands:
     *
     * ```
     * acc_l = EXPAND(acc_{l+1}) + (PWarp_l − EXPAND(PWarp_{l+1})·r_l) / (WSum_l + ε)
     * r_l   = WSum_l / (EXPAND(WSum_{l+1}) + ε)
     * ```
     *
     * with `PWarp_l = Σ W_i,l·M_i,l` and `WSum_l = Σ M_i,l` the renderer's
     * accumulators. The per-frame Laplacian sum `Σ L_i,l·M_i,l` is recovered
     * from them rather than summed per frame — that would hold a whole pyramid
     * per frame — and the `r_l` factor is what keeps that recovery honest at a
     * frame's border. Without it, the upsampled coarse content `EXPAND(PWarp_{l+1})`
     * stays nonzero one pixel past where the fine mask has already gone to
     * zero (the fine and coarse masks only coincide approximately), and a pixel
     * the frames just stop covering divides a nonzero band by ~ε. Scaling the
     * upsampled term by `WSum_l / EXPAND(WSum_{l+1})` makes the numerator vanish
     * exactly where `WSum_l` does — the ratio is ≈1 wherever the masks are
     * smooth and tends to zero with the fine mask — so uncovered pixels fall
     * back to the already-reconstructed coarser content instead of dividing by
     * nothing. The division then needs only the ε guard for genuinely blank
     * canvas, exactly as OpenCV's `MultiBandBlender` handles its own weight sums.
     *
     * Returns the reconstructed band, a `CV_32FC3` Mat `[bandHeight]` rows
     * tall that the caller owns.
     */
    fun reconstructBand(
        colorBand: Mat,
        weightBand: Mat,
        coarseAcc: Mat,
        coarseColor: Mat,
        coarseWeight: Mat,
        canvasWidth: Int,
        canvasHeight: Int,
        coarseWidth: Int,
        coarseHeight: Int,
        bandTop: Int,
        bandHeight: Int,
    ): Mat {
        val bandRows = min(bandHeight, canvasHeight - bandTop)
        if (bandRows <= 0) return Mat()

        // This level's rows come from the coarser level's rows that cover
        // them — [bandTop/2 − 1, (bandTop+bandRows)/2 + 1] — upsampled back.
        val geometry = bandGeometry(bandTop, bandHeight, canvasHeight, coarseHeight)
            ?: return Mat()
        val coarseAccRegion = coarseAcc.submat(
            Rect(0, geometry.coarseTop, coarseWidth, geometry.coarseRows),
        )
        val coarseColorRegion = coarseColor.submat(
            Rect(0, geometry.coarseTop, coarseWidth, geometry.coarseRows),
        )
        val coarseWeightRegion = coarseWeight.submat(
            Rect(0, geometry.coarseTop, coarseWidth, geometry.coarseRows),
        )
        val accUp = Mat()
        val colorUp = Mat()
        val weightUp = Mat()
        val ratio = Mat()
        val scaledColorUp = Mat()
        val ratio3 = Mat()
        val p = Mat()
        val normalized = Mat()
        val weightWithEpsilon = Mat()
        val weight3 = Mat()
        val result = Mat()
        try {
            val upSize = Size(canvasWidth.toDouble(), geometry.upRows.toDouble())
            Imgproc.resize(coarseAccRegion, accUp, upSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.resize(coarseColorRegion, colorUp, upSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.resize(coarseWeightRegion, weightUp, upSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
            val bandRegion = Rect(0, geometry.localTop, canvasWidth, geometry.bandRows)
            val colorUpBand = colorUp.submat(bandRegion)
            val accUpBand = accUp.submat(bandRegion)
            val weightUpBand = weightUp.submat(bandRegion)
            try {
                // The band of the Laplacian sum: PWarp_l − EXPAND(PWarp_{l+1})·r_l.
                Core.add(weightUpBand, Scalar(WEIGHT_EPSILON), ratio)
                Core.divide(weightBand, ratio, ratio)
                Core.merge(listOf(ratio, ratio, ratio), ratio3)
                Core.multiply(colorUpBand, ratio3, scaledColorUp)
                Core.subtract(colorBand, scaledColorUp, p)
                Core.add(weightBand, Scalar(WEIGHT_EPSILON), weightWithEpsilon)
                Core.merge(listOf(weightWithEpsilon, weightWithEpsilon, weightWithEpsilon), weight3)
                Core.divide(p, weight3, normalized)
                // Fold the already-reconstructed coarser content back in.
                Core.add(accUpBand, normalized, result)
            } finally {
                colorUpBand.release()
                accUpBand.release()
                weightUpBand.release()
            }
        } finally {
            coarseAccRegion.release()
            coarseColorRegion.release()
            coarseWeightRegion.release()
            accUp.release()
            colorUp.release()
            weightUp.release()
            ratio.release()
            scaledColorUp.release()
            ratio3.release()
            p.release()
            normalized.release()
            weightWithEpsilon.release()
            weight3.release()
        }
        return result
    }
}
