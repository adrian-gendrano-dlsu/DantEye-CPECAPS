package edu.dlsu.esca_3_2526_c3.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import edu.dlsu.esca_3_2526_c3.dms.AimAdvice
import edu.dlsu.esca_3_2526_c3.BackgroundHelp
import edu.dlsu.esca_3_2526_c3.dms.AlertCommand
import edu.dlsu.esca_3_2526_c3.dms.AutoZoom
import edu.dlsu.esca_3_2526_c3.dms.BuzzerLink
import edu.dlsu.esca_3_2526_c3.dms.CameraWindow
import edu.dlsu.esca_3_2526_c3.CalibrationStore
import edu.dlsu.esca_3_2526_c3.dms.CALIBRATED
import edu.dlsu.esca_3_2526_c3.dms.CameraAimer
import edu.dlsu.esca_3_2526_c3.dms.Config
import edu.dlsu.esca_3_2526_c3.dms.DecisionEngine
import edu.dlsu.esca_3_2526_c3.dms.Detection
import edu.dlsu.esca_3_2526_c3.dms.EngineOutput
import edu.dlsu.esca_3_2526_c3.dms.FaceObservation
import edu.dlsu.esca_3_2526_c3.dms.FeatureExtractor
import edu.dlsu.esca_3_2526_c3.dms.FrameFeatures
import edu.dlsu.esca_3_2526_c3.dms.FusionModel
import edu.dlsu.esca_3_2526_c3.dms.Geometry
import edu.dlsu.esca_3_2526_c3.dms.HeadPose
import edu.dlsu.esca_3_2526_c3.dms.MjpegReader
import edu.dlsu.esca_3_2526_c3.dms.PerceptionResult
import edu.dlsu.esca_3_2526_c3.dms.SensorContext
import edu.dlsu.esca_3_2526_c3.dms.SensorPoller
import edu.dlsu.esca_3_2526_c3.dms.SessionLog
import edu.dlsu.esca_3_2526_c3.dms.ZoomCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** MediaPipe face-mesh lip contours (outer and inner), for the live-picture overlay. */
private val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
private val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)

/** One processed frame for the screen. */
class LiveFrame(
    val image: Bitmap,
    val features: FrameFeatures,
    val out: EngineOutput,
    val fps: Double,
    val totalMs: Double,
    val aim: AimAdvice?,
    val calibrationProgress: Double,
    val warning: String,
    val calibrationProblem: String,
    val sensors: JSONObject?,
    val yoloOn: Boolean,
)

/**
 * The whole pipeline on the phone, like scripts/run_monitor.py:
 *   ESP32-CAM stream (newest frame only) -> MediaPipe (+ YOLO) -> features -> rules,
 *   severity, fuzzy risk -> alert state machine -> buzzer on the sensor board,
 *   GPS / motion from the sensor board, session log, camera watchdog (3 s -> unavailable).
 * Callbacks arrive on background threads.
 */
