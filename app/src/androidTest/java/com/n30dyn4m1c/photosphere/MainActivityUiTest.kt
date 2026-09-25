package com.n30dyn4m1c.photosphere

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests for the app shell: the permission gate and the capture
 * screen's camera-independent chrome.
 *
 * The camera itself is deliberately not exercised — the bind needs a real (or
 * emulated) lens and the gate and HUD state machines are the parts unit tests
 * cannot reach. The capture screen's welcome card, scope selector and progress
 * pill render before and regardless of the bind, so they are what is asserted.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun grantedCamera_reachesTheCaptureScreen() {
        // The welcome card is the capture screen's front door and shows before
        // the camera bind resolves (or fails) — the gate already passed.
        composeRule.onNodeWithText("Capture your sphere").assertIsDisplayed()

        // Dismissing the card exposes the capture chrome.
        composeRule.onNodeWithText("Start capturing").performClick()
        composeRule.onNodeWithText("Sphere").assertIsDisplayed()
        composeRule.onNodeWithText("Horizon ring").assertIsDisplayed()
    }

    @Test
    fun deniedCamera_showsTheRationaleGate() {
        // Take the permission away (a previous test may have granted it), then
        // start the activity fresh so the gate re-evaluates.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "pm revoke ${composeRule.activity.packageName} android.permission.CAMERA"
        )
        composeRule.activityRule.scenario.recreate()

        // The gate's first state is the rationale screen; the system's own
        // request dialog may be layered on top of it, but the app's hierarchy
        // still contains the gate's copy of this title.
        composeRule.onNodeWithText("Camera access needed").assertIsDisplayed()
        composeRule.onNodeWithText("Grant access").assertIsDisplayed()
    }
}
