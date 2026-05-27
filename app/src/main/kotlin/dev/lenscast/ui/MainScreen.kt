package dev.lenscast.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import android.app.Activity
import android.content.res.Configuration
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
import dev.lenscast.R
import dev.lenscast.net.NetworkUtils
import dev.lenscast.prefs.Lens
import dev.lenscast.prefs.Settings
import dev.lenscast.prefs.SettingsRepository
import dev.lenscast.streaming.StreamingService
import dev.lenscast.streaming.rtsp.RtspCameraDriver
import dev.lenscast.ui.components.ConnectionInfoCard
import dev.lenscast.ui.components.LiveStatusPill
import dev.lenscast.ui.components.PermissionRequestRow
import dev.lenscast.ui.components.PreviewSurface
import dev.lenscast.ui.components.StatChip
import dev.lenscast.ui.components.rememberPermissionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    val lens: dev.lenscast.prefs.Lens,
    val protocol: dev.lenscast.prefs.Protocol,
    val streaming: Boolean,
    val plannedSize: android.util.Size?,
    val torchOn: Boolean,
    val onSwitchCamera: () -> Unit,
    val onToggleTorch: () -> Unit,
)

@Composable
fun MainScreen(
    service: StreamingService?,
    startForeground: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx) }
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()

    // Live service state (default IDLE when service not bound yet).
    val statusFlow = service?.status ?: remember { MutableStateFlow(StreamingService.Status()) }
    val status by statusFlow.collectAsStateWithLifecycle()
    val streaming = status.state == StreamingService.State.STREAMING || status.state == StreamingService.State.STARTING

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
    LaunchedEffect(streaming, service) {
        if (!streaming || service == null) {
            fpsObserved = 0
            clientsObserved = 0
            return@LaunchedEffect
        }
        var lastCount = service.framesProducedNow()
        var lastT = System.currentTimeMillis()
        clientsObserved = service.connectedClientCount()
        while (true) {
            delay(500)
            val now = System.currentTimeMillis()
            val count = service.framesProducedNow()
            val dt = (now - lastT).coerceAtLeast(1L)
            fpsObserved = ((count - lastCount) * 1000.0 / dt).toInt()
            clientsObserved = service.connectedClientCount()
            lastCount = count
            lastT = now
        }
    }

    val perm = rememberPermissionStatus(
        needAudio = settings.audioEnabled && settings.protocol == dev.lenscast.prefs.Protocol.RTSP,
    )

    var settingsOpen by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

    // Track display rotation. LocalConfiguration triggers recomposition on rotation, so
    // we re-read the WindowManager's rotation each pass and push it through the service.
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val deviceRotation = remember(configuration) {
        @Suppress("DEPRECATION")
        val display = (ctx as? Activity)?.windowManager?.defaultDisplay
        display?.rotation ?: Surface.ROTATION_0
    }
    LaunchedEffect(service, deviceRotation) {
        service?.setDeviceRotation(deviceRotation)
    }

    // Drive the camera via CameraX whenever the RTSP path isn't actively streaming.
    // That covers MJPEG (always) and RTSP-before-Start. When the RTSP stream is live, the
    // RtspManager owns Camera2 directly and we skip CameraX to avoid two camera owners.
    LaunchedEffect(service, perm.granted, streaming, settings.protocol, settings.lens, settings.resolution, settings.fps, settings.jpegQuality) {
        val svc = service ?: return@LaunchedEffect
        if (!perm.granted) return@LaunchedEffect
        val rtspActive = streaming && settings.protocol == dev.lenscast.prefs.Protocol.RTSP
        if (rtspActive) return@LaunchedEffect
        try {
            svc.bindCameraIfNeeded(
                lens = settings.lens,
                resolution = settings.resolution,
                // CameraX preview never delivers > 30 fps. Clamp to keep the camera config valid.
                fps = minOf(settings.fps.value, 30),
                jpegQuality = settings.jpegQuality,
                // ImageAnalysis only matters for MJPEG streaming. In RTSP mode CameraX is preview-only.
                withAnalysis = streaming && settings.protocol == dev.lenscast.prefs.Protocol.MJPEG,
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
            if (settings.protocol == dev.lenscast.prefs.Protocol.RTSP) {
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
                onSwitchCamera = {
                    torchOn = false
                    service?.setTorch(false)
                    scope.launch {
                        repo.update { it.copy(lens = if (it.lens == Lens.BACK) Lens.FRONT else Lens.BACK) }
                    }
                },
                onToggleTorch = {
                    torchOn = !torchOn
                    service?.setTorch(torchOn)
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
                        OverlayIconButton(
                            icon = Icons.Outlined.Cameraswitch,
                            contentDescription = "Switch camera",
                            enabled = true,
                            onClick = st.onSwitchCamera,
                        )
                        OverlayIconButton(
                            icon = if (st.torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                            contentDescription = if (st.torchOn) "Turn flash off" else "Turn flash on",
                            enabled = st.lens == Lens.BACK,
                            highlighted = st.torchOn,
                            onClick = st.onToggleTorch,
                        )
                    }
                }
            }
        }

        @Composable
        fun controlStack() {
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
                }
            }
            ConnectionInfoCard(
                wifiIp = localIp,
                protocol = settings.protocol,
                mjpegPort = settings.mjpegPort,
                rtspPort = settings.rtspPort,
            )
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
@Composable
private fun OverlayIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false,
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
            .let { if (enabled) it.clickable { onClick() } else it },
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
        Lens.BACK -> Icons.Outlined.PhotoCamera to "Back"
        Lens.FRONT -> Icons.Outlined.PhotoCameraFront to "Front"
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

