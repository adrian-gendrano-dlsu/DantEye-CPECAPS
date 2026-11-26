package edu.dlsu.esca_3_2526_c3.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * CPECAPS: the phone's own GPS (speed + location), used when the sensor board has no GPS fix.
 * Reports about once a second; speed in km/h (null when the phone does not measure it).
 */
class PhoneLocation(private val context: Context, private val onFix: (Double, Double, Double?) -> Unit) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var running = false

    fun allowed(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val retry = android.os.Handler(Looper.getMainLooper())
    private var wanted = false

    /**
     * Returns false when location permission is missing or the phone has no GPS. While it is missing,
     * it checks again every 10 s (the permission dialog may still be open when monitoring starts).
     */
    fun start(): Boolean {
        wanted = true
        val ok = begin()
        if (!ok) retry.postDelayed(object : Runnable {
            override fun run() { if (wanted && !running && !begin()) retry.postDelayed(this, 10_000L) }
        }, 10_000L)
        return ok
    }

    private fun begin(): Boolean {
        val m = manager ?: return false
        if (!allowed()) return false
        return try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.allProviders.contains(provider)) m.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
            }
            running = true
            true
        } catch (_: SecurityException) { false } catch (_: Exception) { false }
    }

    fun stop() {
        wanted = false
        retry.removeCallbacksAndMessages(null)
        if (running) try { manager?.removeUpdates(this) } catch (_: Exception) {}
        running = false
    }

    private var lastGpsMs = 0L

    override fun onLocationChanged(location: Location) {
        // Prefer GPS fixes; network fixes only when GPS has been silent for 5 s.
        val now = System.currentTimeMillis()
        if (location.provider == LocationManager.GPS_PROVIDER) lastGpsMs = now
        else if (now - lastGpsMs < 5000L) return
        val kmh = if (location.hasSpeed()) location.speed * 3.6 else null
        onFix(location.latitude, location.longitude, kmh)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
