package guru.freberg.lenscast.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import guru.freberg.lenscast.R
import guru.freberg.lenscast.system.BatteryOptimization

/**
 * Result of checking whether the user has granted us what we need.
 */
data class PermissionStatus(val granted: Boolean, val missing: List<String>)

@Composable
fun rememberPermissionStatus(needAudio: Boolean): PermissionStatus {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember(needAudio) { mutableStateOf(checkPermissions(ctx, needAudio)) }
    // Re-evaluate on every ON_RESUME — the previous LaunchedEffect approach only fired
    // when needAudio changed, so a permission granted via the system dialog wouldn't
    // flip the gate from "missing" to "granted" until the user manually restarted the
    // app. ON_RESUME catches both the dialog dismiss path and the user returning from
    // the per-app Settings screen.
    DisposableEffect(lifecycle, needAudio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = checkPermissions(ctx, needAudio)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return status
}

private fun checkPermissions(ctx: Context, needAudio: Boolean): PermissionStatus {
    val needed = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (needAudio) add(Manifest.permission.RECORD_AUDIO)
    }
    val missing = needed.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
    return PermissionStatus(granted = missing.isEmpty(), missing = missing)
}

@Composable
fun PermissionRequestRow(
    missing: List<String>,
    modifier: Modifier = Modifier,
    onGranted: () -> Unit,
) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onGranted()
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.permission_camera_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                stringResource(R.string.permission_camera_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launcher.launch(missing.toTypedArray()) }) {
                    Text(stringResource(R.string.permission_grant))
                }
                OutlinedButton(onClick = { openAppSettings(ctx) }) {
                    Text(stringResource(R.string.permission_open_settings))
                }
            }
        }
    }
}

private fun openAppSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}

/**
 * One-shot startup prompt for everything the app needs to stream reliably in the background:
 * the dangerous runtime permissions (camera, notifications, microphone) in a single system
 * dialog, followed by the battery-optimization exemption nudge. Fires once per process launch
 * (survives rotation via [rememberSaveable]) and only prompts for what's still missing, so a
 * fully-granted launch shows nothing. Phone/call permissions are intentionally left out — they
 * belong to the optional call-behavior feature and are requested when the user enables it.
 *
 * Renders no UI itself; drop it once near the top of the main composition.
 */
@Composable
fun StartupPermissionRequester() {
    val ctx = LocalContext.current
    var didRun by rememberSaveable { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Runtime dialog dismissed — chain the battery nudge if we're still optimized.
        if (!BatteryOptimization.isExempt(ctx)) BatteryOptimization.request(ctx)
    }
    LaunchedEffect(Unit) {
        if (didRun) return@LaunchedEffect
        didRun = true
        val missing = startupRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        } else if (!BatteryOptimization.isExempt(ctx)) {
            BatteryOptimization.request(ctx)
        }
    }
}

private fun startupRuntimePermissions(): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    // Microphone is requested up front (not gated on the audio toggle) so enabling audio later
    // is a flip, not a fresh permission round-trip — matches the user's "nudge mic at startup".
    add(Manifest.permission.RECORD_AUDIO)
}
