package guru.freberg.lenscast.streaming

import android.util.Log
import guru.freberg.lenscast.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLContext

/**
 * HTTP control-panel server. Runs on its own port (independent of MJPEG / RTSP) for the
 * entire lifetime of [StreamingService], so the user can start, stop, and tune a stream
 * from any browser on the LAN regardless of which protocol is active or whether a stream
 * is running at all.
 *
 * Endpoints:
 *  - `GET  /`                  — adaptive HTML page (see [renderLandingHtml])
 *  - `GET  /status`            — JSON state for the page's poll
 *  - `POST /control/start`     — kicks off the stream with the saved settings
 *  - `POST /control/stop`      — stops it
 *  - `POST /control/lens`      — switch back/front
 *  - `POST /control/torch`     — toggle torch
 *  - `POST /control/snapshot`  — save the latest JPEG (MJPEG-only)
 *  - `POST /control/zoom?dir=in|out`
 *  - `POST /control/ev?dir=up|down`
 *  - `POST /control/mirror`
 *  - `POST /control/af`         — toggle continuous-AF
 *  - `POST /control/quality?v=10..95` — MJPEG only
 *  - `POST /control/resolution?v=720p|...`
 *  - `POST /control/fps?v=30|60|...`
 *  - `POST   /webrtc/offer`     — bespoke WebRTC handshake (offer SDP in, answer SDP out)
 *  - `POST   /whep`             — WHEP egress: offer SDP in, 201 + answer + `Location` out
 *  - `DELETE /whep/<id>`        — WHEP resource teardown
 *  - `GET    /webrtc/view`      — standalone full-screen WebRTC viewer page
 *
 * The page's JS shows/hides protocol-specific blocks based on the `protocol` field in
 * `/status` — RTSP-only fields don't appear when MJPEG is active and vice versa.
 */
