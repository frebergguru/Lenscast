package dev.lenscast.ui.components

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.lenscast.R
import dev.lenscast.prefs.Protocol
import dev.lenscast.streaming.StreamingService
import kotlinx.coroutines.delay

@Composable
private fun RtspStreamingPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.preview_rtsp_streaming_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.preview_rtsp_streaming_description),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Picks a preview backend based on what's actually driving the camera right now:
 *  - **CameraX `PreviewView`** when the MJPEG path is the camera owner (i.e. MJPEG protocol
 *    in any state, or RTSP protocol before the user taps Start). CameraX is happy to drive
 *    the preview at the device's standard fps regardless of what RTSP will later request.
 *  - **Camera2 `SurfaceView`** only once the RTSP capture session is actively running. The
 *    SurfaceView's holder is fixed to the negotiated plan size so high-speed sessions work.
 */
@Composable
fun PreviewSurface(
    service: StreamingService?,
    protocol: Protocol,
    streaming: Boolean,
    plannedSize: android.util.Size?,
    modifier: Modifier = Modifier,
    blankWhileStreaming: Boolean = false,
) {
    val context = LocalContext.current
    val blanked = blankWhileStreaming && streaming
    val useCameraX = (protocol == Protocol.MJPEG || !streaming) && !blanked

    // Track preview-surface pixel size so tap coordinates can be normalised to 0..1
    // before being sent to FocusMeteringAction. Updated whenever the layout slot resizes.
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var focusIndicator by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusIndicator) {
        if (focusIndicator != null) {
            delay(800)
            focusIndicator = null
        }
    }

    val gestureModifier = if (useCameraX && service != null) {
        Modifier
            .onSizeChanged { surfaceSize = it }
            .pointerInput(service) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom == 1f) return@detectTransformGestures
                    val current = service.currentZoomRatio()
                    service.setZoomRatio(current * zoom)
                }
            }
            .pointerInput(service) {
                detectTapGestures(onTap = { offset ->
                    val sz = surfaceSize
                    if (sz.width > 0 && sz.height > 0) {
                        service.tapToFocus(
                            offset.x / sz.width.toFloat(),
                            offset.y / sz.height.toFloat(),
                        )
                        focusIndicator = offset
                    }
                })
            }
    } else Modifier

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .then(gestureModifier),
    ) {
        if (useCameraX) {
            val previewView = remember {
                PreviewView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // COMPATIBLE (TextureView) transitions through Compose layout swaps
                    // cleanly. PERFORMANCE (SurfaceView) blanks during the swap because
                    // its underlying window layer is recreated.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            AndroidView(factory = { previewView }) { view ->
                service?.setPreviewSurfaceProvider(view.surfaceProvider)
            }
            DisposableEffect(service) {
                onDispose { service?.setPreviewSurfaceProvider(null) }
            }
            focusIndicator?.let { pos ->
                FocusRing(pos)
            }
        } else if (blanked) {
            // Battery-saver mode: drop the preview Surface entirely so the GPU/display can
            // power down. CameraX's ImageAnalysis pipeline still runs because the service
            // owns it independently of the on-screen Preview use case (well, almost —
            // CameraController still binds the Preview use case; we just don't render it).
            BlankPreviewPlaceholder()
        } else {
            // RTSP active path: no on-device preview. Trying to host a SurfaceView/TextureView
            // inside Compose's reparenting layout swaps caused the BufferQueue to be
            // abandoned on rotation, killing the stream. The Camera2 session continues to
            // feed the H.264 encoder without a preview surface; OBS shows the live video.
            RtspStreamingPlaceholder()
        }
    }
}

@Composable
private fun BlankPreviewPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Battery-saver mode",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Preview hidden; the stream is still live.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Small white ring marker drawn at the tap-to-focus point. Fades out after ~800 ms via
 * the LaunchedEffect that clears [PreviewSurface]'s focusIndicator state.
 */
@Composable
private fun FocusRing(at: Offset) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = Color.White,
            radius = 36f,
            center = at,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
        )
    }
}
