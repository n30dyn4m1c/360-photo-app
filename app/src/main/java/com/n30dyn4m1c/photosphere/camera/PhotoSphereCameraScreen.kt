@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@file:SuppressLint("UnsafeOptInUsageError")

package com.n30dyn4m1c.photosphere.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n30dyn4m1c.photosphere.BuildConfig
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.currentDisplayRotation
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationTracker
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchException
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
import com.n30dyn4m1c.photosphere.stitching.StitchStage
import com.n30dyn4m1c.photosphere.stitching.StitchStatus
import com.n30dyn4m1c.photosphere.storage.ImageBufferManager
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.storage.rememberImageBufferManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val TAG = "PhotoSphereCamera"

/**
 * How much taller a ring capture's canvas band is than the lens's vertical
 * field of view.
 *
 * A ring is shot with the camera level, so each frame reaches about half a
 * field of view above and below the horizon when held upright. Rolling the
 * phone in its own plane can reach further — the corners sweep out the frame's
 * half-diagonal, which for a tall portrait frame is noticeably wider than the
 * vertical half-angle — so the band is widened to comfortably cover that plus a
 * degree or two of aim error from the gate and any pose-refinement movement.
 */
private const val RING_LATITUDE_SPAN_FACTOR = 1.3f

/**
 * Guided capture: viewfinder, alignment overlay, and an automatic shutter.
 *
 * The screen owns the loop that turns device attitude into frames. A
 * [SphereTargetPlan] is laid out around whichever bearing the user is facing
 * when the first sensor fix lands; [TargetOverlay] projects it onto the preview;
 * and [AlignmentGate] fires [ImageCapture] once the aim has held on the active
 * target long enough to be deliberate. Frames land in cache as full-resolution
 * JPEGs — they are the stitcher's input, not photos the user asked to keep, so
 * they stay out of the gallery until there is a sphere to save.
 *
 * Stitching ends the screen's involvement: the finished sphere goes to
 * [onSphereReady] as a cached, GPano-tagged JPEG, and what happens to it —
 * gallery, share sheet, or nothing — is the result screen's business.
 *
 * The caller is responsible for the CAMERA permission; see
 * `MainActivity.RequirePermissions`.
 *
 * @param onSphereReady called on the main thread with a finished sphere, once
 *   the frames it was built from have been cleared
 */
