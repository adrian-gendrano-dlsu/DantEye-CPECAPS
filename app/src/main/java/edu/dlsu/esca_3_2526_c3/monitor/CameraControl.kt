package edu.dlsu.esca_3_2526_c3.monitor

import edu.dlsu.esca_3_2526_c3.dms.CameraWindow
import edu.dlsu.esca_3_2526_c3.dms.ZoomCommand
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * CPECAPS: the ESP32-CAM's settings (firmware 07): view crop for a wide lens, zoom to the driver and
 * video recording to its microSD card. Requests go over DMS-NET (WifiNet). Errors throw.
 */
class CameraControl(private val wifi: WifiNet, baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    /** GET <camera><path> and parse the JSON answer (also for an error status, which carries "error"). */
    fun get(path: String, timeoutMs: Int = 4000): JSONObject {
        val c = wifi.open("$base$path", timeoutMs)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject().put("ok", false).put("error", "HTTP $code") }
            if (code !in 200..299 && !json.has("error")) json.put("error", "HTTP $code")
            return json
        } finally {
            c.disconnect()
        }
    }

    fun status(): JSONObject = get("/camera")

    /** lens = lens angle in degrees (0 = normal lens, no crop); fov = the wanted view in degrees. */
    fun setView(lens: Int, fov: Int): JSONObject = get("/camera?lens=$lens&fov=$fov")

    fun setZoom(cmd: ZoomCommand): JSONObject =
        get(String.format(Locale.US, "/camera?zoom=%.2f&cx=%.3f&cy=%.3f", cmd.zoom, cmd.cx, cmd.cy))

    fun resetZoom(): JSONObject = setZoom(ZoomCommand(1.0, 0.5, 0.5))

    fun recordStart(name: String, epochMs: Long): JSONObject =
        get("/record?start=1&name=${URLEncoder.encode(name, "UTF-8")}&t=$epochMs")

    fun recordStop(): JSONObject = get("/record?stop=1")

    fun recordStatus(): JSONObject = get("/record")

    companion object {
        /** The crop window in a /camera answer, or null for old firmware without the view crop. */
        fun window(json: JSONObject): CameraWindow? {
            val w = json.optJSONObject("window") ?: return null
            return CameraWindow(
                w.optDouble("x", 0.0), w.optDouble("y", 0.0), w.optDouble("w", 1600.0), w.optDouble("h", 1200.0),
                json.optDouble("sensor_w", 1600.0), json.optDouble("sensor_h", 1200.0),
                json.optDouble("zoom", 1.0), json.optDouble("max_zoom", 1.0))
        }

        /** One line for the screen, e.g. "VGA 640×480, lens 160° → view 68°, zoom ×1.30". */
        fun describe(json: JSONObject): String {
            if (json.optBoolean("ok", true) == false) return "error: ${json.optString("error")}"
            val lens = json.optInt("lens", 0)
            val view = if (lens > 0) ", lens $lens° → view ${json.optInt("fov")}°" else ", normal lens"
            val zoom = json.optDouble("zoom", 1.0)
            return "${json.optString("res")} ${json.optInt("width")}×${json.optInt("height")}$view" +
                (if (zoom > 1.001) String.format(Locale.US, ", zoom ×%.2f", zoom) else "")
        }

        /** One line about the SD card, e.g. "SD card: 14830 MB free, recording phone_... (1200 frames)". */
        fun describeRecord(json: JSONObject): String {
            if (!json.optBoolean("sd", false)) return "SD card: none found" +
                json.optString("error").let { if (it.isNotEmpty()) " ($it)" else "" }
            val free = "${json.optLong("free_mb")} MB free of ${json.optLong("card_mb")} MB"
            val rec = if (json.optBoolean("recording")) ", recording ${json.optString("name")} " +
                "(${json.optInt("frames")} frames, ${json.optInt("seconds")} s)" else ", not recording"
            val err = json.optString("error").let { if (it.isNotEmpty()) " - $it" else "" }
            return "SD card: $free$rec$err"
        }
    }
}
