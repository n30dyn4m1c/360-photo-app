package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The geometry the sphere is built out of.
 *
 * This is the half of the stitcher that has no OpenCV in it, and it is the half
 * that decides whether a frame lands in the right place — so it is worth
 * pinning down here rather than discovering on a phone.
 */
class SphericalGeometryTest {

    @Test
    fun `a level camera facing north has the axes the world frame expects`() {
        val basis = CameraBasis.of(CameraPose(yawDegrees = 0f, pitchDegrees = 0f, rollDegrees = 0f))

        // Forward is north, right is east, up is up.
        assertVector(0.0, 1.0, 0.0, basis.forwardX, basis.forwardY, basis.forwardZ)
        assertVector(1.0, 0.0, 0.0, basis.rightX, basis.rightY, basis.rightZ)
        assertVector(0.0, 0.0, 1.0, basis.upX, basis.upY, basis.upZ)
    }

    @Test
    fun `facing east swings the axes a quarter turn`() {
        val basis = CameraBasis.of(CameraPose(yawDegrees = 90f, pitchDegrees = 0f, rollDegrees = 0f))

        assertVector(1.0, 0.0, 0.0, basis.forwardX, basis.forwardY, basis.forwardZ)
        // "Right" of a camera facing east is south.
        assertVector(0.0, -1.0, 0.0, basis.rightX, basis.rightY, basis.rightZ)
        assertVector(0.0, 0.0, 1.0, basis.upX, basis.upY, basis.upZ)
    }

    @Test
    fun `a negative pitch aims the camera above the horizon`() {
        // The sensor convention is "negative pitch is aimed up".
        val basis =
            CameraBasis.of(CameraPose(yawDegrees = 0f, pitchDegrees = -90f, rollDegrees = 0f))

        assertVector(0.0, 0.0, 1.0, basis.forwardX, basis.forwardY, basis.forwardZ)
    }

    @Test
    fun `of prefers a measured basis over the angles when the pose carries one`() {
        val measured = CameraBasis.of(
            CameraPose(yawDegrees = 10f, pitchDegrees = 20f, rollDegrees = -15f)
        )
        val pose = CameraPose(
            yawDegrees = 0f,
            pitchDegrees = 0f,
            rollDegrees = 0f,
            matrix = measured.toRotationMatrix(),
        )

        // The matrix wins even though the angles describe a different pose:
        // the measured basis is exact where a reconstruction from the angles
        // is not (the zenith).
        val basis = CameraBasis.of(pose)
        assertEquals(
            0.0,
            RotationMath.angle(basis.toRotationMatrix(), measured.toRotationMatrix()),
            1e-9,
        )
    }

