package guru.freberg.lenscast.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoCameraFront
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import android.app.Activity
import android.content.res.Configuration
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.freberg.lenscast.R
import guru.freberg.lenscast.net.NetworkUtils
import guru.freberg.lenscast.prefs.Lens
import guru.freberg.lenscast.prefs.Settings
import guru.freberg.lenscast.prefs.SettingsRepository
import guru.freberg.lenscast.streaming.StreamingService
import guru.freberg.lenscast.streaming.rtsp.RtspCameraDriver
import guru.freberg.lenscast.ui.components.ConnectionInfoCard
import guru.freberg.lenscast.ui.components.LiveStatusPill
import guru.freberg.lenscast.ui.components.PermissionRequestRow
import guru.freberg.lenscast.ui.components.PreviewSurface
import guru.freberg.lenscast.ui.components.StatChip
import guru.freberg.lenscast.ui.components.VuMeter
import guru.freberg.lenscast.ui.components.rememberPermissionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Snapshot of everything the camera-overlay subtree needs. Wrapping it in a holder lets
 * us pass a single `State<CamBoxState>` reference into a [movableContentOf] block so
 * Compose can move the subtree (and its AndroidView) without the closure freezing stale
 * values.
 */
private data class CamBoxState(
    val service: StreamingService?,
    val lens: guru.freberg.lenscast.prefs.Lens,
    val protocol: guru.freberg.lenscast.prefs.Protocol,
    val streaming: Boolean,
    val plannedSize: android.util.Size?,
    val torchOn: Boolean,
    val blankPreview: Boolean,
    val onSwitchCamera: () -> Unit,
    val onToggleTorch: () -> Unit,
    val onSnapshot: () -> Unit,
    val onSnapshotBurst: () -> Unit,
)

