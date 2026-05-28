package guru.freberg.lenscast.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.freberg.lenscast.R
import guru.freberg.lenscast.prefs.Protocol
import kotlinx.coroutines.delay

@Composable
fun ConnectionInfoCard(
    wifiIp: String?,
    protocol: Protocol,
    mjpegPort: Int,
    rtspPort: Int,
    audioEnabled: Boolean = false,
    webControlEnabled: Boolean = false,
    webControlPort: Int = 8080,
    httpsEnabled: Boolean = false,
    authUsername: String = "",
    authPassword: String = "",
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.card_connection_title).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Wi-Fi row
            val port = when (protocol) {
                Protocol.MJPEG  -> mjpegPort
                Protocol.RTSP   -> rtspPort
                Protocol.WEBRTC -> webControlPort
            }
            // HTTPS toggle now wraps both MJPEG (https://) and RTSP (rtsps://). WebRTC
            // reuses the web control HTTP(S) port for signalling.
            val scheme = when {
                protocol == Protocol.WEBRTC && httpsEnabled -> "https"
                protocol == Protocol.WEBRTC               -> "http"
                protocol == Protocol.MJPEG && httpsEnabled -> "https"
                protocol == Protocol.MJPEG               -> "http"
                httpsEnabled                              -> "rtsps"
                else                                      -> "rtsp"
            }
            val path = when (protocol) {
                Protocol.MJPEG  -> "/video"
                Protocol.WEBRTC -> "/webrtc/view"
                Protocol.RTSP   -> ""
            }
            // Embed user:pass@ when the password is set — both servers ([Mjpeg|Rtsp]Server)
            // honour Basic auth with the same credentials.
            val auth = if (authPassword.isNotEmpty()) "${authUsername.ifBlank { "Lenscast" }}:$authPassword@" else ""
            val wifiUrl = wifiIp?.let { "$scheme://$auth$it:$port$path" }
            UrlRow(
                icon = if (wifiIp != null) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                label = stringResource(R.string.card_connection_wifi),
                value = wifiUrl ?: stringResource(R.string.card_connection_no_wifi),
                copyable = wifiUrl != null,
            )
            Spacer(Modifier.height(12.dp))

            // USB row
            val usbUrl = "$scheme://${auth}localhost:$port$path"
            UrlRow(
                icon = Icons.Outlined.Usb,
                label = stringResource(R.string.card_connection_usb),
                value = usbUrl,
                copyable = true,
            )

            // MJPEG audio sidecar URL — only meaningful when MJPEG protocol + audio toggle
            // are both on. Surfaced here so the user knows to point ffplay / VLC / a
            // browser <audio> at /audio for the WAV stream.
            if (protocol == Protocol.MJPEG && audioEnabled && wifiIp != null) {
                Spacer(Modifier.height(8.dp))
                UrlRow(
                    icon = Icons.Outlined.Wifi,
                    label = stringResource(R.string.card_connection_audio),
                    value = "$scheme://$wifiIp:$mjpegPort/audio",
                    copyable = true,
                )
            }

            if (webControlEnabled && wifiIp != null) {
                Spacer(Modifier.height(8.dp))
                val webScheme = if (httpsEnabled) "https" else "http"
                UrlRow(
                    icon = Icons.Outlined.Wifi,
                    label = stringResource(R.string.card_connection_web_control),
                    value = "$webScheme://$wifiIp:$webControlPort/",
                    copyable = true,
                )
            }
            Spacer(Modifier.height(8.dp))

            // adb forward hint — `forward` tunnels PC→phone, which is what OBS/VLC/etc need.
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.card_connection_usb_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    val cmd = "adb forward tcp:$port tcp:$port"
                    CopyableInline(text = cmd)
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.card_connection_obs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UrlRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    copyable: Boolean,
) {
    val ctx = LocalContext.current
    var justCopied by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

    if (showQr) QrCodeDialog(url = value, onDismiss = { showQr = false })

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .let {
                if (copyable) it.combinedClickable(
                    onClick = { copy(ctx, value); justCopied = true },
                    // Long-press opens a scannable QR — second-device handoff without
                    // having to type the URL into another browser.
                    onLongClick = { showQr = true },
                ) else it
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (copyable) {
            // Explicit QR button as a discoverability cue — long-press also works
            // (the click feeds the copy path) but most users won't think to try it.
            Icon(
                imageVector = Icons.Outlined.QrCode2,
                contentDescription = "Show as QR",
                modifier = Modifier
                    .size(20.dp)
                    .clickable { showQr = true },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            AnimatedVisibility(visible = !justCopied) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(visible = justCopied) {
                Icon(Icons.Outlined.Done, contentDescription = "Copied", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CopyableInline(text: String) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1200); copied = false }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { copy(ctx, text); copied = true }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun copy(ctx: Context, value: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Lenscast", value))
    Toast.makeText(ctx, ctx.getString(R.string.action_copied), Toast.LENGTH_SHORT).show()
}