    @Test
    fun `the basis stays orthonormal and right-handed at every attitude`() {
        for (yaw in -180..170 step 30) {
            for (pitch in -80..80 step 20) {
                for (roll in -180..150 step 45) {
                    val basis = CameraBasis.of(
                        CameraPose(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
                    )
                    val label = "yaw $yaw pitch $pitch roll $roll"

                    val forward = doubleArrayOf(basis.forwardX, basis.forwardY, basis.forwardZ)
                    val right = doubleArrayOf(basis.rightX, basis.rightY, basis.rightZ)
                    val up = doubleArrayOf(basis.upX, basis.upY, basis.upZ)

                    assertEquals("$label: forward not unit", 1.0, norm(forward), 1e-9)
                    assertEquals("$label: right not unit", 1.0, norm(right), 1e-9)
                    assertEquals("$label: up not unit", 1.0, norm(up), 1e-9)

                    assertEquals("$label: right·forward", 0.0, dot(right, forward), 1e-9)
                    assertEquals("$label: up·forward", 0.0, dot(up, forward), 1e-9)
                    assertEquals("$label: right·up", 0.0, dot(right, up), 1e-9)

                    // The triple follows the convention SphereProjection places
                    // markers with — "up" is right × forward, which makes
                    // (right, up, forward) left-handed and so `up × right` the
                    // combination that recovers forward. Pinning it down here is
                    // what would catch a sign slip mirroring every frame.
                    val crossed = cross(up, right)
                    assertVector(forward[0], forward[1], forward[2], crossed[0], crossed[1], crossed[2])
                }
            }
        }
    }

    @Test
    fun `projecting a direction and rotating it back are inverses`() {
        val basis =
            CameraBasis.of(CameraPose(yawDegrees = 35f, pitchDegrees = -12f, rollDegrees = 8f))
        val direction = Equirectangular.direction(longitudeDegrees = 30.0, latitudeDegrees = 5.0)

        val cameraX = basis.lateralOf(direction[0], direction[1], direction[2])
        val cameraY = basis.verticalOf(direction[0], direction[1], direction[2])
        val cameraZ = basis.depthOf(direction[0], direction[1], direction[2])
        val world = basis.toWorld(cameraX, cameraY, cameraZ)

        assertVector(direction[0], direction[1], direction[2], world[0], world[1], world[2])
    }

    @Test
    fun `the canvas centre is north and its edges are the date line`() {
        val width = 4096
        val height = 2048

        // Longitude 0 sits at the middle column, latitude 0 at the middle row.
        assertEquals(0.0, Equirectangular.longitudeDegrees(width / 2, width), 0.1)
        assertEquals(0.0, Equirectangular.latitudeDegrees(height / 2, height), 0.1)

        // The first and last columns straddle the seam.
        assertEquals(-180.0, Equirectangular.longitudeDegrees(0, width), 0.1)
        assertEquals(180.0, Equirectangular.longitudeDegrees(width - 1, width), 0.1)

        // Rows run from the north pole down.
        assertEquals(90.0, Equirectangular.latitudeDegrees(0, height), 0.1)
        assertEquals(-90.0, Equirectangular.latitudeDegrees(height - 1, height), 0.1)
    }

    @Test
    fun `a longitude and latitude survive a trip through a direction`() {
        for (longitude in -170..170 step 20) {
            for (latitude in -80..80 step 20) {
                val direction =
                    Equirectangular.direction(longitude.toDouble(), latitude.toDouble())
                assertEquals(
                    "longitude $longitude",
                    longitude.toDouble(),
                    Equirectangular.longitudeOf(direction[0], direction[1]),
                    1e-9,
                )
                assertEquals(
                    "latitude $latitude",
                    latitude.toDouble(),
                    Equirectangular.latitudeOf(direction[2]),
                    1e-9,
                )
            }
        }
    }

    @Test
    fun `column and row lookups invert the canvas sampling`() {
        val width = 2048
        val height = 1024
        listOf(0, 1, 511, 1024, width - 1).forEach { column ->
            val longitude = Equirectangular.longitudeDegrees(column, width)
            assertEquals(column.toDouble(), Equirectangular.columnFor(longitude, width), 1e-9)
        }
        listOf(0, 1, 500, height - 1).forEach { row ->
            val latitude = Equirectangular.latitudeDegrees(row, height)
            assertEquals(row.toDouble(), Equirectangular.rowFor(latitude, height), 1e-9)
        }
    }

    @Test
    fun `a ring canvas maps its latitude band across the full height`() {
        // A ring capture renders 360° of longitude but only the band of latitude
        // the level frames cover — say 72° about the horizon. Rows then span
        // ±36° rather than the poles, and the row lookup inverts the sampling.
        val height = 720
        val span = 72f
        val centre = 0f

        assertEquals(36.0, Equirectangular.latitudeDegrees(0, height, span, centre), 0.1)
        assertEquals(-36.0, Equirectangular.latitudeDegrees(height - 1, height, span, centre), 0.1)
        assertEquals(0.0, Equirectangular.latitudeDegrees(height / 2, height, span, centre), 0.1)

        listOf(0, 1, 359, height - 1).forEach { row ->
            val latitude = Equirectangular.latitudeDegrees(row, height, span, centre)
            assertEquals(row.toDouble(), Equirectangular.rowFor(latitude, height, span, centre), 1e-9)
        }
    }

    @Test
    fun `the centre of a frame lands where the camera was pointing`() {
        val pose = CameraPose(yawDegrees = 40f, pitchDegrees = -20f, rollDegrees = 0f)
        val basis = CameraBasis.of(pose)
        val intrinsics = squareFrame()

        // Elevation is the negated pitch, so aiming 20° up means latitude 20°.
        val direction = Equirectangular.direction(longitudeDegrees = 40.0, latitudeDegrees = 20.0)
        val pixel = projectDirection(basis, intrinsics, direction[0], direction[1], direction[2])

        assertNotNull("the aim point must be inside the frame", pixel)
        assertEquals(intrinsics.centerXPx, pixel!![0], 1e-6)
        assertEquals(intrinsics.centerYPx, pixel[1], 1e-6)
    }

    @Test
    fun `a direction behind the camera does not project`() {
        val basis = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val intrinsics = squareFrame()

        // Facing north; due south is behind.
        val behind = Equirectangular.direction(longitudeDegrees = 180.0, latitudeDegrees = 0.0)
        assertNull(projectDirection(basis, intrinsics, behind[0], behind[1], behind[2]))
    }

    @Test
    fun `a direction past the edge of the field of view does not project`() {
        val basis = CameraBasis.of(CameraPose(0f, 0f, 0f))
        // 60° across, so anything beyond 30° off the axis is outside the frame.
        val intrinsics = FrameIntrinsics.fromFieldOfView(200, 200, 60f, 60f)

        val justInside = Equirectangular.direction(25.0, 0.0)
        assertNotNull(projectDirection(basis, intrinsics, justInside[0], justInside[1], justInside[2]))

        val outside = Equirectangular.direction(40.0, 0.0)
        assertNull(projectDirection(basis, intrinsics, outside[0], outside[1], outside[2]))
    }

    @Test
    fun `a frame facing north sits in the middle of the canvas`() {
        val width = 3600
        val height = 1800
        val basis = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val footprint = FrameFootprint.compute(
            basis = basis,
            intrinsics = FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
            canvasWidth = width,
            canvasHeight = height,
            marginPx = 0,
        )

        assertFalse("a frame facing north cannot cross the seam", footprint.wrapsSeam(width))
        // 60° of a 360° canvas is a sixth of its width, centred.
        val expectedSpan = width / 6
        assertTrue(
            "span ${footprint.columnSpan} should be about $expectedSpan",
            abs(footprint.columnSpan - expectedSpan) <= expectedSpan / 10,
        )
        val centre = footprint.startColumn + footprint.columnSpan / 2.0
        assertEquals("the frame should straddle the middle column", width / 2.0, centre, 5.0)
    }

    @Test
    fun `a frame facing the date line comes back as one unwrapped range`() {
        val width = 3600
        val basis = CameraBasis.of(CameraPose(yawDegrees = 180f, pitchDegrees = 0f, rollDegrees = 0f))
        val footprint = FrameFootprint.compute(
            basis = basis,
            intrinsics = FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
            canvasWidth = width,
            canvasHeight = width / 2,
            marginPx = 0,
        )

        assertTrue("a frame aimed at ±180° must cross the seam", footprint.wrapsSeam(width))
        // Still one contiguous band of longitude, about a sixth of the canvas —
        // not the near-full-width range a naive min/max would produce.
        val expectedSpan = width / 6
        assertTrue(
            "span ${footprint.columnSpan} should be about $expectedSpan",
            abs(footprint.columnSpan - expectedSpan) <= expectedSpan / 10,
        )
    }

    @Test
    fun `a frame over the pole opens out to the whole canvas width`() {
        val width = 3600
        // Pitch -90° aims straight up.
        val basis = CameraBasis.of(CameraPose(yawDegrees = 0f, pitchDegrees = -90f, rollDegrees = 0f))
        val footprint = FrameFootprint.compute(
            basis = basis,
            intrinsics = FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
            canvasWidth = width,
            canvasHeight = width / 2,
            marginPx = 0,
        )

        // Every longitude passes under a frame containing the pole, so no
        // narrower range would be correct.
        assertEquals(0, footprint.startColumn)
        assertEquals(width, footprint.columnSpan)
        assertEquals("the footprint must reach the top row", 0, footprint.startRow)
    }

    @Test
    fun `a footprint never claims rows outside the canvas`() {
        val width = 2048
        val height = 1024
        for (yaw in -180..150 step 30) {
            for (pitch in -90..90 step 15) {
                val footprint = FrameFootprint.compute(
                    basis = CameraBasis.of(CameraPose(yaw.toFloat(), pitch.toFloat(), 0f)),
                    intrinsics = FrameIntrinsics.fromFieldOfView(800, 600, 66f, 52f),
                    canvasWidth = width,
                    canvasHeight = height,
                )
                val label = "yaw $yaw pitch $pitch"
                assertTrue("$label: start row ${footprint.startRow}", footprint.startRow >= 0)
                assertTrue(
                    "$label: runs past the bottom",
                    footprint.startRow + footprint.rowSpan <= height,
                )
                assertTrue("$label: empty footprint", footprint.rowSpan > 0)
                assertTrue(
                    "$label: span ${footprint.columnSpan} exceeds the canvas",
                    footprint.columnSpan <= width,
                )
            }
        }
    }

    @Test
    fun `the footprint contains every canvas pixel a frame can paint`() {
        // The footprint decides which part of the canvas each frame is even
        // offered. Anything it leaves out is a piece of sphere that silently
        // never gets painted, so this walks all 120,000 pixels of a frame and
        // checks each one lands inside — a far denser check than the 100-point
        // border walk the footprint itself is built from.
        val canvasWidth = 720
        val canvasHeight = 360
        val intrinsics = FrameIntrinsics.fromFieldOfView(400, 300, 66f, 52f)

        for (yaw in -180..150 step 60) {
            for (pitch in -75..75 step 30) {
                for (roll in listOf(0, 30)) {
                    val pose = CameraPose(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
                    val basis = CameraBasis.of(pose)
                    val footprint = FrameFootprint.compute(
                        basis = basis,
                        intrinsics = intrinsics,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                    )
                    val label = "yaw $yaw pitch $pitch roll $roll"
                    val columns = footprintColumns(footprint, canvasWidth)

                    for (frameRow in 0 until intrinsics.heightPx) {
                        for (frameColumn in 0 until intrinsics.widthPx) {
                            val cameraX =
                                (frameColumn - intrinsics.centerXPx) / intrinsics.focalXPx
                            val cameraY = -(frameRow - intrinsics.centerYPx) / intrinsics.focalYPx
                            val world = basis.toWorld(cameraX, cameraY, 1.0)
                            val length = Math.sqrt(
                                world[0] * world[0] + world[1] * world[1] + world[2] * world[2]
                            )

                            val longitude = Equirectangular.longitudeOf(world[0], world[1])
                            val latitude = Equirectangular.latitudeOf(world[2] / length)
                            val column = wrapColumn(
                                Math.floor(Equirectangular.columnFor(longitude, canvasWidth))
                                    .toInt(),
                                canvasWidth,
                            )
                            val row = Math.floor(
                                Equirectangular.rowFor(latitude, canvasHeight)
                            ).toInt().coerceIn(0, canvasHeight - 1)

                            assertTrue(
                                "$label: frame pixel ($frameColumn, $frameRow) maps to column " +
                                    "$column, outside the footprint",
                                column in columns,
                            )
                            assertTrue(
                                "$label: frame pixel ($frameColumn, $frameRow) maps to row $row, " +
                                    "outside rows ${footprint.startRow}.." +
                                    "${footprint.startRow + footprint.rowSpan}",
                                row >= footprint.startRow &&
                                    row < footprint.startRow + footprint.rowSpan,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `unwrapping picks the branch nearest the reference`() {
        assertEquals(170.0, unwrapNear(170.0, 175.0), 1e-9)
        // 175° and -175° are 10° apart, not 350°.
        assertEquals(185.0, unwrapNear(-175.0, 175.0), 1e-9)
        assertEquals(-185.0, unwrapNear(175.0, -175.0), 1e-9)
        assertEquals(0.0, unwrapNear(720.0, 0.0), 1e-9)
    }

    @Test
    fun `columns wrap onto the canvas from either side`() {
        assertEquals(0, wrapColumn(0, 100))
        assertEquals(99, wrapColumn(99, 100))
        assertEquals(0, wrapColumn(100, 100))
        assertEquals(5, wrapColumn(105, 100))
        assertEquals(99, wrapColumn(-1, 100))
        assertEquals(95, wrapColumn(-105, 100))
    }

    @Test
    fun `intrinsics turn a field of view into the focal length that spans it`() {
        // A 90° field of view puts the frame edge at 45°, where tan is 1, so the
        // focal length is exactly half the width.
        val intrinsics = FrameIntrinsics.fromFieldOfView(1000, 500, 90f, 90f)
        assertEquals(500.0, intrinsics.focalXPx, 1e-6)
        assertEquals(250.0, intrinsics.focalYPx, 1e-6)
    }

    @Test
    fun `a basis round-trips through the pose it was built from`() {
        for (yaw in -170..170 step 30) {
            for (pitch in -70..70 step 20) {
                for (roll in -160..150 step 40) {
                    val pose = CameraPose(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
                    val basis = CameraBasis.of(pose)
                    val recovered = CameraBasis.of(basis.toPose())
                    assertEquals(
                        "yaw $yaw pitch $pitch roll $roll",
                        0.0,
                        RotationMath.angle(
                            basis.toRotationMatrix(),
                            recovered.toRotationMatrix(),
                        ),
                        1e-6,
                    )
                }
            }
        }
    }

    @Test
    fun `a basis round-trips through its own rotation matrix`() {
        // The regression this guards: fromRotationMatrix used to read the
        // matrix's *rows* as the vectors while toRotationMatrix stores them as
        // columns, so the inverse was actually a transpose. A level frame's
        // recovered forward collapsed to (0,1,0) whatever its yaw, and every
        // frame of a capture stacked onto the same longitude.
        for (yaw in -170..170 step 20) {
            for (pitch in -60..60 step 20) {
                for (roll in -160..150 step 40) {
                    val basis = CameraBasis.of(
                        CameraPose(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
                    )
                    val recovered = CameraBasis.fromRotationMatrix(basis.toRotationMatrix())
                    assertEquals(
                        "yaw $yaw pitch $pitch roll $roll: forward",
                        0.0,
                        RotationMath.angle(
                            basis.toRotationMatrix(),
                            recovered.toRotationMatrix(),
                        ),
                        1e-6,
                    )
                    // The recovered forward must aim where the yaw says it does.
                    val pose = recovered.toPose()
                    assertEquals("yaw $yaw: recovered yaw", yaw.toFloat(), pose.yawDegrees, 1e-3f)
                    assertEquals("pitch $pitch: recovered pitch", pitch.toFloat(), pose.pitchDegrees, 1e-3f)
                    assertEquals("roll $roll: recovered roll", roll.toFloat(), pose.rollDegrees, 1e-3f)
                }
            }
        }
    }

    @Test
    fun `level frames at different yaws get different forwards`() {
        // The exact failure the user saw: three level frames across a pan all
        // collapsed to due north. Distinct yaw must mean distinct forward.
        val a = CameraBasis.fromRotationMatrix(
            CameraBasis.of(CameraPose(72f, 0f, 0f)).toRotationMatrix()
        )
        val b = CameraBasis.fromRotationMatrix(
            CameraBasis.of(CameraPose(116f, 0f, 0f)).toRotationMatrix()
        )
        val angleBetween = Math.toDegrees(
            Math.acos(
                (a.forwardX * b.forwardX + a.forwardY * b.forwardY + a.forwardZ * b.forwardZ)
                    .coerceIn(-1.0, 1.0)
            )
        )
        assertTrue(
            "forwards 72° and 116° apart must stay apart, were $angleBetween° apart",
            angleBetween > 40.0,
        )
    }

    @Test
    fun `the optical axis is untouched by radial distortion`() {
        val basis = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val intrinsics = FrameIntrinsics.fromFieldOfView(
            400, 400, 60f, 60f,
            radial = doubleArrayOf(-2.5e-7, 0.0, 0.0),
        )

        // Facing north along the axis: the ideal pixel is the centre and the
        // distortion factor there is exactly 1.
        val pixel = projectDirection(basis, intrinsics, 0.0, 1.0, 0.0)
        assertNotNull(pixel)
        assertEquals(intrinsics.centerXPx, pixel!![0], 1e-6)
        assertEquals(intrinsics.centerYPx, pixel[1], 1e-6)
    }

    @Test
    fun `an off-axis direction lands elsewhere once the lens is distorted`() {
        val basis = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val pinhole = FrameIntrinsics.fromFieldOfView(400, 400, 60f, 60f)
        val lens = FrameIntrinsics.fromFieldOfView(
            400, 400, 60f, 60f,
            radial = doubleArrayOf(-2.5e-7, 0.0, 0.0),
        )
        val direction = Equirectangular.direction(28.0, 12.0)

        val atPinhole = projectDirection(basis, pinhole, direction[0], direction[1], direction[2])
        val atLens = projectDirection(basis, lens, direction[0], direction[1], direction[2])
        assertNotNull(atPinhole)
        assertNotNull(atLens)
        assertTrue(
            "barrel distortion should pull the pixel inward",
            abs(atPinhole!![0] - atLens!![0]) + abs(atPinhole[1] - atLens[1]) > 1.0,
        )
    }

    @Test
    fun `a barrel-distorted frame paints a wider footprint than a pinhole`() {
        val basis = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val plain = FrameFootprint.compute(
            basis = basis,
            intrinsics = FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
            canvasWidth = 3600,
            canvasHeight = 1800,
            marginPx = 0,
        )
        val barrel = FrameFootprint.compute(
            basis = basis,
            intrinsics = FrameIntrinsics.fromFieldOfView(
                1000, 1000, 60f, 60f,
                radial = doubleArrayOf(-2.5e-7, 0.0, 0.0),
            ),
            canvasWidth = 3600,
            canvasHeight = 1800,
            marginPx = 0,
        )

        // A barrel-distorted frame sees past the pinhole's field of view at its
        // edges, so its footprint must reach further — a footprint that shrank
        // here would be a missed edge in the stitch.
        assertTrue(
            "barrel span ${barrel.columnSpan} should exceed pinhole ${plain.columnSpan}",
            barrel.columnSpan > plain.columnSpan,
        )
    }

    @Test
    fun `overlapping aims are found by the pose graph`() {
        // Two level frames 20° apart on a portrait lens (52° wide, 66° tall):
        // well inside the shared view, so the edge is measured.
        val a = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val b = CameraBasis.of(CameraPose(20f, 0f, 0f))
        assertNotNull(angularOverlap(a, b, horizontalFovDegrees = 52f, verticalFovDegrees = 66f))
        // Vertical separation is judged against the taller axis.
        val above = CameraBasis.of(CameraPose(0f, pitchDegrees = -30f, 0f))
        assertNotNull(angularOverlap(a, above, horizontalFovDegrees = 52f, verticalFovDegrees = 66f))
    }

    @Test
    fun `aims past a full field of view do not overlap`() {
        val a = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val beyond = CameraBasis.of(CameraPose(60f, 0f, 0f))
        assertNull(angularOverlap(a, beyond, horizontalFovDegrees = 52f, verticalFovDegrees = 66f))
    }

    @Test
    fun `the overlap test is directional, not a distance ballpark`() {
        val a = CameraBasis.of(CameraPose(0f, 0f, 0f))
        // 55° of aim difference, all of it horizontal: on a 52°-wide portrait
        // frame the images cannot share a pixel, so the edge is rejected even
        // though a single distance threshold (the old "widest FOV" rule) would
        // have accepted it.
        val farSideways = CameraBasis.of(CameraPose(55f, 0f, 0f))
        assertNull(angularOverlap(a, farSideways, horizontalFovDegrees = 52f, verticalFovDegrees = 66f))
        // The same 55° of aim difference, split across both axes, still
        // overlaps on the tall axis.
        val upAndAcross = CameraBasis.of(CameraPose(35f, -40f, 0f))
        assertNotNull(angularOverlap(a, upAndAcross, horizontalFovDegrees = 52f, verticalFovDegrees = 66f))
    }

    @Test
    fun `closer aims report a stronger overlap`() {
        val a = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val near = angularOverlap(a, CameraBasis.of(CameraPose(10f, 0f, 0f)), 52f, 66f)!!
        val far = angularOverlap(a, CameraBasis.of(CameraPose(30f, 0f, 0f)), 52f, 66f)!!
        assertTrue("near $near should be smaller than far $far", near < far)
    }

    @Test
    fun `an ultra-wide lens still builds a pose graph`() {
        // The bug this guards: the overlap test bounds a separation by
        // `tan(fov)`, which turns negative past 90° and used to reject every
        // pair a wide lens could see. The default device profile *prefers* the
        // widest rear camera, so that silently disabled pose refinement and gain
        // compensation on exactly the hardware they were tuned for.
        val a = CameraBasis.of(CameraPose(0f, 0f, 0f))
        val neighbour = CameraBasis.of(CameraPose(40f, 0f, 0f))
        assertNotNull(
            angularOverlap(a, neighbour, horizontalFovDegrees = 104f, verticalFovDegrees = 120f)
        )
        // And a frame facing away is still rejected, wide lens or not.
        assertNull(
            angularOverlap(
                a,
                CameraBasis.of(CameraPose(170f, 0f, 0f)),
                horizontalFovDegrees = 104f,
                verticalFovDegrees = 120f,
            )
        )
    }

    // -- The blend feather ----------------------------------------------------

    @Test
    fun `the feather is strongest on the optical axis and dies at the border`() {
        assertEquals(1.0, featherWeight(200.0, 150.0, 200.0, 150.0), 1e-12)
        assertEquals(0.0, featherWeight(0.0, 150.0, 200.0, 150.0), 1e-12)
        assertEquals(0.0, featherWeight(400.0, 150.0, 200.0, 150.0), 1e-12)
        assertEquals(0.0, featherWeight(200.0, 0.0, 200.0, 150.0), 1e-12)
        assertTrue(
            featherWeight(240.0, 160.0, 200.0, 150.0) <
                featherWeight(210.0, 152.0, 200.0, 150.0)
        )
    }

    @Test
    fun `a pixel outside the frame on both axes never earns a weight`() {
        // Two negative factors multiply into a positive one. Radial distortion
        // can pull a pixel whose *ideal* position is well outside the frame back
        // inside the distorted bounds, and an unclamped feather would then hand
        // a direction the frame never saw a full-strength vote in the blend —
        // and, at an odd blend power, a negative one that subtracts colour from
        // its neighbours.
        assertEquals(0.0, featherWeight(-500.0, -400.0, 200.0, 150.0), 1e-12)
        assertEquals(0.0, featherWeight(900.0, 700.0, 200.0, 150.0), 1e-12)
        assertEquals(0.0, featherWeight(-500.0, 150.0, 200.0, 150.0), 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an impossible field of view is rejected rather than warped around`() {
        FrameIntrinsics.fromFieldOfView(1000, 1000, 180f, 60f)
    }

    private fun squareFrame() = FrameIntrinsics.fromFieldOfView(400, 400, 66f, 66f)

    /** The canvas columns a footprint covers, with any seam crossing resolved. */
    private fun footprintColumns(footprint: CanvasFootprint, canvasWidth: Int): Set<Int> =
        (0 until footprint.columnSpan)
            .map { wrapColumn(footprint.startColumn + it, canvasWidth) }
            .toSet()

    private fun assertVector(
        expectedX: Double,
        expectedY: Double,
        expectedZ: Double,
        actualX: Double,
        actualY: Double,
        actualZ: Double,
    ) {
        assertEquals("x", expectedX, actualX, TOLERANCE)
        assertEquals("y", expectedY, actualY, TOLERANCE)
        assertEquals("z", expectedZ, actualZ, TOLERANCE)
    }

    private fun norm(vector: DoubleArray): Double = Math.sqrt(dot(vector, vector))

    private fun dot(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
