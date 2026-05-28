package dev.lenscast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lenscast.R
import dev.lenscast.prefs.Fps
import dev.lenscast.prefs.Lens
import dev.lenscast.prefs.Protocol
import dev.lenscast.prefs.Resolution
import dev.lenscast.prefs.Settings
import dev.lenscast.system.SystemWebcam

@Composable
fun SettingsSheet(
    settings: Settings,
    streaming: Boolean,
    onDismiss: () -> Unit,
    onChange: (Settings) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Protocol — picking RTSP unlocks high fps + audio.
            SectionLabel(stringResource(R.string.settings_protocol))
            SegmentedRow(
                options = Protocol.entries.toList(),
                selected = settings.protocol,
                enabled = !streaming,
                labelOf = {
                    when (it) {
                        Protocol.MJPEG -> stringResource(R.string.settings_protocol_mjpeg)
                        Protocol.RTSP  -> stringResource(R.string.settings_protocol_rtsp)
                    }
                },
                onSelect = { onChange(settings.copy(protocol = it)) },
            )
            if (settings.protocol == Protocol.RTSP) {
                Text(
                    text = stringResource(R.string.settings_rtsp_landscape_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Camera — switchable mid-stream.
            SectionLabel(stringResource(R.string.settings_camera))
            SegmentedRow(
                options = Lens.entries.toList(),
                selected = settings.lens,
                enabled = true,
                labelOf = {
                    when (it) {
                        Lens.BACK  -> stringResource(R.string.settings_camera_back)
                        Lens.FRONT -> stringResource(R.string.settings_camera_front)
                    }
                },
                onSelect = { onChange(settings.copy(lens = it)) },
            )

            Spacer(Modifier.height(16.dp))

            // Resolution
            SectionLabel(stringResource(R.string.settings_resolution))
            SegmentedRow(
                options = Resolution.entries.toList(),
                selected = settings.resolution,
                enabled = !streaming,
                labelOf = { it.label },
                onSelect = { onChange(settings.copy(resolution = it)) },
            )

            Spacer(Modifier.height(16.dp))

            // FPS — high-speed options are only valid with RTSP since MJPEG can't carry them
            // (Android caps the standard preview path at the sensor's reported standard fps).
            SectionLabel(stringResource(R.string.settings_fps))
            val fpsOptions = Fps.entries.filter { !it.isHighSpeed || settings.protocol == Protocol.RTSP }
            SegmentedRow(
                options = fpsOptions,
                selected = if (settings.fps in fpsOptions) settings.fps else Fps.FPS30,
                enabled = !streaming,
                labelOf = { it.value.toString() },
                onSelect = { onChange(settings.copy(fps = it)) },
            )
            if (settings.protocol == Protocol.MJPEG) {
                Text(
                    text = "MJPEG: capped at the device's standard preview fps (typically 30). Switch to RTSP for 60+ fps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            if (settings.protocol == Protocol.MJPEG) {
                SectionLabel(stringResource(R.string.settings_quality))
                var quality by remember(settings.jpegQuality) { mutableStateOf(settings.jpegQuality.toFloat()) }
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    onValueChangeFinished = { onChange(settings.copy(jpegQuality = quality.toInt())) },
                    valueRange = 30f..95f,
                    steps = 12,
                )
                Text(
                    text = stringResource(R.string.settings_quality_summary, quality.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Server port — editable per active protocol. Disabled while streaming since the
            // server is already bound; user has to Stop first.
            SectionLabel(stringResource(R.string.settings_port_section))
            val portLabel = stringResource(
                if (settings.protocol == Protocol.MJPEG) R.string.settings_mjpeg_port
                else R.string.settings_rtsp_port
            )
            PortField(
                label = portLabel,
                port = if (settings.protocol == Protocol.MJPEG) settings.mjpegPort else settings.rtspPort,
                enabled = !streaming,
                onPortChange = { newPort ->
                    onChange(
                        if (settings.protocol == Protocol.MJPEG) settings.copy(mjpegPort = newPort)
                        else settings.copy(rtspPort = newPort)
                    )
                },
            )

            Spacer(Modifier.height(16.dp))

            if (settings.protocol == Protocol.RTSP) {
                ToggleRow(
                    title = stringResource(R.string.settings_audio),
                    checked = settings.audioEnabled,
                    enabled = !streaming,
                    onCheckedChange = { onChange(settings.copy(audioEnabled = it)) },
                )
            }

            ToggleRow(
                title = stringResource(R.string.settings_keep_screen_on),
                checked = settings.keepScreenOn,
                onCheckedChange = { onChange(settings.copy(keepScreenOn = it)) },
            )

            val context = LocalContext.current
            if (SystemWebcam.isSupported(context)) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                SectionLabel(stringResource(R.string.settings_system_webcam_title))
                Text(
                    text = stringResource(R.string.settings_system_webcam_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedButton(onClick = { SystemWebcam.openUsbSettings(context) }) {
                    Text(stringResource(R.string.settings_system_webcam_open))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    enabled: Boolean,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, opt ->
            SegmentedButton(
                selected = opt == selected,
                onClick = { if (enabled) onSelect(opt) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
            ) {
                Text(labelOf(opt))
            }
        }
    }
}

@Composable
private fun PortField(
    label: String,
    port: Int,
    enabled: Boolean,
    onPortChange: (Int) -> Unit,
) {
    // Local string state lets the user clear the field and retype without the int model
    // snapping it back. `remember(port)` resyncs when the upstream value changes (e.g.
    // protocol switch swaps which port we're displaying).
    var text by remember(port) { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null && parsed in 1024..65535
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            val cleaned = new.filter { it.isDigit() }.take(5)
            text = cleaned
            cleaned.toIntOrNull()?.takeIf { it in 1024..65535 }?.let {
                if (it != port) onPortChange(it)
            }
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = !isValid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = if (!isValid) {
            { Text(stringResource(R.string.settings_port_range_hint)) }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

