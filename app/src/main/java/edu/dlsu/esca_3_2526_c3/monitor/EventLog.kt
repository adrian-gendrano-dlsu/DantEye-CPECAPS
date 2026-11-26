package edu.dlsu.esca_3_2526_c3.monitor

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * CPECAPS: everything that happens during a session besides the per-frame measurements (frames.jsonl),
 * so a drive can be checked afterwards:
 *   events.jsonl   one line per event: app in front / in the background, screen on / off, Wi-Fi network,
 *                  camera connected / errors / lost, camera view and zoom, SD recording, phone state
 *                  (battery, temperature, power saving, battery optimisation), session start / stop
 *   sensors.jsonl  every reading from the sensor board (/data) and every phone GPS fix
 * Each line has "t" (seconds since the session started, the same clock as frames.jsonl), "wall_ms"
 * (phone time, Unix ms; the SD-card video's .csv uses the same clock) and "type".
 * Lines are written at once, so a crash or a killed app loses nothing.
 */
class EventLog(val dir: File, private val clock: () -> Double) {
    private val events: BufferedWriter
    private val sensors: BufferedWriter
    @Volatile private var closed = false

    init {
        dir.mkdirs()
        events = File(dir, "events.jsonl").bufferedWriter()
        sensors = File(dir, "sensors.jsonl").bufferedWriter()
    }

    private fun line(type: String, fields: JSONObject?): JSONObject {
        val rec = JSONObject()
            .put("t", Math.round(clock() * 1000.0) / 1000.0)
            .put("wall_ms", System.currentTimeMillis())
            .put("type", type)
        if (fields != null) for (k in fields.keys()) rec.put(k, fields.get(k))
        return rec
    }

    @Synchronized
    fun event(type: String, fields: JSONObject? = null) {
        if (closed) return
        try { events.write(line(type, fields).toString()); events.newLine(); events.flush() } catch (_: Exception) { }
    }

    fun event(type: String, vararg pairs: Pair<String, Any?>) =
        event(type, JSONObject().apply { for ((k, v) in pairs) put(k, v ?: JSONObject.NULL) })

    /** source: "board" (sensor board /data) or "phone_gps". */
    @Synchronized
    fun sensor(source: String, data: JSONObject) {
        if (closed) return
        try {
            sensors.write(line(source, JSONObject().put("data", data)).toString()); sensors.newLine(); sensors.flush()
        } catch (_: Exception) { }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try { events.close() } catch (_: Exception) { }
        try { sensors.close() } catch (_: Exception) { }
    }

    companion object {
        /** The running session's log (null when not monitoring or logs are off): app-wide events go here. */
        @Volatile var current: EventLog? = null

        fun note(type: String, vararg pairs: Pair<String, Any?>) { current?.event(type, *pairs) }
    }
}