@Composable
fun PhotoSphereCameraScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Guided capture can take minutes of pointing the phone around the scene;
    // the system screen timeout must not cut in partway through.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        rootView.keepScreenOn = true
        onDispose {
            rootView.keepScreenOn = false
        }
    }

    val tracker = rememberOrientationTracker()
    // Held as a State rather than read with `by`: touching `.value` here would
    // recompose the whole screen at the sensor's rate. Only the overlay's draw
    // lambda reads it.
    val orientationState = tracker.orientation.collectAsStateWithLifecycle()
    val feedback = rememberCaptureFeedback()
    val deviceProfile = remember { SphereDeviceProfile.forDevice() }

    // The capture loop's decision-making, and the single owner of where the run
    // has got to. Capture, undo and restart all move that position, and they are
    // not naturally exclusive — a capture suspends for hundreds of milliseconds
    // while its frame is written — so they are serialised in there rather than
    // raced against each other from here.
    val coordinator = remember { SphereCaptureCoordinator() }
    val guidance by coordinator.guidance.collectAsStateWithLifecycle()

    // Which lens CameraX actually bound, and the shape of the buffer it is
    // producing. Both are only knowable once the bind has resolved, and both
    // scale everything downstream — the spacing of the plan, the rectangles on
    // the overlay, the angle each stitched frame is taken to cover — so the
    // optics are re-read from them rather than from a guess at what would bind.
    var boundCameraId by remember { mutableStateOf<String?>(null) }
    var streamAspectRatio by remember { mutableFloatStateOf(0f) }
    val optics = rememberSphereOptics(boundCameraId, streamAspectRatio)
    val fieldOfView = optics.fieldOfView

    // How much of the sphere the plan walks: the whole sphere (rings out to
    // both poles) or just the horizon ring — a regular pano that goes all the
    // way around. Locked once the first frame lands: re-planning under buffered
    // frames would renumber the targets they were shot against.
    var captureScope by rememberSaveable { mutableStateOf(SphereCaptureScope.Sphere) }

    // How tall the output canvas is, as a span of latitude. A sphere covers the
    // poles (180°); a ring covers only the band of latitude its level frames
    // reach, widened past the vertical field of view so tilt or refinement can
    // not push content off the canvas.
    val latitudeSpanDegrees = when (captureScope) {
        SphereCaptureScope.Sphere -> 180f
        SphereCaptureScope.Ring -> fieldOfView.verticalDegrees * RING_LATITUDE_SPAN_FACTOR
    }

    val currentFieldOfView by rememberUpdatedState(fieldOfView)

    // Where focus is aimed and whether the lens has locked there. Set by the
    // initial centre lock once the camera binds, then re-aimed by every tap on
    // the viewfinder.
    var focusReticle by remember { mutableStateOf<FocusReticle?>(null) }

    val previewView = remember(context) {
        PreviewView(context).apply {
            // The projection maths assumes a uniform fill: see
            // SphereProjection.focalLengthPx.
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val buffer = rememberImageBufferManager()
    val bufferedFrames by buffer.frames.collectAsStateWithLifecycle()
    val sessionId by buffer.sessionId.collectAsStateWithLifecycle()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    // The locked capture settings this device settled on, and the Camera the
    // locks were bound with. Read by the capture loop, and by the HUD to say
    // what the session is holding.
    var captureProfile by remember { mutableStateOf<SphereCaptureProfile?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var isHolding by remember { mutableStateOf(false) }
    var accuracy by remember { mutableStateOf(OrientationAccuracy.Unknown) }

    // Why the camera did not start, and the counter that asks it to try again.
    // A bind can fail for reasons that pass — another app holding the camera,
    // a provider that was not ready — so the failure is a state the user can
    // act on rather than a dead screen.
    var cameraError by remember { mutableStateOf<String?>(null) }
    var bindAttempt by remember { mutableIntStateOf(0) }

    // Lens model defaults. The sensor poses + pinhole combination is the
    // reliable core of the stitch; pose refinement can move frames off the
    // sensor's accurate answer when the scene has parallax (a body-swivel
    // capture of a close scene breaks the pure-rotation feature model), so it
    // starts OFF. The debug toggles let the two optional stages be switched
    // back on to compare.
    var distortionEnabled by remember { mutableStateOf(false) }
    var refinementEnabled by remember { mutableStateOf(false) }
    // Debug: paint each frame a solid colour so the pano shows where each one
    // was placed — distinguishes a placement bug from a content bug.
    var colorFrames by remember { mutableStateOf(false) }

    /**
     * Runs a focus sweep at a normalised point in the preview and locks the
     * lens there once it converges.
     *
     * This is tap-to-focus: AE and AWB are already locked by the session, so a
     * tap only moves focus. In `AF_MODE_AUTO` the lens holds the converged
     * distance until the next trigger, which is what keeps every frame of the
     * sphere on one focal plane — and what keeps the shots sharp on the actual
     * scene rather than parked at infinity.
     */
    fun lockFocusAt(nx: Float, ny: Float) {
        val camera = boundCamera
        if (camera == null) return
        focusReticle = FocusReticle(x = nx, y = ny, isWorking = true, isLocked = false)
        scope.launch {
            val ran = runCatching { camera.focusAt(nx, ny, previewView) }.getOrDefault(false)
            focusReticle = FocusReticle(x = nx, y = ny, isWorking = false, isLocked = ran)
        }
    }

    // A one-time reminder of how to stand, shown before the first frame falls
    // so the capture that follows is built on a steady pivot.
    var showInstructions by rememberSaveable { mutableStateOf(true) }

    // The stitch runs off the main thread and reports back from there, so its
    // progress travels as a StateFlow rather than as Compose state written from
    // a background dispatcher.
    val stitchProgress = remember { MutableStateFlow(StitchProgress.Preparing) }
    var stitchJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Changes at the sensor's rate and is therefore never read during
     * composition — only inside [TargetOverlay]'s draw lambda.
     */
    var alignment by remember { mutableStateOf(AlignmentState()) }

    /** Clears the guidance state so the next sphere starts from scratch. */
    suspend fun resetGuidance() {
        coordinator.reset()
        isHolding = false
        alignment = AlignmentState()
    }

    // Bind preview + capture once per lifecycle owner. CameraX unbinds on its own
    // when that lifecycle is destroyed. Re-keyed on [bindAttempt] so a failed
    // bind can be retried without leaving and re-entering the screen.
    LaunchedEffect(lifecycleOwner, previewView, bindAttempt) {
        cameraError = null
        val cameraProvider = try {
            context.awaitCameraProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Camera provider unavailable", e)
            cameraError = context.getString(R.string.capture_failed, e.message.orEmpty())
            return@LaunchedEffect
        }

        val profile = resolveSphereCaptureProfile(context, deviceProfile)
        captureProfile = profile

        // Which lens the sphere is shot on, per the device profile. Speed-first
        // profiles take the widest back lens, because a sphere is captured
        // faster the more each frame covers; sharpness-first profiles (the S23
        // among them) take the default back camera, which Android requires to
        // be the primary rear one — the main lens, not the ultrawide. Either
        // way the optics are re-read afterwards from whatever actually bound.
        val cameraSelector = if (deviceProfile.preferWidestCamera) {
            widestCameraSelector(context) ?: CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Exposure, white balance and focus are locked for the whole session
        // (see SphereCaptureProfile): a sphere's seams are exposure and colour
        // seams, so no frame is allowed to re-meter on its own. The locks ride
        // in the preview's repeating request as well as the stills', so the
        // viewfinder shows exactly what each frame will record.
        val preview = Preview.Builder()
            // Pinned to the stills' shape. Left to itself CameraX picks a
            // preview close to the display's aspect ratio — 16:9 or taller on a
            // modern phone — which off a 4:3 sensor is a *crop*, not a squeeze:
            // it throws away a quarter of one axis. The overlay would then be
            // drawing the field of view of a frame the viewfinder never shows,
            // and the markers would sit where the capture reaches rather than
            // where the user can see. One shape for both keeps a single field of
            // view honest about the preview and the stills at once.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .also { applySphereCaptureOptions(Camera2Interop.Extender(it), profile) }
            .build()
            .apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Frames are stitched, so a flash firing on some of them would leave
            // seams no amount of blending can hide.
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            // Capture is capped to what the stitch can use: it reads ~1024 px
            // per frame, so a 50 MP still (the S23's main sensor at full size)
            // is pure waste — it is slower to write, slows the per-target burst,
            // and heats the phone up for nothing. 12 MP 4:3 is several times
            // more resolution than the stitch reads.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(
                                deviceProfile.captureMaxLongEdgePx,
                                deviceProfile.captureMaxLongEdgePx * 3 / 4,
                            ),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setTargetRotation(context.currentDisplayRotation())
            .also { applySphereCaptureOptions(Camera2Interop.Extender(it), profile) }
            .also { applyStillImageOptions(Camera2Interop.Extender(it), profile) }
            .build()

        try {
            cameraProvider.unbindAll()
            val camera = try {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
            } catch (e: Exception) {
                Log.w(TAG, "Widest camera unavailable, binding the default", e)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
            }
            imageCapture = capture
            boundCamera = camera
            // The lens that answered, not the one that was asked for: a filter
            // that matched nothing, or a device that substitutes a logical
            // camera, both land here and both would otherwise leave the optics
            // describing a lens the session is not looking through.
            boundCameraId = runCatching {
                Camera2CameraInfo.from(camera.cameraInfo).cameraId
            }.getOrNull()
            streamAspectRatio = capture.resolutionInfo
                ?.resolution
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width.toFloat() / it.height }
                ?: 0f
            Log.i(TAG, "Bound camera $boundCameraId, stills $streamAspectRatio:1")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            // Nothing is bound, so there is no viewfinder to put a transient
            // snackbar over — the failure stays on screen with a way out of it.
            imageCapture = null
            boundCamera = null
            cameraError = context.getString(R.string.capture_failed, e.message.orEmpty())
        }
    }

    // CameraX releases the camera when the *lifecycle* is destroyed, which for a
    // single-activity app is not when this screen goes away. Leaving the session
    // bound behind the result screen keeps the sensor streaming and the preview
    // converging — measurable battery and heat for a viewfinder nobody is
    // looking at — so the bind is undone explicitly on the way out.
    DisposableEffect(lifecycleOwner) {
        onDispose {
            imageCapture = null
            boundCamera = null
            // Only if the provider has already resolved: this runs on the main
            // thread, and a future that is still pending has nothing bound to
            // release anyway.
            val future = ProcessCameraProvider.getInstance(context)
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
                    .onFailure { Log.w(TAG, "Could not release the camera", it) }
            }
        }
    }

    // Old sessions are dead weight once a new one starts; clearing them keeps the
    // cache from growing by a sphere's worth of full-resolution JPEGs per run.
    LaunchedEffect(sessionId) {
        buffer.pruneStaleSessions()
    }

    // The capture loop's camera half. The rule that decides whether the shutter
    // fires lives in [SphereCaptureCoordinator]; what is left here is the part
    // that needs an ImageCapture behind it.
    LaunchedEffect(imageCapture) {
        val capture = imageCapture ?: return@LaunchedEffect
        val profile = captureProfile ?: return@LaunchedEffect

        // Until the user taps, focus holds the centre of the first scene the
        // lens saw. AE and AWB are already locked by the session, so this pins
        // the last free variable before any frame is taken; the trigger lands
        // before the first dwell can complete, so it cannot race a shutter. On
        // a fixed-focus lens there is nothing to aim, and tapping is a no-op.
        if (profile.focusMode == FocusMode.FOCUS_POINT) {
            lockFocusAt(0.5f, 0.5f)
        }

        // Collecting suspends across the shutter, which is what keeps a second
        // capture from starting while one is still being written — the
        // StateFlow simply conflates the samples that arrive meanwhile.
        tracker.orientation.collect { orientation ->
            accuracy = orientation.accuracy

            val decision = coordinator.onSample(
                orientation = orientation,
                nowMillis = SystemClock.elapsedRealtime(),
                // A frame landing halfway through a stitch would not be in the
                // set being stitched, and would be deleted when that set is
                // cleared.
                isStitching = stitchJob != null,
                createPlan = { startYawDegrees ->
                    SphereTargetPlan.createForFieldOfView(
                        startYawDegrees = startYawDegrees,
                        fieldOfView = currentFieldOfView,
                        scope = captureScope,
                    )
                },
            )

            when (decision) {
                CaptureDecision.Ignore -> Unit

                is CaptureDecision.Guide -> {
                    alignment = decision.alignment
                    isHolding = decision.isHolding
                }

                is CaptureDecision.Capture -> {
                    alignment = decision.alignment
                    // A trigger only ever comes out of an aligned reading.
                    isHolding = true

                    val result = capture.saveFrame(
                        context = context,
                        buffer = buffer,
                        index = decision.index,
                        orientation = decision.pose,
                        burstPerTarget = deviceProfile.burstPerTarget,
                    )
                    alignment = alignment.copy(isCapturing = false)

                    result
                        .onSuccess {
                            // The token is what makes this safe: a run restarted
                            // or stepped back while the frame was being written
                            // refuses the advance, so the reticle never lands
                            // past a target nothing was shot at. The tick is
                            // confirmation the frame counted, so it follows the
                            // same answer.
                            if (coordinator.onCaptured(decision.token)) {
                                feedback.onFrameCaptured()
                            }
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Frame ${decision.index} failed", error)
                            // Stays on the same target, so a failed capture is
                            // retried rather than silently skipped.
                            coordinator.onCaptureFailed(decision.token)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.capture_failed,
                                        error.message.orEmpty(),
                                    )
                                )
                            }
                        }
                }
            }
        }
    }

    // A plan is laid out from the optics, so a change has to throw it away and
    // start again. Only while the session is still empty: re-planning around
    // captured frames would renumber the targets they were shot against. The
    // optics settle as the camera binds, before the first frame in practice.
    // The scope changes do the same thing — and the toggle is disabled once a
    // frame exists, so this is really just the initial layout.
    LaunchedEffect(fieldOfView, captureScope) {
        if (buffer.frames.value.isEmpty()) {
            resetGuidance()
        }
    }

    /**
     * Hands the buffered frames to the stitcher and passes on what comes back.
     *
     * Started lazily so [stitchJob] is set before the body can reach its own
     * `finally` and clear it.
     */
    fun startStitch() {
        if (stitchJob != null) return
        // The stitcher works from where each frame was shot, not just its
        // pixels: the capture attitude is what places it on the sphere.
        val frames = buffer.frames.value.map { frame ->
            SphereFrame(
                file = frame.file,
                pose = CameraPose(
                    yawDegrees = frame.yawDegrees,
                    pitchDegrees = frame.pitchDegrees,
                    rollDegrees = frame.rollDegrees,
                ),
            )
        }
        stitchProgress.value = StitchProgress.Preparing

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                PhotoSphereStitcher.stitchPhotos(
                    frames = frames,
                    horizontalFovDegrees = fieldOfView.horizontalDegrees,
                    verticalFovDegrees = fieldOfView.verticalDegrees,
                    radialDistortion = if (distortionEnabled) optics.radialDistortion else null,
                    maxInputDimension = deviceProfile.stitchMaxInputDimension,
                    maxOutputWidth = deviceProfile.stitchMaxOutputWidth,
                    unsharpAmount = deviceProfile.unsharpAmount,
                    // Guided capture is shot standing up and turning on the
                    // spot, so the lens swings on the end of an arm rather than
                    // sitting on the axis. See PivotModel.
                    pivot = deviceProfile.pivot,
                    // The turn CameraX records in each still's EXIF; used to put
                    // a frame back upright when that tag was lost.
                    portraitRotationDegrees = optics.portraitRotationDegrees,
                    useRefinement = refinementEnabled,
                    debugColorFrames = colorFrames,
                    // The canvas region: a full 360° of longitude either way,
                    // and either the whole 180° of latitude (sphere) or the band
                    // a level ring actually covers.
                    longitudeSpanDegrees = 360f,
                    centerLongitudeDegrees = 0f,
                    latitudeSpanDegrees = latitudeSpanDegrees,
                    centerLatitudeDegrees = 0f,
                ) { stitchProgress.value = it }
                    .onSuccess { sphere ->
                        val stitched = try {
                            withContext(Dispatchers.IO) {
                                SphereImageStore.writeStitchedSphere(
                                    context = context,
                                    bitmap = sphere,
                                    gpano = GPanoMetadata.forSphereRegion(
                                        imageWidth = sphere.width,
                                        imageHeight = sphere.height,
                                        longitudeSpanDegrees = 360f,
                                        centerLongitudeDegrees = 0f,
                                        latitudeSpanDegrees = latitudeSpanDegrees,
                                        centerLatitudeDegrees = 0f,
                                    ),
                                )
                                    .copy(
                                        diagnostics = stitchDiagnostics(
                                            frames,
                                            fieldOfView,
                                            optics,
                                            distortionEnabled,
                                            refinementEnabled,
                                        )
                                    )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A full sphere that will not fit on disk. The
                            // frames survive, so a retry after clearing space
                            // costs nothing more than the stitch.
                            Log.e(TAG, "Could not write the stitched sphere", e)
                            null
                        } finally {
                            sphere.recycle()
                        }

                        if (stitched == null) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.stitch_failed,
                                    context.getString(R.string.stitch_error_write_failed),
                                )
                            )
                            return@onSuccess
                        }

                        // The frames have done their job. Clearing before the
                        // handover matters: this coroutine is cancelled the
                        // moment the result screen replaces this one.
                        buffer.clear()
                        resetGuidance()
                        onSphereReady(stitched)
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Stitch failed", error)
                        // Frames are deliberately kept: the usual fix is to
                        // capture a few more and try again.
                        snackbarHostState.showSnackbar(context.stitchFailureMessage(error))
                    }
            } finally {
                stitchJob = null
            }
        }
        stitchJob = job
        job.start()
    }

    val stitchState by stitchProgress.collectAsStateWithLifecycle()
    if (stitchJob != null) {
        StitchingDialog(
            progress = stitchState,
            onCancel = { stitchJob?.cancel() },
        )
    }

    // What the focus badge says. FOCUS_POINT starts as an invitation to tap and
    // settles on "locked" once the first sweep has converged.
    val lockBadge: String? = when (captureProfile?.focusMode) {
        FocusMode.FIXED_FOCUS -> stringResource(R.string.capture_lock_fixed)
        FocusMode.FOCUS_POINT -> when {
            focusReticle == null -> stringResource(R.string.capture_lock_hint)
            focusReticle?.isWorking == true -> stringResource(R.string.capture_lock_focusing)
            else -> stringResource(R.string.capture_lock_locked)
        }
        null -> null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Tap-to-focus: a tap anywhere on the viewfinder aims and locks
                // the lens on that part of the scene. The buttons and the
                // instructions card sit on top and consume their own taps.
                .pointerInput(captureProfile) {
                    detectTapGestures { offset ->
                        if (captureProfile?.focusMode == FocusMode.FOCUS_POINT) {
                            lockFocusAt(
                                (offset.x / size.width).coerceIn(0f, 1f),
                                (offset.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                    }
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )

            TargetOverlay(
                orientation = { orientationState.value },
                alignment = { alignment },
                plan = guidance.plan,
                activeIndex = guidance.activeIndex,
                fieldOfView = fieldOfView,
            )

            FocusReticleOverlay(
                focus = focusReticle,
                modifier = Modifier.fillMaxSize(),
            )

            if (showInstructions && guidance.activeIndex == 0 && bufferedFrames.isEmpty()) {
                CaptureInstructions(
                    onDismiss = { showInstructions = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            CaptureHud(
                capturedCount = guidance.capturedTargets,
                totalTargets = guidance.totalTargets,
                completedRings = guidance.completedRings,
                ringCount = guidance.ringCount,
                hint = captureHint(
                    isSensorAvailable = tracker.isSensorAvailable,
                    isCameraReady = imageCapture != null,
                    hasPlan = guidance.plan != null,
                    isComplete = guidance.isComplete,
                    isHolding = isHolding,
                    accuracy = accuracy,
                    completedRings = guidance.completedRings,
                    ringCount = guidance.ringCount,
                ),
                lockBadge = lockBadge,
                isComplete = guidance.isComplete,
                captureScope = captureScope,
                // A mode change would renumber the targets already-shot frames
                // were aimed at, so the choice is made before the first frame.
                canChangeScope = bufferedFrames.isEmpty() && stitchJob == null,
                onScopeChange = { captureScope = it },
                // Three overlapping frames are already a panorama. Whether one
                // is worth keeping is the user's call, made on the result
                // screen; the button's job is not to stand between them and it.
                canStitch = bufferedFrames.size >= PhotoSphereStitcher.MIN_FRAMES &&
                    stitchJob == null,
                onFinish = { startStitch() },
                onRestart = {
                    scope.launch {
                        resetGuidance()
                        buffer.cancelSession()
                    }
                },
                // A blurred frame, or one shot while something moved through the
                // scene: undoing pops it off the buffer and puts its target back
                // on the reticle so it can simply be shot again.
                canUndo = bufferedFrames.isNotEmpty() && stitchJob == null,
                // Inert while a shutter is in flight: that frame is not in the
                // buffer yet, so an undo would drop the one before it and then
                // be overwritten when the capture advances the run. The
                // coordinator refuses it regardless — dimming is what makes the
                // refusal visible rather than a tap that does nothing. It stays
                // on screen rather than disappearing, because a control that
                // vanished on every one of thirty-odd captures would read as a
                // glitch.
                undoEnabled = !guidance.isCapturing,
                onUndo = {
                    scope.launch {
                        val undone = coordinator.undo { buffer.undoLastFrame()?.index }
                        if (undone) {
                            isHolding = false
                            alignment = AlignmentState()
                        }
                    }
                },
                // Debug A/B for the lens model; hidden in release builds.
                distortionEnabled = distortionEnabled,
                onToggleDistortion = { distortionEnabled = !distortionEnabled },
                refinementEnabled = refinementEnabled,
                onToggleRefinement = { refinementEnabled = !refinementEnabled },
                colorFrames = colorFrames,
                onToggleColorFrames = { colorFrames = !colorFrames },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            )

            // Nothing bound: no preview under it and no targets to guide toward,
            // so this covers the screen rather than sitting alongside a HUD that
            // has nothing to report.
            cameraError?.let { message ->
                CameraErrorCard(
                    message = message,
                    onRetry = { bindAttempt++ },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets),
                )
            }
        }
    }
}

/**
 * What the screen shows when the camera will not start.
 *
 * A bind fails for reasons that pass — another app holding the camera, a
 * provider that had not finished starting, a lens the device withdrew — so the
 * failure is worth stating and worth offering a second go at. Without this the
 * screen keeps a live-looking viewfinder over a camera that never bound, and
 * the only way out is to kill the app.
 */
@Composable
private fun CameraErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 28.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF11161A),
            tonalElevation = 6.dp,
            shadowElevation = 20.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.capture_camera_unavailable),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CaptureAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_camera_retry),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * The capture HUD: a glass progress pill up top, the focus lock chip, the one
 * line of guidance the user needs, and the action that finishes a run.
 *
 * Designed to sit over a live viewfinder, so every surface is translucent dark
 * glass with high-contrast text, and the chrome is limited to what the
 * workflow needs: aim, hold, shoot, and stop once the area is covered.
 */
