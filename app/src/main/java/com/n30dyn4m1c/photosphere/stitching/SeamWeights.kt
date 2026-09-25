package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Which frame paints each pixel, at a reduced "label resolution".
 *
 * The renderer paints one frame per pixel; this is the map that says which. It
 * lives at a fraction of the canvas resolution — every entry covers a `scale`×
 * `scale` block of output pixels — because a seam only needs to be decided to
 * within a few output pixels, and the reduced grid keeps the graph-cut solve
 * and the feathering cheap.
 *
 * [labelMap] holds the winning frame per grid pixel (-1 where nothing covers
 * the sphere). The seam itself is never a hard knife edge: [loserLabel] and
 * [loserWeight] name, for grid pixels within a feather of a boundary, the frame
 * on the other side and how much of it the blend should keep — 0.5 on the
 * boundary itself, ramping to nothing a few pixels away. Everywhere else the
 * winner paints alone at full strength, which is the sharpness seam carving
 * exists for.
 */
internal class SeamWeights(
    val gridWidth: Int,
    val gridHeight: Int,
    val scale: Int,
    val labelMap: IntArray,
    val loserLabel: IntArray,
    val loserWeight: FloatArray,
) {
    init {
        require(gridWidth > 0 && gridHeight > 0) { "seam grid must have extent" }
        require(scale > 0) { "seam scale must be positive" }
        val size = gridWidth * gridHeight
        require(labelMap.size == size && loserLabel.size == size && loserWeight.size == size) {
            "seam arrays must match the grid"
        }
    }

    /**
     * The blend weight of [frameIndex] at one pixel of a pyramid level.
     *
     * [levelRow]/[levelCol] are coordinates in the level's own canvas (the
     * renderer's banded accumulation), so [sourceScale] (`2^level`) brings them
     * back to level-0 pixels before the grid lookup.
     */
    fun weightFor(frameIndex: Int, levelRow: Int, levelCol: Int, sourceScale: Int): Float {
        val row0 = levelRow * sourceScale
        val col0 = levelCol * sourceScale
        val gridRow = (row0 / scale).coerceIn(0, gridHeight - 1)
        val gridCol = (col0 / scale).coerceIn(0, gridWidth - 1)
        val index = gridRow * gridWidth + gridCol
        val winner = labelMap[index]
        if (winner == frameIndex) return 1f
        if (loserLabel[index] == frameIndex) return loserWeight[index]
        return 0f
    }
}

/**
 * Turns a solved label map into the narrow seam feather.
 *
 * Each grid pixel gets the *nearest* pixel painted by a different frame and a
 * weight for it that starts at half on the boundary and falls to zero over
 * [halfWidth] pixels — the "fading only a few pixels across the cut" of the
 * README's seam carving. The winner always holds weight 1; only the losing
 * frame's contribution ramps, so the blend inside the feather goes smoothly
 * from one frame to the other and stays pure either side.
 *
 * The scan is deliberately a square window rather than a distance transform:
 * the feather is a few pixels wide, so the O(window) work per pixel over the
 * small label grid is far cheaper than a transform over the full canvas.
 */
internal object SeamFeather {

    fun derive(
        labelMap: IntArray,
        gridWidth: Int,
        gridHeight: Int,
        halfWidth: Int,
        wrapLongitude: Boolean = false,
    ): Pair<IntArray, FloatArray> {
        val size = gridWidth * gridHeight
        val loserLabel = IntArray(size) { -1 }
        val loserWeight = FloatArray(size)
        for (r in 0 until gridHeight) {
            for (c in 0 until gridWidth) {
                val index = r * gridWidth + c
                val winner = labelMap[index]
                if (winner < 0) continue

                var bestLabel = -1
                var bestDistance = halfWidth + 1
                val minRow = max(0, r - halfWidth)
                val maxRow = min(gridHeight - 1, r + halfWidth)
                for (nr in minRow..maxRow) {
                    for (dc in -halfWidth..halfWidth) {
                        // On a full-turn canvas the ±180° meridian wraps: a
                        // window that runs past the left edge keeps looking at
                        // the row's far end, so the loser ramp can cross the
                        // seam (the column distance is just |dc| — the window
                        // is narrower than the grid, so the wrapped distance to
                        // a wrapped neighbour is the offset itself).
                        var nc = c + dc
                        if (wrapLongitude) {
                            nc = ((nc % gridWidth) + gridWidth) % gridWidth
                        } else if (nc < 0 || nc >= gridWidth) {
                            continue
                        }
                        val neighbour = labelMap[nr * gridWidth + nc]
                        if (neighbour < 0 || neighbour == winner) continue
                        val distance = max(abs(nr - r), abs(dc))
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestLabel = neighbour
                        }
                    }
                }

                if (bestLabel >= 0) {
                    loserLabel[index] = bestLabel
                    // Half on the boundary, zero just past the feather, linear
                    // in between — one divide per boundary pixel, cheap.
                    loserWeight[index] = 0.5f * (1f - bestDistance / (halfWidth + 1f))
                }
            }
        }
        return loserLabel to loserWeight
    }
}
