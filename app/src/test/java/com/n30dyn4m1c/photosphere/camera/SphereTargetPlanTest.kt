package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.normalizeDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private const val TOLERANCE = 1e-3f

class SphereTargetPlanTest {

    @Test
    fun `every ring lies inside the plus or minus sixty degree band`() {
        val plan = SphereTargetPlan.create()

        assertTrue(plan.size > 0)
        plan.targets.forEach { target ->
            assertTrue(
                "elevation ${target.elevationDegrees} outside the band",
                abs(target.elevationDegrees) <= 60f + TOLERANCE,
            )
            assertTrue(
                "yaw ${target.yawDegrees} outside [-180, 180)",
                target.yawDegrees >= -180f && target.yawDegrees < 180f,
            )
        }
    }

    @Test
    fun `rings thin out toward the poles`() {
        val plan = SphereTargetPlan.create()
        val perRing = plan.targets.groupingBy { it.elevationDegrees }.eachCount()

        // 30 degrees of yaw at the equator, widened by 1/cos(elevation) above it.
        assertEquals(12, perRing[0f])
        assertEquals(10, perRing[30f])
        assertEquals(10, perRing[-30f])
        assertEquals(6, perRing[60f])
        assertEquals(6, perRing[-60f])
        assertEquals(44, plan.size)
    }

    @Test
    fun `capture starts at the bearing the user is already facing`() {
        val plan = SphereTargetPlan.create(startYawDegrees = -75f)

        assertEquals(-75f, plan[0].yawDegrees, TOLERANCE)
        assertEquals(0f, plan[0].elevationDegrees, TOLERANCE)
    }

    @Test
    fun `alternate rings are swept the other way so the seams meet`() {
        val plan = SphereTargetPlan.create(startYawDegrees = 0f)
        val equator = plan.targets.filter { it.elevationDegrees == 0f }
        val secondRing = plan.targets.filter { it.elevationDegrees == 30f }

        // The equator sweeps clockwise and finishes just short of a full turn...
        assertEquals(30f, equator[1].yawDegrees, TOLERANCE)
        assertEquals(-30f, equator.last().yawDegrees, TOLERANCE)
        // ...and the next ring is walked back the other way, so it picks up near
        // the bearing the equator left off at instead of a turn away from it.
        val handover = normalizeDegrees(secondRing.first().yawDegrees - equator.last().yawDegrees)
        assertTrue("ring hand-over turns $handover degrees", abs(handover) < 45f)
        assertEquals(0f, secondRing.last().yawDegrees, TOLERANCE)
    }

    @Test
    fun `neighbours within a ring stay a constant angular distance apart`() {
        val plan = SphereTargetPlan.create()

        listOf(0f, 30f, 60f).forEach { elevation ->
            val ring = plan.targets.filter { it.elevationDegrees == elevation }
            val separations = ring.indices.map { index ->
                val a = ring[index]
                val b = ring[(index + 1) % ring.size]
                angularDistance(a, b)
            }
            separations.forEach { separation ->
                // The ring counts are integers, so the spacing lands near the
                // 30-degree target rather than exactly on it.
                assertTrue(
                    "elevation $elevation spaced $separation degrees apart",
                    separation in 24f..37f,
                )
            }
        }
    }

