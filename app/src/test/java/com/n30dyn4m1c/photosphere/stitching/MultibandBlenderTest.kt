package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure-Kotlin parts of the multi-band blend: how a canvas is split
 * into pyramid levels, and how footprints travel between levels. The pyramid
 * arithmetic itself is exercised on a device, where OpenCV's native library
 * is available.
 */
class MultibandBlenderTest {

    // -- Level counting -------------------------------------------------------

    @Test
    fun `a tiny canvas keeps a single level`() {
        listOf(1, 4, 8).forEach { height ->
            assertEquals("height $height should not split", 1, MultibandBlender.levelCountFor(height))
        }
    }

    @Test
    fun `the canvas is split so the coarsest level is about eight rows tall`() {
        // 16 rows split once into 8, 32 rows twice into 8, and so on.
        assertEquals(2, MultibandBlender.levelCountFor(16))
        assertEquals(3, MultibandBlender.levelCountFor(32))
        assertEquals(4, MultibandBlender.levelCountFor(64))
        assertEquals(5, MultibandBlender.levelCountFor(128))
        assertEquals(6, MultibandBlender.levelCountFor(256))
    }

    @Test
    fun `the level count caps so the passes stay worthwhile`() {
        // 2048 rows would happily split into 8 levels; the cap keeps the extra
        // passes out.
        assertEquals(MultibandBlender.MAX_LEVELS, MultibandBlender.levelCountFor(2048))
        assertEquals(MultibandBlender.MAX_LEVELS, MultibandBlender.levelCountFor(4096))
        assertEquals(MultibandBlender.MAX_LEVELS, MultibandBlender.levelCountFor(100_000))
    }

    @Test
    fun `a broken canvas is treated as single level`() {
        assertEquals(1, MultibandBlender.levelCountFor(0))
        assertEquals(1, MultibandBlender.levelCountFor(-5))
    }

    // -- Level sizes ----------------------------------------------------------

    @Test
    fun `level zero is the canvas itself`() {
        assertEquals(4096 to 2048, MultibandBlender.levelSize(4096, 2048, 0))
        assertEquals(512 to 256, MultibandBlender.levelSize(512, 256, 0))
    }

    @Test
    fun `each level is half the previous in both axes`() {
        assertEquals(2048 to 1024, MultibandBlender.levelSize(4096, 2048, 1))
        assertEquals(1024 to 512, MultibandBlender.levelSize(4096, 2048, 2))
        assertEquals(512 to 256, MultibandBlender.levelSize(4096, 2048, 3))
        assertEquals(64 to 32, MultibandBlender.levelSize(4096, 2048, 6))
    }

    @Test
    fun `odd sizes round down but never to nothing`() {
        // A 72-row ring band: level 2 is 18 rows, level 3 is 9.
        assertEquals(72, MultibandBlender.levelSize(4096, 72, 0).second)
        assertEquals(36, MultibandBlender.levelSize(4096, 72, 1).second)
        assertEquals(18, MultibandBlender.levelSize(4096, 72, 2).second)
        assertEquals(9, MultibandBlender.levelSize(4096, 72, 3).second)
        // Far past the canvas the level is still one pixel wide.
        assertEquals(1 to 1, MultibandBlender.levelSize(3, 3, 4))
    }

    // -- Footprint scaling ----------------------------------------------------

    @Test
    fun `level one halves a footprint`() {
        val scaled = EquirectangularRenderer.scaleFootprint(
            CanvasFootprint(startColumn = 400, columnSpan = 800, startRow = 300, rowSpan = 400),
            scale = 2,
        )
        assertTrue(scaled.startColumn in 199..201)
        assertTrue(scaled.startRow in 149..151)
        assertTrue(scaled.columnSpan in 400..402)
        assertTrue(scaled.rowSpan in 200..202)
    }

    @Test
    fun `scaling a seam-wrapping footprint keeps it on the left of zero`() {
        // A footprint straddling the ±180° seam unwraps to negative columns;
        // scaling must not round -3 toward zero and lose the wrapped edge.
        val scaled = EquirectangularRenderer.scaleFootprint(
            CanvasFootprint(startColumn = -3, columnSpan = 600, startRow = 0, rowSpan = 100),
            scale = 2,
        )
        assertTrue("wrapped edge was rounded toward zero", scaled.startColumn <= -1)
    }

    @Test
    fun `scaling by one leaves the footprint untouched`() {
        val footprint = CanvasFootprint(startColumn = -3, columnSpan = 600, startRow = 4, rowSpan = 100)
        assertEquals(footprint, EquirectangularRenderer.scaleFootprint(footprint, scale = 1))
    }

    // -- Band geometry --------------------------------------------------------

    @Test
    fun `every band fits inside its upsampled region`() {
        // A full sphere: level 1 of a 4096x2048 canvas is 1024 tall, bands of 128.
        for (band in 0 until 8) {
            val bandTop = band * 128
            val g = MultibandBlender.bandGeometry(bandTop, 128, 2048, 1024)!!
            assertTrue("band $band crop runs off its region", g.localTop + g.bandRows <= g.upRows)
            assertTrue("band $band region past the coarse level", g.coarseTop + g.coarseRows <= 1024)
        }
    }

    @Test
    fun `an odd canvas height keeps the last band in bounds`() {
        // A 72-degree ring over 360 degrees of longitude: 4096x819 canvas.
        // Level 1 is 409 rows tall, so 818 maps to coarse row 409 (clamped to
        // 408) and the naive 2x2 coarseRows upsample is one row short — this
        // used to throw a native CvException from submat.
        val lastBandTop = 6 * 128 // 768
        val g = MultibandBlender.bandGeometry(lastBandTop, 128, 819, 409)!!
        assertTrue(g.localTop + g.bandRows <= g.upRows)
        assertTrue(g.coarseTop + g.coarseRows <= 409)
    }

    @Test
    fun `odd levels at every scale stay in bounds`() {
        // 819 >> 4 = 51 rows, reconstructed from level 5 (25 rows).
        val g = MultibandBlender.bandGeometry(0, 51, 51, 25)!!
        assertTrue(g.localTop + g.bandRows <= g.upRows)
        assertTrue(g.coarseTop + g.coarseRows <= 25)
    }

    @Test
    fun `the upsampled region is never smaller than the band`() {
        // Sweep odd and even canvas heights and every band position.
        for (canvasHeight in 9..300) {
            val coarseHeight = canvasHeight / 2
            var bandTop = 0
            while (bandTop < canvasHeight) {
                val bandHeight = minOf(128, canvasHeight - bandTop)
                val g = MultibandBlender.bandGeometry(bandTop, bandHeight, canvasHeight, coarseHeight)
                    ?: error("h=$canvasHeight bandTop=$bandTop")
                assertTrue(g.localTop + g.bandRows <= g.upRows)
                assertTrue(g.coarseTop + g.coarseRows <= coarseHeight)
                bandTop += 128
            }
        }
    }

    @Test
    fun `empty bands reconstruct to nothing`() {
        assertEquals(null, MultibandBlender.bandGeometry(2048, 128, 2048, 1024))
        assertEquals(null, MultibandBlender.bandGeometry(0, 128, 0, 0))
    }

    @Test
    fun `a coarse footprint is never empty`() {
        val scaled = EquirectangularRenderer.scaleFootprint(
            CanvasFootprint(startColumn = 10, columnSpan = 20, startRow = 10, rowSpan = 20),
            scale = 16,
        )
        assertTrue(scaled.columnSpan > 0)
        assertTrue(scaled.rowSpan > 0)
    }
}