@Composable
fun MainScreen(
    service: StreamingService?,
    startForeground: () -> Unit,
    inPictureInPicture: Boolean = false,
) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx) }
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val health by remember { guru.freberg.lenscast.system.HealthMonitor.observe(ctx) }
        .collectAsStateWithLifecycle(initialValue = guru.freberg.lenscast.system.HealthMonitor.State(
            guru.freberg.lenscast.system.HealthMonitor.Severity.OK, null,
        ))

    // Live service state (default IDLE when service not bound yet).
    val statusFlow = service?.status ?: remember { MutableStateFlow(StreamingService.Status()) }
    val status by statusFlow.collectAsStateWithLifecycle()
    // Treat the "restarting" window (rotate-phone-triggered stop+start) as still-streaming
    // so the CameraX rebind effect below doesn't fire and race Camera2's teardown.
    val restartingFlow = service?.restarting ?: remember { MutableStateFlow(false) }
    val restarting by restartingFlow.collectAsStateWithLifecycle()
    val streaming = restarting ||
        status.state == StreamingService.State.STREAMING ||
        status.state == StreamingService.State.STARTING

    // Surface startStreaming failures we couldn't pre-validate (camera busy, RTSP port in use,
    // USB unplug, etc.). The Settings sheet hides options the planner already knows can't work,
    // but not every failure is predictable from CameraCharacteristics alone.
    LaunchedEffect(status.state, status.errorMessage) {
        val msg = status.errorMessage
        if (status.state == StreamingService.State.ERROR && msg != null) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            service?.clearError()
        }
    }

    // One-shot transient messages from the service (e.g. "RTSP orientation locked").
    LaunchedEffect(service) {
        service?.messages?.collect { msg ->
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // When a recording stop transitions IDLE, surface the saved-file Toast.
    var prevState by remember { mutableStateOf(status.state) }
    LaunchedEffect(status.state) {
        if (prevState == StreamingService.State.STREAMING && status.state == StreamingService.State.IDLE) {
            val uri = service?.lastRecordingUri()
            if (uri != null) {
                android.widget.Toast.makeText(
                    ctx, ctx.getString(R.string.recording_saved), android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        prevState = status.state
    }

    // Network info, refreshed every couple of seconds while screen is open.
    var localIp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            localIp = NetworkUtils.getLocalIpv4(ctx)
            delay(3000)
        }
    }

    // Live frame count → derive FPS for the stat chip. Reads from whichever counter is
    // active for the current protocol (MJPEG broadcaster vs RTSP encoder). Same loop
    // also polls the client count so it updates without depending on other state changes.
    var fpsObserved by remember { mutableStateOf(0) }
    var clientsObserved by remember { mutableStateOf(0) }
    var audioPeak by remember { mutableStateOf(-90f) }
    var txKbps by remember { mutableStateOf(0) }
    LaunchedEffect(streaming, service) {
        if (!streaming || service == null) {
            fpsObserved = 0
            clientsObserved = 0
            audioPeak = -90f
            txKbps = 0
            return@LaunchedEffect
        }
        var lastCount = service.framesProducedNow()
        var lastBytes = service.bytesSentNow()
        var lastT = System.currentTimeMillis()
        clientsObserved = service.connectedClientCount()
        while (true) {
            delay(250)
            val now = System.currentTimeMillis()
            val count = service.framesProducedNow()
            val bytes = service.bytesSentNow()
            val dt = (now - lastT).coerceAtLeast(1L)
            fpsObserved = ((count - lastCount) * 1000.0 / dt).toInt()
            txKbps = (((bytes - lastBytes) * 8.0) / dt).toInt() // bytes * 8 / ms = kbps
            clientsObserved = service.connectedClientCount()
            audioPeak = service.audioPeakDbfs()
            lastCount = count
            lastBytes = bytes
            lastT = now
        }
    }

    val perm = rememberPermissionStatus(
        needAudio = settings.audioEnabled,
    )

    var settingsOpen by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    // Remembered across recompositions, NOT across process restarts — we want auto-start to
    // fire once per app launch, not on every recomposition triggered by, say, rotation.
    var autoStartFired by remember { mutableStateOf(false) }
    LaunchedEffect(service, perm.granted, streaming, settings.autoStart) {
        if (settings.autoStart && !autoStartFired && perm.granted && service != null && !streaming) {
            autoStartFired = true
            startForeground()
            service.startStreaming(settings)
        }
    }

    // UI layout follows the activity's actual display orientation (changes only when the
    // OS rotates the activity, which respects rotation lock — that's correct here).
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Camera rotation follows the *physical* phone orientation via accelerometer, so the
    // MJPEG output rotates with the user even when system auto-rotate is off. This is the
    // common case: people lock rotation to keep the UI portrait but still expect the
    // stream they're sending to OBS to follow how they're holding the phone.
    //
    // Manual override (settings.rotationLock != AUTO) pins the encoder rotation to a
    // chosen orientation — useful on a tripod where the sensor is fixed but the user
    // wants a specific picture orientation in OBS.
    val deviceRotation = rememberPhysicalDeviceRotation()
    val effectiveRotation = when (settings.rotationLock) {
        guru.freberg.lenscast.prefs.RotationLock.AUTO -> deviceRotation
        guru.freberg.lenscast.prefs.RotationLock.PORTRAIT -> Surface.ROTATION_0
        guru.freberg.lenscast.prefs.RotationLock.LANDSCAPE_LEFT -> Surface.ROTATION_90
        guru.freberg.lenscast.prefs.RotationLock.PORTRAIT_UPSIDE_DOWN -> Surface.ROTATION_180
        guru.freberg.lenscast.prefs.RotationLock.LANDSCAPE_RIGHT -> Surface.ROTATION_270
    }
    LaunchedEffect(service, effectiveRotation) {
        service?.setDeviceRotation(effectiveRotation)
    }

    // Drive the camera via CameraX whenever the RTSP path isn't actively streaming.
    // That covers MJPEG (always) and RTSP-before-Start. When the RTSP stream is live, the
    // RtspManager owns Camera2 directly and we skip CameraX to avoid two camera owners.
    LaunchedEffect(
        service, perm.granted, streaming,
        settings.protocol, settings.lens, settings.resolution, settings.fps, settings.jpegQuality,
        settings.mirror, settings.whiteBalance, settings.antiBanding, settings.continuousAf, settings.exposureEv,
        settings.effect, settings.sceneMode, settings.manualFocus, settings.manualFocusCentidiopters,
        settings.manualExposure, settings.iso, settings.shutterUs,
    ) {
        val svc = service ?: return@LaunchedEffect
        if (!perm.granted) return@LaunchedEffect
        // Both RTSP and WebRTC own Camera2 directly; skip CameraX (re)bind while either
        // is streaming or the surface would fight Camera2Capturer / RtspCameraDriver.
        val proto = settings.protocol
        val nonCameraXActive = streaming && (proto == guru.freberg.lenscast.prefs.Protocol.RTSP
            || proto == guru.freberg.lenscast.prefs.Protocol.WEBRTC
            || proto == guru.freberg.lenscast.prefs.Protocol.SRT)
        if (nonCameraXActive) return@LaunchedEffect
        try {
            svc.bindCameraIfNeeded(
                lens = settings.lens,
                resolution = settings.resolution,
                // CameraX preview never delivers > 30 fps. Clamp to keep the camera config valid.
                fps = minOf(settings.fps.value, 30),
                jpegQuality = settings.jpegQuality,
                mirror = settings.mirror,
                whiteBalance = settings.whiteBalance,
                antiBanding = settings.antiBanding,
                continuousAf = settings.continuousAf,
                exposureEv = settings.exposureEv,
                effect = settings.effect,
                sceneMode = settings.sceneMode,
                manualFocus = settings.manualFocus,
                manualFocusCentidiopters = settings.manualFocusCentidiopters,
                manualExposure = settings.manualExposure,
                iso = settings.iso,
                shutterUs = settings.shutterUs,
            )
        } catch (_: Throwable) {
            // Camera might be transiently unavailable (e.g. another app holding it);
            // it'll retry on the next recomposition.
        }
    }
    DisposableEffect(service) {
        onDispose {
            // Release the preview-only camera when the screen leaves composition. The
            // foreground streaming path is untouched by this.
            service?.unbindCamera()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        // Wrap everything the camera-box subtree reads through rememberUpdatedState. The
        // movableContentOf lambda below captures these State<T> references once and reads
        // them freshly each composition — so when the layout swaps Column↔Row on rotation,
        // Compose can pick up the SAME composable subtree and reattach it without
        // destroying the PreviewView/TextureView. CameraX never sees its surface die.
        // Pre-compute the RTSP camera plan from CameraCharacteristics so the SurfaceView can
        // be sized correctly BEFORE the camera opens. High-speed sessions reject surfaces
        // whose dimensions aren't in the supported list, so guessing the SurfaceView size
        // from screen pixels would crash session config.
        val rtspPlannedSize = remember(settings.protocol, settings.lens, settings.resolution, settings.fps) {
            // RTSP + SRT both go through RtspCameraDriver, so the planned-size lookup is
            // the same for both.
            if (settings.protocol == guru.freberg.lenscast.prefs.Protocol.RTSP
                || settings.protocol == guru.freberg.lenscast.prefs.Protocol.SRT) {
                RtspCameraDriver.plan(
                    ctx, settings.lens,
                    android.util.Size(settings.resolution.width, settings.resolution.height),
                    settings.fps.value,
                )?.size
            } else null
        }
        val camStateRef = rememberUpdatedState(
            CamBoxState(
                service = service,
                lens = settings.lens,
                protocol = settings.protocol,
                streaming = streaming,
                plannedSize = rtspPlannedSize,
                torchOn = torchOn,
                blankPreview = settings.blankPreview,
                onSwitchCamera = {
                    torchOn = false
                    service?.setTorch(false)
                    scope.launch {
                        repo.update {
                            val newLens = if (it.lens == Lens.BACK) Lens.FRONT else Lens.BACK
                            // Front sensors often don't advertise the current resolution (1440p
                            // / 4K back, max 1080p front is the common pattern) and almost
                            // never advertise high-speed FPS. Clamp resolution first, then
                            // derive an FPS that's valid for the clamped (lens, resolution).
                            val supportedRes = guru.freberg.lenscast.camera.CameraCapabilities
                                .supportedResolutions(ctx, newLens)
                            val newRes = guru.freberg.lenscast.camera.CameraCapabilities
                                .nextBestResolution(supportedRes, it.resolution)
                            val supportedFps = guru.freberg.lenscast.camera.CameraCapabilities
                                .supportedFps(ctx, newLens, newRes, it.protocol)
                            val newFps = guru.freberg.lenscast.camera.CameraCapabilities
                                .nextBestFps(supportedFps, it.fps)
                            it.copy(lens = newLens, resolution = newRes, fps = newFps)
                        }
                        // For protocols that lock the camera at start (RTSP, SRT), the
                        // settings-flow rebind path the MJPEG service uses is a no-op:
                        // those pipelines own Camera2 directly. Ask the service to
                        // restart with the just-persisted settings instead.
                        if (streaming &&
                            (settings.protocol == guru.freberg.lenscast.prefs.Protocol.SRT ||
                                settings.protocol == guru.freberg.lenscast.prefs.Protocol.RTSP)) {
                            val refreshed = repo.flow.first()
                            service?.restartStreaming(refreshed)
                        }
                    }
                },
                onToggleTorch = {
                    torchOn = !torchOn
                    service?.setTorch(torchOn)
                },
                onSnapshot = {
                    val uri = service?.saveSnapshot()
                    val msg = if (uri != null) {
                        ctx.getString(R.string.snapshot_saved)
                    } else {
                        ctx.getString(R.string.snapshot_failed)
                    }
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                },
                onSnapshotBurst = {
                    val svc = service ?: return@CamBoxState
                    scope.launch {
                        var saved = 0
                        repeat(5) {
                            if (svc.saveSnapshot() != null) saved++
                            delay(250)
                        }
                        val msg = if (saved > 0) {
                            ctx.getString(R.string.snapshot_burst_done, saved)
                        } else {
                            ctx.getString(R.string.snapshot_failed)
                        }
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
        )
        val cameraBox = remember {
            movableContentOf<Modifier> { boxModifier ->
                val st = camStateRef.value
                Box(modifier = boxModifier) {
                    PreviewSurface(
                        service = st.service,
                        protocol = st.protocol,
                        streaming = st.streaming,
                        plannedSize = st.plannedSize,
                        modifier = Modifier.fillMaxSize(),
                        blankWhileStreaming = st.blankPreview,
                    )
                    LensChip(
                        lens = st.lens,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        // MJPEG rebinds CameraX cleanly mid-stream. RTSP and SRT both lock
                        // Camera2 at stream-start (the H.264 encoder's input Surface is
                        // bound to one CameraDevice), so lens switching there goes through
                        // restartStreaming() — a brief stop + start with the new lens. The
                        // receiver disconnects and well-behaved clients reconnect.
                        // WebRTC stays hidden: the signalling session would need to be
                        // renegotiated, which we haven't wired up yet.
                        val cameraLocked = st.streaming &&
                            st.protocol == guru.freberg.lenscast.prefs.Protocol.WEBRTC
                        if (!cameraLocked) {
                            OverlayIconButton(
                                icon = Icons.Outlined.Cameraswitch,
                                contentDescription = stringResource(R.string.action_switch_camera),
                                enabled = true,
                                onClick = st.onSwitchCamera,
                            )
                        }
                        OverlayIconButton(
                            icon = if (st.torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                            contentDescription = if (st.torchOn) stringResource(R.string.cd_turn_flash_off) else stringResource(R.string.cd_turn_flash_on),
                            enabled = st.lens == Lens.BACK,
                            highlighted = st.torchOn,
                            onClick = st.onToggleTorch,
                        )
                        OverlayIconButton(
                            icon = Icons.Outlined.CameraAlt,
                            contentDescription = stringResource(R.string.cd_snapshot),
                            enabled = true,
                            onClick = st.onSnapshot,
                            onLongClick = st.onSnapshotBurst,
                        )
                    }
                }
            }
        }

        @Composable
        fun controlStack() {
            // Pre-emptive banner — battery low (and not charging) or thermal throttling.
            // Sits above the Start button so the user sees it before tapping.
            HealthBanner(state = health)
            PrimaryActionButton(
                streaming = streaming,
                permissionGranted = perm.granted,
                onStart = {
                    if (perm.granted) {
                        startForeground()
                        service?.startStreaming(settings)
                    }
                },
                onStop = {
                    service?.stopStreaming()
                    torchOn = false
                },
            )
            if (!perm.granted) {
                PermissionRequestRow(
                    missing = perm.missing,
                    onGranted = { /* status re-evaluates on next composition */ },
                )
            }
            if (streaming) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatChip(
                        label = stringResource(R.string.stat_resolution),
                        value = settings.resolution.label,
                        modifier = Modifier.weight(1f),
                    )
                    StatChip(
                        label = stringResource(R.string.stat_fps),
                        value = if (fpsObserved > 0) fpsObserved.toString() else "—",
                        modifier = Modifier.weight(1f),
                    )
                    StatChip(
                        label = stringResource(R.string.stat_clients),
                        value = clientsObserved.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    StatChip(
                        label = stringResource(R.string.stat_bitrate),
                        value = formatBitrate(txKbps),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (settings.audioEnabled) {
                    VuMeter(
                        label = stringResource(R.string.stat_audio),
                        peakDbfs = audioPeak,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ConnectionInfoCard(
                wifiIp = localIp,
                protocol = settings.protocol,
                mjpegPort = settings.mjpegPort,
                rtspPort = settings.rtspPort,
                audioEnabled = settings.audioEnabled,
                webControlEnabled = settings.webControlEnabled,
                webControlPort = settings.webControlPort,
                httpsEnabled = settings.httpsEnabled,
                authUsername = settings.streamUsername,
                authPassword = settings.streamPassword,
                srtMode = settings.srtMode,
                srtHost = settings.srtHost,
                srtPort = settings.srtPort,
            )
        }

        if (inPictureInPicture) {
            // PiP window: no chrome, no padding — just the preview filling the small overlay.
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(padding)) {
                cameraBox(Modifier.fillMaxSize())
            }
            return@Scaffold
        }

        if (isLandscape) {
            // Landscape: top bar across the top, then preview on the left filling the height
            // and control stack on the right, scrollable.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(streaming = streaming, onSettings = { settingsOpen = true })
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    cameraBox(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(20.dp)),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        controlStack()
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(streaming = streaming, onSettings = { settingsOpen = true })
                cameraBox(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true),
                )
                controlStack()
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = settings,
            streaming = streaming,
            onDismiss = { settingsOpen = false },
            onChange = { newSettings ->
                scope.launch { repo.update { newSettings } }
            },
        )
    }
}

/**
 * Discrete Surface.ROTATION_* value driven by the accelerometer. Unlike Display.getRotation
 * + LocalConfiguration (which only update when the OS actually rotates the activity), this
 * follows physical motion regardless of the system's rotation-lock setting. Seeded with the
 * current display rotation so the very first frame after launch is right-way-up.
 */
private fun formatBitrate(kbps: Int): String = when {
    kbps <= 0 -> "—"
    kbps < 1000 -> "${kbps} kbps"
    else -> "%.1f Mbps".format(kbps / 1000.0)
}

@Composable
private fun rememberPhysicalDeviceRotation(): Int {
    val ctx = LocalContext.current
    var rotation by remember {
        mutableIntStateOf(
            @Suppress("DEPRECATION")
            (ctx as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        )
    }
    DisposableEffect(ctx) {
        val listener = object : OrientationEventListener(ctx, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val next = when (orientation) {
                    in 45 until 135  -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else             -> Surface.ROTATION_0
                }
                if (next != rotation) rotation = next
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return rotation
}

@Composable
private fun TopBar(
    streaming: Boolean,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (streaming) stringResource(R.string.subtitle_streaming) else stringResource(R.string.subtitle_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LiveStatusPill(streaming = streaming)
        Spacer(Modifier.size(8.dp))
        IconButton(
            onClick = onSettings,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
        }
    }
}

/**
 * Circular icon button overlaid on the camera preview. Translucent dark background so it
 * reads clearly against both bright and dark frames. Disabled state desaturates without
 * removing the button so users can tell the control still exists.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OverlayIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val container = when {
        highlighted -> MaterialTheme.colorScheme.primary
        else -> androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)
    }
    val tint = when {
        !enabled -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)
        highlighted -> MaterialTheme.colorScheme.onPrimary
        else -> androidx.compose.ui.graphics.Color.White
    }
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .let {
                if (enabled) it.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ) else it
            },
        color = container,
        contentColor = tint,
        shape = CircleShape,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Small overlay chip labeling the active lens, top-left of the preview. Lets the user see
 * which camera they're looking at without having to interpret the camera-switch icon.
 */
@Composable
private fun LensChip(lens: Lens, modifier: Modifier = Modifier) {
    val (icon, label) = when (lens) {
        Lens.BACK -> Icons.Outlined.PhotoCamera to stringResource(R.string.settings_camera_back)
        Lens.FRONT -> Icons.Outlined.PhotoCameraFront to stringResource(R.string.settings_camera_front)
    }
    Surface(
        modifier = modifier,
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
        shape = CircleShape,
        contentColor = androidx.compose.ui.graphics.Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(
    streaming: Boolean,
    permissionGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val container = if (streaming) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val onContainer = contentColorFor(container)
    FilledTonalButton(
        onClick = if (streaming) onStop else onStart,
        enabled = permissionGranted || streaming,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = onContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        AnimatedContent(
            targetState = streaming,
            transitionSpec = { fadeIn(animationSpec = spring()) togetherWith fadeOut(animationSpec = spring()) },
            label = "primary-cta",
        ) { isLive ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isLive) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (isLive) stringResource(R.string.action_stop) else stringResource(R.string.action_start),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun HealthBanner(state: guru.freberg.lenscast.system.HealthMonitor.State) {
    val msg = state.message ?: return
    val critical = state.severity == guru.freberg.lenscast.system.HealthMonitor.Severity.CRITICAL
    val container = if (critical) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
    val content   = if (critical) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