/** logName: guided recording (log always saved, folder "<logName>_<date>"); null = normal monitoring ("phone_<date>"). */
class MonitorSession(private val context: Context, private val settings: DeviceSettings, private val listener: Listener,
                     private val logName: String? = null) {

    interface Listener {
        fun onFrame(frame: LiveFrame)
        /** pushed: a supervisor push belongs to this start / escalate (sent once per HIGH alert, at most one per minute). */
        fun onAlert(cmd: AlertCommand, pushed: Boolean)
        fun onStatus(text: String)
    }

    val cfg: Config = ModelFiles.config(context)
    private val engine = DecisionEngine(cfg)
    private var fusionProblem = ""
    val fusion: FusionModel? = try { ModelFiles.fusionModel(context) } catch (e: Exception) { fusionProblem = e.message ?: "unreadable"; null }
    private val extractor = FeatureExtractor(cfg.detector, fusion)
    private val sensorLock = Any()
    private val sensors = SensorContext(cfg.context)
    private val aimer = CameraAimer(cfg.calibration.maxCameraOffsetDeg)
    private val wifi = WifiNet(context)
    private val running = AtomicBoolean(false)
    private val latest = AtomicReference<Pair<ByteArray, Double>?>(null) // newest JPEG + receive time
    private val t0 = SystemClock.elapsedRealtimeNanos()
    private var face: FaceAi? = null
    private var yolo: YoloAi? = null
    private var eye: EyeAi? = null
    /** True when the optional eye-state classifier runs (Details shows its output). */
    val eyeModelOn: Boolean get() = eye != null
    private var buzzer: BuzzerLink? = null
    private var poller: SensorPoller? = null
    private var log: SessionLog? = null
    private var detail: DetailLog? = null // developer mode: frames_detail.jsonl
    private var phoneGps: PhoneLocation? = null
    private var simGps: SimulatedGps? = null
    private var streamThread: Thread? = null
    private var procThread: Thread? = null
    // YOLO (phone detector, ~150 ms per picture on a mid-range phone) runs beside the face model on its own thread,
    // on the newest picture; the pipeline uses its latest boxes. The face pipeline then keeps up with the camera.
    private var yoloThread: Thread? = null
    private val yoloInput = AtomicReference<android.graphics.Bitmap?>(null)
    private val yoloBoxes = AtomicReference<List<Detection>>(emptyList())
    @Volatile private var yoloMs = 0.0
    @Volatile var framesIn = 0
        private set
    @Volatile var reconnects = 0
        private set
    private var cameraLostEvents = 0
    // Saved calibration (CalibrationStore): restored at the start, saved again after a recalibration.
    @Volatile private var restoredCalibration = false
    private val poseCheck = ArrayList<Pair<Double, Double>>()
    private var poseCheckDone = false
    val logDir: File? get() = log?.dir

    // CPECAPS: everything else that happens (events.jsonl, sensors.jsonl), camera view / zoom, SD video.
    private var events: EventLog? = null
    private val camera = CameraControl(wifi, settings.cameraUrl)
    private val autoZoom = AutoZoom()
    @Volatile private var zoomPhase = ZOOM_OFF
    @Volatile private var cameraWindow: CameraWindow? = null
    @Volatile private var lastZoom: ZoomCommand? = null
    @Volatile private var zoomResult = "off"
    @Volatile private var recordName: String? = null
    @Volatile private var recordOn = false

    private companion object {
        const val ZOOM_OFF = 0        // auto zoom off, or the camera cannot zoom
        const val ZOOM_WAIT = 1       // camera settings being sent
        const val ZOOM_MEASURING = 2  // collecting the face size
        const val ZOOM_SENDING = 3    // zoom sent, calibration starts again
        const val ZOOM_DONE = 4       // fixed for the rest of the trip
    }

    fun now(): Double = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9

    fun start() {
        if (!running.compareAndSet(false, true)) return
        wifi.onChange = { state -> events?.event("wifi_dms_net", "state" to state) }
        wifi.start()
        try {
            face = FaceAi(context, cfg.face)
        } catch (e: Throwable) { // includes UnsatisfiedLinkError from the native MediaPipe library
            listener.onStatus("Face model failed to load: ${e.message}")
            running.set(false)
            wifi.stop()
            return
        }
        yolo = try {
            ModelFiles.yoloModel(context)?.let { YoloAi(it, ModelFiles.yoloLabels(context), cfg.detector) }
        } catch (e: Throwable) {
            listener.onStatus("YOLO model not usable (${e.message}); phone detection off")
            null
        }
        eye = try {
            ModelFiles.eyeModel(context)?.let { EyeAi(it, ModelFiles.eyeLabels(context), cfg.eyeModel) }
        } catch (e: Throwable) {
            listener.onStatus("Eye-state model not usable (${e.message}); running without it")
            null
        }
        val sensorUrl = settings.sensorUrl.trim()
        if (sensorUrl.isNotEmpty()) {
            buzzer = BuzzerLink(sensorUrl, { url -> wifi.getBytes(url, 1000) }, { listener.onStatus(it) })
            poller = SensorPoller(sensorUrl, settings.pollMs.toLong(), { url -> wifi.getText(url, 1200) }, ::now, sensors, sensorLock,
                { listener.onStatus(it) }, { json -> events?.sensor("board", json) })
        }
        // CPECAPS: the phone's GPS fills in speed and location when the sensor board has no fix.
        phoneGps = PhoneLocation(context) { lat, lon, kmh ->
            synchronized(sensorLock) { sensors.ingestPhone(lat, lon, kmh, now()) }
            events?.sensor("phone_gps", JSONObject().put("lat", lat).put("lon", lon).put("kmh", kmh ?: JSONObject.NULL))
        }.also { if (!it.start()) listener.onStatus("Phone location is off: allow Location for Danteye to use the phone's GPS") }
        // CPECAPS developer test: a made-up drive in the sensor board's GPS format (maps, speed, supervisor map).
        if (settings.simulateGps) {
            simGps = SimulatedGps { json ->
                synchronized(sensorLock) { sensors.ingest(json, now()) }
                events?.sensor("simulated_gps", json)
            }.also { it.start() }
            listener.onStatus("Simulated drive is ON (developer test): the location is made up")
        }
        if (settings.saveLogs || logName != null) {
            val root = context.getExternalFilesDir("sessions") ?: File(context.filesDir, "sessions")
            val name = (logName ?: "phone") + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val meta = JSONObject()
                .put("source", settings.streamUrl)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                .put("models", JSONObject()
                    .put("face", "face_landmarker.task")
                    .put("detector", yolo?.let { JSONObject().put("file", ModelFiles.source(context, ModelFiles.YOLO))
                        .put("input", it.inputSize).put("classes", JSONArray(it.classNames)) } ?: JSONObject.NULL))
                .put("config", ModelFiles.source(context, ModelFiles.CONFIG))
                .put("eye_model", eye?.let { JSONObject().put("file", ModelFiles.source(context, ModelFiles.EYE))
                    .put("input", it.inputSize).put("classes", JSONArray(it.classNames)) } ?: JSONObject.NULL)
                .put("fusion", ModelFiles.source(context, ModelFiles.FUSION))
            log = try { SessionLog(File(root, name), meta) } catch (e: Exception) { listener.onStatus("Log not saved: ${e.message}"); null }
            detail = if (settings.detailedLog) log?.let { l -> try { DetailLog(l.dir) } catch (_: Exception) { null } } else null
            events = log?.let { l -> try { EventLog(l.dir, ::now) } catch (_: Exception) { null } }
            EventLog.current = events
            events?.event("session_start", JSONObject(meta.toString())
                .put("app_version", appVersion())
                .put("settings", JSONObject()
                    .put("camera_url", settings.cameraUrl).put("sensor_url", settings.sensorUrl).put("poll_ms", settings.pollMs)
                    .put("camera_res", settings.cameraResolution).put("lens_fov", settings.lensFov).put("target_fov", settings.targetFov)
                    .put("auto_zoom", settings.autoZoom).put("record_sd", settings.recordSd)
                    .put("buzzer_volume", settings.buzzerVolume).put("buzzer_type", settings.buzzerType)
                    .put("yolo_every", settings.yoloEvery).put("speak_alerts", settings.speakAlerts).put("vibrate_alerts", settings.vibrateAlerts))
                .put("phone", BackgroundHelp.phoneState(context)))
        }
        if (fusionProblem.isNotEmpty()) listener.onStatus("Fusion model not usable ($fusionProblem); running without it")
        listener.onStatus("Connecting to the camera ${settings.streamUrl} …")
        zoomPhase = ZOOM_WAIT
        Thread(::cameraSetup, "camera-setup").apply { isDaemon = true; start() }
        streamThread = Thread(::readStream, "camera-stream").apply { isDaemon = true; start() }
        procThread = Thread(::process, "dms-pipeline").apply { isDaemon = true; start() }
        if (yolo != null) yoloThread = Thread(::runYolo, "yolo").apply { isDaemon = true; start() }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    /** Write a phone-state line now (the service calls this every 30 s). */
    fun notePhoneState() { events?.event("phone_state", BackgroundHelp.phoneState(context)) }

    // ------------------------------------------------------------------ CPECAPS: camera view, zoom, SD video

    /**
     * Send the lens / view settings, restore the saved calibration (with its camera zoom) or start a new one
     * (undo the last zoom and measure the face again), then start the SD video.
     */
    private fun cameraSetup() {
        val saved = CalibrationStore.load(context)
        val ex = saved?.extra
        // A saved calibration belongs to one camera view: use it only with the same lens, view and resolution.
        val savedOk = saved != null && ex != null && ex.optInt("lens_fov", settings.lensFov) == settings.lensFov &&
            ex.optInt("target_fov", settings.targetFov) == settings.targetFov &&
            ex.optString("camera_res", settings.cameraResolution) == settings.cameraResolution
        var changed = false
        try {
            var info = camera.status()
            val canCrop = info.has("window")
            val ov2640 = info.optString("sensor") == "OV2640"
            if (canCrop) {
                if (info.optInt("lens", -1) != settings.lensFov || info.optInt("fov", -1) != settings.targetFov) {
                    info = camera.setView(settings.lensFov, settings.targetFov); changed = true
                }
            }
            if (savedOk && canCrop && ov2640 && ex != null) {
                // the zoom that was used when calibrating
                val z = ZoomCommand(ex.optDouble("zoom", 1.0), ex.optDouble("cx", 0.5), ex.optDouble("cy", 0.5))
                if (kotlin.math.abs(info.optDouble("zoom", 1.0) - z.zoom) > 0.01 ||
                    kotlin.math.abs(info.optDouble("cx", 0.5) - z.cx) > 0.005 || kotlin.math.abs(info.optDouble("cy", 0.5) - z.cy) > 0.005)
                    info = camera.setZoom(z)
                lastZoom = z.takeIf { it.zoom > 1.001 }
                zoomPhase = ZOOM_DONE
                zoomResult = if (z.zoom > 1.001) String.format(Locale.US, "saved x%.2f", z.zoom) else "saved (no zoom)"
            } else {
                if (canCrop && settings.autoZoom && info.optDouble("zoom", 1.0) > 1.001) { info = camera.resetZoom(); changed = true }
                zoomPhase = if (settings.autoZoom && canCrop && ov2640 && info.optBoolean("ok", true)) ZOOM_MEASURING else ZOOM_OFF
                zoomResult = when {
                    !settings.autoZoom -> "off"
                    !canCrop -> "camera firmware without zoom"
                    !ov2640 -> "not an OV2640 camera"
                    else -> "measuring"
                }
            }
            cameraWindow = CameraControl.window(info)
            events?.event("camera_settings", JSONObject(info.toString()).put("changed", changed).put("saved_calibration", savedOk))
            if (!canCrop && (settings.lensFov > 0 || settings.autoZoom))
                listener.onStatus("This camera firmware has no view crop or zoom: upload the CPECAPS firmware 07")
            else if (!info.optBoolean("ok", true)) listener.onStatus("Camera view: ${info.optString("error")}")
        } catch (e: Exception) {
            zoomPhase = ZOOM_OFF
            zoomResult = "camera did not answer"
            events?.event("camera_settings", "error" to (e.message ?: e.javaClass.simpleName))
        }
        if (savedOk && saved != null) {
            synchronized(engine) { engine.calibrator.restore(saved.baseline) }
            restoredCalibration = true
            events?.event("calibration", "result" to "restored", "saved_at_ms" to saved.savedAtMs, "source" to saved.source)
        } else {
            if (saved != null) listener.onStatus("The camera settings changed since your calibration: look at the road for 5 seconds")
            if (changed) recalibrate() // the first pictures were taken with the old view
        }
        if (settings.recordSd && running.get()) startRecording()
    }

    /** The camera view saved with a calibration (see cameraSetup). */
    private fun calibrationExtra(): JSONObject = JSONObject()
        .put("lens_fov", settings.lensFov).put("target_fov", settings.targetFov).put("camera_res", settings.cameraResolution)
        .put("zoom", lastZoom?.zoom ?: 1.0).put("cx", lastZoom?.cx ?: 0.5).put("cy", lastZoom?.cy ?: 0.5)

    private fun startRecording() {
        val name = recordName ?: (log?.dir?.name ?: ((logName ?: "phone") + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))).also { recordName = it }
        try {
            val r = camera.recordStart(name, System.currentTimeMillis())
            recordOn = r.optBoolean("ok", false) && r.optBoolean("recording", false)
            events?.event("sd_record", JSONObject(r.toString()).put("action", "start"))
            if (!recordOn) listener.onStatus("SD video is not recording: ${r.optString("error").ifEmpty { "no answer" }}")
        } catch (e: Exception) {
            events?.event("sd_record", "action" to "start", "error" to (e.message ?: e.javaClass.simpleName))
            listener.onStatus("SD video: the camera did not answer (${e.message})")
        }
    }

    /** After a camera reconnect: keep recording (a restarted camera starts a new file) and restore the zoom. */
    private fun afterReconnect() {
        try {
            val z = lastZoom
            if (z != null) {
                val info = camera.status()
                if (kotlin.math.abs(info.optDouble("zoom", 1.0) - z.zoom) > 0.01) {
                    val r = camera.setZoom(z)
                    events?.event("auto_zoom", JSONObject(r.toString()).put("result", "restored after reconnect"))
                }
            }
        } catch (_: Exception) { }
        if (settings.recordSd && recordName != null && running.get()) startRecording()
    }

    /** Zoom to the driver once enough face sizes are measured; calibration then starts again. */
    private fun applyAutoZoom() {
        try {
            val window = cameraWindow
            val cmd = window?.let { autoZoom.command(it) }
            val share = autoZoom.faceShare()
            if (window == null || cmd == null) {
                zoomResult = "kept (face already fills the view)"
                events?.event("auto_zoom", "result" to "kept", "face_share" to share)
            } else {
                val r = camera.setZoom(cmd)
                if (!r.optBoolean("ok", false)) {
                    zoomResult = "failed: ${r.optString("error")}"
                    events?.event("auto_zoom", JSONObject(r.toString()).put("result", "failed"))
                    listener.onStatus("Camera zoom failed: ${r.optString("error")}")
                } else {
                    lastZoom = cmd
                    cameraWindow = CameraControl.window(r)
                    zoomResult = String.format(Locale.US, "x%.2f", r.optDouble("zoom", cmd.zoom))
                    events?.event("auto_zoom", JSONObject(r.toString()).put("result", "zoomed").put("face_share", share)
                        .put("asked_zoom", cmd.zoom).put("asked_cx", cmd.cx).put("asked_cy", cmd.cy))
                    Thread.sleep(700) // let the new view arrive before calibrating again
                    recalibrate()
                    listener.onStatus(String.format(Locale.US,
                        "Camera zoomed to the driver (×%.2f). Look at the road while it calibrates again.", r.optDouble("zoom", cmd.zoom)))
                }
            }
        } catch (e: Exception) {
            zoomResult = "failed: ${e.message}"
            events?.event("auto_zoom", "result" to "failed", "error" to (e.message ?: e.javaClass.simpleName))
        }
        zoomPhase = ZOOM_DONE
    }

    fun recalibrate() = synchronized(engine) { engine.recalibrate() }

    /** Settings > Recalibrate during a trip: frame the camera on the face again (zoom), then calibrate and save. */
    fun userRecalibrate() {
        recalibrate()
        poseCheckDone = true
        events?.event("calibration", "result" to "recalibrate requested")
        if (settings.autoZoom && cameraWindow != null) Thread({
            try {
                val r = camera.resetZoom()
                cameraWindow = CameraControl.window(r) ?: cameraWindow
                lastZoom = null
                autoZoom.reset()
                zoomResult = "measuring"
                zoomPhase = ZOOM_MEASURING
            } catch (_: Exception) { }
        }, "recalibrate-zoom").apply { isDaemon = true; start() }
    }

    /** After each step: save a calibration measured during this trip; warn once if the camera seems to have moved. */
    private fun afterStep(before: String, out: EngineOutput, f: FrameFeatures) {
        if (before == edu.dlsu.esca_3_2526_c3.dms.CALIBRATING && out.calibrationStatus == CALIBRATED) {
            CalibrationStore.save(context, engine.calibrator.baseline.copy(), "trip", calibrationExtra())
            events?.event("calibration", "result" to "saved")
            listener.onStatus("Calibrated and saved: next trips start right away")
        }
        if (!restoredCalibration || poseCheckDone || out.calibrationStatus != CALIBRATED) return
        val yaw = f.yaw ?: return
        val pitch = f.pitch ?: return
        if (!f.facePresent) return
        poseCheck += yaw to pitch
        if (poseCheck.size < 40) return
        poseCheckDone = true
        val b = engine.calibrator.baseline
        val dy = edu.dlsu.esca_3_2526_c3.dms.Num.median(poseCheck.map { it.first }) - b.yaw
        val dp = edu.dlsu.esca_3_2526_c3.dms.Num.median(poseCheck.map { it.second }) - b.pitch
        if (kotlin.math.abs(dy) > 15.0 || kotlin.math.abs(dp) > 15.0) {
            events?.event("calibration", "result" to "camera may have moved", "yaw_diff" to dy, "pitch_diff" to dp)
            listener.onStatus("The camera seems to have moved since calibration. If alerts seem wrong: Settings > Calibration > Recalibrate")
        }
    }

    /** Stop everything; the buzzer is switched off; returns the session summary (or null). */
    fun stop(): JSONObject? {
        if (!running.compareAndSet(true, false)) return null
        procThread?.join(2000)
        yoloThread?.interrupt()
        yoloThread?.join(1000)
        streamThread?.interrupt()
        streamThread?.join(1000)
        buzzer?.close()
        poller?.close()
        face?.close()
        yolo?.close()
        eye?.close()
        phoneGps?.stop()
        simGps?.stop()
        if (recordOn) {
            try {
                val r = camera.recordStop()
                events?.event("sd_record", JSONObject(r.toString()).put("action", "stop"))
            } catch (e: Exception) {
                events?.event("sd_record", "action" to "stop", "error" to (e.message ?: e.javaClass.simpleName))
            }
        }
        detail?.close()
        val summary = log?.let { l ->
            val extra = JSONObject()
                .put("stream", JSONObject().put("frames_received", framesIn).put("reconnects", reconnects).put("camera_lost_events", cameraLostEvents))
                .put("sensor_poll_errors", poller?.errors ?: 0)
                .put("buzzer_failures", buzzer?.failures ?: 0)
                .put("driving_duration_min", synchronized(sensorLock) { sensors.duration.continuousS / 60.0 })
                .put("camera_view", JSONObject().put("lens_fov", settings.lensFov).put("target_fov", settings.targetFov)
                    .put("auto_zoom", zoomResult).put("zoom", lastZoom?.zoom ?: 1.0))
                .put("sd_video", JSONObject().put("requested", settings.recordSd).put("name", recordName ?: JSONObject.NULL)
                    .put("recording", recordOn))
            try { l.close(engine.calibrator, extra) } catch (_: Exception) { null }
        }
        events?.event("session_stop", JSONObject().put("frames", summary?.optInt("frames") ?: 0).put("reconnects", reconnects)
            .put("camera_lost_events", cameraLostEvents).put("phone", BackgroundHelp.phoneState(context)))
        events?.close()
        if (EventLog.current === events) EventLog.current = null
        wifi.stop()
        return summary
    }

    // ------------------------------------------------------------------ camera stream
    private fun readStream() {
        while (running.get()) {
            var conn: HttpURLConnection? = null
            try {
                conn = wifi.open(settings.streamUrl, 5000)
                val reader = MjpegReader(conn.inputStream)
                listener.onStatus("Camera connected")
                events?.event("camera_connected", "reconnects" to reconnects)
                if (reconnects > 0) Thread(::afterReconnect, "camera-reconnect").apply { isDaemon = true; start() }
                while (running.get()) {
                    val jpeg = reader.next()
                    latest.set(Pair(jpeg, now())) // older unprocessed frames are dropped: delay cannot build up
                    framesIn++
                }
            } catch (e: Exception) {
                if (!running.get()) break
                reconnects++
                events?.event("camera_error", "error" to (e.message ?: e.javaClass.simpleName), "reconnects" to reconnects,
                    "wifi" to (wifi.network != null))
                listener.onStatus("Camera: ${e.message ?: e.javaClass.simpleName} - retrying (close any browser tab showing the stream)")
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ------------------------------------------------------------------ pipeline
    private fun process() {
        var lastFrameT: Double? = null
        var lostReported = false
        var index = 0
        var lastDets: List<Detection> = emptyList()
        var fpsEma = 0.0
        var lastWall = SystemClock.elapsedRealtimeNanos()
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        while (running.get()) {
            val item = latest.getAndSet(null)
            if (item == null) {
                val lt = lastFrameT
                if (lt != null && !lostReported && now() - lt > cfg.alerts.cameraTimeoutS) {
                    val cmds = synchronized(engine) { engine.cameraLost(lt + cfg.alerts.cameraTimeoutS, "no frame for ${cfg.alerts.cameraTimeoutS} s") }
                    dispatch(cmds)
                    log?.logAlerts(cmds)
                    lostReported = true
                    cameraLostEvents++
                    events?.event("camera_lost", "seconds_without_picture" to cfg.alerts.cameraTimeoutS,
                        "wifi" to (wifi.network != null), "app_in_front" to edu.dlsu.esca_3_2526_c3.DanteyeApp.inFront)
                    listener.onStatus("No picture from the camera: monitoring unavailable")
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }
            lostReported = false
            val (jpeg, t) = item
            val start = SystemClock.elapsedRealtimeNanos()
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: continue
            val timings = linkedMapOf<String, Double>()
            fun ms(from: Long) = (SystemClock.elapsedRealtimeNanos() - from) / 1e6

            var s = SystemClock.elapsedRealtimeNanos()
            val faceObs = try { face?.detect(bmp, (t * 1000).toLong()) } catch (e: Exception) { null }
            timings["face_ms"] = ms(s)
            // CPECAPS: measure the face size for the zoom to the driver (once per trip).
            if (zoomPhase == ZOOM_MEASURING && faceObs != null && autoZoom.add(faceObs.bbox, bmp.width, bmp.height)) {
                zoomPhase = ZOOM_SENDING
                Thread(::applyAutoZoom, "auto-zoom").apply { isDaemon = true; start() }
            }
            val e = eye
            var eyeProb: Double? = null
            if (e != null && faceObs != null) {
                s = SystemClock.elapsedRealtimeNanos()
                eyeProb = try { e.predict(bmp, faceObs) } catch (_: Exception) { null }
                timings["eye_ms"] = ms(s)
            }
            val y = yolo
            if (y != null) {
                if (index % maxOf(1, settings.yoloEvery) == 0) yoloInput.set(bmp) // the YOLO thread takes the newest picture
                lastDets = yoloBoxes.get()
                timings["yolo_ms"] = yoloMs // last YOLO run (on its own thread)
            }
            val dets = lastDets.map { Detection(it.label, it.confidence, it.box, it.role) }
            timings["perception_ms"] = ms(start)
            s = SystemClock.elapsedRealtimeNanos()
            val f = extractor.extract(PerceptionResult(bmp.width, bmp.height, faceObs, dets, eyeProb, y?.roles ?: emptySet()), t)
            timings["features_ms"] = ms(s)
            s = SystemClock.elapsedRealtimeNanos()
            val ctx = synchronized(sensorLock) { sensors.read(t) }
            val before = engine.calibrator.status
            val out = synchronized(engine) { engine.step(f, ctx) }
            afterStep(before, out, f)
            timings["decision_ms"] = ms(s)
            timings["total_ms"] = ms(start)
            dispatch(out.alerts)
            log?.log(index, f, out, timings)
            detail?.log(index, t, bmp.width, bmp.height, faceObs, dets, f, out)

            val wall = SystemClock.elapsedRealtimeNanos()
            val inst = 1e9 / maxOf(1L, wall - lastWall)
            lastWall = wall
            fpsEma = if (fpsEma == 0.0) inst else 0.9 * fpsEma + 0.1 * inst
            val cal = engine.calibrator
            val aim = if (out.calibrationStatus != CALIBRATED || cal.warning.isNotEmpty()) aimer.update(f.yaw, f.pitch) else null
            listener.onFrame(LiveFrame(draw(bmp, faceObs, dets, out), f, out, fpsEma, timings.getValue("total_ms"), aim,
                cal.progress(t), cal.warning, cal.lastFailure, poller?.lastJson, y != null))
            lastFrameT = t
            index++
        }
    }

    /** YOLO thread: detect on the newest picture, publish the boxes, repeat. */
    private fun runYolo() {
        val y = yolo ?: return
        while (running.get()) {
            val bmp = yoloInput.getAndSet(null)
            if (bmp == null) { try { Thread.sleep(5) } catch (_: InterruptedException) { break }; continue }
            val s = SystemClock.elapsedRealtimeNanos()
            val boxes = try { y.detect(bmp) } catch (_: Exception) { emptyList() }
            yoloMs = (SystemClock.elapsedRealtimeNanos() - s) / 1e6
            yoloBoxes.set(boxes)
        }
    }

    private fun dispatch(cmds: List<AlertCommand>) {
        val pushed = cmds.any { it.event == "push" }
        for (c in cmds) {
            buzzer?.send(c)
            listener.onAlert(c, pushed && (c.event == "start" || c.event == "escalate"))
        }
    }

    // ------------------------------------------------------------------ overlay
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun draw(src: Bitmap, face: FaceObservation?, dets: List<Detection>, out: EngineOutput): Bitmap {
        val img = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(img)
        val r = maxOf(2f, img.width / 220f)
        if (face != null) {
            val p = face.landmarks
            paint.style = Paint.Style.FILL
            paint.color = Color.CYAN
            for (i in Geometry.RIGHT_EYE_EAR + Geometry.LEFT_EYE_EAR) c.drawCircle(p[i][0].toFloat(), p[i][1].toFloat(), r, paint)
            paint.color = Color.MAGENTA
            // Full outer and inner lip outlines (MediaPipe mesh), then the 8 points the mouth measurement uses.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1.5f, r / 2)
            for (contour in listOf(LIPS_OUTER, LIPS_INNER)) {
                val path = android.graphics.Path()
                contour.forEachIndexed { k, i -> if (k == 0) path.moveTo(p[i][0].toFloat(), p[i][1].toFloat()) else path.lineTo(p[i][0].toFloat(), p[i][1].toFloat()) }
                path.close()
                c.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
            for (i in Geometry.MOUTH_MAR) c.drawCircle(p[i][0].toFloat(), p[i][1].toFloat(), r, paint)
            paint.color = Color.GREEN
            for (i in HeadPose.POSE_LANDMARKS) c.drawCircle(p[i][0].toFloat(), p[i][1].toFloat(), r * 1.3f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r / 2
            paint.color = Color.LTGRAY
            val b = face.bbox
            c.drawRect(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(), paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r
        for (d in dets) {
            paint.color = when (d.role) {
                "phone" -> Color.RED; "eye_closed" -> Color.rgb(255, 140, 0); "eye_open" -> Color.GREEN; "yawn" -> Color.MAGENTA
                else -> continue
            }
            if (!d.inDriverRoi) paint.color = Color.GRAY
            c.drawRect(d.box[0].toFloat(), d.box[1].toFloat(), d.box[2].toFloat(), d.box[3].toFloat(), paint)
        }
        paint.style = Paint.Style.FILL
        val tierColor = when (out.activeTier) { "HIGH" -> Color.RED; "MEDIUM" -> Color.rgb(255, 140, 0); "LOW" -> Color.YELLOW; else -> Color.GREEN }
        paint.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, img.width.toFloat(), r * 9, paint)
        paint.color = tierColor
        paint.textSize = r * 6
        val label = "${out.alertState} ${if (out.activeTier != "NONE") out.activeTier else ""}  risk ${out.risk.tier}  " +
            out.behavior.drowsinessState.replace('_', ' ')
        c.drawText(label, r * 2, r * 7, paint)
        return img
    }
}
