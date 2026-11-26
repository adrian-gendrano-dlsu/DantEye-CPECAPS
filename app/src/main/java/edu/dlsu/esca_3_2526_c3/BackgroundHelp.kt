package edu.dlsu.esca_3_2526_c3

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/**
 * CPECAPS: keep monitoring alive while Danteye is in the background.
 * Android (and Samsung's battery saver even more) pauses or closes apps that are not on the screen unless
 * they are left out of battery optimisation. The foreground service, wake lock and Wi-Fi locks only help
 * once that is allowed.
 */
object BackgroundHelp {
    private const val PREFS = "background_help"

    fun isUnrestricted(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Android's "Let app always run in background?" question; falls back to the battery settings list. */
    @SuppressLint("BatteryLife")
    fun askUnrestricted(a: Activity) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
        try { a.startActivity(direct) } catch (_: Exception) {
            try { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { openAppSettings(a) }
        }
    }

    /** Ask once when monitoring starts for the first time (later: Setup guide or Settings). */
    fun askOnceAtStart(a: Activity) {
        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isUnrestricted(a) || prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        askUnrestricted(a)
    }

    /** Settings > Apps > Danteye (battery, mobile data, permissions). */
    fun openAppSettings(a: Activity) {
        try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) }
        catch (_: Exception) { a.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    /** Data usage settings (Samsung: Connections > Data usage). */
    fun openDataUsage(a: Activity) {
        val intents = listOf(Intent(Settings.ACTION_DATA_USAGE_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in intents) { try { a.startActivity(i); return } catch (_: Exception) { } }
    }

    /** What to change on this phone so monitoring keeps running and both networks are used. */
    fun backgroundSteps(): String = if (isSamsung)
        "Samsung: Settings > Apps > Danteye > Battery > Unrestricted. Also Settings > Battery > Background usage limits > " +
            "Never sleeping apps > add Danteye, and make sure Danteye is not in Sleeping or Deep sleeping apps."
    else "Settings > Apps > Danteye > Battery (or App battery usage) > Unrestricted / Don't optimise."

    fun dataSteps(): String = if (isSamsung)
        "Settings > Connections > Data usage > Allowed networks for apps > Danteye > Mobile data preferred. " +
            "Danteye then reaches the boards over DMS-NET and the supervisor (Firebase) over mobile data at the same time."
    else "Keep mobile data on and choose Stay connected when Android says DMS-NET has no internet. " +
        "If your phone has a per-app network setting (Allowed networks / Mobile data preferred), set Danteye to use mobile data."

    /** Phone state for the session's events.jsonl (every 30 s and at the start). */
    fun phoneState(c: Context): JSONObject {
        val bm = c.getSystemService(BatteryManager::class.java)
        val pm = c.getSystemService(PowerManager::class.java)
        val battery = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val o = JSONObject()
            .put("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", plugged)
            .put("battery_temp_c", tempC ?: JSONObject.NULL)
            .put("screen_on", pm.isInteractive)
            .put("app_in_front", DanteyeApp.inFront)
            .put("power_save", pm.isPowerSaveMode)
            .put("device_idle", pm.isDeviceIdleMode)
            .put("battery_unrestricted", isUnrestricted(c))
        if (Build.VERSION.SDK_INT >= 29) o.put("thermal_status", pm.currentThermalStatus)
        return o
    }
}
