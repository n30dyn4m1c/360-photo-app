package com.n30dyn4m1c.photosphere.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.n30dyn4m1c.photosphere.stitching.sampleSizeFor
import java.io.File

/**
 * Picks the sharpest of a burst of otherwise-identical frames.
 *
 * Every shot in a burst is captured at the same locked exposure, focus and
 * colour, so they differ only in what handheld motion did to them. The one to
 * keep is the sharpest, judged by the variance of the Laplacian — the classic
 * focus/sharpness metric: edges push the Laplacian response up, so a frame with
 * crisp edges scores higher than one blurred by shake.
 *
 * Frames are scored at the resolution the stitch reads them at, not at a
 * thumbnail. Handheld shake on a 12 MP still is typically a few pixels of
 * smear — plainly visible in a 2000 px stitch input, and gone entirely once the
 * frame is shrunk to a few hundred pixels, where every candidate scores the
 * same and the pick is a coin toss. Only the central part of the frame is
 * decoded (a region decode, so the rest of the JPEG is never inflated), which
 * keeps the score to a few tens of milliseconds per candidate.
 */
object SharpnessSelection {

    /**
     * Long edge the *full* frame would have at the scoring resolution — the
     * stitch's own input size, so the score sees exactly the blur the sphere
     * will show.
     */
    const val SCORE_FULL_FRAME_LONG_EDGE: Int = 2000

    /** Fraction of each axis, centred, that is decoded and scored. */
    const val SCORE_CENTER_FRACTION: Float = 0.5f

    /**
     * Index of the sharpest of [files], decoded and scored; the first on a tie.
     *
     * An index rather than the file, so the caller can keep whatever else it
     * recorded per shot (the pose each one was taken at) paired with the pick.
     */
    fun sharpestIndex(files: List<File>): Int {
        require(files.isNotEmpty()) { "nothing to pick from" }
        if (files.size == 1) return 0
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        files.forEachIndexed { index, file ->
            val score = laplacianVarianceOf(file)
            if (score > bestScore) {
                best = index
                bestScore = score
            }
        }
        return best
    }

    /**
     * Variance of the Laplacian response over [pixels], a 1-D array laid out
     * row-major as [width] × [height] ARGB ints.
     *
     * Pure, so it can be unit-tested without a device. Higher is sharper.
     */
    fun laplacianVariance(pixels: IntArray, width: Int, height: Int): Float {
        require(pixels.size >= width * height) { "pixel buffer is too small" }
        if (width < 3 || height < 3) return 0f

        var sum = 0.0
        var sumSquared = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = luma(pixels[i - 1]) + luma(pixels[i + 1]) +
                    luma(pixels[i - width]) + luma(pixels[i + width]) -
                    4.0 * luma(pixels[i])
                sum += lap
                sumSquared += lap * lap
                count++
            }
        }
        if (count == 0) return 0f

        val mean = sum / count
        return ((sumSquared / count) - mean * mean).toFloat()
    }

    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    private fun laplacianVarianceOf(file: File): Float {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0f

        val options = BitmapFactory.Options().apply {
            inSampleSize =
                sampleSizeFor(bounds.outWidth, bounds.outHeight, SCORE_FULL_FRAME_LONG_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // The JPEG's stored axes (EXIF rotation is irrelevant to a sharpness
        // score): the centre crop is the same region whichever way is up.
        val region = centerRegion(bounds.outWidth, bounds.outHeight, SCORE_CENTER_FRACTION)
        val bitmap = runCatching {
            @Suppress("DEPRECATION")
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file.path)
            } else {
                BitmapRegionDecoder.newInstance(file.path, false)
            }
            try {
                decoder?.decodeRegion(region, options)
            } finally {
                decoder?.recycle()
            }
        }.getOrNull()
            // A decoder that refuses the file (not a JPEG it can tile) still
            // gets a score from a whole-frame decode.
            ?: BitmapFactory.decodeFile(file.path, options)
            ?: return 0f
        // Read the dimensions out before recycling: a recycled bitmap's
        // accessors are not contractually defined, and scoring against whatever
        // they happen to return would silently pick the wrong frame.
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return laplacianVariance(pixels, width, height)
    }

    /** The centred [fraction] of a [width] × [height] image, at least 1 px. */
    internal fun centerRegion(width: Int, height: Int, fraction: Float): Rect {
        val cropWidth = (width * fraction).toInt().coerceIn(1, width)
        val cropHeight = (height * fraction).toInt().coerceIn(1, height)
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }
}