@Composable
private fun CaptureHud(
    capturedCount: Int,
    totalTargets: Int,
    completedRings: Int,
    ringCount: Int,
    hint: String,
    lockBadge: String?,
    isComplete: Boolean,
    canStitch: Boolean,
    onFinish: () -> Unit,
    onRestart: () -> Unit,
    canUndo: Boolean,
    undoEnabled: Boolean,
    onUndo: () -> Unit,
    captureScope: SphereCaptureScope,
    canChangeScope: Boolean,
    onScopeChange: (SphereCaptureScope) -> Unit,
    distortionEnabled: Boolean,
    onToggleDistortion: () -> Unit,
    refinementEnabled: Boolean,
    onToggleRefinement: () -> Unit,
    colorFrames: Boolean,
    onToggleColorFrames: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // A soft scrim keeps the top chrome readable over a bright sky.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    )
                ),
        )

        // The StreetView-style undo: drop the last shot and put its target back
        // on the reticle. Sits top-left, clear of the centred progress pill.
        if (canUndo) {
            UndoButton(
                onUndo = onUndo,
                enabled = undoEnabled,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 22.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CaptureScopeSelector(
                scope = captureScope,
                enabled = canChangeScope,
                onScopeChange = onScopeChange,
            )
            CaptureProgressPill(
                capturedCount = capturedCount,
                totalTargets = totalTargets,
                completedRings = completedRings,
                ringCount = ringCount,
            )
            if (lockBadge != null) {
                FocusLockChip(text = lockBadge)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HintCard(text = hint)
            // Debug-only A/B control for the lens model: a device that reports
            // LENS_DISTORTION in the wrong convention warps every frame, and
            // re-stitching the same frames as a pinhole isolates it.
            if (BuildConfig.DEBUG && canStitch) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onToggleDistortion) {
                        Text(
                            text = "Dist: ${if (distortionEnabled) "on" else "off"}",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onToggleRefinement) {
                        Text(
                            text = "Refine: ${if (refinementEnabled) "on" else "off"}",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onToggleColorFrames) {
                        Text(
                            text = "Color: ${if (colorFrames) "on" else "off"}",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            // Offered as soon as there is enough to stitch, not only at the
            // end: a user who has covered what they care about should not have
            // to walk the remaining targets to get a sphere out of it.
            if (canStitch) {
                Button(
                    onClick = onFinish,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CaptureAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_finish_stitch),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (isComplete) {
                TextButton(onClick = onRestart) {
                    Text(
                        text = stringResource(R.string.capture_restart),
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

/**
 * The choice of how much of the sphere the run walks: the whole thing, or just
 * the horizon ring. A small two-way pill above the progress readout; dimmed and
 * inert once a frame has been captured, because re-planning under buffered
 * frames would renumber the targets they were shot against.
 */
@Composable
private fun CaptureScopeSelector(
    scope: SphereCaptureScope,
    enabled: Boolean,
    onScopeChange: (SphereCaptureScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SphereCaptureScope.entries.forEach { option ->
            val selected = option == scope
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) CaptureAccent else Color.Black.copy(alpha = 0.4f),
                onClick = { if (enabled) onScopeChange(option) },
            ) {
                Text(
                    text = when (option) {
                        SphereCaptureScope.Sphere -> stringResource(R.string.capture_scope_sphere)
                        SphereCaptureScope.Ring -> stringResource(R.string.capture_scope_ring)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) {
                        Color.Black
                    } else {
                        Color.White.copy(alpha = if (enabled) 0.9f else 0.45f)
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The translucent pill that reports how much of the sphere is covered.
 *
 * [ringCount] is shown only when the plan has more than one band, so a
 * single-band run reads as a plain frame count rather than "band 0 of 1".
 */
@Composable
private fun CaptureProgressPill(
    capturedCount: Int,
    totalTargets: Int,
    completedRings: Int,
    ringCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.5f),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "$capturedCount",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "/ $totalTargets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    text = stringResource(R.string.capture_progress_frames),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            if (totalTargets > 0) {
                LinearProgressIndicator(
                    progress = { capturedCount.toFloat() / totalTargets },
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .fillMaxWidth(0.75f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50)),
                    color = CaptureAccent,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
            if (ringCount > 1) {
                Text(
                    text = stringResource(R.string.capture_rings, completedRings, ringCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The StreetView-style "undo that shot" control: a small glass button that
 * drops the most recently captured frame and puts its target back on the
 * reticle. Only appears once there is something to undo.
 *
 * Dimmed and inert while a shutter is in flight — the frame being written is
 * not in the buffer yet, so there is a moment where "the last shot" is not the
 * one the user means.
 */
@Composable
private fun UndoButton(
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = if (enabled) 0.5f else 0.3f),
        shadowElevation = 8.dp,
    ) {
        IconButton(onClick = onUndo, enabled = enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.capture_undo),
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            )
        }
    }
}

/** The small status chip that says what the lens is doing. */
@Composable
private fun FocusLockChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(CaptureAccent),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** The single line of guidance, in a glass card over the bottom of the frame. */
@Composable
private fun HintCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.5f),
        shadowElevation = 6.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

/** The accent used across the capture chrome: a confident photographic green. */
private val CaptureAccent = Color(0xFF3DDC84)

/**
 * The one-shot welcome shown before the first frame.
 *
 * A dimmed overlay with a floating card, rather than a modal dialog: it sits
 * over the viewfinder without taking focus or pausing the camera, and a single
 * tap anywhere dismisses it for the session. The steps are the whole workflow
 * — stay put, aim and hold, tap to focus, cover the scene — so the user never
 * has to learn a control to shoot a sphere.
 */
@Composable
private fun CaptureInstructions(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, animationSpec = tween(280, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .alpha(appear.value)
            .background(Color.Black.copy(alpha = 0.5f * appear.value))
            // Tapping anywhere dismisses the card and starts capture.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .graphicsLayer {
                    alpha = appear.value
                    scaleX = 0.92f + 0.08f * appear.value
                    scaleY = 0.92f + 0.08f * appear.value
                },
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF11161A),
            tonalElevation = 6.dp,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_instructions_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.capture_instructions_subtitle),
                        style = MaterialTheme.typography.labelLarge,
                        color = CaptureAccent,
                        letterSpacing = 2.sp,
                    )
                }

                listOf(
                    1 to (R.string.capture_step_1 to R.string.capture_instructions_1),
                    2 to (R.string.capture_step_2 to R.string.capture_instructions_2),
                    3 to (R.string.capture_step_3 to R.string.capture_instructions_3),
                    4 to (R.string.capture_step_4 to R.string.capture_instructions_4),
                ).forEach { (number, step) ->
                    val (label, detail) = step
                    InstructionStep(
                        number = number,
                        label = stringResource(label),
                        detail = stringResource(detail),
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CaptureAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capture_instructions_dismiss),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** One numbered step of the welcome card. */
@Composable
private fun InstructionStep(
    number: Int,
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(CaptureAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$number",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CaptureAccent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * Blocks the screen while OpenCV works.
 *
 * Deliberately modal and not dismissable by tapping outside: the stitch owns the
 * frames for its duration, so the capture loop is paused behind it and there is
 * nothing useful to go back to. Cancelling is offered explicitly, and takes
 * effect at the next stage boundary — the native call cannot be interrupted
 * partway.
 */
@Composable
private fun StitchingDialog(
    progress: StitchProgress,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.stitch_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                val fraction = progress.fraction
                if (fraction != null) {
                    CircularProgressIndicator(progress = { fraction })
                } else {
                    // Everything inside OpenCV's stitch call is opaque, so the
                    // spinner spins rather than reporting a made-up percentage.
                    CircularProgressIndicator()
                }

                Text(
                    text = progress.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.stitch_cancel))
                }
            }
        }
    }
}

/** The one line describing what the stitcher is currently doing. */
@Composable
private fun StitchProgress.label(): String = when (stage) {
    StitchStage.Preparing -> stringResource(R.string.stitch_stage_preparing)
    StitchStage.Reading -> stringResource(R.string.stitch_stage_reading, completed, total)
    StitchStage.Refining -> stringResource(R.string.stitch_stage_refining, completed, total)
    StitchStage.Stitching -> stringResource(R.string.stitch_stage_stitching)
    StitchStage.Projecting -> stringResource(R.string.stitch_stage_projecting)
}

/** Turns a failed stitch into something worth showing a user. */
private fun Context.stitchFailureMessage(error: Throwable): String {
    val status = (error as? StitchException)?.status ?: StitchStatus.Unknown
    val reason = when (status) {
        StitchStatus.NeedMoreImages,
        StitchStatus.NoInputImages,
        -> getString(R.string.stitch_error_need_more_images)

        StitchStatus.AlignmentFailed -> getString(R.string.stitch_error_alignment)
        StitchStatus.CameraEstimationFailed -> getString(R.string.stitch_error_camera_estimation)
        StitchStatus.OpenCvUnavailable -> getString(R.string.stitch_error_opencv_unavailable)
        StitchStatus.UnreadableInput -> getString(R.string.stitch_error_unreadable_input)
        StitchStatus.OutOfMemory -> getString(R.string.stitch_error_out_of_memory)
        StitchStatus.EmptyResult,
        StitchStatus.Unknown,
        StitchStatus.Ok,
        -> getString(R.string.stitch_error_unknown, status.code)
    }
    return getString(R.string.stitch_failed, reason)
}

/** Picks the single most useful thing to tell the user right now. */
@Composable
private fun captureHint(
    isSensorAvailable: Boolean,
    isCameraReady: Boolean,
    hasPlan: Boolean,
    isComplete: Boolean,
    isHolding: Boolean,
    accuracy: OrientationAccuracy,
    completedRings: Int,
    ringCount: Int,
): String = when {
    !isSensorAvailable -> stringResource(R.string.capture_orientation_unavailable)
    !isCameraReady -> stringResource(R.string.capture_starting)
    isComplete -> stringResource(R.string.capture_sphere_complete)
    !hasPlan -> stringResource(R.string.capture_waiting_for_orientation)
    // The shutter is held only at Unreliable, so that case says so plainly.
    // Low still shoots, but the compass is worth calibrating, so it is offered
    // as advice rather than as an explanation for a stalled capture.
    !accuracy.allowsCapture -> stringResource(R.string.capture_accuracy_blocked)
    !accuracy.isUsable && accuracy != OrientationAccuracy.Unknown ->
        stringResource(R.string.capture_low_accuracy)
    isHolding -> stringResource(R.string.capture_hint_hold)
    // A closed ring is a complete band of sphere and a perfectly good result.
    // Saying so is what turns "keep going or lose it" into a real choice.
    completedRings > 0 -> stringResource(R.string.capture_hint_ring_done, completedRings, ringCount)
    else -> stringResource(R.string.capture_hint_search)
}

/**
 * Debug-only summary of what a stitch was built from, for the result screen.
 *
 * The pose column is what decides whether frames land where they were shot:
 * yaw should sweep across a capture, pitch should follow the rings, and roll
 * should stay small when the phone is held level. When a sphere comes out
 * scrambled this is the first place to look.
 *
 * The EXIF column is the other half of the puzzle: the stitch decodes each
 * JPEG upright via its `ORIENTATION` tag, and a frame that lost that tag
 * decodes landscape and lands rotated 90° against its pose.
 */
private fun stitchDiagnostics(
    frames: List<SphereFrame>,
    fieldOfView: FieldOfView,
    optics: SphereOptics,
    distortionApplied: Boolean,
    refinementApplied: Boolean,
): String {
    val poses = frames.joinToString(separator = "\n") { frame ->
        val exif = runCatching {
            ExifInterface(frame.file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        "  #${frames.indexOf(frame)} yaw ${frame.pose.yawDegrees.roundToInt()}° " +
            "pitch ${frame.pose.pitchDegrees.roundToInt()}° roll ${frame.pose.rollDegrees.roundToInt()}°" +
            " exif=$exif"
    }
    val device = listOfNotNull(
        Build.MANUFACTURER,
        Build.MODEL,
    ).joinToString(" ")
    val distortion = optics.radialDistortion
        ?.coefficients
        ?.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }
        ?: "none"
    return "Device: $device\n" +
        "FOV: ${fieldOfView.horizontalDegrees.roundToInt()}° x " +
        "${fieldOfView.verticalDegrees.roundToInt()}° (screen) " +
        "rotation=${optics.portraitRotationDegrees}°\n" +
        "Distortion k: $distortion ${if (distortionApplied) "applied" else "OFF (pinhole)"}\n" +
        "Refinement: ${if (refinementApplied) "on" else "off (sensor poses)"}\n" +
        "Frames (capture order):\n$poses"}

/** Writes one frame to the session's cache directory and buffers it.
 *
 * The device's attitude at the moment of capture goes into the buffer entry and
 * into the file's EXIF: the stitcher gets a free initial guess at where each
 * frame belongs, which is worth far more than the couple of milliseconds it
 * costs here.
 *
 * With [burstPerTarget] > 1 the target is shot [burstPerTarget] times at the
 * same locked settings and the sharpest of the burst is kept. The losers are
 * deleted in [ImageBufferManager.commitBestFrame], so the session still holds
 * one file per index. A frame halfway through its burst that fails leaves the
 * reserved candidates behind; they are deleted here so the session directory
 * stays clean.
 */
private suspend fun ImageCapture.saveFrame(
    context: Context,
    buffer: ImageBufferManager,
    index: Int,
    orientation: OrientationData,
    burstPerTarget: Int,
): Result<File> {
    val requests = try {
        if (burstPerTarget > 1) {
            buffer.reserveBurstFrames(index, burstPerTarget)
        } else {
            listOf(buffer.reserveFrame(index))
        }
    } catch (e: Exception) {
        return Result.failure(e)
    }

    return try {
        requests.forEach { takePictureTo(context, it.outputOptions) }

        val best = if (burstPerTarget > 1) {
            // Scoring decodes each candidate, which is cheap next to the captures
            // that just ran but is still real work — keep it off the main thread.
            withContext(Dispatchers.Default) {
                SharpnessSelection.pickSharpest(requests.map { it.file })
            }
        } else {
            requests.first().file
        }

        buffer.commitBestFrame(best, index, orientation)
        Result.success(best)
    } catch (e: CancellationException) {
        // Leaving the screen mid-shutter is not a capture failure; let the
        // cancellation travel rather than reporting it to the user.
        throw e
    } catch (e: Exception) {
        // Half a burst written, none of it buffered: clear the candidates so the
        // session directory does not accumulate rejected frames.
        withContext(NonCancellable + Dispatchers.IO) {
            requests.forEach { request ->
                if (request.file.exists() && !request.file.delete()) {
                    Log.w(TAG, "Could not delete failed burst file ${request.file.name}")
                }
            }
        }
        Result.failure(e)
    }
}

/**
 * Aims and locks autofocus at a normalised point in the preview, awaiting
 * convergence.
 *
 * This is tap-to-focus: AE and AWB are already held by the session request, so
 * the trigger only moves focus. After the trigger completes in `AF_MODE_AUTO`
 * the lens stays where it is until another trigger comes along — the same lock
 * a regular camera's tap-to-focus leaves behind, and the thing that keeps a
 * sphere's frames on one focal plane.
 *
 * @return false when the preview is not laid out yet and no sweep could run.
 */
private suspend fun Camera.focusAt(
    nx: Float,
    ny: Float,
    previewView: PreviewView,
): Boolean {
    if (previewView.width <= 0 || previewView.height <= 0) return false
    val point = previewView.meteringPointFactory.createPoint(
        previewView.width * nx.coerceIn(0f, 1f),
        previewView.height * ny.coerceIn(0f, 1f),
    )
    val future = cameraControl.startFocusAndMetering(
        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build(),
    )
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                // A failed focus sweep is not worth failing the session over; the
                // lens just stays where auto left it.
                runCatching { future.get() }
                continuation.resume(Unit)
            },
            ContextCompat.getMainExecutor(previewView.context),
        )
    }
    return true
}

/** Suspending [ImageCapture.takePicture]; the callback form fits nothing here. */
private suspend fun ImageCapture.takePictureTo(
    context: Context,
    outputOptions: ImageCapture.OutputFileOptions,
): ImageCapture.OutputFileResults = suspendCancellableCoroutine { continuation ->
    takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                continuation.resume(output)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        },
    )
}

/**
 * A [CameraSelector] for the widest physical back camera, or null when it
 * cannot be identified.
 *
 * CameraX filters the available cameras down to the one with the matching
 * camera2 id, so the preview and stills bind to the same lens
 * [widestBackCameraId] picks for the optics. A filter that matches nothing
 * makes [ProcessCameraProvider.bindToLifecycle] throw, which the caller turns
 * into a fall back to the default back camera.
 */
private fun widestCameraSelector(context: Context): CameraSelector? {
    val id = widestBackCameraId(context) ?: return null
    return CameraSelector.Builder()
        .addCameraFilter { cameras ->
            cameras.filter { camera ->
                runCatching { Camera2CameraInfo.from(camera).cameraId }.getOrNull() == id
            }
        }
        .build()
}

/**
 * Suspending wrapper around [ProcessCameraProvider.getInstance].
 *
 * The provider arrives as a `ListenableFuture`; bridging it here keeps the call
 * site free of callbacks without a coroutines-guava dependency.
 */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