class WebControlServer(
    /** Used to resolve localized strings via Context.getString so the panel follows the
     *  per-app language preference. Built fresh at each page render so a mid-session
     *  language switch via the in-app picker or system Settings → Apps → Lenscast → Language
     *  shows up on the next page load. */
    private val context: android.content.Context,
    private val port: Int,
    private val control: MjpegControl,
    /** Phone-side port the MJPEG server listens on. The page builds the URL from this. */
    private val mjpegPort: () -> Int,
    private val rtspPort: () -> Int,
    private val sslContext: SSLContext? = null,
    /** True when MJPEG is also serving HTTPS; the page uses https:// for the preview URL. */
    private val mjpegIsHttps: () -> Boolean = { false },
    /** App version string (e.g. "1.0.1") — rendered in the page footer. */
    private val appVersion: () -> String = { "" },
) {

    /** Snapshot of every user-visible string on the page, resolved against the current
     *  app locale. One instance built per page request — cheap (a few dozen string lookups)
     *  and ensures locale changes propagate without restarting the server. */
    private data class WebI18n(
        val title: String, val stateIdle: String, val heroTitle: String, val idlePick: String,
        val protoMjpegDesc: String, val protoRtspDesc: String, val protoWebrtcDesc: String,
        val start: String, val rtspLiveTitle: String, val rtspLiveNote: String, val webrtcViewLink: String,
        val statusH: String, val statAudioPeak: String, val statConnectedClients: String,
        val quickH: String, val btnSwitchCamera: String, val btnTorch: String, val btnMirror: String,
        val btnContinuousAf: String, val btnSnapshot: String, val btnStopStreaming: String,
        val btnSpeedtest: String, val lblJpegQuality: String, val hintMjpegOnly: String,
        val settingsH: String, val tabCamera: String, val tabImage: String, val tabAudio: String,
        val tabStream: String, val tabUx: String, val tabSystem: String,
        val lblLens: String, val lensBack: String, val lensFront: String, val lblResolution: String,
        val hintResFromCaps: String, val lblFps: String, val hintFpsRange: String,
        val lblRotationLock: String, val hintRotationLock: String,
        val rotAuto: String, val rotPortrait: String, val rotLandscapeLeft: String,
        val rotLandscapeRight: String, val rotPortraitUpside: String,
        val lblMirror: String, val lblContinuousAf: String, val lblWatermark: String, val hintWatermark: String,
        val lblExposure: String, val lblWhiteBalance: String,
        val wbAuto: String, val wbIncandescent: String, val wbFluorescent: String, val wbDaylight: String,
        val wbCloudy: String, val wbShade: String,
        val lblAntibanding: String, val abAuto: String, val abOff: String,
        val lblEffect: String, val effectNone: String, val effectMono: String, val effectNegative: String,
        val effectSepia: String, val effectAqua: String, val effectSolarize: String,
        val effectPosterize: String, val effectBlackboard: String, val effectWhiteboard: String,
        val lblScene: String, val sceneOff: String, val sceneAction: String, val scenePortrait: String,
        val sceneLandscape: String, val sceneNight: String, val sceneSports: String, val sceneTheatre: String,
        val sceneFireworks: String, val sceneBeach: String, val sceneSnow: String, val sceneSunset: String,
        val lblManualFocus: String, val lblFocusDistance: String, val hintFocusZeroInf: String,
        val lblManualExposure: String, val hintManualExpUnsupported: String, val lblIso: String,
        val lblShutter: String,
        val lblStreamMic: String, val lblMicSource: String, val micCamcorder: String, val micDefault: String,
        val micVoiceRecog: String, val micVoiceComm: String, val micUnprocessed: String,
        val lblGain: String, val lblNoiseSuppress: String, val lblEchoCancel: String,
        val hintMjpegOutput: String, val lblRtspBitrateCap: String, val hintRtspBitrateAuto: String,
        val lblMjpegSidecar: String, val hintMjpegSidecar: String, val lblRecordMp4: String,
        val hintRecordRtspOnly: String,
        val lblSftpAutoUpload: String, val hintSftpAutoUpload: String,
        val lblSftpHost: String, val lblSftpPort: String, val lblSftpUser: String, val lblSftpPassword: String,
        val lblSftpRemoteDir: String, val hintSftpRemoteDir: String,
        val lblSftpFingerprint: String, val hintSftpFingerprint: String,
        val lblSftpStatus: String, val btnSftpRetry: String,
        val lblKeepScreenOn: String, val lblBlankPreview: String, val hintBatterySaver: String,
        val lblAutoStart: String, val lblStartOnBoot: String,
        val lblMjpegPort: String, val lblRtspPort: String, val lblHttps: String, val hintHttps: String,
        val lblCertFp: String, val lblStreamUser: String, val hintStreamUser: String,
        val lblStreamPass: String, val hintStreamPass: String,
        val lblCallBehavior: String, val hintCallBehavior: String,
        val callIgnore: String, val callMute: String, val callDrop: String,
        val lblPersistentWeb: String, val hintPersistentWeb: String,
        val lblExport: String, val btnDownloadJson: String, val lblImport: String,
        val hintImportReplaces: String, val phImportJson: String, val btnImport: String,
        val footerPanel: String,
        val streamLockedHint: String,
        // WebRTC viewer
        val viewerTitle: String, val viewerH1: String, val viewerNote: String, val viewerBtnConnect: String,
    )

    private fun buildI18n(): WebI18n {
        // The Service's Configuration may lag the app locale change; pull a fresh
        // localized Context so getString reflects the current LocaleManager state.
        val cfg = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.getDefault())
        }
        val c = context.createConfigurationContext(cfg)
        fun s(id: Int) = c.getString(id)
        return WebI18n(
            title = s(R.string.web_title), stateIdle = s(R.string.web_state_idle),
            heroTitle = s(R.string.web_hero_title), idlePick = s(R.string.web_idle_pick),
            protoMjpegDesc = s(R.string.web_proto_mjpeg_desc),
            protoRtspDesc = s(R.string.web_proto_rtsp_desc),
            protoWebrtcDesc = s(R.string.web_proto_webrtc_desc),
            start = s(R.string.web_start),
            rtspLiveTitle = s(R.string.web_rtsp_live_title),
            rtspLiveNote = s(R.string.web_rtsp_live_note),
            webrtcViewLink = s(R.string.web_webrtc_view_link),
            statusH = s(R.string.web_status_h),
            statAudioPeak = s(R.string.web_stat_audio_peak),
            statConnectedClients = s(R.string.web_stat_connected_clients),
            quickH = s(R.string.web_quick_h),
            btnSwitchCamera = s(R.string.web_btn_switch_camera),
            btnTorch = s(R.string.web_btn_torch), btnMirror = s(R.string.web_btn_mirror),
            btnContinuousAf = s(R.string.web_btn_continuous_af),
            btnSnapshot = s(R.string.web_btn_snapshot),
            btnStopStreaming = s(R.string.web_btn_stop_streaming),
            btnSpeedtest = s(R.string.web_btn_speedtest),
            lblJpegQuality = s(R.string.web_lbl_jpeg_quality),
            hintMjpegOnly = s(R.string.web_hint_mjpeg_only),
            settingsH = s(R.string.web_settings_h),
            tabCamera = s(R.string.web_tab_camera), tabImage = s(R.string.web_tab_image),
            tabAudio = s(R.string.web_tab_audio), tabStream = s(R.string.web_tab_stream),
            tabUx = s(R.string.web_tab_ux), tabSystem = s(R.string.web_tab_system),
            lblLens = s(R.string.web_lbl_lens), lensBack = s(R.string.web_lens_back),
            lensFront = s(R.string.web_lens_front),
            lblResolution = s(R.string.web_lbl_resolution),
            hintResFromCaps = s(R.string.web_hint_res_from_caps),
            lblFps = s(R.string.web_lbl_fps), hintFpsRange = s(R.string.web_hint_fps_range),
            lblRotationLock = s(R.string.web_lbl_rotation_lock),
            hintRotationLock = s(R.string.web_hint_rotation_lock),
            rotAuto = s(R.string.web_rotation_auto), rotPortrait = s(R.string.web_rotation_portrait),
            rotLandscapeLeft = s(R.string.web_rotation_landscape_left),
            rotLandscapeRight = s(R.string.web_rotation_landscape_right),
            rotPortraitUpside = s(R.string.web_rotation_portrait_upside),
            lblMirror = s(R.string.web_lbl_mirror),
            lblContinuousAf = s(R.string.web_lbl_continuous_af),
            lblWatermark = s(R.string.web_lbl_watermark),
            hintWatermark = s(R.string.web_hint_watermark),
            lblExposure = s(R.string.web_lbl_exposure),
            lblWhiteBalance = s(R.string.web_lbl_white_balance),
            wbAuto = s(R.string.web_rotation_auto), // re-uses "Auto"
            wbIncandescent = s(R.string.web_wb_incandescent),
            wbFluorescent = s(R.string.web_wb_fluorescent),
            wbDaylight = s(R.string.web_wb_daylight), wbCloudy = s(R.string.web_wb_cloudy),
            wbShade = s(R.string.web_wb_shade),
            lblAntibanding = s(R.string.web_lbl_antibanding),
            abAuto = s(R.string.web_rotation_auto), // re-uses "Auto"
            abOff = s(R.string.web_ab_off),
            lblEffect = s(R.string.web_lbl_effect), effectNone = s(R.string.web_effect_none),
            effectMono = s(R.string.web_effect_mono), effectNegative = s(R.string.web_effect_negative),
            effectSepia = s(R.string.web_effect_sepia), effectAqua = s(R.string.web_effect_aqua),
            effectSolarize = s(R.string.web_effect_solarize),
            effectPosterize = s(R.string.web_effect_posterize),
            effectBlackboard = s(R.string.web_effect_blackboard),
            effectWhiteboard = s(R.string.web_effect_whiteboard),
            lblScene = s(R.string.web_lbl_scene), sceneOff = s(R.string.web_scene_off),
            sceneAction = s(R.string.web_scene_action), scenePortrait = s(R.string.web_scene_portrait),
            sceneLandscape = s(R.string.web_scene_landscape),
            sceneNight = s(R.string.web_scene_night), sceneSports = s(R.string.web_scene_sports),
            sceneTheatre = s(R.string.web_scene_theatre),
            sceneFireworks = s(R.string.web_scene_fireworks),
            sceneBeach = s(R.string.web_scene_beach), sceneSnow = s(R.string.web_scene_snow),
            sceneSunset = s(R.string.web_scene_sunset),
            lblManualFocus = s(R.string.web_lbl_manual_focus),
            lblFocusDistance = s(R.string.web_lbl_focus_distance),
            hintFocusZeroInf = s(R.string.web_hint_focus_zero_inf),
            lblManualExposure = s(R.string.web_lbl_manual_exposure),
            hintManualExpUnsupported = s(R.string.web_hint_manual_exp_unsupported),
            lblIso = s(R.string.web_lbl_iso), lblShutter = s(R.string.web_lbl_shutter),
            lblStreamMic = s(R.string.web_lbl_stream_mic),
            lblMicSource = s(R.string.web_lbl_mic_source),
            micCamcorder = s(R.string.web_mic_camcorder), micDefault = s(R.string.web_mic_default),
            micVoiceRecog = s(R.string.web_mic_voice_recog),
            micVoiceComm = s(R.string.web_mic_voice_comm),
            micUnprocessed = s(R.string.web_mic_unprocessed),
            lblGain = s(R.string.web_lbl_gain),
            lblNoiseSuppress = s(R.string.web_lbl_noise_suppress),
            lblEchoCancel = s(R.string.web_lbl_echo_cancel),
            hintMjpegOutput = s(R.string.web_hint_mjpeg_output),
            lblRtspBitrateCap = s(R.string.web_lbl_rtsp_bitrate_cap),
            hintRtspBitrateAuto = s(R.string.web_hint_rtsp_bitrate_auto),
            lblMjpegSidecar = s(R.string.web_lbl_mjpeg_sidecar),
            hintMjpegSidecar = s(R.string.web_hint_mjpeg_sidecar),
            lblRecordMp4 = s(R.string.web_lbl_record_mp4),
            hintRecordRtspOnly = s(R.string.web_hint_record_rtsp_only),
            lblSftpAutoUpload = s(R.string.web_lbl_sftp_auto_upload),
            hintSftpAutoUpload = s(R.string.web_hint_sftp_auto_upload),
            lblSftpHost = s(R.string.web_lbl_sftp_host), lblSftpPort = s(R.string.web_lbl_sftp_port),
            lblSftpUser = s(R.string.web_lbl_sftp_user),
            lblSftpPassword = s(R.string.web_lbl_sftp_password),
            lblSftpRemoteDir = s(R.string.web_lbl_sftp_remote_dir),
            hintSftpRemoteDir = s(R.string.web_hint_sftp_remote_dir),
            lblSftpFingerprint = s(R.string.web_lbl_sftp_fingerprint),
            hintSftpFingerprint = s(R.string.web_hint_sftp_fingerprint),
            lblSftpStatus = s(R.string.web_lbl_sftp_status),
            btnSftpRetry = s(R.string.web_btn_sftp_retry),
            lblKeepScreenOn = s(R.string.web_lbl_keep_screen_on),
            lblBlankPreview = s(R.string.web_lbl_blank_preview),
            hintBatterySaver = s(R.string.web_hint_battery_saver),
            lblAutoStart = s(R.string.web_lbl_auto_start),
            lblStartOnBoot = s(R.string.web_lbl_start_on_boot),
            lblMjpegPort = s(R.string.web_lbl_mjpeg_port),
            lblRtspPort = s(R.string.web_lbl_rtsp_port),
            lblHttps = s(R.string.web_lbl_https), hintHttps = s(R.string.web_hint_https),
            lblCertFp = s(R.string.web_lbl_cert_fp),
            lblStreamUser = s(R.string.web_lbl_stream_user),
            hintStreamUser = s(R.string.web_hint_stream_user),
            lblStreamPass = s(R.string.web_lbl_stream_pass),
            hintStreamPass = s(R.string.web_hint_stream_pass),
            lblCallBehavior = s(R.string.web_lbl_call_behavior),
            hintCallBehavior = s(R.string.web_hint_call_behavior),
            callIgnore = s(R.string.web_call_ignore), callMute = s(R.string.web_call_mute),
            callDrop = s(R.string.web_call_drop),
            lblPersistentWeb = s(R.string.web_lbl_persistent_web),
            hintPersistentWeb = s(R.string.web_hint_persistent_web),
            lblExport = s(R.string.web_lbl_export),
            btnDownloadJson = s(R.string.web_btn_download_json),
            lblImport = s(R.string.web_lbl_import),
            hintImportReplaces = s(R.string.web_hint_import_replaces),
            phImportJson = s(R.string.web_ph_import_json),
            btnImport = s(R.string.web_btn_import),
            footerPanel = s(R.string.web_footer_panel),
            streamLockedHint = s(R.string.web_stream_locked_hint),
            viewerTitle = s(R.string.web_viewer_title), viewerH1 = s(R.string.web_viewer_h1),
            viewerNote = s(R.string.web_viewer_note),
            viewerBtnConnect = s(R.string.web_viewer_btn_connect),
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        acceptJob = scope.launchHttpAcceptLoop(
            port, sslContext, TAG, "Web control",
            isRunning = { running },
            onBound = { server = it },
            handle = ::handle,
        )
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return@withContext
            var contentLength = 0
            var contentType = ""
            // Drain headers, but capture Content-Length (so we can read the body when the
            // route needs one) and Content-Type (so the WHEP endpoint can reject non-SDP).
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                } else if (line.startsWith("Content-Type:", ignoreCase = true)) {
                    contentType = line.substringAfter(':').trim()
                }
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val target = parts.getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            val pathOnly = target.substringBefore('?')
            val query = if ('?' in target) target.substringAfter('?') else ""

            // Lazy-read the body so we don't stall on routes that don't need it.
            fun readBody(): String {
                if (contentLength <= 0) return ""
                val buf = CharArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = reader.read(buf, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                return String(buf, 0, off)
            }

            when {
                pathOnly == "/status"             && method == "GET"  -> writeStatus(out)
                pathOnly == "/export"             && method == "GET"  -> writeExport(out)
                pathOnly == "/import"             && method == "POST" -> handleControl(out) {
                    val ok = control.importSettingsJson(readBody())
                    if (!ok) error("import rejected: invalid JSON or stream is running")
                }
                pathOnly == "/control/start"      && method == "POST" -> handleControl(out) {
                    val ok = control.startStream()
                    if (!ok) error("cannot start — already streaming or service not ready")
                }
                pathOnly == "/control/stop"       && method == "POST" -> handleControl(out) { control.stopStream() }
                pathOnly == "/control/torch"      && method == "POST" -> handleControl(out) { control.toggleTorch() }
                pathOnly == "/control/lens"       && method == "POST" -> handleControl(out) { control.switchLens() }
                pathOnly == "/control/snapshot"   && method == "POST" -> handleControl(out) {
                    val ok = control.snapshot()
                    if (!ok) error("no frame yet")
                }
                pathOnly == "/control/zoom"       && method == "POST" -> handleControl(out) {
                    val factor = if (queryParam(query, "dir") == "out") 1f / 1.25f else 1.25f
                    control.zoomBy(factor)
                }
                pathOnly == "/control/ev"         && method == "POST" -> handleControl(out) {
                    val delta = if (queryParam(query, "dir") == "down") -1 else 1
                    control.nudgeExposure(delta)
                }
                pathOnly == "/control/mirror"     && method == "POST" -> handleControl(out) { control.toggleMirror() }
                pathOnly == "/control/af"         && method == "POST" -> handleControl(out) { control.toggleContinuousAf() }
                pathOnly == "/control/quality"    && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v")?.toIntOrNull() ?: error("missing v=<10..95>")
                    control.setJpegQuality(v)
                }
                pathOnly == "/control/resolution" && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v") ?: error("missing v=<label>")
                    val ok = control.setResolutionLabel(v)
                    if (!ok) error("unknown resolution: $v")
                }
                pathOnly == "/control/fps"        && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v")?.toIntOrNull() ?: error("missing v=<int>")
                    val ok = control.setFpsValue(v)
                    if (!ok) error("unsupported fps: $v")
                }
                pathOnly == "/control/protocol"   && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v") ?: error("missing v=mjpeg|rtsp")
                    val ok = control.setProtocol(v)
                    if (!ok) error("cannot set protocol to '$v' (stream may be running)")
                }
                pathOnly == "/control/setting"    && method == "POST" -> handleControl(out) {
                    val k = queryParam(query, "key") ?: error("missing key=")
                    val v = queryParam(query, "v")   ?: error("missing v=")
                    val ok = control.updateSetting(k, v)
                    // Most settings updates that fail mid-stream do so because the key is on
                    // the stream-locked list (audio toggle, mic source, ports, …). Tell the
                    // user what to do instead of the generic "rejected" wording — the
                    // toast on the web side just relays this string verbatim.
                    if (!ok) {
                        val isStreaming = control.statusJson().contains("\"state\":\"streaming\"")
                        if (isStreaming) error("Stop the stream first to change $k.")
                        else error("Setting $k = $v is invalid.")
                    }
                }
                pathOnly == "/control/kick"       && method == "POST" -> handleControl(out) {
                    val r = queryParam(query, "remote")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        ?: error("missing remote=")
                    val ok = control.kickClient(r)
                    if (!ok) error("no client matches $r")
                }
                pathOnly == "/webrtc/offer"       && method == "POST" -> {
                    val offerSdp = readBody()
                    val peerHostPort = (socket.remoteSocketAddress as? java.net.InetSocketAddress)
                        ?.let { "${it.address.hostAddress}:${it.port}" } ?: "?:?"
                    val answer = control.webRtcAnswer(offerSdp, peerHostPort)
                    if (answer == null) {
                        val body = "WebRTC not running — start the stream in WebRTC mode first.".toByteArray()
                        out.write(("HTTP/1.0 503 Service Unavailable\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        out.write(body); out.flush()
                    } else {
                        val body = answer.toByteArray(Charsets.UTF_8)
                        out.write(("HTTP/1.0 200 OK\r\n" +
                            "Content-Type: application/sdp\r\n" +
                            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        out.write(body); out.flush()
                    }
                }
                pathOnly == "/webrtc/view"        && method == "GET"  -> writeWebRtcViewer(out)
                // ── WHEP (WebRTC-HTTP Egress Protocol, draft-ietf-wish-whep) ──────────────
                // Standard egress signalling so OBS / GStreamer / browser WHEP players can
                // pull the stream without our bespoke /webrtc/offer dance. POST an SDP offer,
                // get a 201 with the answer + a Location resource to DELETE when done.
                pathOnly == "/whep"               && method == "OPTIONS" ->
                    writeCorsPreflight(out, "POST, OPTIONS")
                pathOnly == "/whep"               && method == "POST" -> {
                    if (!contentType.startsWith("application/sdp", ignoreCase = true)) {
                        writePlain(out, "415 Unsupported Media Type",
                            "WHEP requires Content-Type: application/sdp")
                    } else {
                        val offerSdp = readBody()
                        val peerHostPort = (socket.remoteSocketAddress as? java.net.InetSocketAddress)
                            ?.let { "${it.address.hostAddress}:${it.port}" } ?: "?:?"
                        val session = control.webRtcWhepCreate(offerSdp, peerHostPort)
                        if (session == null) {
                            writePlain(out, "503 Service Unavailable",
                                "WebRTC not running — start the stream in WebRTC mode first.")
                        } else {
                            val (id, answer) = session
                            val body = answer.toByteArray(Charsets.UTF_8)
                            // Relative Location is a valid URI reference (RFC 7231 §7.1.2);
                            // WHEP players resolve it against the request URL. Expose-Headers
                            // lets browser fetch() read it under CORS.
                            out.write(("HTTP/1.0 201 Created\r\n" +
                                "Content-Type: application/sdp\r\n" +
                                "Location: /whep/$id\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Access-Control-Expose-Headers: Location\r\n" +
                                "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            out.write(body); out.flush()
                        }
                    }
                }
                pathOnly.startsWith("/whep/")     && method == "OPTIONS" ->
                    writeCorsPreflight(out, "DELETE, PATCH, OPTIONS")
                pathOnly.startsWith("/whep/")     && method == "DELETE" -> {
                    val id = pathOnly.removePrefix("/whep/")
                    val ok = control.webRtcWhepDelete(id)
                    if (ok) writePlain(out, "200 OK", "")
                    else writePlain(out, "404 Not Found", "no such WHEP session")
                }
                pathOnly.startsWith("/whep/")     && method == "PATCH" ->
                    // Trickle ICE / ICE restart is optional in WHEP. We gather every candidate
                    // before returning the answer, so there's nothing to patch.
                    writePlain(out, "405 Method Not Allowed", "trickle ICE not supported")
                pathOnly == "/speedtest"          && method == "GET"  -> writeSpeedTest(out, query)
                pathOnly == "/sftp/status"        && method == "GET"  -> {
                    val body = control.sftpStatusJson().toByteArray(Charsets.UTF_8)
                    out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n" +
                        "Cache-Control: no-cache\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    out.write(body); out.flush()
                }
                pathOnly == "/sftp/retry"         && method == "POST" -> handleControl(out) {
                    val ok = control.retryLastSftpUpload()
                    if (!ok) error("no recent recording to upload")
                }
                else -> writeLanding(out)
            }
        } catch (_: SocketException) { /* normal */ }
        catch (t: Throwable) { Log.w(TAG, "Client handler error: ${t.message}") }
        finally { try { socket.close() } catch (_: Throwable) {} }
    }

    private fun queryParam(query: String, key: String): String? {
        if (query.isEmpty()) return null
        for (kv in query.split('&')) {
            val eq = kv.indexOf('=')
            if (eq < 0) continue
            if (kv.substring(0, eq) == key) return kv.substring(eq + 1)
        }
        return null
    }

    private fun handleControl(out: OutputStream, action: () -> Unit) {
        var body = "ok".toByteArray(Charsets.US_ASCII)
        var status = "200 OK"
        try {
            action()
        } catch (t: Throwable) {
            body = (t.message ?: "error").toByteArray(Charsets.UTF_8)
            status = "503 Service Unavailable"
        }
        val header = ("HTTP/1.0 $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeWebRtcViewer(out: OutputStream) {
        val html = renderWebRtcViewerHtml().toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.0 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Length: ${html.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
        out.write(html); out.flush()
    }

    /**
     * Sinks `bytes=N` (default 10 MB, capped at 100 MB) of zeros so the browser can measure
     * raw LAN throughput. Lives on the web control port (always running) instead of the
     * MJPEG port (only up when MJPEG / sidecar is active) — the previous wiring failed
     * silently for users on RTSP-only or WebRTC because their MJPEG server was null.
     */
    private fun writeSpeedTest(out: OutputStream, query: String) {
        val requested = queryParam(query, "bytes")?.toLongOrNull() ?: 10_000_000L
        val total = requested.coerceIn(1_000L, 100_000_000L)
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: $total\r\n\r\n").toByteArray(Charsets.US_ASCII)
        out.write(header)
        val chunk = ByteArray(64 * 1024)
        var remaining = total
        while (remaining > 0) {
            val n = minOf(chunk.size.toLong(), remaining).toInt()
            out.write(chunk, 0, n)
            remaining -= n
        }
        out.flush()
    }

    /** Same handshake as the embedded panel preview, but as a standalone full-screen page.
     *  Localized via the same Context.getString path as the main panel. */
    private fun renderWebRtcViewerHtml(): String {
        val i = buildI18n()
        // Pull a few extra strings the data class doesn't carry — they're only used here.
        val cfg = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.getDefault())
        }
        val c = context.createConfigurationContext(cfg)
        val sCreating = c.getString(R.string.web_viewer_status_creating)
        val sExchanging = c.getString(R.string.web_viewer_status_exchanging)
        val sStreaming = c.getString(R.string.web_viewer_status_streaming)
        val sAnswer = c.getString(R.string.web_viewer_status_answer)
        val sIdle = c.getString(R.string.web_viewer_status_idle)
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>${i.viewerTitle}</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{background:#14121c;color:#eee;font-family:system-ui,sans-serif;margin:0;padding:20px;text-align:center}
              video{max-width:100%;border-radius:12px;background:#000;box-shadow:0 8px 32px rgba(120,73,242,0.3)}
              button{background:#7849f2;color:#fff;border:0;padding:8px 14px;border-radius:8px;font-size:14px;cursor:pointer;margin:4px}
              button:disabled{opacity:.4;cursor:not-allowed}
              code{background:#262335;padding:2px 6px;border-radius:4px}
              #status{color:#9c8fff;margin:8px 0;min-height:1.2em}
              .ctl{margin-top:14px;display:none;flex-wrap:wrap;justify-content:center;gap:6px}
              .ctl button{background:#262335;border:1px solid #3b3754}
            </style></head>
            <body>
              <h1>${i.viewerH1}</h1>
              <p>${i.viewerNote}</p>
              <video id="v" autoplay playsinline controls></video>
              <div id="status">$sIdle</div>
              <button id="go">${i.viewerBtnConnect}</button>
              <div class="ctl" id="controls">
                <button data-cmd="lens">${i.btnSwitchCamera}</button>
                <button data-cmd="torch">${i.btnTorch}</button>
                <button data-cmd="mirror">${i.btnMirror}</button>
                <button data-cmd="af">${i.btnContinuousAf}</button>
                <button data-cmd="zoom_in">Zoom +</button>
                <button data-cmd="zoom_out">Zoom −</button>
                <button data-cmd="ev_up">EV +</button>
                <button data-cmd="ev_down">EV −</button>
                <button data-cmd="snapshot">${i.btnSnapshot}</button>
              </div>
              <script>
                const v = document.getElementById('v');
                const status = (m) => document.getElementById('status').textContent = m;
                let dataChannel = null;
                document.getElementById('go').addEventListener('click', async () => {
                  document.getElementById('go').disabled = true;
                  status('$sCreating');
                  const pc = new RTCPeerConnection({iceServers: []});
                  // Receive both video and audio if the server has them.
                  pc.addTransceiver('video', {direction: 'recvonly'});
                  pc.addTransceiver('audio', {direction: 'recvonly'});
                  pc.ontrack = (e) => { v.srcObject = e.streams[0]; status('$sStreaming'); };
                  pc.oniceconnectionstatechange = () => status('ICE: ' + pc.iceConnectionState);
                  // The phone opens a "lenscast" DataChannel after PC creation; show the
                  // command buttons once it's ready so the user can drive the camera.
                  pc.ondatachannel = (e) => {
                    dataChannel = e.channel;
                    dataChannel.onopen = () => { document.getElementById('controls').style.display = 'flex'; };
                    dataChannel.onclose = () => { document.getElementById('controls').style.display = 'none'; };
                  };
                  const offer = await pc.createOffer();
                  await pc.setLocalDescription(offer);
                  await new Promise(r => {
                    if (pc.iceGatheringState === 'complete') return r();
                    pc.addEventListener('icegatheringstatechange', () => {
                      if (pc.iceGatheringState === 'complete') r();
                    });
                  });
                  status('$sExchanging');
                  const res = await fetch('/webrtc/offer', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/sdp'},
                    body: pc.localDescription.sdp,
                  });
                  if (!res.ok) { status('server: ' + res.status + ' ' + await res.text()); return; }
                  const answerSdp = await res.text();
                  await pc.setRemoteDescription({type: 'answer', sdp: answerSdp});
                  status('$sAnswer');
                });
                document.querySelectorAll('.ctl button').forEach(b => {
                  b.addEventListener('click', () => {
                    if (dataChannel && dataChannel.readyState === 'open') {
                      dataChannel.send(JSON.stringify({cmd: b.dataset.cmd}));
                    }
                  });
                });
              </script>
            </body></html>
        """.trimIndent()
    }

    /** Bare-bones text/plain response with permissive CORS — used by the WHEP routes for
     *  errors and the empty-bodied DELETE ack. */
    private fun writePlain(out: OutputStream, status: String, msg: String) {
        val body = msg.toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    /** CORS preflight reply for the WHEP endpoint/resource, so browser-based players can
     *  POST `application/sdp` and DELETE the resource cross-origin. */
    private fun writeCorsPreflight(out: OutputStream, allowMethods: String) {
        val header = ("HTTP/1.0 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: $allowMethods\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.flush() } catch (_: IOException) {}
    }

    private fun writeStatus(out: OutputStream) {
        val body = control.statusJson().toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeExport(out: OutputStream) {
        val body = control.exportSettingsJson().toByteArray(Charsets.UTF_8)
        // Content-Disposition encourages browsers to "Save As" instead of inlining the
        // raw JSON in a tab — friendlier for a "download backup" workflow.
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Disposition: attachment; filename=\"lenscast-settings.json\"\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeLanding(out: OutputStream) {
        val html = renderLandingHtml().toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Length: ${html.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(html); out.flush() } catch (_: IOException) {}
    }

    /**
     * Single dynamic page. All sections are rendered; the script shows or hides them based
     * on the current `state` (idle vs streaming) and `protocol` from `/status`. The reasons
     * for one page instead of multiple:
     *  - Smoother UX — no full reload when the user starts the stream.
     *  - Easier to keep button bindings consistent across modes.
     *  - The page is small enough that JS-side conditional rendering stays readable.
     *
     * Layout: CSS-grid two-column on desktop (preview/hero on the left, stats + quick
     * controls on the right), single-column stack on mobile. Settings live below the
     * fold as a tabbed panel — Camera / Image / Audio / Stream / UX / System — so the
     * full Settings sheet doesn't have to be a 1000-line vertical scroll on a phone
     * browser.
     */
    /**
     * Hand-rolled JSON map of `data-help` key → localized tooltip text. Injected into the
     * page; JS scans every `[data-help]` field, appends a `?` icon next to its label, and
     * shows the text on click. Adding a new tooltip = add the key+R.string here and the
     * `data-help="key"` attribute on the corresponding `<div class="field">`.
     */
    private fun helpMapJson(): String {
        val cfg = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.getDefault())
        }
        val c = context.createConfigurationContext(cfg)
        val entries = mapOf(
            "lens" to R.string.web_help_lens,
            "resolution" to R.string.web_help_resolution,
            "fps" to R.string.web_help_fps,
            "rotation_lock" to R.string.web_help_rotation_lock,
            "mirror" to R.string.web_help_mirror,
            "continuous_af" to R.string.web_help_continuous_af,
            "watermark" to R.string.web_help_watermark,
            "exposure" to R.string.web_help_exposure,
            "white_balance" to R.string.web_help_white_balance,
            "antibanding" to R.string.web_help_antibanding,
            "effect" to R.string.web_help_effect,
            "scene" to R.string.web_help_scene,
            "manual_focus" to R.string.web_help_manual_focus,
            "focus_distance" to R.string.web_help_focus_distance,
            "manual_exposure" to R.string.web_help_manual_exposure,
            "iso" to R.string.web_help_iso,
            "shutter" to R.string.web_help_shutter,
            "stream_mic" to R.string.web_help_stream_mic,
            "mic_source" to R.string.web_help_mic_source,
            "gain" to R.string.web_help_gain,
            "noise_suppress" to R.string.web_help_noise_suppress,
            "echo_cancel" to R.string.web_help_echo_cancel,
            "jpeg_quality" to R.string.web_help_jpeg_quality,
            "rtsp_bitrate" to R.string.web_help_rtsp_bitrate,
            "mjpeg_sidecar" to R.string.web_help_mjpeg_sidecar,
            "record_mp4" to R.string.web_help_record_mp4,
            "sftp_auto_upload" to R.string.web_help_sftp_auto_upload,
            "sftp_host" to R.string.web_help_sftp_host,
            "sftp_port" to R.string.web_help_sftp_port,
            "sftp_user" to R.string.web_help_sftp_user,
            "sftp_password" to R.string.web_help_sftp_password,
            "sftp_remote_dir" to R.string.web_help_sftp_remote_dir,
            "sftp_fingerprint" to R.string.web_help_sftp_fingerprint,
            "keep_screen_on" to R.string.web_help_keep_screen_on,
            "blank_preview" to R.string.web_help_blank_preview,
            "auto_start" to R.string.web_help_auto_start,
            "start_on_boot" to R.string.web_help_start_on_boot,
            "mjpeg_port" to R.string.web_help_mjpeg_port,
            "rtsp_port" to R.string.web_help_rtsp_port,
            "https" to R.string.web_help_https,
            "stream_user" to R.string.web_help_stream_user,
            "stream_pass" to R.string.web_help_stream_pass,
            "call_behavior" to R.string.web_help_call_behavior,
            "persistent_web" to R.string.web_help_persistent_web,
            "export" to R.string.web_help_export,
            "import" to R.string.web_help_import,
        )
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        return entries.entries.joinToString(",", prefix = "{", postfix = "}") {
            "\"${it.key}\":\"${esc(c.getString(it.value))}\""
        }
    }

    private fun renderLandingHtml(): String {
        val mjpegPortNow = mjpegPort()
        val rtspPortNow = rtspPort()
        val initialQuality = control.jpegQuality()
        val version = appVersion().ifEmpty { "" }
        val i = buildI18n()
        val helpJson = helpMapJson()
        return """
            <!doctype html>
            <html><head><title>${i.title}</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              /* Design tokens — single source of truth for colours and spacing. The
                 dark scheme is the default and the only one we ship; brand violet
                 matches the in-app Material 3 theme. */
              :root{
                --bg:#0E0B17; --bg-elev:#14121C; --surface:#1A1827; --surface-2:#262335;
                --surface-3:#33304A; --border:#3b3754;
                --text:#EDEAF7; --text-dim:#bba8ff; --text-mute:#9089a8;
                --accent:#9B7CFF; --accent-strong:#7849F2; --accent-soft:rgba(120,73,242,.15);
                --danger:#FF5E5B; --danger-bg:#5a2330;
                --ok:#66BB6A; --warn:#FFB300; --alert:#E53935;
                --r-sm:8px; --r-md:12px; --r-lg:16px;
                --gap-1:6px; --gap-2:10px; --gap-3:14px; --gap-4:20px;
              }
              *{box-sizing:border-box}
              html,body{margin:0;padding:0;background:var(--bg);color:var(--text);
                        font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
                        font-size:14px;line-height:1.45}
              a{color:var(--accent);text-decoration:none}
              a:hover{text-decoration:underline}
              code{background:var(--surface-2);padding:2px 6px;border-radius:6px;
                   font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
              img{max-width:100%;display:block}

              /* Page chrome ----------------------------------------------------- */
              header{display:flex;align-items:center;gap:var(--gap-2);
                     padding:14px 20px;border-bottom:1px solid var(--surface-2);
                     position:sticky;top:0;background:var(--bg);z-index:5}
              header .logo{width:28px;height:28px;border-radius:8px;
                           background:linear-gradient(135deg,var(--accent),var(--accent-strong));
                           display:grid;place-items:center;color:#fff;font-weight:700;
                           font-size:15px}
              header h1{margin:0;font-size:16px;font-weight:600;letter-spacing:-.01em;flex:1}
              header .state-badge{display:inline-flex;align-items:center;gap:6px;
                                  padding:4px 10px;border-radius:999px;font-size:12px;
                                  font-weight:600;background:var(--surface-2);
                                  color:var(--text-mute)}
              header .state-badge .dot{width:8px;height:8px;border-radius:50%;
                                       background:var(--text-mute)}
              header .state-badge[data-state=streaming]{background:rgba(102,187,106,.15);
                                                       color:var(--ok)}
              header .state-badge[data-state=streaming] .dot{background:var(--ok);
                                                            animation:pulse 1.5s ease-in-out infinite}
              header .state-badge[data-state=starting]{background:rgba(255,179,0,.15);color:var(--warn)}
              header .state-badge[data-state=starting] .dot{background:var(--warn)}
              header .state-badge[data-state=error]{background:rgba(229,57,53,.15);color:var(--alert)}
              @keyframes pulse{50%{opacity:.4}}

              main{max-width:1100px;margin:0 auto;padding:20px;
                   display:grid;grid-template-columns:1fr;gap:var(--gap-4)}
              @media (min-width:900px){
                main{grid-template-columns:minmax(0,1.4fr) minmax(0,1fr)}
              }

              /* Card primitive -------------------------------------------------- */
              .card{background:var(--surface);border:1px solid var(--surface-2);
                    border-radius:var(--r-lg);padding:var(--gap-4)}
              .card h2{margin:0 0 var(--gap-3) 0;font-size:13px;font-weight:600;
                       text-transform:uppercase;letter-spacing:.07em;color:var(--text-dim)}
              .card .subtitle{color:var(--text-mute);margin:0 0 var(--gap-3);font-size:13px}

              /* Hero (preview / start) ---------------------------------------- */
              .hero{grid-column:1/-1}
              @media (min-width:900px){.hero{grid-column:1/2}}
              .hero .preview-frame{position:relative;background:#000;border-radius:var(--r-md);
                                   overflow:hidden;aspect-ratio:16/9}
              .hero .preview-frame img{width:100%;height:100%;object-fit:contain}
              .hero .preview-frame .overlay{position:absolute;inset:0;display:grid;
                                            place-items:center;text-align:center;padding:24px;
                                            background:radial-gradient(circle at center, rgba(120,73,242,.18), transparent 70%)}
              .hero .preview-frame .overlay .big{font-size:18px;font-weight:600;color:#fff;
                                                 margin-bottom:6px}
              .hero .preview-frame .overlay .small{color:#bba8ff;font-size:13px}
              .hero .links{display:flex;flex-wrap:wrap;gap:var(--gap-2);
                           margin-top:var(--gap-3);font-size:12px}
              .hero .links code{font-size:11px}

              /* Idle hero: protocol picker + Start */
              .idle-picker{display:flex;flex-direction:column;gap:var(--gap-3)}
              .idle-picker .proto-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:var(--gap-2)}
              .idle-picker .proto{cursor:pointer;border:2px solid var(--surface-2);
                                  background:var(--surface-2);border-radius:var(--r-md);
                                  padding:var(--gap-3);text-align:left;transition:all .15s}
              .idle-picker .proto:hover{border-color:var(--surface-3)}
              .idle-picker .proto input{display:none}
              .idle-picker .proto input:checked + .proto-inner{color:var(--text)}
              .idle-picker .proto:has(input:checked){border-color:var(--accent-strong);
                                                     background:var(--accent-soft)}
              .idle-picker .proto .proto-name{font-weight:600;margin-bottom:4px}
              .idle-picker .proto .proto-desc{color:var(--text-mute);font-size:12px;line-height:1.4}

              /* Quick controls + stats ---------------------------------------- */
              .side{display:flex;flex-direction:column;gap:var(--gap-4)}

              .stats-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:var(--gap-2)}
              .stat{background:var(--surface-2);border-radius:var(--r-md);
                    padding:var(--gap-2) var(--gap-3)}
              .stat .label{font-size:11px;color:var(--text-mute);text-transform:uppercase;
                           letter-spacing:.05em}
              .stat .value{font-size:16px;font-weight:600;color:var(--text);
                           font-variant-numeric:tabular-nums;line-height:1.2}
              .stat.full{grid-column:1/-1}
              .vu{height:8px;background:var(--surface-3);border-radius:4px;overflow:hidden;
                  margin-top:6px}
              .vu .fill{height:100%;background:var(--ok);transition:width .1s linear,
                                                                    background-color .2s}

              .quick-controls{display:grid;grid-template-columns:repeat(2,1fr);gap:var(--gap-2)}
              .quick-controls.row3{grid-template-columns:repeat(3,1fr)}

              /* Buttons -------------------------------------------------------- */
              .btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;
                   background:var(--surface-2);color:var(--text);border:0;
                   padding:10px 14px;border-radius:var(--r-md);font-size:13px;
                   font-weight:500;cursor:pointer;font-family:inherit;
                   transition:background .15s,transform .05s}
              .btn:hover{background:var(--surface-3)}
              .btn:active{transform:scale(.98)}
              .btn.primary{background:var(--accent-strong);color:#fff}
              .btn.primary:hover{background:#9061ff}
              .btn.danger{background:var(--danger-bg);color:#fff}
              .btn.danger:hover{background:#7a2f3f}
              .btn.ghost{background:transparent;border:1px solid var(--surface-2)}
              .btn:disabled{opacity:.4;cursor:not-allowed}
              .btn.big{padding:14px 18px;font-size:15px}

              /* While a stream is running, hide every widget whose backing setting is
                 locked server-side (audio toggle, mic source, ports, HTTPS, credentials,
                 SFTP credentials, etc.). The complementary `.stream-only` hint becomes
                 visible in the same state so the panel doesn't look mysteriously empty. */
              body.is-streaming .stream-locked{display:none !important}
              body.is-streaming .stream-only{display:block !important}

              /* Help tooltips — `?` button injected next to each labeled field by JS. */
              .help-btn{display:inline-flex;align-items:center;justify-content:center;
                width:16px;height:16px;margin-left:6px;border-radius:50%;border:0;
                background:var(--surface-3);color:var(--text-mute);font-size:11px;
                font-weight:700;cursor:pointer;flex-shrink:0;font-family:inherit}
              .help-btn:hover{background:var(--accent-strong);color:#fff}
              .help-tip{position:absolute;z-index:50;max-width:280px;
                background:#1d1a2e;color:#eee;border:1px solid var(--accent-strong);
                border-radius:8px;padding:10px 12px;font-size:12px;line-height:1.4;
                box-shadow:0 6px 24px rgba(0,0,0,0.5);pointer-events:none}
              .help-tip code{background:#262335;padding:1px 4px;border-radius:3px;font-size:11px}

              /* Tabs ----------------------------------------------------------- */
              .settings-card{grid-column:1/-1}
              .tabs{display:flex;gap:2px;border-bottom:1px solid var(--surface-2);
                    overflow-x:auto;margin-bottom:var(--gap-3);
                    scrollbar-width:thin}
              .tab{flex:none;background:none;border:0;border-bottom:2px solid transparent;
                   color:var(--text-mute);padding:10px 14px;font-size:13px;cursor:pointer;
                   font-family:inherit;font-weight:500;white-space:nowrap;transition:all .15s}
              .tab:hover{color:var(--text)}
              .tab[aria-selected=true]{color:var(--accent);border-bottom-color:var(--accent)}
              .panel{display:none;flex-direction:column;gap:var(--gap-3)}
              .panel[data-active]{display:flex}

              /* Form rows ------------------------------------------------------ */
              .field{display:flex;align-items:center;gap:var(--gap-3);
                     padding:var(--gap-2) 0;flex-wrap:wrap}
              .field > .l{flex:1;min-width:140px;color:var(--text)}
              .field > .l small{display:block;color:var(--text-mute);font-size:11px;font-weight:400;line-height:1.4}
              .field > .c{display:flex;align-items:center;gap:var(--gap-2);flex-wrap:wrap}
              .field input[type=range]{accent-color:var(--accent-strong);min-width:160px}
              .field input[type=checkbox]{accent-color:var(--accent-strong);width:18px;height:18px}
              .field select,.field input[type=number],.field input[type=text]{
                background:var(--bg-elev);color:var(--text);border:1px solid var(--border);
                border-radius:6px;padding:6px 10px;font-size:13px;font-family:inherit}
              .field .val{font-variant-numeric:tabular-nums;color:var(--text-mute);
                          min-width:50px;text-align:right;font-size:12px}
              .field textarea{width:100%;background:var(--bg-elev);color:var(--text);
                              border:1px solid var(--border);border-radius:6px;padding:8px;
                              font-family:ui-monospace,monospace;font-size:12px;resize:vertical;
                              min-height:80px}
              .divider{height:1px;background:var(--surface-2);margin:var(--gap-2) 0}

              /* Notice / message ---------------------------------------------- */
              .notice{background:var(--surface-2);border-radius:var(--r-md);
                      padding:var(--gap-3);font-size:13px;color:var(--text-dim)}
              .toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);
                     background:var(--surface);border:1px solid var(--surface-2);
                     border-radius:var(--r-md);padding:10px 16px;color:var(--text);
                     box-shadow:0 8px 24px rgba(0,0,0,.4);font-size:13px;
                     opacity:0;pointer-events:none;transition:opacity .2s;z-index:10}
              .toast.show{opacity:1}
              .toast.error{border-color:var(--alert);color:#ffd1d0}

              .hidden{display:none!important}
              .row{display:flex;gap:var(--gap-2);flex-wrap:wrap}

              footer{max-width:1100px;margin:0 auto;padding:16px 20px 32px;
                     color:var(--text-mute);font-size:12px}
              footer code{font-size:10px;word-break:break-all}
            </style></head>
            <body>
              <header>
                <div class="logo">L</div>
                <h1>${i.title}</h1>
                <span id="state-badge" class="state-badge" data-state="idle">
                  <span class="dot"></span><span id="state-text">${i.stateIdle}</span>
                </span>
              </header>

              <main>
                <div id="health-banner" class="hidden" style="margin:0 0 16px 0;padding:10px 14px;border-radius:10px;font-size:14px"></div>
                <!-- HERO ============================================== -->
                <section class="card hero">
                  <h2 id="hero-title">${i.heroTitle}</h2>

                  <!-- Idle hero: protocol picker + Start -->
                  <div id="idle-block" class="hidden idle-picker">
                    <p class="subtitle">${i.idlePick}</p>
                    <div class="proto-grid">
                      <label class="proto">
                        <input type="radio" name="proto" value="mjpeg">
                        <div class="proto-inner">
                          <div class="proto-name">MJPEG</div>
                          <div class="proto-desc">${i.protoMjpegDesc}</div>
                        </div>
                      </label>
                      <label class="proto">
                        <input type="radio" name="proto" value="rtsp">
                        <div class="proto-inner">
                          <div class="proto-name">RTSP</div>
                          <div class="proto-desc">${i.protoRtspDesc}</div>
                        </div>
                      </label>
                      <label class="proto">
                        <input type="radio" name="proto" value="webrtc">
                        <div class="proto-inner">
                          <div class="proto-name">WebRTC</div>
                          <div class="proto-desc">${i.protoWebrtcDesc}</div>
                        </div>
                      </label>
                    </div>
                    <button data-act="start" class="btn primary big">${i.start}</button>
                  </div>

                  <!-- Live MJPEG hero -->
                  <div id="mjpeg-block" class="hidden">
                    <div class="preview-frame">
                      <img id="preview" alt="${i.heroTitle}">
                    </div>
                    <div class="links">
                      <code id="mjpeg-video-url"></code>
                      <code id="mjpeg-audio-url"></code>
                      <code id="mjpeg-shot-url"></code>
                    </div>
                  </div>

                  <!-- Live RTSP hero -->
                  <div id="rtsp-block" class="hidden">
                    <div class="preview-frame">
                      <div class="overlay">
                        <div>
                          <div class="big">${i.rtspLiveTitle}</div>
                          <div class="small">${i.rtspLiveNote}</div>
                        </div>
                      </div>
                    </div>
                    <div class="links"><code id="rtsp-url"></code></div>
                  </div>

                  <!-- Live WebRTC hero -->
                  <div id="webrtc-block" class="hidden">
                    <div class="preview-frame">
                      <video id="webrtc-preview" autoplay playsinline muted style="width:100%;border-radius:12px;background:#000"></video>
                    </div>
                    <div class="links">
                      <a id="webrtc-view-link" href="/webrtc/view" target="_blank">${i.webrtcViewLink}</a>
                      <code id="webrtc-whep-url"></code>
                    </div>
                  </div>
                </section>

                <!-- SIDE: stats + quick controls ======================= -->
                <aside class="side">
                  <section class="card hidden" id="live-card">
                    <h2>${i.statusH}</h2>
                    <div class="stats-grid">
                      <div class="stat"><div class="label">Protocol</div><div class="value" id="s-proto">—</div></div>
                      <div class="stat"><div class="label">${i.lblLens}</div><div class="value" id="s-lens">—</div></div>
                      <div class="stat"><div class="label">${i.lblFps}</div><div class="value" id="s-fps">—</div></div>
                      <div class="stat"><div class="label">Clients</div><div class="value" id="s-clients">0</div></div>
                      <div class="stat"><div class="label">${i.lblResolution}</div><div class="value" id="s-res">—</div></div>
                      <div class="stat"><div class="label">Zoom · EV</div><div class="value" id="s-zoomev">—</div></div>
                      <div class="stat"><div class="label">Bitrate</div><div class="value" id="s-bitrate">—</div></div>
                      <div class="stat full" id="audio-stat">
                        <div class="label">${i.statAudioPeak} <span id="s-peak" style="float:right;color:var(--text-mute)">—</span></div>
                        <div class="vu"><div class="fill" id="vu-fill" style="width:0%"></div></div>
                      </div>
                      <div class="stat full hidden" id="clients-stat">
                        <div class="label">${i.statConnectedClients}</div>
                        <div id="client-list" style="display:flex;flex-direction:column;gap:6px;margin-top:6px"></div>
                      </div>
                    </div>
                  </section>

                  <section class="card hidden" id="live-controls-card">
                    <h2>${i.quickH}</h2>
                    <div class="quick-controls" id="quick-row1">
                      <button class="btn" data-act="lens">${i.btnSwitchCamera}</button>
                      <button class="btn" data-act="torch">${i.btnTorch}</button>
                      <button class="btn" data-act="mirror">${i.btnMirror}</button>
                      <button class="btn" data-act="af">${i.btnContinuousAf}</button>
                    </div>
                    <div class="row" style="margin-top:var(--gap-2)">
                      <button class="btn" data-act="zoom" data-arg="dir=in">Zoom +</button>
                      <button class="btn" data-act="zoom" data-arg="dir=out">Zoom −</button>
                      <button class="btn" data-act="ev"   data-arg="dir=up">EV +</button>
                      <button class="btn" data-act="ev"   data-arg="dir=down">EV −</button>
                    </div>
                    <div class="row" style="margin-top:var(--gap-2)">
                      <button class="btn" data-act="snapshot" id="snapshot-btn" style="flex:1">${i.btnSnapshot}</button>
                      <button class="btn danger" data-act="stop" style="flex:1">${i.btnStopStreaming}</button>
                    </div>
                    <div class="row" style="margin-top:var(--gap-2)">
                      <button class="btn ghost" id="speedtest-btn" style="flex:1">${i.btnSpeedtest}</button>
                    </div>
                    <div class="divider"></div>
                    <div class="field">
                      <div class="l">${i.lblResolution}</div>
                      <div class="c"><select id="res-sel"></select></div>
                    </div>
                    <div class="field">
                      <div class="l">${i.lblFps}</div>
                      <div class="c"><select id="fps-sel"></select></div>
                    </div>
                    <div class="field" id="quality-row">
                      <div class="l">${i.lblJpegQuality}<small>${i.hintMjpegOnly}</small></div>
                      <div class="c">
                        <input type="range" min="10" max="95" value="$initialQuality" id="q">
                        <span class="val" id="qval">$initialQuality</span>
                      </div>
                    </div>
                  </section>
                </aside>

                <!-- TABBED SETTINGS ==================================== -->
                <section class="card settings-card">
                  <h2>${i.settingsH}</h2>
                  <div class="tabs" role="tablist">
                    <button class="tab" role="tab" data-tab="camera" aria-selected="true">${i.tabCamera}</button>
                    <button class="tab" role="tab" data-tab="image">${i.tabImage}</button>
                    <button class="tab" role="tab" data-tab="audio">${i.tabAudio}</button>
                    <button class="tab" role="tab" data-tab="stream">${i.tabStream}</button>
                    <button class="tab" role="tab" data-tab="ux">${i.tabUx}</button>
                    <button class="tab" role="tab" data-tab="system">${i.tabSystem}</button>
                  </div>

                  <!-- Camera tab -->
                  <div class="panel" data-panel="camera" data-active>
                    <div class="field" data-help="lens">
                      <div class="l">${i.lblLens}</div>
                      <div class="c">
                        <select data-setting="lens">
                          <option value="back">${i.lensBack}</option>
                          <option value="front">${i.lensFront}</option>
                        </select>
                      </div>
                    </div>
                    <div class="field" data-help="resolution">
                      <div class="l">${i.lblResolution}<small>${i.hintResFromCaps}</small></div>
                      <div class="c"><select id="res-sel-tab"></select></div>
                    </div>
                    <div class="field" data-help="fps">
                      <div class="l">${i.lblFps}<small>${i.hintFpsRange}</small></div>
                      <div class="c"><select id="fps-sel-tab"></select></div>
                    </div>
                    <div class="field" data-help="rotation_lock">
                      <div class="l">${i.lblRotationLock}<small>${i.hintRotationLock}</small></div>
                      <div class="c">
                        <select data-setting="rotationLock">
                          <option value="auto">${i.rotAuto}</option>
                          <option value="portrait">${i.rotPortrait}</option>
                          <option value="landscape_left">${i.rotLandscapeLeft}</option>
                          <option value="landscape_right">${i.rotLandscapeRight}</option>
                          <option value="portrait_upside_down">${i.rotPortraitUpside}</option>
                        </select>
                      </div>
                    </div>
                  </div>

                  <!-- Image tab -->
                  <div class="panel" data-panel="image">
                    <div class="field" data-help="mirror">
                      <div class="l">${i.lblMirror}</div>
                      <div class="c"><input type="checkbox" data-setting="mirror"></div>
                    </div>
                    <div class="field" data-help="continuous_af">
                      <div class="l">${i.lblContinuousAf}</div>
                      <div class="c"><input type="checkbox" data-setting="continuousAf"></div>
                    </div>
                    <div class="field" data-help="watermark">
                      <div class="l">${i.lblWatermark}<small>${i.hintWatermark}</small></div>
                      <div class="c"><input type="text" data-setting="watermarkText" placeholder="Lenscast %t"></div>
                    </div>
                    <div class="field" data-help="exposure">
                      <div class="l">${i.lblExposure}</div>
                      <div class="c">
                        <input type="range" min="-12" max="12" step="1" data-setting-range="exposureEv">
                        <span class="val" data-rangeval="exposureEv">0</span><span class="val">EV</span>
                      </div>
                    </div>
                    <div class="field" data-help="white_balance">
                      <div class="l">${i.lblWhiteBalance}</div>
                      <div class="c">
                        <select data-setting="whiteBalance">
                          <option value="auto">${i.wbAuto}</option>
                          <option value="incandescent">${i.wbIncandescent}</option>
                          <option value="fluorescent">${i.wbFluorescent}</option>
                          <option value="daylight">${i.wbDaylight}</option>
                          <option value="cloudy">${i.wbCloudy}</option>
                          <option value="shade">${i.wbShade}</option>
                        </select>
                      </div>
                    </div>
                    <div class="field" data-help="antibanding">
                      <div class="l">${i.lblAntibanding}</div>
                      <div class="c">
                        <select data-setting="antiBanding">
                          <option value="auto">${i.abAuto}</option>
                          <option value="hz50">50 Hz</option>
                          <option value="hz60">60 Hz</option>
                          <option value="off">${i.abOff}</option>
                        </select>
                      </div>
                    </div>
                    <div class="field" data-help="effect">
                      <div class="l">${i.lblEffect}</div>
                      <div class="c">
                        <select data-setting="effect">
                          <option value="none">${i.effectNone}</option><option value="mono">${i.effectMono}</option>
                          <option value="negative">${i.effectNegative}</option><option value="sepia">${i.effectSepia}</option>
                          <option value="aqua">${i.effectAqua}</option><option value="solarize">${i.effectSolarize}</option>
                          <option value="posterize">${i.effectPosterize}</option><option value="blackboard">${i.effectBlackboard}</option>
                          <option value="whiteboard">${i.effectWhiteboard}</option>
                        </select>
                      </div>
                    </div>
                    <div class="field" data-help="scene">
                      <div class="l">${i.lblScene}</div>
                      <div class="c">
                        <select data-setting="sceneMode">
                          <option value="disabled">${i.sceneOff}</option><option value="action">${i.sceneAction}</option>
                          <option value="portrait">${i.scenePortrait}</option><option value="landscape">${i.sceneLandscape}</option>
                          <option value="night">${i.sceneNight}</option><option value="sports">${i.sceneSports}</option>
                          <option value="theatre">${i.sceneTheatre}</option><option value="fireworks">${i.sceneFireworks}</option>
                          <option value="beach">${i.sceneBeach}</option><option value="snow">${i.sceneSnow}</option>
                          <option value="sunset">${i.sceneSunset}</option>
                        </select>
                      </div>
                    </div>
                    <div class="divider"></div>
                    <div class="field" data-help="manual_focus">
                      <div class="l">${i.lblManualFocus}</div>
                      <div class="c"><input type="checkbox" data-setting="manualFocus"></div>
                    </div>
                    <div class="field" data-help="focus_distance">
                      <div class="l">${i.lblFocusDistance}<small>${i.hintFocusZeroInf}</small></div>
                      <div class="c">
                        <input type="range" min="0" max="1000" step="50" data-setting-range="manualFocusCentidiopters">
                        <span class="val" data-rangeval="manualFocusCentidiopters">0</span>
                      </div>
                    </div>
                    <div class="field" id="manual-exp-row" data-help="manual_exposure">
                      <div class="l">${i.lblManualExposure}<small id="manual-exp-unsupported" class="hidden">${i.hintManualExpUnsupported}</small></div>
                      <div class="c"><input type="checkbox" data-setting="manualExposure"></div>
                    </div>
                    <div class="field" id="iso-row" data-help="iso">
                      <div class="l">${i.lblIso}</div>
                      <div class="c">
                        <input type="range" min="50" max="3200" step="50" data-setting-range="iso">
                        <span class="val" data-rangeval="iso">100</span>
                      </div>
                    </div>
                    <div class="field" id="shutter-row" data-help="shutter">
                      <div class="l">${i.lblShutter}</div>
                      <div class="c">
                        <input type="range" min="100" max="500000" step="100" data-setting-range="shutterUs">
                        <span class="val" data-rangeval="shutterUs">16666</span>
                      </div>
                    </div>
                  </div>

                  <!-- Audio tab -->
                  <div class="panel" data-panel="audio">
                    <div class="field stream-locked" data-help="stream_mic">
                      <div class="l">${i.lblStreamMic}</div>
                      <div class="c"><input type="checkbox" data-setting="audioEnabled"></div>
                    </div>
                    <div class="field stream-locked" data-help="mic_source">
                      <div class="l">${i.lblMicSource}</div>
                      <div class="c">
                        <select data-setting="micSource">
                          <option value="camcorder">${i.micCamcorder}</option>
                          <option value="mic">${i.micDefault}</option>
                          <option value="voice_recognition">${i.micVoiceRecog}</option>
                          <option value="voice_communication">${i.micVoiceComm}</option>
                          <option value="unprocessed">${i.micUnprocessed}</option>
                        </select>
                      </div>
                    </div>
                    <div class="field" data-help="gain">
                      <div class="l">${i.lblGain}</div>
                      <div class="c">
                        <input type="range" min="-24" max="24" step="1" data-setting-range="audioGainDb">
                        <span class="val" data-rangeval="audioGainDb">0</span><span class="val">dB</span>
                      </div>
                    </div>
                    <div class="field stream-locked" data-help="noise_suppress">
                      <div class="l">${i.lblNoiseSuppress}</div>
                      <div class="c"><input type="checkbox" data-setting="noiseSuppress"></div>
                    </div>
                    <div class="field stream-locked" data-help="echo_cancel">
                      <div class="l">${i.lblEchoCancel}</div>
                      <div class="c"><input type="checkbox" data-setting="echoCancel"></div>
                    </div>
                    <p class="stream-only" style="display:none;color:var(--text-mute);font-size:12px;text-align:center;margin:8px 0">${i.streamLockedHint}</p>
                  </div>

                  <!-- Stream tab -->
                  <div class="panel" data-panel="stream">
                    <div class="field" data-help="jpeg_quality">
                      <div class="l">${i.lblJpegQuality}<small>${i.hintMjpegOutput}</small></div>
                      <div class="c">
                        <input type="range" min="10" max="95" step="1" data-setting-range="jpegQuality">
                        <span class="val" data-rangeval="jpegQuality">80</span>
                      </div>
                    </div>
                    <div class="field stream-locked" data-help="rtsp_bitrate">
                      <div class="l">${i.lblRtspBitrateCap}<small>${i.hintRtspBitrateAuto}</small></div>
                      <div class="c">
                        <input type="range" min="0" max="20000" step="500" data-setting-range="rtspBitrateKbps">
                        <span class="val" data-rangeval="rtspBitrateKbps">0</span><span class="val">kbps</span>
                      </div>
                    </div>
                    <div class="field stream-locked" data-help="mjpeg_sidecar">
                      <div class="l">${i.lblMjpegSidecar}<small>${i.hintMjpegSidecar}</small></div>
                      <div class="c"><input type="checkbox" data-setting="mjpegSidecar"></div>
                    </div>
                    <div class="field stream-locked" data-help="record_mp4">
                      <div class="l">${i.lblRecordMp4}<small>${i.hintRecordRtspOnly}</small></div>
                      <div class="c"><input type="checkbox" data-setting="recordLocally"></div>
                    </div>
                    <div class="divider"></div>
                    <div class="field stream-locked" data-help="sftp_auto_upload">
                      <div class="l">${i.lblSftpAutoUpload}<small>${i.hintSftpAutoUpload}</small></div>
                      <div class="c"><input type="checkbox" data-setting="sftpEnabled"></div>
                    </div>
                    <div class="field stream-locked" data-help="sftp_host">
                      <div class="l">${i.lblSftpHost}</div>
                      <div class="c"><input type="text" data-setting="sftpHost" placeholder="files.example.org"></div>
                    </div>
                    <div class="field stream-locked" data-help="sftp_port">
                      <div class="l">${i.lblSftpPort}</div>
                      <div class="c"><input type="number" min="1" max="65535" data-setting-int="sftpPort"></div>
                    </div>
                    <div class="field stream-locked" data-help="sftp_user">
                      <div class="l">${i.lblSftpUser}</div>
                      <div class="c"><input type="text" data-setting="sftpUser"></div>
                    </div>
                    <div class="field stream-locked" data-help="sftp_password">
                      <div class="l">${i.lblSftpPassword}</div>
                      <div class="c"><input type="password" data-setting="sftpPassword"></div>
                    </div>
                    <div class="field stream-locked" data-help="sftp_remote_dir">
                      <div class="l">${i.lblSftpRemoteDir}<small>${i.hintSftpRemoteDir}</small></div>
                      <div class="c"><input type="text" data-setting="sftpRemoteDir" placeholder="recordings"></div>
                    </div>
                    <div class="field stream-locked" data-help="sftp_fingerprint">
                      <div class="l">${i.lblSftpFingerprint}<small>${i.hintSftpFingerprint}</small></div>
                      <div class="c"><input type="text" data-setting="sftpHostKeyFingerprint" placeholder="SHA256:…"></div>
                    </div>
                    <!-- Status + retry are useful both during and after a stream, so they stay. -->
                    <div class="field">
                      <div class="l">${i.lblSftpStatus}</div>
                      <div class="c"><code id="sftp-status" style="font-size:11px">—</code></div>
                    </div>
                    <div class="row" style="margin-top:var(--gap-2)">
                      <button class="btn ghost" id="sftp-retry-btn" style="flex:1">${i.btnSftpRetry}</button>
                    </div>
                    <p class="stream-only" style="display:none;color:var(--text-mute);font-size:12px;text-align:center;margin:8px 0">${i.streamLockedHint}</p>
                  </div>

                  <!-- UX tab -->
                  <div class="panel" data-panel="ux">
                    <div class="field" data-help="keep_screen_on">
                      <div class="l">${i.lblKeepScreenOn}</div>
                      <div class="c"><input type="checkbox" data-setting="keepScreenOn"></div>
                    </div>
                    <div class="field" data-help="blank_preview">
                      <div class="l">${i.lblBlankPreview}<small>${i.hintBatterySaver}</small></div>
                      <div class="c"><input type="checkbox" data-setting="blankPreview"></div>
                    </div>
                    <div class="field" data-help="auto_start">
                      <div class="l">${i.lblAutoStart}</div>
                      <div class="c"><input type="checkbox" data-setting="autoStart"></div>
                    </div>
                    <div class="field" data-help="start_on_boot">
                      <div class="l">${i.lblStartOnBoot}</div>
                      <div class="c"><input type="checkbox" data-setting="startOnBoot"></div>
                    </div>
                  </div>

                  <!-- System / Advanced tab -->
                  <div class="panel" data-panel="system">
                    <div class="field stream-locked" data-help="mjpeg_port">
                      <div class="l">${i.lblMjpegPort}</div>
                      <div class="c"><input type="number" min="1024" max="65535" data-setting-int="mjpegPort"></div>
                    </div>
                    <div class="field stream-locked" data-help="rtsp_port">
                      <div class="l">${i.lblRtspPort}</div>
                      <div class="c"><input type="number" min="1024" max="65535" data-setting-int="rtspPort"></div>
                    </div>
                    <div class="divider stream-locked"></div>
                    <div class="field stream-locked" data-help="https">
                      <div class="l">${i.lblHttps}<small>${i.hintHttps}</small></div>
                      <div class="c"><input type="checkbox" data-setting="httpsEnabled"></div>
                    </div>
                    <div class="field">
                      <div class="l">${i.lblCertFp}</div>
                      <div class="c"><code id="tls-fingerprint" style="font-size:10px">—</code></div>
                    </div>
                    <div class="divider stream-locked"></div>
                    <div class="field stream-locked" data-help="stream_user">
                      <div class="l">${i.lblStreamUser}<small>${i.hintStreamUser}</small></div>
                      <div class="c"><input type="text" data-setting="streamUsername" placeholder="Lenscast"></div>
                    </div>
                    <div class="field stream-locked" data-help="stream_pass">
                      <div class="l">${i.lblStreamPass}<small>${i.hintStreamPass}</small></div>
                      <div class="c"><input type="text" data-setting="streamPassword"></div>
                    </div>
                    <div class="divider"></div>
                    <div class="field" data-help="call_behavior">
                      <div class="l">${i.lblCallBehavior}<small>${i.hintCallBehavior}</small></div>
                      <div class="c">
                        <select data-setting="callBehavior">
                          <option value="ignore">${i.callIgnore}</option>
                          <option value="mute_stream">${i.callMute}</option>
                          <option value="drop_call">${i.callDrop}</option>
                        </select>
                      </div>
                    </div>
                    <div class="field" data-help="persistent_web">
                      <div class="l">${i.lblPersistentWeb}<small>${i.hintPersistentWeb}</small></div>
                      <div class="c"><input type="checkbox" data-setting="persistentWebControl"></div>
                    </div>
                    <div class="divider"></div>
                    <div class="field" data-help="export">
                      <div class="l">${i.lblExport}</div>
                      <div class="c"><a href="/export" download="lenscast-settings.json" class="btn">${i.btnDownloadJson}</a></div>
                    </div>
                    <div class="field stream-locked" data-help="import">
                      <div class="l">${i.lblImport}<small>${i.hintImportReplaces}</small></div>
                      <div class="c" style="flex-direction:column;align-items:stretch;width:100%">
                        <textarea id="import-json" placeholder="${i.phImportJson}"></textarea>
                        <button id="import-btn" class="btn" style="align-self:flex-end;margin-top:6px">${i.btnImport}</button>
                      </div>
                    </div>
                    <p class="stream-only" style="display:none;color:var(--text-mute);font-size:12px;text-align:center;margin:8px 0">${i.streamLockedHint}</p>
                  </div>
                </section>
              </main>

              <footer id="footer-text"></footer>
              <div id="toast" class="toast"></div>

              <script>
                // ── Help tooltips ─────────────────────────────────────
                // Injected `?` icon next to every `[data-help]` field's label. Click to
                // toggle a popover; click elsewhere to dismiss. Touch + mouse friendly.
                const helpTexts = $helpJson;
                let openTip = null;
                function closeTip() {
                  if (openTip) { openTip.remove(); openTip = null; }
                }
                document.addEventListener('click', (e) => {
                  if (openTip && !openTip.contains(e.target) && !e.target.classList.contains('help-btn')) {
                    closeTip();
                  }
                });
                document.querySelectorAll('[data-help]').forEach(field => {
                  const key = field.dataset.help;
                  const text = helpTexts[key];
                  if (!text) return;
                  const label = field.querySelector('.l');
                  if (!label) return;
                  const btn = document.createElement('button');
                  btn.type = 'button';
                  btn.className = 'help-btn';
                  btn.textContent = '?';
                  btn.setAttribute('aria-label', 'Help');
                  btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    if (openTip && openTip.dataset.key === key) { closeTip(); return; }
                    closeTip();
                    const tip = document.createElement('div');
                    tip.className = 'help-tip';
                    tip.dataset.key = key;
                    tip.innerHTML = text;
                    document.body.appendChild(tip);
                    // Position below the button, kept within the viewport.
                    const r = btn.getBoundingClientRect();
                    const tw = tip.offsetWidth;
                    const left = Math.max(8, Math.min(r.left, window.innerWidth - tw - 8));
                    tip.style.left = left + 'px';
                    tip.style.top = (r.bottom + window.scrollY + 6) + 'px';
                    openTip = tip;
                  });
                  label.appendChild(btn);
                });

                // ── URL prep ──────────────────────────────────────────
                const mjpegPort = $mjpegPortNow;
                const rtspPort = $rtspPortNow;
                const mjpegScheme = ${if (mjpegIsHttps()) "'https'" else "'http'"};
                const host = window.location.hostname;
                const mjpegBase = mjpegScheme + '://' + host + ':' + mjpegPort;
                // NOTE: don't set preview.src here — at page-load time the MjpegServer
                // probably isn't running yet, the browser caches the failed connect, and
                // won't retry when streaming starts. The refresh() loop swaps in a
                // cache-busting URL on the idle → MJPEG-live transition instead.
                document.getElementById('mjpeg-video-url').textContent = mjpegBase + '/video';
                document.getElementById('mjpeg-audio-url').textContent = mjpegBase + '/audio';
                document.getElementById('mjpeg-shot-url').textContent  = mjpegBase + '/shot.jpg';
                // RTSP scheme tracks the same HTTPS toggle as MJPEG — Lenscast either
                // serves both as TLS or both as plain text, never mixed.
                const rtspScheme = ${if (mjpegIsHttps()) "'rtsps'" else "'rtsp'"};
                document.getElementById('rtsp-url').textContent =
                  rtspScheme + '://' + host + ':' + rtspPort + '/lenscast';
                // WHEP egress URL — paste into any WHEP player (OBS WHEP source, GStreamer
                // whepsrc, browser WHEP clients). Served off this same control port.
                document.getElementById('webrtc-whep-url').textContent =
                  window.location.origin + '/whep';
                document.getElementById('footer-text').textContent =
                  'Lenscast ${if (version.isEmpty()) "" else "v$version "}· ${i.footerPanel} · ' + host;

                // ── Toast (replaces the old #msg single-line strip) ──
                const toast = document.getElementById('toast');
                let toastTimer = null;
                function showToast(text, isError) {
                  toast.textContent = text;
                  toast.classList.toggle('error', !!isError);
                  toast.classList.add('show');
                  clearTimeout(toastTimer);
                  toastTimer = setTimeout(() => toast.classList.remove('show'), 2400);
                }

                async function post(act, arg) {
                  const qs = arg ? ('?' + arg) : '';
                  try {
                    const r = await fetch('/control/' + act + qs, { method: 'POST' });
                    if (!r.ok) showToast(act + ': ' + (await r.text()), true);
                  } catch (e) { showToast(act + ': ' + e, true); }
                }
                async function setSetting(key, value) {
                  return post('setting', 'key=' + encodeURIComponent(key) + '&v=' + encodeURIComponent(value));
                }

                // ── Buttons ──────────────────────────────────────────
                document.querySelectorAll('button[data-act]').forEach(btn => {
                  btn.addEventListener('click', () => {
                    // When the WebRTC DataChannel is open, send via that — saves an HTTP
                    // roundtrip and is the canonical control path for the WebRTC protocol.
                    if (sendCmdViaDataChannel(btn.dataset.act, btn.dataset.arg)) return;
                    post(btn.dataset.act, btn.dataset.arg);
                  });
                });

                // SFTP retry — fires off a fresh queue for the last MP4 the service finalised.
                const sftpRetryBtn = document.getElementById('sftp-retry-btn');
                if (sftpRetryBtn) sftpRetryBtn.addEventListener('click', async () => {
                  sftpRetryBtn.disabled = true;
                  try {
                    const res = await fetch('/sftp/retry', {method: 'POST'});
                    if (res.ok) showToast('Upload queued');
                    else showToast('Retry failed: ' + await res.text(), true);
                  } finally { sftpRetryBtn.disabled = false; }
                });

                // 2 Hz SFTP status poll. Cheap (single-line JSON), independent of the
                // 1 Hz /status poll so the badge updates while a long upload is in flight.
                async function refreshSftpStatus() {
                  try {
                    const r = await fetch('/sftp/status', {cache: 'no-store'});
                    if (!r.ok) return;
                    const j = await r.json();
                    const cell = document.getElementById('sftp-status');
                    if (!cell) return;
                    if (!j.enabled) { cell.textContent = 'disabled'; return; }
                    let line = j.state;
                    if (j.current) line += ' · ' + j.current;
                    if (j.queue > 0) line += ' · queue ' + j.queue;
                    if (j.lastUploaded && j.state === 'success') line = 'last ↑ ' + j.lastUploaded;
                    if (j.lastError && j.state === 'failed') line = 'failed · ' + j.lastError;
                    cell.textContent = line;
                  } catch (e) {}
                }
                setInterval(refreshSftpStatus, 2000);
                refreshSftpStatus();

                // In-panel WebRTC preview — same handshake as /webrtc/view, embedded so
                // the user gets one-glance verification without opening another tab.
                let webRtcPc = null;
                let webRtcDataChannel = null;
                async function ensureWebRtcPreview() {
                  if (webRtcPc) return;
                  try {
                    const pc = new RTCPeerConnection({iceServers: []});
                    pc.addTransceiver('video', {direction: 'recvonly'});
                    pc.addTransceiver('audio', {direction: 'recvonly'});
                    pc.ontrack = (e) => {
                      const v = document.getElementById('webrtc-preview');
                      if (v) v.srcObject = e.streams[0];
                    };
                    pc.ondatachannel = (e) => { webRtcDataChannel = e.channel; };
                    pc.oniceconnectionstatechange = () => {
                      if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'closed') {
                        tearDownWebRtcPreview();
                      }
                    };
                    const offer = await pc.createOffer();
                    await pc.setLocalDescription(offer);
                    await new Promise(r => {
                      if (pc.iceGatheringState === 'complete') return r();
                      pc.addEventListener('icegatheringstatechange', () => {
                        if (pc.iceGatheringState === 'complete') r();
                      });
                    });
                    const res = await fetch('/webrtc/offer', {
                      method: 'POST',
                      headers: {'Content-Type': 'application/sdp'},
                      body: pc.localDescription.sdp,
                    });
                    if (!res.ok) { pc.close(); return; }
                    await pc.setRemoteDescription({type: 'answer', sdp: await res.text()});
                    webRtcPc = pc;
                  } catch (e) { /* keep silent — user can retry by switching protocol */ }
                }
                function tearDownWebRtcPreview() {
                  if (!webRtcPc) return;
                  try { webRtcPc.close(); } catch (e) {}
                  webRtcPc = null;
                  webRtcDataChannel = null;
                  const v = document.getElementById('webrtc-preview');
                  if (v) v.srcObject = null;
                }

                // Quick-controls buttons (Switch camera / Torch / Mirror / AF / Zoom / EV /
                // Snapshot) prefer the WebRTC DataChannel when WebRTC is the active protocol
                // — saves an HTTP roundtrip per click. Falls back to /control/* otherwise.
                function sendCmdViaDataChannel(act, arg) {
                  if (!webRtcDataChannel || webRtcDataChannel.readyState !== 'open') return false;
                  const cmd = act === 'zoom' && arg === 'dir=in' ? 'zoom_in'
                            : act === 'zoom' ? 'zoom_out'
                            : act === 'ev' && arg === 'dir=up' ? 'ev_up'
                            : act === 'ev' ? 'ev_down'
                            : act;
                  webRtcDataChannel.send(JSON.stringify({cmd: cmd}));
                  return true;
                }

                // LAN speed test — downloads 10 MB from /speedtest on THIS port (the web
                // control server). Hitting the MJPEG port broke in WebRTC mode or RTSP
                // without the sidecar, because the MJPEG server isn't running there.
                const speedBtn = document.getElementById('speedtest-btn');
                if (speedBtn) speedBtn.addEventListener('click', async () => {
                  speedBtn.disabled = true;
                  const label = speedBtn.textContent;
                  speedBtn.textContent = 'Testing…';
                  try {
                    const bytes = 10000000;
                    const t0 = performance.now();
                    const res = await fetch('/speedtest?bytes=' + bytes, {cache: 'no-store'});
                    const buf = await res.arrayBuffer();
                    const dt = (performance.now() - t0) / 1000.0;
                    const mbps = (buf.byteLength * 8 / 1e6 / dt).toFixed(1);
                    showToast(mbps + ' Mbps over ' + dt.toFixed(2) + ' s');
                  } catch (e) {
                    showToast('Speed test failed: ' + e, true);
                  } finally {
                    speedBtn.textContent = label;
                    speedBtn.disabled = false;
                  }
                });
                document.querySelectorAll('input[name=proto]').forEach(r => {
                  r.addEventListener('change', () => {
                    if (r.checked) post('protocol', 'v=' + r.value);
                  });
                });

                // ── Tabs ─────────────────────────────────────────────
                document.querySelectorAll('.tab').forEach(t => {
                  t.addEventListener('click', () => {
                    document.querySelectorAll('.tab').forEach(x => x.setAttribute('aria-selected', 'false'));
                    document.querySelectorAll('.panel').forEach(p => p.removeAttribute('data-active'));
                    t.setAttribute('aria-selected', 'true');
                    document.querySelector('[data-panel=' + t.dataset.tab + ']').setAttribute('data-active', '');
                  });
                });

                // ── Generic setting dispatchers ───────────────────────
                document.querySelectorAll('[data-setting]').forEach(el => {
                  el.addEventListener('change', () => {
                    const key = el.dataset.setting;
                    const value = (el.type === 'checkbox') ? (el.checked ? 'true' : 'false') : el.value;
                    setSetting(key, value);
                  });
                });
                document.querySelectorAll('[data-setting-range]').forEach(el => {
                  const key = el.dataset.settingRange;
                  const span = document.querySelector('[data-rangeval="' + key + '"]');
                  let timer = null;
                  el.addEventListener('input', () => {
                    if (span) span.textContent = el.value;
                    clearTimeout(timer);
                    timer = setTimeout(() => setSetting(key, el.value), 150);
                  });
                });
                document.querySelectorAll('[data-setting-int]').forEach(el => {
                  const key = el.dataset.settingInt;
                  const commit = () => setSetting(key, el.value);
                  el.addEventListener('change', commit);
                  el.addEventListener('blur', commit);
                });

                // ── Import button ─────────────────────────────────────
                document.getElementById('import-btn').addEventListener('click', async () => {
                  const body = document.getElementById('import-json').value.trim();
                  if (!body) { showToast('import: paste JSON first', true); return; }
                  try {
                    const r = await fetch('/import', { method: 'POST', body, headers: { 'Content-Type': 'application/json' } });
                    showToast(r.ok ? 'Settings imported' : 'import: ' + (await r.text()), !r.ok);
                  } catch (e) { showToast('import: ' + e, true); }
                });

                // ── JPEG quality slider (live, debounced) ─────────────
                const q = document.getElementById('q');
                let qTimer = null;
                q.addEventListener('input', () => {
                  document.getElementById('qval').textContent = q.value;
                  clearTimeout(qTimer);
                  qTimer = setTimeout(() => fetch('/control/quality?v=' + q.value, { method: 'POST' }), 150);
                });

                // ── /status refresh + live wiring ─────────────────────
                function show(id, on) { document.getElementById(id).classList.toggle('hidden', !on); }
                function populateSelect(el, options, current) {
                  const key = options.join(',') + '|' + current;
                  if (el.dataset.key === key) return;
                  el.dataset.key = key;
                  el.innerHTML = '';
                  for (const opt of options) {
                    const o = document.createElement('option');
                    o.value = opt; o.textContent = opt;
                    if (String(opt) === String(current)) o.selected = true;
                    el.appendChild(o);
                  }
                }
                ['res-sel', 'res-sel-tab'].forEach(id => document.getElementById(id)
                  .addEventListener('change', e => post('resolution', 'v=' + encodeURIComponent(e.target.value))));
                ['fps-sel', 'fps-sel-tab'].forEach(id => document.getElementById(id)
                  .addEventListener('change', e => post('fps', 'v=' + encodeURIComponent(e.target.value))));

                function setIfNotFocused(el, value) {
                  if (el === document.activeElement) return;
                  if (el.type === 'checkbox') el.checked = (value === true);
                  else el.value = value;
                }

                async function refresh() {
                  let s;
                  try {
                    const r = await fetch('/status');
                    if (!r.ok) return;
                    s = await r.json();
                  } catch (e) { return; }

                  const live = s.state === 'streaming' || s.state === 'starting';
                  // Drives the `.stream-locked` / `.stream-only` CSS rules below — every
                  // widget whose server-side updater short-circuits on `streaming` is gated
                  // on this class so the user never sees rows they can't change.
                  document.body.classList.toggle('is-streaming', live);

                  // Header badge
                  const badge = document.getElementById('state-badge');
                  badge.dataset.state = s.state || 'idle';
                  document.getElementById('state-text').textContent = (s.state || 'idle').toUpperCase();

                  // Hero title
                  document.getElementById('hero-title').textContent = live
                    ? (s.protocol === 'mjpeg' ? 'Live preview' : 'RTSP active')
                    : 'Ready';

                  // Hero swap
                  show('idle-block', !live);
                  show('mjpeg-block', live && s.protocol === 'mjpeg');
                  show('rtsp-block',  live && s.protocol === 'rtsp');
                  show('webrtc-block', live && s.protocol === 'webrtc');
                  // Lazy-attach the in-panel WebRTC preview on the live→live transition.
                  if (live && s.protocol === 'webrtc') ensureWebRtcPreview();
                  else tearDownWebRtcPreview();

                  // Side cards — status + quick controls — only make sense while live.
                  show('live-card', live);
                  show('live-controls-card', live);

                  // Live preview hookup: only attach <img src=/video> when we know the
                  // MJPEG server is up, and bust the cache so the browser doesn't reuse
                  // a stale failed connection from idle state. Detach on stop so it
                  // doesn't endlessly retry against a dead server.
                  const mjpegLive = live && s.protocol === 'mjpeg';
                  const previewEl = document.getElementById('preview');
                  if (mjpegLive && !previewEl.dataset.live) {
                    previewEl.src = mjpegBase + '/video?t=' + Date.now();
                    previewEl.dataset.live = '1';
                  } else if (!mjpegLive && previewEl.dataset.live) {
                    previewEl.removeAttribute('src');
                    delete previewEl.dataset.live;
                  }

                  // Hide the snapshot button on RTSP — broadcaster has no frames there.
                  const snap = document.getElementById('snapshot-btn');
                  if (snap) snap.style.display = (s.protocol === 'rtsp') ? 'none' : '';

                  // JPEG quality slider only matters for MJPEG
                  show('quality-row', live && s.protocol === 'mjpeg');

                  // Resolution / FPS pickers — populated whenever the status carries the
                  // capability lists, so the Camera tab picker works idle *and* live, and
                  // the Quick-controls picker mirrors it while streaming.
                  if (Array.isArray(s.availableResolutions)) {
                    populateSelect(document.getElementById('res-sel'),     s.availableResolutions, s.resolution);
                    populateSelect(document.getElementById('res-sel-tab'), s.availableResolutions, s.resolution);
                  }
                  if (Array.isArray(s.availableFps)) {
                    populateSelect(document.getElementById('fps-sel'),     s.availableFps, s.targetFps);
                    populateSelect(document.getElementById('fps-sel-tab'), s.availableFps, s.targetFps);
                  }

                  // Protocol radios (idle screen only)
                  document.querySelectorAll('input[name=proto]').forEach(r => {
                    if (r !== document.activeElement) r.checked = (r.value === s.protocol);
                  });

                  // Stats grid
                  document.getElementById('s-proto').textContent = (s.protocol || '—').toUpperCase();
                  document.getElementById('s-lens').textContent = (s.lens || '—');
                  document.getElementById('s-fps').textContent = (s.fps || 0) + ' / ' + (s.targetFps || 0);
                  document.getElementById('s-clients').textContent = (s.clients || 0);
                  document.getElementById('s-res').textContent = (s.resolution || '—');
                  document.getElementById('s-zoomev').textContent =
                    (s.zoom != null ? s.zoom.toFixed(2) : '1.00') + 'x · ' +
                    (s.ev >= 0 ? '+' : '') + (s.ev || 0) + ' EV';
                  const kbps = s.txKbps || 0;
                  document.getElementById('s-bitrate').textContent =
                    kbps <= 0 ? '—' : (kbps < 1000 ? kbps + ' kbps' : (kbps / 1000).toFixed(1) + ' Mbps');

                  // Health banner — colours track HealthMonitor.Severity (ok/warn/critical).
                  const h = s.health || {severity: 'ok', message: null};
                  const banner = document.getElementById('health-banner');
                  if (banner) {
                    if (h.severity === 'ok' || !h.message) {
                      banner.classList.add('hidden');
                    } else {
                      banner.classList.remove('hidden');
                      banner.textContent = h.message;
                      banner.style.background = h.severity === 'critical' ? '#5a1f2c' : '#5a4a1f';
                      banner.style.color = h.severity === 'critical' ? '#ffcdd2' : '#ffe7a3';
                    }
                  }

                  // Client list with kick buttons (rendered once per /status tick).
                  const list = s.clientList || [];
                  const cs = document.getElementById('clients-stat');
                  const cl = document.getElementById('client-list');
                  if (list.length > 0) {
                    cs.classList.remove('hidden');
                    cl.replaceChildren(...list.map(addr => {
                      const row = document.createElement('div');
                      row.style.cssText = 'display:flex;gap:8px;align-items:center;justify-content:space-between';
                      const span = document.createElement('span');
                      span.textContent = addr;
                      span.style.cssText = 'font-family:ui-monospace,monospace;font-size:13px;color:var(--text-mute)';
                      const btn = document.createElement('button');
                      btn.className = 'btn ghost';
                      btn.textContent = 'Drop';
                      btn.style.cssText = 'padding:4px 10px;font-size:12px';
                      btn.addEventListener('click', async () => {
                        btn.disabled = true;
                        try { await post('kick', 'remote=' + encodeURIComponent(addr)); }
                        catch (e) { btn.disabled = false; }
                      });
                      row.appendChild(span);
                      row.appendChild(btn);
                      return row;
                    }));
                  } else {
                    cs.classList.add('hidden');
                  }

                  // VU meter
                  const peak = (s.audioPeakDbfs != null) ? s.audioPeakDbfs : -90;
                  const audioOn = !!s.audioEnabled && live;
                  document.getElementById('audio-stat').style.display = audioOn ? '' : 'none';
                  if (audioOn) {
                    const frac = Math.max(0, Math.min(1, (peak + 60) / 60));
                    const fill = document.getElementById('vu-fill');
                    fill.style.width = (frac * 100).toFixed(0) + '%';
                    fill.style.background = peak > -3 ? 'var(--alert)'
                      : peak > -12 ? 'var(--warn)' : 'var(--ok)';
                    document.getElementById('s-peak').textContent =
                      (peak <= -89) ? '—' : peak.toFixed(0) + ' dBFS';
                  }

                  // Mirror all Settings widgets with the server state.
                  document.querySelectorAll('[data-setting]').forEach(el => {
                    const key = el.dataset.setting;
                    if (s[key] !== undefined) setIfNotFocused(el, s[key]);
                  });
                  document.querySelectorAll('[data-setting-range]').forEach(el => {
                    const key = el.dataset.settingRange;
                    if (s[key] !== undefined) {
                      setIfNotFocused(el, s[key]);
                      const span = document.querySelector('[data-rangeval="' + key + '"]');
                      if (span) span.textContent = s[key];
                    }
                  });
                  document.querySelectorAll('[data-setting-int]').forEach(el => {
                    const key = el.dataset.settingInt;
                    if (s[key] !== undefined) setIfNotFocused(el, s[key]);
                  });

                  // TLS fingerprint in the System tab
                  if (s.tlsFingerprint)
                    document.getElementById('tls-fingerprint').textContent = s.tlsFingerprint;

                  // Manual exposure: gate the row + clamp slider ranges
                  const manualOk = !!s.supportsManualSensor;
                  document.getElementById('manual-exp-unsupported').classList.toggle('hidden', manualOk);
                  document.querySelector('[data-setting="manualExposure"]').disabled = !manualOk;
                  const showManual = manualOk && s.manualExposure;
                  document.getElementById('iso-row').style.display = showManual ? '' : 'none';
                  document.getElementById('shutter-row').style.display = showManual ? '' : 'none';
                  if (Array.isArray(s.isoRange)) {
                    const isoEl = document.querySelector('[data-setting-range="iso"]');
                    isoEl.min = s.isoRange[0]; isoEl.max = s.isoRange[1];
                  }
                  if (Array.isArray(s.shutterRangeUs)) {
                    const sEl = document.querySelector('[data-setting-range="shutterUs"]');
                    sEl.min = s.shutterRangeUs[0]; sEl.max = s.shutterRangeUs[1];
                  }
                }

                refresh();
                setInterval(refresh, 1000);
              </script>
            </body></html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "WebControlServer"

    }
}