    @Test
    fun `field of view scaling keeps overlap roughly constant across lenses`() {
        val narrow = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
        )
        val wide = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 76f, verticalDegrees = 66f),
        )

        // A wider lens needs fewer, larger frames per ring.
        assertTrue("wide lens took more frames", wide.size < narrow.size)

        // The equator spacing tracks the lens, so the overlap each frame has
        // with its neighbour stays near the 35% target rather than piling up.
        val overlap = { plan: SphereTargetPlan, horizontalFov: Float ->
            val equator = plan.targets.filter { it.elevationDegrees == 0f }
            val separation = angularDistance(equator[0], equator[1])
            1f - separation / horizontalFov
        }
        // The band is wide because the plan deliberately spends less of the
        // lens than the lens claims (FIELD_OF_VIEW_SAFETY_FACTOR), so the real
        // overlap sits above the nominal target rather than below it.
        assertTrue("narrow-lens overlap off target", overlap(narrow, 52f) in 0.30f..0.55f)
        assertTrue("wide-lens overlap off target", overlap(wide, 76f) in 0.30f..0.55f)
    }

    @Test
    fun `a plan can be laid out with custom rings`() {
        val plan = SphereTargetPlan.create(
            ringElevations = listOf(0f),
            equatorSpacingDegrees = 90f,
        )

        assertEquals(4, plan.size)
        assertEquals(listOf(0f, 90f, -180f, -90f), plan.targets.map { it.yawDegrees })
    }

    @Test
    fun `adaptive rings step with the vertical field of view and reach the poles`() {
        val vFov = 66f
        val overlap = SphereTargetPlan.DEFAULT_TARGET_OVERLAP_FRACTION
        val step = vFov * (1f - overlap)
        val elevations = SphereTargetPlan.adaptiveRingElevations(vFov, overlap)

        // The horizon is always the first ring, and rings are symmetric.
        assertTrue(elevations.contains(0f))
        elevations.filter { it != 0f }.forEach {
            assertTrue("ring $it has no mirror", elevations.contains(-it))
        }

        // The first ring above the horizon is one vertical step up, so vertical
        // overlap is guaranteed whatever the lens.
        val positive = elevations.filter { it > 0f }.sorted()
        assertEquals("first ring above the horizon", step, positive.first(), 1f)

        // And the top ring must cover the pole with room to spare.
        val top = elevations.maxOf { abs(it) }
        assertTrue("top ring $top leaves the pole uncovered", top + vFov / 2f >= 90f)
    }

    @Test
    fun `a narrower lens gets more rings than a wider one`() {
        val narrow = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 40f),
        )
        val wide = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 76f, verticalDegrees = 90f),
        )

        // A small vertical field of view needs more, thinner rings to cover the
        // same sphere; every target still stays inside the legal band.
        assertTrue("narrow lens took fewer frames", narrow.size > wide.size)
        narrow.targets.forEach { target ->
            assertTrue(
                "elevation ${target.elevationDegrees} outside the band",
                abs(target.elevationDegrees) <= 75f + TOLERANCE,
            )
        }
    }

    @Test
    fun `an ultra-wide lens does not stack near-duplicate zenith rings`() {
        // The S23's ultrawide in portrait has a ~105° vertical field of view,
        // which puts the vertical step (~68°) and the pole-covering cap (~72°)
        // within half a step of each other — the intermediate ring would be a
        // near-duplicate, so only the cap ring is laid out above the horizon.
        val elevations = SphereTargetPlan.adaptiveRingElevations(105f, 0.35f)
        val positive = elevations.filter { it > 0f }.sorted()

        assertEquals(1, positive.size)
        val top = positive.first()
        assertTrue("cap $top leaves the pole uncovered", top + 105f / 2f >= 90f)
    }

    @Test
    fun `a ring capture lays out the horizon and nothing else`() {
        val ring = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
            scope = SphereCaptureScope.Ring,
        )

        assertEquals(1, ring.ringCount)
        assertTrue("a ring should still take a dozen-odd frames", ring.size in 8..20)
        ring.targets.forEach {
            assertEquals("ring target off the horizon", 0f, it.elevationDegrees, TOLERANCE)
        }
    }

    @Test
    fun `a ring capture closes the full circle`() {
        // The "regular pano that goes all the way around": the horizon band,
        // but the sweep must cover the whole 360° — not just a 180° slice of it.
        val ring = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 40f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
            scope = SphereCaptureScope.Ring,
        )

        val gaps = ring.targets.indices.sumOf { index ->
            angularDistance(ring.targets[index], ring.targets[(index + 1) % ring.size]).toDouble()
        }
        assertEquals("the ring does not close on itself", 360.0, gaps, 1.0)
    }

    @Test
    fun `a sphere capture is many rings and starts with the horizon`() {
        val sphere = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
            scope = SphereCaptureScope.Sphere,
        )
        val ring = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
            scope = SphereCaptureScope.Ring,
        )

        assertTrue("a sphere should take more rings than a ring", sphere.ringCount > 1)
        // The first ring of a sphere run *is* the ring run, which is what lets a
        // user start on a sphere and stop with a complete band anyway.
        assertEquals(ring.size, sphere.rings.first().last + 1)
    }

    @Test
    fun `rings cover the plan exactly once, in order`() {
        val plan = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 12f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
        )

        assertEquals(0, plan.rings.first().first)
        assertEquals(plan.size - 1, plan.rings.last().last)
        plan.rings.zipWithNext { earlier, later ->
            assertEquals("rings must be contiguous", earlier.last + 1, later.first)
        }
    }

    @Test
    fun `a ring counts as complete only once every target on it is shot`() {
        val plan = SphereTargetPlan.createForFieldOfView(
            startYawDegrees = 0f,
            fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
        )
        val firstRing = plan.rings.first()

        // One short of the ring is still no complete ring.
        assertEquals(0, plan.completedRings((firstRing.first until firstRing.last).toSet()))
        assertEquals(1, plan.completedRings(firstRing.toSet()))

        // Order does not matter: a ring shot back to front is just as closed,
        // and a ring missing its *first* target is just as open.
        assertEquals(1, plan.completedRings(firstRing.reversed().toSet()))
        assertEquals(0, plan.completedRings((firstRing.first + 1..firstRing.last).toSet()))

        // Nothing captured is no rings, whatever the plan looks like.
        assertEquals(0, plan.completedRings(emptySet()))
        assertEquals(plan.ringCount, plan.completedRings(plan.targets.indices.toSet()))
    }

    @Test
    fun `adaptive rings go horizon, up, then down`() {
        val elevations = SphereTargetPlan.adaptiveRingElevations(59f, 0.35f)
        assertEquals(0f, elevations.first())
        val firstDown = elevations.indexOfFirst { it < 0f }
        assertTrue(elevations.drop(1).take(firstDown - 1).all { it > 0f })
        assertTrue(elevations.drop(firstDown).all { it < 0f })
    }

    /** Great-circle angle between two targets, for checking coverage. */
    private fun angularDistance(a: SphereTarget, b: SphereTarget): Float =
        SphereProjection.angularDistanceDegrees(
            OrientationData(yawDegrees = a.yawDegrees, pitchDegrees = a.pitchDegrees),
            b,
        )
}
