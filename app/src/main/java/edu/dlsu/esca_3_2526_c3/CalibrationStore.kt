package edu.dlsu.esca_3_2526_c3

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import edu.dlsu.esca_3_2526_c3.dms.Baseline
import org.json.JSONObject

/**
 * The driver's saved calibration (their normal eyes, mouth and head position for this camera mount).
 * It is measured in the setup guide's camera-position step (or in the first trip without one) and restored
 * at the start of every trip, so the driver does not wait 5 seconds each time. Settings > Calibration >
 * Recalibrate measures it again. One calibration per signed-in account (or "this phone" when signed out).
 */
object CalibrationStore {
    private const val PREFS = "driver_calibration_v1"

    class Saved(val baseline: Baseline, val savedAtMs: Long, val source: String, val extra: JSONObject)

    private fun key(): String = try {
        FirebaseAuth.getInstance().currentUser?.email?.lowercase() ?: "local"
    } catch (_: Exception) { "local" }

    fun load(context: Context): Saved? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(), null) ?: return null
        return try {
            val o = JSONObject(text)
            val b = o.getJSONObject("baseline")
            fun opt(name: String) = if (b.isNull(name)) null else b.optDouble(name).takeIf { it.isFinite() }
            Saved(Baseline(b.getDouble("ear"), b.getDouble("mar"), b.getDouble("yaw"), b.getDouble("pitch"),
                b.getDouble("blink"), b.getDouble("jaw"), opt("ear_left"), opt("ear_right")),
                o.optLong("saved_at_ms"), o.optString("source"), o.optJSONObject("extra") ?: JSONObject())
        } catch (_: Exception) { null }
    }

    /** source: "setup" (camera-position step) or "trip" (measured during monitoring). extra: e.g. the camera zoom. */
    fun save(context: Context, baseline: Baseline, source: String, extra: JSONObject = JSONObject()) {
        val b = JSONObject().put("ear", baseline.ear).put("mar", baseline.mar).put("yaw", baseline.yaw)
            .put("pitch", baseline.pitch).put("blink", baseline.blink).put("jaw", baseline.jaw)
            .put("ear_left", baseline.earLeft ?: JSONObject.NULL).put("ear_right", baseline.earRight ?: JSONObject.NULL)
        val o = JSONObject().put("baseline", b).put("saved_at_ms", System.currentTimeMillis()).put("source", source).put("extra", extra)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(), o.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key()).apply()
    }

    /** For Settings: "Saved Nov 26, 3:40 PM (setup)" or null. */
    fun describe(context: Context): String? = load(context)?.let {
        val time = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it.savedAtMs))
        "Saved $time" + if (it.source == "setup") " in the setup guide" else " during a trip"
    }
}
