package com.n30dyn4m1c.photosphere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission gate's verdict state machine, extracted from the launcher
 * callback so the failure that once shipped — a cancelled dialog reading as a
 * grant — stays pinned.
 */
class PermissionGateTest {

    @Test
    fun `an empty result map with nothing granted is a cancellation, not a grant`() {
        // The regression: the request contract delivers an empty map when the
        // dialog is dismissed without an answer, and emptyMap().values.all { }
        // is vacuously true — reading the map directly counted the cancellation
        // as a grant and dropped the user into a camera screen with no camera.
        assertEquals(
            PermissionStatus.ShowRationale,
            resolvePermissionStatus(
                results = emptyMap(),
                hasAll = false,
                anyShouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `the grants are read from the system, so a cancelled dialog still grants when the permission is held`() {
        // Even with an empty result map, the gate must not re-ask (or worse,
        // downgrade) a user who already holds every permission.
        assertEquals(
            PermissionStatus.Granted,
            resolvePermissionStatus(
                results = emptyMap(),
                hasAll = true,
                anyShouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `an explicit denial the system would re-ask for shows the rationale`() {
        assertEquals(
            PermissionStatus.ShowRationale,
            resolvePermissionStatus(
                results = mapOf(android.Manifest.permission.CAMERA to false),
                hasAll = false,
                anyShouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `a partial grant that is not enough still lands on the rationale`() {
        assertEquals(
            PermissionStatus.ShowRationale,
            resolvePermissionStatus(
                results = mapOf(android.Manifest.permission.CAMERA to true),
                hasAll = false,
                anyShouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `a denial with do-not-ask-again is permanent and needs settings`() {
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            resolvePermissionStatus(
                results = mapOf(android.Manifest.permission.CAMERA to false),
                hasAll = false,
                anyShouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `a full grant of every requested permission is a grant`() {
        assertEquals(
            PermissionStatus.Granted,
            resolvePermissionStatus(
                results = mapOf(
                    android.Manifest.permission.CAMERA to true,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE to true,
                ),
                hasAll = true,
                anyShouldShowRationale = false,
            ),
        )
    }
}
