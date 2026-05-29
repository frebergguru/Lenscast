package guru.freberg.lenscast.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import guru.freberg.lenscast.R
import guru.freberg.lenscast.camera.CameraCapabilities
import guru.freberg.lenscast.prefs.AntiBanding
import guru.freberg.lenscast.prefs.CallBehavior
import guru.freberg.lenscast.prefs.CameraEffect
import guru.freberg.lenscast.prefs.Fps
import guru.freberg.lenscast.prefs.Lens
import guru.freberg.lenscast.prefs.MicSource
import guru.freberg.lenscast.prefs.Preset
import guru.freberg.lenscast.prefs.Protocol
import guru.freberg.lenscast.prefs.Resolution
import guru.freberg.lenscast.prefs.RotationLock
import guru.freberg.lenscast.prefs.SceneMode
import guru.freberg.lenscast.prefs.Settings
import guru.freberg.lenscast.prefs.SettingsCodec
import guru.freberg.lenscast.prefs.WhiteBalance
import guru.freberg.lenscast.system.SystemWebcam

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
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // Hoisted to the top so every SettingsGroup lambda can close over the same
            // context — it's used in capability checks across multiple cards.
            val context = LocalContext.current

            SettingsGroup(title = stringResource(R.string.settings_group_streaming_basics), initiallyExpanded = true) {
            // Protocol — picking RTSP unlocks high fps + audio.
            SectionLabel(stringResource(R.string.settings_protocol))
            SegmentedRow(
                options = Protocol.entries.toList(),
                selected = settings.protocol,
                enabled = !streaming,
                labelOf = {
                    when (it) {
                        Protocol.MJPEG  -> stringResource(R.string.settings_protocol_mjpeg)
                        Protocol.RTSP   -> stringResource(R.string.settings_protocol_rtsp)
                        Protocol.WEBRTC -> stringResource(R.string.settings_protocol_webrtc)
                        Protocol.SRT    -> stringResource(R.string.settings_protocol_srt)
                    }
                },
                onSelect = { onChange(settings.copy(protocol = it)) },
            )
            val rotationNote = when (settings.protocol) {
                Protocol.RTSP -> R.string.settings_rtsp_landscape_note
                Protocol.SRT -> R.string.settings_srt_rotation_note
                else -> R.string.settings_mjpeg_rotation_note
            }
            Text(
                text = stringResource(rotationNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (settings.protocol == Protocol.SRT) {
                Spacer(Modifier.height(16.dp))
                SrtSection(settings = settings, onChange = onChange, streaming = streaming)
            }

            Spacer(Modifier.height(16.dp))

            // Camera — switchable mid-stream for MJPEG, locked at Start for RTSP.
            // FPS is clamped on lens switch (front cams typically don't do high-speed).
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
                onSelect = { newLens ->
                    // Resolution may not exist on the new lens (front sensors often cap below
                    // the back); clamp first, then derive the FPS set from the clamped
                    // (lens, resolution) pair so all three stay mutually consistent.
                    val supportedRes = CameraCapabilities.supportedResolutions(context, newLens)
                    val newRes = CameraCapabilities.nextBestResolution(supportedRes, settings.resolution)
                    val newSupportedFps = CameraCapabilities.supportedFps(context, newLens, newRes, settings.protocol)
                    val newFps = CameraCapabilities.nextBestFps(newSupportedFps, settings.fps)
                    onChange(settings.copy(lens = newLens, resolution = newRes, fps = newFps))
                },
            )

            Spacer(Modifier.height(16.dp))

            // Resolutions come from SCALER_STREAM_CONFIGURATION_MAP on the chosen lens —
            // back cameras typically reach 1440p / 4K, front cameras often cap at 1080p,
            // budget devices may stop sooner. Changing resolution can also invalidate the
            // current FPS (some high-speed modes only exist at specific sizes) so we clamp.
            SectionLabel(stringResource(R.string.settings_resolution))
            val supportedResolutions = remember(settings.lens) {
                CameraCapabilities.supportedResolutions(context, settings.lens)
            }
            val resolutionOptions = Resolution.entries.filter { it in supportedResolutions }
            SegmentedRow(
                options = resolutionOptions,
                selected = if (settings.resolution in resolutionOptions) settings.resolution
                           else CameraCapabilities.nextBestResolution(supportedResolutions, settings.resolution),
                enabled = !streaming,
                labelOf = { it.label },
                onSelect = { newRes ->
                    val newSupportedFps = CameraCapabilities.supportedFps(context, settings.lens, newRes, settings.protocol)
                    val newFps = CameraCapabilities.nextBestFps(newSupportedFps, settings.fps)
                    onChange(settings.copy(resolution = newRes, fps = newFps))
                },
            )

            Spacer(Modifier.height(16.dp))

            // FPS options come from Camera2 capabilities: AE ranges for MJPEG, the planner's
            // standard + constrained-high-speed search for RTSP. So the picker only ever
            // shows values this device's selected lens can actually produce at the chosen
            // resolution. Recomputed when any of (lens, resolution, protocol) changes.
            SectionLabel(stringResource(R.string.settings_fps))
            val supportedFpsValues = remember(settings.lens, settings.resolution, settings.protocol) {
                CameraCapabilities.supportedFps(context, settings.lens, settings.resolution, settings.protocol)
            }
            val fpsOptions = Fps.entries.filter { it.value in supportedFpsValues }
            SegmentedRow(
                options = fpsOptions,
                selected = if (settings.fps in fpsOptions) settings.fps else CameraCapabilities.nextBestFps(supportedFpsValues, settings.fps),
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
            } // ← close Streaming basics card

            SettingsGroup(title = stringResource(R.string.settings_group_image), initiallyExpanded = true) {
            // Image controls — MJPEG path only on the current pass (RTSP encoder consumes the
            // camera Surface directly and uses sensor defaults; matching these into the RTSP
            // CaptureRequest is a planned follow-up).
            SectionLabel(stringResource(R.string.settings_image_section))
            ToggleRow(
                title = stringResource(R.string.settings_mirror),
                checked = settings.mirror,
                onCheckedChange = { onChange(settings.copy(mirror = it)) },
            )
            ToggleRow(
                title = stringResource(R.string.settings_continuous_af),
                checked = settings.continuousAf,
                onCheckedChange = { onChange(settings.copy(continuousAf = it)) },
            )

            Spacer(Modifier.height(8.dp))
            var watermark by remember(settings.watermarkText) { mutableStateOf(settings.watermarkText) }
            OutlinedTextField(
                value = watermark,
                onValueChange = {
                    val clipped = it.take(120)
                    watermark = clipped
                    onChange(settings.copy(watermarkText = clipped))
                },
                label = { Text(stringResource(R.string.settings_watermark)) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.settings_watermark_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_exposure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            var ev by remember(settings.exposureEv) { mutableStateOf(settings.exposureEv.toFloat()) }
            // EV range is camera-dependent and only known after bind; -12..12 covers every
            // device I've seen (most report -6..6 or -12..12). Clamp at apply time.
            Slider(
                value = ev,
                onValueChange = { ev = it },
                onValueChangeFinished = { onChange(settings.copy(exposureEv = ev.toInt())) },
                valueRange = -12f..12f,
                steps = 23,
            )
            Text(
                text = stringResource(R.string.settings_exposure_summary, ev.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            EnumDropdown(
                label = stringResource(R.string.settings_white_balance),
                options = WhiteBalance.entries.toList(),
                selected = settings.whiteBalance,
                labelOf = {
                    when (it) {
                        WhiteBalance.AUTO        -> stringResource(R.string.settings_wb_auto)
                        WhiteBalance.INCANDESCENT -> stringResource(R.string.settings_wb_incandescent)
                        WhiteBalance.FLUORESCENT  -> stringResource(R.string.settings_wb_fluorescent)
                        WhiteBalance.DAYLIGHT     -> stringResource(R.string.settings_wb_daylight)
                        WhiteBalance.CLOUDY       -> stringResource(R.string.settings_wb_cloudy)
                        WhiteBalance.SHADE        -> stringResource(R.string.settings_wb_shade)
                    }
                },
                onSelect = { onChange(settings.copy(whiteBalance = it)) },
            )

            Spacer(Modifier.height(12.dp))
            val manualSensorOk = remember(settings.lens) {
                CameraCapabilities.supportsManualSensor(context, settings.lens)
            }
            ToggleRow(
                title = stringResource(R.string.settings_manual_exposure),
                checked = settings.manualExposure && manualSensorOk,
                enabled = manualSensorOk,
                onCheckedChange = { onChange(settings.copy(manualExposure = it)) },
            )
            if (!manualSensorOk) {
                Text(
                    text = stringResource(R.string.settings_manual_exposure_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            if (settings.manualExposure && manualSensorOk) {
                val isoR = remember(settings.lens) {
                    CameraCapabilities.isoRange(context, settings.lens)
                }
                val shutterR = remember(settings.lens) {
                    CameraCapabilities.exposureTimeRangeNs(context, settings.lens)
                }
                if (isoR != null) {
                    Text(stringResource(R.string.settings_iso), style = MaterialTheme.typography.bodyMedium)
                    var iso by remember(settings.iso) { mutableStateOf(settings.iso.toFloat()) }
                    Slider(
                        value = iso.coerceIn(isoR.lower.toFloat(), isoR.upper.toFloat()),
                        onValueChange = { iso = it },
                        onValueChangeFinished = { onChange(settings.copy(iso = iso.toInt())) },
                        valueRange = isoR.lower.toFloat()..isoR.upper.toFloat(),
                    )
                    Text(
                        text = "${iso.toInt()} (${isoR.lower}..${isoR.upper})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (shutterR != null) {
                    val minUs = shutterR.lower / 1000L
                    val maxUs = shutterR.upper / 1000L
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_shutter), style = MaterialTheme.typography.bodyMedium)
                    var shutter by remember(settings.shutterUs) { mutableStateOf(settings.shutterUs.toFloat()) }
                    Slider(
                        value = shutter.coerceIn(minUs.toFloat(), maxUs.toFloat()),
                        onValueChange = { shutter = it },
                        onValueChangeFinished = { onChange(settings.copy(shutterUs = shutter.toLong())) },
                        valueRange = minUs.toFloat()..maxUs.toFloat(),
                    )
                    Text(
                        text = "${shutter.toLong()} µs  (1/${(1_000_000L / shutter.toLong().coerceAtLeast(1L))}s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = stringResource(R.string.settings_manual_focus),
                checked = settings.manualFocus,
                onCheckedChange = { onChange(settings.copy(manualFocus = it)) },
            )
            if (settings.manualFocus) {
                Text(
                    text = stringResource(R.string.settings_focus_distance),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 0..10 diopters covers every consumer-phone sensor I've seen
                // (minimum focus distance reported as 10 = ~10cm). Camera2 clamps
                // out-of-range values silently. Stored as centidiopters (0..1000).
                var focus by remember(settings.manualFocusCentidiopters) {
                    mutableStateOf(settings.manualFocusCentidiopters.toFloat())
                }
                Slider(
                    value = focus,
                    onValueChange = { focus = it },
                    onValueChangeFinished = {
                        onChange(settings.copy(manualFocusCentidiopters = focus.toInt()))
                    },
                    valueRange = 0f..1000f,
                    steps = 19,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_focus_infinity),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.settings_focus_close),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            EnumDropdown(
                label = stringResource(R.string.settings_effect),
                options = CameraEffect.entries.toList(),
                selected = settings.effect,
                labelOf = {
                    when (it) {
                        CameraEffect.NONE       -> stringResource(R.string.effect_none)
                        CameraEffect.MONO       -> stringResource(R.string.effect_mono)
                        CameraEffect.NEGATIVE   -> stringResource(R.string.effect_negative)
                        CameraEffect.SEPIA      -> stringResource(R.string.effect_sepia)
                        CameraEffect.AQUA       -> stringResource(R.string.effect_aqua)
                        CameraEffect.SOLARIZE   -> stringResource(R.string.effect_solarize)
                        CameraEffect.POSTERIZE  -> stringResource(R.string.effect_posterize)
                        CameraEffect.BLACKBOARD -> stringResource(R.string.effect_blackboard)
                        CameraEffect.WHITEBOARD -> stringResource(R.string.effect_whiteboard)
                    }
                },
                onSelect = { onChange(settings.copy(effect = it)) },
            )

            Spacer(Modifier.height(12.dp))
            EnumDropdown(
                label = stringResource(R.string.settings_scene),
                options = SceneMode.entries.toList(),
                selected = settings.sceneMode,
                labelOf = {
                    when (it) {
                        SceneMode.DISABLED  -> stringResource(R.string.scene_disabled)
                        SceneMode.ACTION    -> stringResource(R.string.scene_action)
                        SceneMode.PORTRAIT  -> stringResource(R.string.scene_portrait)
                        SceneMode.LANDSCAPE -> stringResource(R.string.scene_landscape)
                        SceneMode.NIGHT     -> stringResource(R.string.scene_night)
                        SceneMode.SPORTS    -> stringResource(R.string.scene_sports)
                        SceneMode.THEATRE   -> stringResource(R.string.scene_theatre)
                        SceneMode.FIREWORKS -> stringResource(R.string.scene_fireworks)
                        SceneMode.BEACH     -> stringResource(R.string.scene_beach)
                        SceneMode.SNOW      -> stringResource(R.string.scene_snow)
                        SceneMode.SUNSET    -> stringResource(R.string.scene_sunset)
                    }
                },
                onSelect = { onChange(settings.copy(sceneMode = it)) },
            )

            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.settings_antibanding))
            SegmentedRow(
                options = AntiBanding.entries.toList(),
                selected = settings.antiBanding,
                enabled = true,
                labelOf = {
                    when (it) {
                        AntiBanding.AUTO -> stringResource(R.string.settings_ab_auto)
                        AntiBanding.HZ50 -> stringResource(R.string.settings_ab_50)
                        AntiBanding.HZ60 -> stringResource(R.string.settings_ab_60)
                        AntiBanding.OFF  -> stringResource(R.string.settings_ab_off)
                    }
                },
                onSelect = { onChange(settings.copy(antiBanding = it)) },
            )
            if (settings.protocol == Protocol.RTSP) {
                Text(
                    text = stringResource(R.string.settings_image_mjpeg_only_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            } // ← close Image card

            SettingsGroup(title = stringResource(R.string.settings_group_stream_output_audio)) {
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

            // Audio is available on both transports. MJPEG exposes it via the /audio
            // endpoint (AAC ADTS); RTSP carries it inside the RTSP session as the
            // second track.
            ToggleRow(
                title = stringResource(R.string.settings_audio),
                checked = settings.audioEnabled,
                enabled = !streaming,
                onCheckedChange = { onChange(settings.copy(audioEnabled = it)) },
            )
            if (settings.audioEnabled) {
                Spacer(Modifier.height(8.dp))
                    EnumDropdown(
                        label = stringResource(R.string.settings_mic_source),
                        options = MicSource.entries.toList(),
                        selected = settings.micSource,
                        enabled = !streaming,
                        labelOf = {
                            when (it) {
                                MicSource.CAMCORDER           -> stringResource(R.string.mic_camcorder)
                                MicSource.MIC                 -> stringResource(R.string.mic_mic)
                                MicSource.VOICE_RECOGNITION   -> stringResource(R.string.mic_voice_recognition)
                                MicSource.VOICE_COMMUNICATION -> stringResource(R.string.mic_voice_communication)
                                MicSource.UNPROCESSED         -> stringResource(R.string.mic_unprocessed)
                            }
                        },
                        onSelect = { onChange(settings.copy(micSource = it)) },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_audio_gain),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    var gain by remember(settings.audioGainDb) { mutableStateOf(settings.audioGainDb.toFloat()) }
                    Slider(
                        value = gain,
                        onValueChange = { gain = it },
                        // Live forward to the encoder is safe (gainLinear is @Volatile);
                        // also persists on slide-end so the value survives restarts.
                        onValueChangeFinished = { onChange(settings.copy(audioGainDb = gain.toInt())) },
                        valueRange = -24f..24f,
                        steps = 23,
                    )
                    Text(
                        text = stringResource(R.string.settings_audio_gain_summary, gain.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = stringResource(R.string.settings_noise_suppress),
                        checked = settings.noiseSuppress,
                        enabled = !streaming,
                        onCheckedChange = { onChange(settings.copy(noiseSuppress = it)) },
                    )
                ToggleRow(
                    title = stringResource(R.string.settings_echo_cancel),
                    checked = settings.echoCancel,
                    enabled = !streaming,
                    onCheckedChange = { onChange(settings.copy(echoCancel = it)) },
                )
            }

            ToggleRow(
                title = stringResource(R.string.settings_keep_screen_on),
                checked = settings.keepScreenOn,
                onCheckedChange = { onChange(settings.copy(keepScreenOn = it)) },
            )

            ToggleRow(
                title = stringResource(R.string.settings_blank_preview),
                checked = settings.blankPreview,
                onCheckedChange = { onChange(settings.copy(blankPreview = it)) },
            )

            if (settings.protocol == Protocol.MJPEG) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.settings_rotation_lock))
                SegmentedRow(
                    options = RotationLock.entries.toList(),
                    selected = settings.rotationLock,
                    enabled = true,
                    labelOf = {
                        when (it) {
                            RotationLock.AUTO -> stringResource(R.string.settings_rotation_auto)
                            RotationLock.PORTRAIT -> stringResource(R.string.settings_rotation_portrait)
                            RotationLock.LANDSCAPE_LEFT -> stringResource(R.string.settings_rotation_landscape_left)
                            RotationLock.LANDSCAPE_RIGHT -> stringResource(R.string.settings_rotation_landscape_right)
                            RotationLock.PORTRAIT_UPSIDE_DOWN -> stringResource(R.string.settings_rotation_portrait_down)
                        }
                    },
                    onSelect = { onChange(settings.copy(rotationLock = it)) },
                )
            }

            if (settings.protocol == Protocol.RTSP) {
                ToggleRow(
                    title = stringResource(R.string.settings_mjpeg_sidecar),
                    checked = settings.mjpegSidecar,
                    enabled = !streaming && settings.fps.value <= 30,
                    onCheckedChange = { onChange(settings.copy(mjpegSidecar = it)) },
                )
                if (settings.fps.value > 30) {
                    Text(
                        text = stringResource(R.string.settings_mjpeg_sidecar_highspeed_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                } else if (settings.mjpegSidecar) {
                    Text(
                        text = stringResource(R.string.settings_mjpeg_sidecar_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                }
                ToggleRow(
                    title = stringResource(R.string.settings_record_locally),
                    checked = settings.recordLocally,
                    enabled = !streaming,
                    onCheckedChange = { onChange(settings.copy(recordLocally = it)) },
                )
                if (settings.recordLocally) {
                    Text(
                        text = stringResource(R.string.settings_record_locally_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    SftpSection(settings = settings, onChange = onChange)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_rtsp_bitrate),
                    style = MaterialTheme.typography.bodyMedium,
                )
                var br by remember(settings.rtspBitrateKbps) { mutableStateOf(settings.rtspBitrateKbps.toFloat()) }
                Slider(
                    value = br,
                    onValueChange = { br = it },
                    onValueChangeFinished = { onChange(settings.copy(rtspBitrateKbps = br.toInt())) },
                    valueRange = 0f..20_000f,
                    steps = 19,
                )
                Text(
                    text = if (br.toInt() == 0) "Auto" else "${br.toInt()} kbps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_rtsp_bitrate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            } // ← close Stream output & audio card

            SettingsGroup(title = stringResource(R.string.settings_group_security_access)) {
            SectionLabel(stringResource(R.string.settings_security_section))
            var user by remember(settings.streamUsername) { mutableStateOf(settings.streamUsername) }
            OutlinedTextField(
                value = user,
                onValueChange = { newUser ->
                    user = newUser
                    // Persist whatever the user types; the repository / status JSON
                    // already coerce blank back to "Lenscast" so an empty box doesn't
                    // lock anyone out of a passworded URL.
                    onChange(settings.copy(streamUsername = newUser))
                },
                label = { Text(stringResource(R.string.settings_username)) },
                singleLine = true,
                enabled = !streaming,
                supportingText = { Text(stringResource(R.string.settings_username_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            var pwd by remember(settings.streamPassword) { mutableStateOf(settings.streamPassword) }
            OutlinedTextField(
                value = pwd,
                onValueChange = { newPwd ->
                    pwd = newPwd
                    onChange(settings.copy(streamPassword = newPwd))
                },
                label = { Text(stringResource(R.string.settings_password)) },
                singleLine = true,
                enabled = !streaming,
                supportingText = { Text(stringResource(R.string.settings_password_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = stringResource(R.string.settings_https),
                checked = settings.httpsEnabled,
                enabled = !streaming,
                onCheckedChange = { onChange(settings.copy(httpsEnabled = it)) },
            )
            if (settings.httpsEnabled) {
                val ctx = LocalContext.current
                val fingerprint = remember { guru.freberg.lenscast.net.TlsManager.forContext(ctx).fingerprintSha256() }
                Text(
                    text = stringResource(R.string.settings_https_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (fingerprint != null) {
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // Permission prompt — fired the first time the user picks a non-IGNORE option.
            // TelephonyMonitor handles missing permission gracefully (silently no-ops), so
            // a denial doesn't break anything, just makes the feature inert.
            val phonePermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { /* result ignored — service falls back if missing */ }
            EnumDropdown(
                label = stringResource(R.string.settings_call_behavior),
                options = CallBehavior.entries.toList(),
                selected = settings.callBehavior,
                labelOf = {
                    when (it) {
                        CallBehavior.IGNORE      -> stringResource(R.string.call_ignore)
                        CallBehavior.MUTE_STREAM -> stringResource(R.string.call_mute)
                        CallBehavior.DROP_CALL   -> stringResource(R.string.call_drop)
                    }
                },
                onSelect = { newBehavior ->
                    onChange(settings.copy(callBehavior = newBehavior))
                    if (newBehavior != CallBehavior.IGNORE) {
                        val needed = mutableListOf(android.Manifest.permission.READ_PHONE_STATE)
                        if (newBehavior == CallBehavior.DROP_CALL) {
                            needed += android.Manifest.permission.ANSWER_PHONE_CALLS
                        }
                        phonePermLauncher.launch(needed.toTypedArray())
                    }
                },
            )
            if (settings.callBehavior != CallBehavior.IGNORE) {
                Text(
                    text = stringResource(R.string.settings_call_behavior_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Live permission re-check — fires on every ON_RESUME so a grant via the
                // system dialog (or via System Settings) immediately clears the warning
                // without the user having to navigate back into this sheet.
                val ctx2 = LocalContext.current
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                val needed = remember(settings.callBehavior) {
                    buildList {
                        add(android.Manifest.permission.READ_PHONE_STATE)
                        if (settings.callBehavior == CallBehavior.DROP_CALL) {
                            add(android.Manifest.permission.ANSWER_PHONE_CALLS)
                        }
                    }
                }
                var missing by remember { mutableStateOf(neededMissing(ctx2, needed)) }
                DisposableEffect(lifecycle, needed) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            missing = neededMissing(ctx2, needed)
                        }
                    }
                    lifecycle.addObserver(obs)
                    onDispose { lifecycle.removeObserver(obs) }
                }
                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_call_permission_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { phonePermLauncher.launch(missing.toTypedArray()) }) {
                            Text(stringResource(R.string.permission_grant))
                        }
                    }
                }
            }

            } // ← close Security & access card

            SettingsGroup(title = stringResource(R.string.settings_group_automation)) {
            SectionLabel(stringResource(R.string.settings_automation_section))
            ToggleRow(
                title = stringResource(R.string.settings_auto_start),
                checked = settings.autoStart,
                onCheckedChange = { onChange(settings.copy(autoStart = it)) },
            )
            ToggleRow(
                title = stringResource(R.string.settings_start_on_boot),
                checked = settings.startOnBoot,
                onCheckedChange = { onChange(settings.copy(startOnBoot = it)) },
            )
            if (settings.startOnBoot) {
                Text(
                    text = stringResource(R.string.settings_start_on_boot_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            } // ← close Automation card

            SettingsGroup(title = stringResource(R.string.settings_group_web_control)) {
            SectionLabel(stringResource(R.string.settings_web_control_section))
            ToggleRow(
                title = stringResource(R.string.settings_web_control_enabled),
                checked = settings.webControlEnabled,
                onCheckedChange = { onChange(settings.copy(webControlEnabled = it)) },
            )
            if (settings.webControlEnabled) {
                PortField(
                    label = stringResource(R.string.settings_web_control_port),
                    port = settings.webControlPort,
                    enabled = true,
                    onPortChange = { onChange(settings.copy(webControlPort = it)) },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_persist_web),
                    checked = settings.persistentWebControl,
                    onCheckedChange = { onChange(settings.copy(persistentWebControl = it)) },
                )
                if (settings.persistentWebControl) {
                    Text(
                        text = stringResource(R.string.settings_persist_web_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            } // ← close Web control panel card

            SettingsGroup(title = stringResource(R.string.settings_group_presets)) {
                PresetsSection(settings, streaming, onChange)
            }

            if (SystemWebcam.isSupported(context)) {
                SettingsGroup(title = stringResource(R.string.settings_group_system_webcam)) {
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

            SettingsGroup(title = stringResource(R.string.settings_group_language)) {
                LanguageSection(settings, onChange)
            }

            SettingsGroup(title = stringResource(R.string.settings_group_backup)) {
                BackupSection(settings, streaming, onChange)
            }

            // Read versionName at runtime from PackageManager so the footer always
            // matches whatever Gradle compiled — no risk of the string drifting from
            // the apk metadata that updates / Play Store / Android Settings rely on.
            val versionName = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
            }
            if (versionName.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.settings_version, versionName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Collapsible "group" card around a subset of related Settings rows. Replaces the old
 * flat-scroll layout where every section was always visible — on a screen with 30+
 * settings, that was a wall of text. The group title sits in a clickable header with a
 * chevron that rotates on expand; tapping anywhere on the header toggles open/closed.
 *
 * Cards that the user touches most (Streaming basics, Image) default expanded; the
 * rest start collapsed. State persists via [rememberSaveable] so it survives rotation
 * and recompositions but resets when the sheet is dismissed.
 */
@Composable
private fun SettingsGroup(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settings-group-chevron",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(
                    start = 16.dp, end = 16.dp, bottom = 14.dp, top = 0.dp,
                ),
            ) {
                content()
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

/**
 * Picker row backed by [ExposedDropdownMenuBox] — same role as [SegmentedRow] but for
 * long option lists that would otherwise squash labels into single-character columns.
 * Used for white balance (6), effect (9) and scene mode (11).
 */
@Composable
private fun <T> EnumDropdown(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = labelOf(selected)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it else expanded = false },
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(labelOf(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
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
private fun PresetsSection(
    settings: Settings,
    streaming: Boolean,
    onChange: (Settings) -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_presets_section))
    var newName by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it.take(40) },
            label = { Text(stringResource(R.string.settings_preset_name_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = newName.isNotBlank() && !streaming,
            onClick = {
                val preset = Preset(
                    name = newName.trim(),
                    protocol = settings.protocol,
                    resolution = settings.resolution,
                    fps = settings.fps,
                    lens = settings.lens,
                )
                onChange(settings.copy(presets = settings.presets + preset))
                newName = ""
            },
        ) { Text(stringResource(R.string.settings_preset_save)) }
    }
    Spacer(Modifier.height(8.dp))
    settings.presets.forEachIndexed { index, preset ->
        val lensLabel = if (preset.lens == Lens.BACK) {
            stringResource(R.string.settings_preset_lens_back)
        } else {
            stringResource(R.string.settings_preset_lens_front)
        }
        val protoLabel = if (preset.protocol == Protocol.MJPEG) "MJPEG" else "RTSP"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(
                        R.string.settings_preset_summary,
                        protoLabel, preset.resolution.label, preset.fps.value, lensLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                enabled = !streaming,
                onClick = {
                    onChange(settings.copy(
                        protocol = preset.protocol,
                        resolution = preset.resolution,
                        fps = preset.fps,
                        lens = preset.lens,
                    ))
                },
            ) { Text(stringResource(R.string.settings_preset_apply)) }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = {
                    onChange(settings.copy(presets = settings.presets.toMutableList().also { it.removeAt(index) }))
                },
            ) { Text(stringResource(R.string.settings_preset_delete)) }
        }
    }
}

@Composable
private fun LanguageSection(settings: Settings, onChange: (Settings) -> Unit) {
    SectionLabel(stringResource(R.string.settings_language_section))
    // Trio: System (empty tag), English ("en"), Norsk ("nb"). Each persists the BCP-47 tag
    // into Settings.languageTag; the Application observer applies it via AppCompatDelegate.
    val options = listOf("" to stringResource(R.string.settings_language_system),
        "en" to stringResource(R.string.settings_language_en),
        "nb" to stringResource(R.string.settings_language_nb))
    EnumDropdown(
        label = stringResource(R.string.settings_language),
        options = options.map { it.first },
        selected = settings.languageTag,
        labelOf = { tag -> options.firstOrNull { it.first == tag }?.second ?: tag },
        onSelect = { onChange(settings.copy(languageTag = it)) },
    )
    Text(
        text = stringResource(R.string.settings_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SrtSection(settings: Settings, onChange: (Settings) -> Unit, streaming: Boolean) {
    SectionLabel(stringResource(R.string.settings_srt_section))
    EnumDropdown(
        label = stringResource(R.string.settings_srt_mode),
        options = guru.freberg.lenscast.prefs.SrtMode.entries.toList(),
        selected = settings.srtMode,
        labelOf = {
            when (it) {
                guru.freberg.lenscast.prefs.SrtMode.CALLER   -> stringResource(R.string.settings_srt_mode_caller)
                guru.freberg.lenscast.prefs.SrtMode.LISTENER -> stringResource(R.string.settings_srt_mode_listener)
            }
        },
        onSelect = { onChange(settings.copy(srtMode = it)) },
    )
    Text(
        text = stringResource(
            if (settings.srtMode == guru.freberg.lenscast.prefs.SrtMode.CALLER)
                R.string.settings_srt_mode_caller_hint
            else R.string.settings_srt_mode_listener_hint
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    if (settings.srtMode == guru.freberg.lenscast.prefs.SrtMode.CALLER) {
        OutlinedTextField(
            value = settings.srtHost,
            onValueChange = { onChange(settings.copy(srtHost = it)) },
            label = { Text(stringResource(R.string.settings_srt_host)) },
            singleLine = true,
            enabled = !streaming,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
    OutlinedTextField(
        value = settings.srtPort.toString(),
        onValueChange = { onChange(settings.copy(srtPort = it.toIntOrNull() ?: settings.srtPort)) },
        label = { Text(stringResource(R.string.settings_srt_port)) },
        singleLine = true,
        enabled = !streaming,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = settings.srtPassphrase,
        onValueChange = { onChange(settings.copy(srtPassphrase = it)) },
        label = { Text(stringResource(R.string.settings_srt_passphrase)) },
        singleLine = true,
        enabled = !streaming,
        supportingText = { Text(stringResource(R.string.settings_srt_passphrase_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = settings.srtLatencyMs.toString(),
        onValueChange = { onChange(settings.copy(srtLatencyMs = it.toIntOrNull() ?: settings.srtLatencyMs)) },
        label = { Text(stringResource(R.string.settings_srt_latency)) },
        singleLine = true,
        enabled = !streaming,
        supportingText = { Text(stringResource(R.string.settings_srt_latency_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = settings.srtStreamId,
        onValueChange = { onChange(settings.copy(srtStreamId = it)) },
        label = { Text(stringResource(R.string.settings_srt_streamid)) },
        singleLine = true,
        enabled = !streaming,
        supportingText = { Text(stringResource(R.string.settings_srt_streamid_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SftpSection(settings: Settings, onChange: (Settings) -> Unit) {
    Spacer(Modifier.height(8.dp))
    SectionLabel(stringResource(R.string.settings_sftp_section))
    ToggleRow(
        title = stringResource(R.string.settings_sftp_enabled),
        checked = settings.sftpEnabled,
        onCheckedChange = { onChange(settings.copy(sftpEnabled = it)) },
    )
    if (settings.sftpEnabled) {
        Text(
            text = stringResource(R.string.settings_sftp_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = settings.sftpHost,
            onValueChange = { onChange(settings.copy(sftpHost = it)) },
            label = { Text(stringResource(R.string.settings_sftp_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.sftpPort.toString(),
            onValueChange = { onChange(settings.copy(sftpPort = it.toIntOrNull() ?: settings.sftpPort)) },
            label = { Text(stringResource(R.string.settings_sftp_port)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.sftpUser,
            onValueChange = { onChange(settings.copy(sftpUser = it)) },
            label = { Text(stringResource(R.string.settings_sftp_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.sftpPassword,
            onValueChange = { onChange(settings.copy(sftpPassword = it)) },
            label = { Text(stringResource(R.string.settings_sftp_password)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.sftpRemoteDir,
            onValueChange = { onChange(settings.copy(sftpRemoteDir = it)) },
            label = { Text(stringResource(R.string.settings_sftp_dir)) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.settings_sftp_dir_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.sftpHostKeyFingerprint,
            onValueChange = { onChange(settings.copy(sftpHostKeyFingerprint = it)) },
            label = { Text(stringResource(R.string.settings_sftp_fingerprint)) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.settings_sftp_fingerprint_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BackupSection(
    settings: Settings,
    streaming: Boolean,
    onChange: (Settings) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        it.write(SettingsCodec.toJson(settings).toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (_: Throwable) { false }
            }
            android.widget.Toast.makeText(
                ctx,
                ctx.getString(if (ok) R.string.settings_export_done else R.string.settings_import_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8)
                    text?.let { SettingsCodec.fromJson(it) }
                } catch (_: Throwable) { null }
            }
            if (parsed != null) {
                onChange(parsed)
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_import_done), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_import_failed), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    SectionLabel(stringResource(R.string.settings_backup_section))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { exporter.launch("lenscast-settings.json") },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.settings_export)) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            enabled = !streaming,
            onClick = { importer.launch(arrayOf("application/json")) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.settings_import)) }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { shareDiagnostics(ctx) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_share_diagnostics)) }
}

/** Filter `wanted` down to the entries the user hasn't granted yet. */
private fun neededMissing(ctx: android.content.Context, wanted: List<String>): List<String> =
    wanted.filter {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

private fun shareDiagnostics(ctx: android.content.Context) {
    val file = guru.freberg.lenscast.system.CrashReporter.reportFile(ctx)
    if (!file.exists()) {
        android.widget.Toast.makeText(
            ctx, ctx.getString(R.string.settings_share_diagnostics_empty),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        ctx, "guru.freberg.lenscast.fileprovider", file,
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(intent, ctx.getString(R.string.share_diagnostics_chooser)))
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

