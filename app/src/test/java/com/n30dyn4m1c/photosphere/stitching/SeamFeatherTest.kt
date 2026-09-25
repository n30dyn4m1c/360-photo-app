package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure-Kotlin seam feathering: how the solved label map becomes the
 * narrow cross-fade the renderer looks up, and how the lookup maps pyramid
 * levels back onto the reduced grid.
 */
class SeamFeatherTest {

    @Test
    fun `boundary pixels get the other frame with a half-weight at the cut`() {
        // Two frames split down the middle: frame 0 paints the left half,
        // frame 1 the right half.
        val labelMap = intArrayOf(
            0, 0, 1, 1,
            0, 0, 1, 1,
        )
        val (loser, weight) = SeamFeather.derive(labelMap, gridWidth = 4, gridHeight = 2, halfWidth = 2)

        // The pixel right next to the cut (column 1, frame 0's side) loses to
        // frame 1 at the nearest distance 1.
        assertEquals(1, loser[1])
        assertEquals(0.5f * (1f - 1f / 3f), weight[1], 1e-6f)
        assertEquals(1, loser[4 + 1])
        // Column 2, on frame 1's side, loses to frame 0.
        assertEquals(0, loser[2])
        assertEquals(0, loser[4 + 2])
    }

    @Test
    fun `pixels far from a boundary keep no losing frame`() {
        val labelMap = intArrayOf(
            0, 0, 0,
            0, 0, 0,
        )
        val (loser, weight) = SeamFeather.derive(labelMap, gridWidth = 3, gridHeight = 2, halfWidth = 2)
        assertTrue(loser.all { it == -1 })
        assertTrue(weight.all { it == 0f })
    }

    @Test
    fun `the weight falls monotonically away from the seam`() {
        // A single vertical seam in the middle of a wide row.
        val width = 7
        val labelMap = IntArray(width) { if (it < 3) 0 else 1 }
        val (loser, weight) = SeamFeather.derive(labelMap, width, gridHeight = 1, halfWidth = 2)
        assertEquals(0, loser[3])
        assertEquals(1, loser[2])
        // Weight at the boundary is highest and decays with distance.
        assertTrue("boundary weight is the half of the ramp", weight[2] > weight[1])
        assertTrue("one pixel further the ramp is smaller still", weight[1] > weight[0])
        // All weights stay within [0, 0.5].
        weight.forEach { assertTrue("weight $it out of range", it in 0f..0.5f) }
    }

    @Test
    fun `uncovered pixels are ignored by the feather`() {
        // The middle column is never shot (-1); the two frames still carve a
        // seam, but no feather reaches across the gap.
        val labelMap = intArrayOf(
            0, 0, -1, 1, 1,
        )
        val (loser, weight) = SeamFeather.derive(labelMap, gridWidth = 5, gridHeight = 1, halfWidth = 2)
        assertEquals(-1, loser[2])
        assertEquals(0f, weight[2], 1e-6f)
        assertEquals(1, loser[1])
        assertEquals(0, loser[3])
    }

    @Test
    fun `the weight lookup maps a pyramid level back to the grid`() {
        // Grid scale 2: each grid pixel covers a 2x2 block of level-0 pixels.
        val seams = SeamWeights(
            gridWidth = 2,
            gridHeight = 1,
            scale = 2,
            labelMap = intArrayOf(0, 1),
            loserLabel = intArrayOf(1, 0),
            loserWeight = floatArrayOf(0.25f, 0.25f),
        )
        // Level 0, left block: frame 0 wins at full strength.
        assertEquals(1f, seams.weightFor(0, 0, 0, sourceScale = 1), 1e-6f)
        assertEquals(0.25f, seams.weightFor(1, 0, 0, sourceScale = 1), 1e-6f)
        // Right block: frame 1 wins.
        assertEquals(1f, seams.weightFor(1, 0, 3, sourceScale = 1), 1e-6f)
        assertEquals(0.25f, seams.weightFor(0, 0, 3, sourceScale = 1), 1e-6f)
        // A frame that is neither winner nor loser contributes nothing.
        assertEquals(0f, seams.weightFor(2, 0, 0, sourceScale = 1), 1e-6f)
        // Level 1 halves the coordinates: (1, 1) at level 1 is (2, 2) at level 0,
        // which is the right block.
        assertEquals(1f, seams.weightFor(1, 1, 1, sourceScale = 2), 1e-6f)
    }
}
