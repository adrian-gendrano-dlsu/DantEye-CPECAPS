package edu.dlsu.esca_3_2526_c3

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import edu.dlsu.esca_3_2526_c3.dms.CALIBRATED
import edu.dlsu.esca_3_2526_c3.dms.CALIBRATING
import edu.dlsu.esca_3_2526_c3.dms.DriverCalibrator
import edu.dlsu.esca_3_2526_c3.monitor.ModelFiles
import edu.dlsu.esca_3_2526_c3.dms.AutoZoom
import edu.dlsu.esca_3_2526_c3.dms.CameraWindow
import edu.dlsu.esca_3_2526_c3.dms.ZoomCommand
import edu.dlsu.esca_3_2526_c3.monitor.CameraControl
import edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings
import edu.dlsu.esca_3_2526_c3.monitor.WifiNet
import org.json.JSONObject

/**
 * Setup guide (first start, and later from Settings): how the app works and what the screen means,
 * notifications, DMS-NET Wi-Fi, device tests, camera position, done.
 * The camera-position step runs the same MediaPipe face model as monitoring and gives the
 * advice of scripts/aim_camera.py, and its 5-second check calibrates the driver (normal eyes, mouth and head
 * position) and saves it (CalibrationStore), so trips start without waiting. Settings > Calibration >
 * Recalibrate opens this step again (intent extra "step" = 4).
 */
class SetupActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("driver_setup_v1", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val devices by lazy { DeviceStatusMonitor(this, aim = true) { refreshDeviceViews() } }
    private lateinit var content: LinearLayout
    private var step = 0
    private var alignment = false
    private var runningCheck = false
    private var checkEnds = 0L
    private var calibrated = false
    private var connections: LinearLayout? = null
    private var preview: LinearLayout? = null
    private var offsetLabel: TextView? = null
    private var offsetMeter: SeekBar? = null
    private var startCheck: Button? = null
    private var nextButton: Button? = null
    private var progress: ProgressBar? = null
    private var countdown: TextView? = null
    private var feedback: TextView? = null
    private var cal: DriverCalibrator? = null
    private var waitUntil = 0L
    @Volatile private var setupZoom: ZoomCommand? = null

    private val timer = object : Runnable {
        override fun run() {
            if (!runningCheck) return

            val remaining = (checkEnds - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            progress?.progress = (5000L - remaining).toInt()

            countdown?.text = if (remaining > 0L)
                "Look straight ahead for 5 seconds · ${(remaining + 999L) / 1000L} s remaining"
            else "Five seconds completed"

            if (remaining > 0L) handler.postDelayed(this, 100L)
            else {
                val c = cal
                val done = c == null || synchronized(c) { c.status != CALIBRATING || c.lastFailure.isNotEmpty() }
                if (!done && SystemClock.elapsedRealtime() < waitUntil) { handler.postDelayed(this, 200L); return }
                runningCheck = false
                devices.fast = false
                devices.onFace = null
                val state = devices.state
                val ok = c != null && synchronized(c) { c.status == CALIBRATED }
                val angleOk = c != null && c.warning.isEmpty()
                calibrated = ok && angleOk
                if (calibrated && c != null) {
                    CalibrationStore.save(this@SetupActivity, c.baseline.copy(), "setup", calibrationExtra())
                    prefs.edit().putLong("aligned_at", System.currentTimeMillis()).apply()
                }
                feedback?.text = when {
                    calibrated -> "Done: your calibration is saved, so trips start right away. " +
                            "To redo it later: Settings > Calibration > Recalibrate."
                    ok && !angleOk -> "The camera is too far to the side. Follow the advice above and try again."
                    !state.camera -> "The camera picture was lost. Check the camera and try again."
                    c != null && c.lastFailure.isNotEmpty() -> plainFailure(c.lastFailure)
                    else -> "The camera did not see your face clearly enough. Check the light and the camera position, then try again."
                }
                startCheck?.isEnabled = state.faceFound
                startCheck?.text = "Calibrate again"
                nextButton?.isEnabled = calibrated
            }
        }
    }

    /** The calibrator's reason in plain words. */
    private fun plainFailure(reason: String): String = when {
        "head not steady" in reason -> "Keep your head still and look at the road for the whole 5 seconds, then try again."
        "eyes not open" in reason -> "Keep your eyes open normally (not squinting), then try again."
        "mouth" in reason -> "Keep your mouth relaxed and closed, then try again."
        "eyeBlink" in reason -> "Try not to blink much during the 5 seconds, then try again."
        "usable frames" in reason -> "The camera did not see your face clearly enough. Check the light and the camera position, then try again."
        else -> "Calibration did not work. Look at the road normally and try again."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.nightMode = Ui.nightPreference(this)
        step = savedInstanceState?.getInt("step") ?: intent.getIntExtra("step", 0)
        calibrated = CalibrationStore.load(this) != null
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.canvas); isFillViewport = true } // dark in night mode too
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SetupActivity, 16), Ui.dp(this@SetupActivity, 16),
                Ui.dp(this@SetupActivity, 16), Ui.dp(this@SetupActivity, 24))
        }
        scroll.addView(content)
        // Navy top bar behind the Android status bar, then the scrolling page.
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.canvas) }
        val header = Ui.header(this, "Setup guide")
        screen.addView(header)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        Ui.fitSystemBars(this, screen, header)
        render()
    }

    override fun onResume() {
        super.onResume()
        devices.start()
    }

    override fun onPause() {
        handler.removeCallbacks(timer)
        runningCheck = false
        devices.fast = false
        devices.onFace = null
        devices.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("step", step)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 90 && step == 1) render()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun goTo(next: Int) {
        handler.removeCallbacks(timer)
        runningCheck = false
        step = next
        alignment = false
        render()
    }

    private fun render() {
        connections = null
        preview = null
        offsetLabel = null
        offsetMeter = null
        startCheck = null
        nextButton = null
        progress = null
        countdown = null
        feedback = null
        content.removeAllViews()
        content.addView(Ui.title(this, if (alignment) "Camera alignment" else "First-time setup"))
        content.addView(Ui.note(this, "Step ${step + 1} of 6"))
        content.addView(Ui.space(this, 12))
        if (alignment) showAlignment() else when (step) {
            0 -> showIntro()
            1 -> showNotifications()
            2 -> showWifi()
            3 -> showDevices()
            4 -> showCalibration()
            else -> showDone()
        }
        if (!alignment) {
            if (step > 0 && step < 5) content.addView(button("Previous step") { goTo(step - 1) })
            if (step < 5) content.addView(button("Skip for now") { finish() })
        }
    }

    /** Step 1: how Danteye works and what the screen means. */
    private fun showIntro() {
        val how = Ui.card(this)
        how.addView(Ui.title(this, "Welcome to Danteye"))
        how.addView(Ui.note(this, "Danteye watches for signs of tiredness and distraction while you drive, and warns you in time."))
        for ((step, text) in listOf(
            "1. Watch" to "A small camera in front of you sends its picture to this phone over the DMS-NET Wi-Fi. " +
                    "The picture is analysed on the phone only; it is not saved or sent anywhere.",
            "2. Learn your face" to "In the first 5 seconds of every drive the app learns how your eyes and face normally look " +
                    "(CALIBRATING). Look at the road normally.",
            "3. Detect" to "It notices eyes closing, yawning, looking away from the road and using a phone, " +
                    "and combines them with speed and sudden movements from the sensor board.",
            "4. Warn" to "The buzzer on the sensor board beeps, and the phone can speak a short instruction such as " +
                    "\"Eyes on the road\" and vibrate.",
            "5. Share" to "Your supervisor sees your risk level, alerts and location while you monitor. " +
                    "History keeps every session on this phone.")) {
            how.addView(Ui.title(this, step).apply { textSize = 15f; setPadding(0, Ui.dp(this@SetupActivity, 6), 0, 0) })
            how.addView(Ui.note(this, text).apply { setPadding(0, 0, 0, Ui.dp(this@SetupActivity, 4)) })
        }
        content.addView(how, Ui.blockParams(this))

        val screen = Ui.card(this)
        screen.addView(Ui.title(this, "What the screen means"))
        screen.addView(Ui.note(this, "The big panel on the Home screen shows your risk level:"))
        for ((level, text) in listOf(
            "NORMAL" to "No warning signs. Keep driving safely.",
            "LOW" to "Mild signs, e.g. a yawn or a short look away. Stay alert.",
            "MEDIUM" to "Clear signs, e.g. eyes closing or looking away for seconds. The buzzer beeps.",
            "HIGH" to "Dangerous, e.g. a long eye closure or using the phone. Take a break.")) {
            screen.addView(Ui.alert(this, text, "", level, Ui.riskColor(level)))
        }
        screen.addView(Ui.note(this, "Other messages: CALIBRATING (look at the road for 5 seconds), " +
                "NO CAMERA PICTURE (check the camera power and the Wi-Fi), MONITORING OFF (press Start monitoring)."))
        screen.addView(Ui.note(this, "The badges under the panel say whether the camera, the sensor board, the GPS and the " +
                "supervisor link are Online or Offline. Settings has the alert options, night mode, the device tests and this guide."))
        screen.addView(Ui.note(this, "Never touch the phone while driving: set everything up before you start."))
        screen.addView(button("Next: notifications") { goTo(1) })
        content.addView(screen, Ui.blockParams(this))
    }

    private fun showNotifications() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Allow notifications"))
        card.addView(Ui.note(this, "Notifications keep the reminder and HIGH alerts visible."))
        val granted = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        card.addView(Ui.note(this, if (granted) "Notifications are permitted." else "Notifications are not yet permitted."))
        if (!granted) card.addView(button("Allow notifications") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
        })
        content.addView(card, Ui.blockParams(this))

        // CPECAPS: monitoring must keep running when Danteye is not on the screen.
        val bg = Ui.card(this)
        bg.addView(Ui.title(this, "Keep running in the background"))
        val unrestricted = BackgroundHelp.isUnrestricted(this)
        bg.addView(Ui.note(this, "While you drive, Danteye works with the screen off or with another app (e.g. maps) in front. " +
                "Battery saving would pause it, so allow it to run in the background."))
        bg.addView(Ui.deviceIndicator(this, "Runs in the background", unrestricted, onText = "Allowed", offText = "Not allowed yet"))
        if (!unrestricted) bg.addView(button("Allow running in the background") { BackgroundHelp.askUnrestricted(this) })
        bg.addView(Ui.note(this, BackgroundHelp.backgroundSteps()))
        bg.addView(button("Open Danteye's app settings") { BackgroundHelp.openAppSettings(this) })
        bg.addView(button("Continue to Wi-Fi") { goTo(2) })
        content.addView(bg, Ui.blockParams(this))
    }

    private fun showWifi() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Join DMS-NET"))
        card.addView(Ui.note(this, "Open Wi-Fi settings and connect your phone to DMS-NET " +
                "(password ${edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings(this).dmsNetPassword}). " +
                "When Android says the network has no internet, choose Stay connected. Keep mobile data on for the supervisor. " +
                "Return here afterward."))
        card.addView(Ui.note(this, "The app cannot verify the Wi-Fi network name without additional location permission."))
        card.addView(button("Open Wi-Fi settings") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        content.addView(card, Ui.blockParams(this))

        // CPECAPS: the boards are on DMS-NET (no internet); the supervisor link needs mobile data at the same time.
        val data = Ui.card(this)
        data.addView(Ui.title(this, "Use Wi-Fi and mobile data at the same time"))
        data.addView(Ui.note(this, "Danteye talks to the camera and the sensor board over DMS-NET, and to your supervisor " +
                "over mobile data. Allow Danteye to use both:"))
        data.addView(Ui.note(this, BackgroundHelp.dataSteps()))
        data.addView(button("Open data usage settings") { BackgroundHelp.openDataUsage(this) })
        data.addView(button("I have joined DMS-NET") { goTo(3) })
        content.addView(data, Ui.blockParams(this))
    }

    private fun showDevices() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Test the devices"))
        connections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(connections)
        card.addView(button("Run tests again") {
            devices.stop()
            devices.start()
            refreshDeviceViews()
        })
        card.addView(button("Continue to the camera position") { goTo(4) })
        content.addView(card, Ui.blockParams(this))
        refreshDeviceViews()
    }

    private fun showCalibration() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Camera position and calibration"))
        card.addView(Ui.note(this, "Sit in your driving position and look at the road for 5 seconds with a relaxed face. " +
                "The app learns your normal face and saves it, so your trips start right away. " +
                "The camera can be up to ${"%.0f".format(ModelLimits.cameraOffsetDeg(this))}° to the side."))
        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(preview)
        offsetLabel = Ui.note(this, "Waiting for a camera picture…")
        card.addView(offsetLabel)
        card.addView(button("Camera alignment helper") {
            alignment = true
            render()
        })
        countdown = Ui.note(this, CalibrationStore.describe(this) ?: "Not calibrated yet")
        card.addView(countdown)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 5000
            progress = 0
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(this, 20)))
        feedback = Ui.note(this, if (calibrated) "You can calibrate again if you changed the camera or the driver." else "Your face must be visible to start.")
        card.addView(feedback)
        startCheck = button(if (calibrated) "Calibrate again" else "Start 5-second calibration") { startCalibration() }
        card.addView(startCheck)
        nextButton = button("Continue to Done") { goTo(5) }.apply { isEnabled = calibrated }
        card.addView(nextButton)
        content.addView(card, Ui.blockParams(this))
        refreshDeviceViews()
    }

    private fun showAlignment() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Center your face"))
        card.addView(Ui.note(this, "Move the camera until your face is near the picture center."))

        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(preview)

        offsetLabel = Ui.note(this, "Waiting for a camera picture…")
        card.addView(offsetLabel)

        card.addView(Ui.note(this, "Left                         Center                         Right"))
        offsetMeter = SeekBar(this).apply { max = 200; progress = 100; isEnabled = false }
        card.addView(offsetMeter)

        card.addView(button("Back to calibration") { alignment = false; render() })
        content.addView(card, Ui.blockParams(this))
        refreshDeviceViews()
    }

    private fun startCalibration() {
        if (runningCheck || !devices.state.camera || !devices.state.faceFound) return
        calibrated = false
        startCheck?.isEnabled = false
        nextButton?.isEnabled = false
        if (DeviceSettings(this).autoZoom) startFraming() else { setupZoom = null; beginCalibrationWindow() }
    }

    /** CPECAPS: before calibrating, zoom the camera to the face (the same view is used on every trip). */
    private fun startFraming() {
        runningCheck = true
        countdown?.text = "Framing the camera on your face…"
        feedback?.text = "Look at the road normally."
        val settings = DeviceSettings(this)
        Thread {
            val wifi = WifiNet(this).apply { start() }
            var waited = 0
            while (wifi.network == null && waited < 2000) { Thread.sleep(100); waited += 100 }
            val cam = CameraControl(wifi, settings.cameraUrl)
            var window: CameraWindow? = null
            try {
                var info = cam.status()
                if (info.has("window") && info.optString("sensor") == "OV2640") {
                    if (info.optInt("lens", -1) != settings.lensFov || info.optInt("fov", -1) != settings.targetFov)
                        info = cam.setView(settings.lensFov, settings.targetFov)
                    if (info.optDouble("zoom", 1.0) > 1.001) info = cam.resetZoom()
                    window = CameraControl.window(info)
                }
            } catch (_: Exception) { }
            var zoom = ZoomCommand(1.0, 0.5, 0.5)
            if (window != null) {
                Thread.sleep(800) // the new view arrives
                val az = AutoZoom(samplesNeeded = 5)
                val latch = java.util.concurrent.CountDownLatch(1)
                devices.onFace = { _, obs, frame -> synchronized(az) { if (az.add(obs.bbox, frame.width, frame.height)) latch.countDown() } }
                devices.fast = true
                latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
                devices.onFace = null
                val cmd = synchronized(az) { if (az.ready) az.command(window) else null }
                if (cmd != null) try {
                    if (cam.setZoom(cmd).optBoolean("ok", false)) zoom = cmd
                    Thread.sleep(900)
                } catch (_: Exception) { }
            }
            wifi.stop()
            setupZoom = zoom
            runOnUiThread { if (!isFinishing && runningCheck) beginCalibrationWindow() }
        }.start()
    }

    /** The 5-second calibration: every face picture goes into a calibrator, as during a trip. */
    private fun beginCalibrationWindow() {
        val cfg = ModelFiles.config(this)
        val c = DriverCalibrator(cfg.calibration, cfg.thresholds)
        cal = c
        devices.onFace = { f, _, _ -> synchronized(c) { c.collect(f) } }
        devices.fast = true
        runningCheck = true
        checkEnds = SystemClock.elapsedRealtime() + 5000L
        waitUntil = checkEnds + 3000L
        feedback?.text = "Keep looking at the road normally while the timer runs."
        timer.run()
    }

    /** The camera view used while calibrating (MonitorSession uses the calibration only with the same view). */
    private fun calibrationExtra(): JSONObject {
        val s = DeviceSettings(this)
        val z = setupZoom ?: ZoomCommand(1.0, 0.5, 0.5)
        return JSONObject().put("lens_fov", s.lensFov).put("target_fov", s.targetFov).put("camera_res", s.cameraResolution)
            .put("zoom", z.zoom).put("cx", z.cx).put("cy", z.cy)
    }

    private fun showDone() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Setup done"))
        card.addView(Ui.note(this, if (CalibrationStore.load(this) != null)
            "You are calibrated. Press Start monitoring on the Home screen: no waiting."
            else "Press Start monitoring on the Home screen and look at the road for the first 5 seconds: " +
                "the app calibrates to you once and remembers it."))
        card.addView(button("Finish setup") {
            prefs.edit().putBoolean("complete", true).apply()
            finish()
        })
        content.addView(card, Ui.blockParams(this))
    }

    /** Only the camera actually delivers a face image; sensor yaw cannot locate the camera. */
    private fun refreshDeviceViews() {
        if (!::content.isInitialized) return
        val state = devices.state
        connections?.apply {
            removeAllViews()
            val sensorSet = edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings(this@SetupActivity).sensorUrl.isNotBlank()
            fun badge(v: android.view.View) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@SetupActivity, 4) })
            badge(Ui.deviceIndicator(this@SetupActivity, "📷 Camera", state.camera))
            badge(Ui.deviceIndicator(this@SetupActivity, "▣ Sensor board", state.sensor, notSet = !sensorSet))
            badge(Ui.deviceIndicator(this@SetupActivity, "⌖ GPS", state.gps, "Location fix", "No fix yet", notSet = !sensorSet))
        }
        preview?.apply {
            removeAllViews()
            if (state.photo != null && state.camera) {
                addView(ImageView(this@SetupActivity).apply {
                    setImageBitmap(state.photo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Most recent camera JPEG"
                }, LinearLayout.LayoutParams(-1, Ui.dp(this@SetupActivity, 200)))
            } else addView(Ui.placeholder(this@SetupActivity,
                "NO CAMERA PICTURE", "Check the camera power and Wi-Fi connection", 200))
        }
        offsetMeter?.progress = (100 + (state.faceOffsetPercent ?: 0)).coerceIn(0, 200)
        val aim = state.aim
        offsetLabel?.text = when {
            DriverMonitoringService.active -> "Monitoring is running: stop it to use the camera here."
            !state.camera -> "Camera is offline. No alignment measurement available."
            !state.faceFound -> "No face detected. Look ahead in good lighting."
            aim?.offset == null -> "Face found. Measuring the camera angle …"
            aim.ok -> String.format(java.util.Locale.US, "Camera angle %.0f° (limit %.0f°): good.", aim.offset, aim.limit)
            else -> String.format(java.util.Locale.US, "Camera angle %.0f° (limit %.0f°). Left/right: %s. Up/down: %s.",
                aim.offset, aim.limit, aim.horizontal, aim.vertical)
        }
        startCheck?.isEnabled = !runningCheck && state.camera && state.faceFound
    }
}

/** Limits of the detection settings shown in the setup texts. */
private object ModelLimits {
    fun cameraOffsetDeg(context: android.content.Context): Double =
        try { ModelFiles.config(context).calibration.maxCameraOffsetDeg } catch (_: Exception) { 25.0 }
}
