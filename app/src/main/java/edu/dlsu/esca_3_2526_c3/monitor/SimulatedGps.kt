package edu.dlsu.esca_3_2526_c3.monitor

import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPECAPS developer test: a made-up drive for testing the live maps indoors.
 *
 * Once per second it hands the monitoring pipeline a reading in the same format as the sensor board's GPS
 * (firmware 06 /data: fix, lat, lng, kmh, sats, hdop), so the driver's map, the speed in the risk engine and the
 * supervisor's live map all behave as on a real drive. The route is an invented loop near DLSU (Taft Avenue,
 * Pedro Gil, Roxas Boulevard, Vito Cruz) driven at 0-60 km/h, with slower corners and one red-light stop.
 * Speeds stay under 80 km/h, so the speed rule does not raise the risk. Never use it on a real drive.
 */
class SimulatedGps(private val onReading: (JSONObject) -> Unit) {
    private val route = listOf(
        14.5646 to 120.9932, // Taft Ave, DLSU
        14.5690 to 120.9918,
        14.5742 to 120.9898, // Taft / Pedro Gil
        14.5748 to 120.9862,
        14.5752 to 120.9826, // Pedro Gil / Roxas Blvd
        14.5700 to 120.9834,
        14.5650 to 120.9842,
        14.5617 to 120.9850, // Roxas / Vito Cruz
        14.5621 to 120.9890,
        14.5626 to 120.9934, // Vito Cruz / Taft
    )
    private val cruiseKmh = listOf(45.0, 45.0, 35.0, 35.0, 60.0, 60.0, 60.0, 40.0, 40.0, 35.0) // per segment
    private val redLightAt = 2 // waits at this corner

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "sim-gps").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var seg = 0          // driving from route[seg] to route[seg + 1]
        var along = 0.0      // metres along the current segment
        var speed = 0.0      // m/s
        var waitS = 0
        var stopsDone = -1
        val dt = 1.0
        while (running) {
            val a = route[seg]
            val b = route[(seg + 1) % route.size]
            val len = metres(a, b)
            val toCorner = len - along
            val cruise = cruiseKmh[seg] / 3.6
            if (waitS > 0) {
                waitS--
                speed = 0.0
            } else {
                // slow down for the next corner (15 km/h), or to a stop at the red light
                val stopHere = (seg + 1) % route.size == redLightAt && stopsDone != lapCornerKey(seg)
                val cornerSpeed = if (stopHere) 0.0 else 15.0 / 3.6
                val brakeTo = sqrt(cornerSpeed * cornerSpeed + 2 * 2.0 * max(0.0, toCorner - 5))
                val target = min(cruise, brakeTo)
                speed = if (target > speed) min(target, speed + 2.0 * dt) else max(target, speed - 3.0 * dt)
                if (stopHere && toCorner < 8 && speed < 0.5) {
                    waitS = 6; stopsDone = lapCornerKey(seg); speed = 0.0
                }
                along += max(speed, if (stopHere) 0.0 else 0.5) * dt
            }
            if (along >= len) { along -= len; seg = (seg + 1) % route.size; if (seg == 0) stopsDone = -1 }
            val k = if (len > 0) min(1.0, along / len) else 0.0
            val lat = a.first + (b.first - a.first) * k
            val lng = a.second + (b.second - a.second) * k
            onReading(JSONObject().put("fix", true).put("lat", lat).put("lng", lng)
                .put("kmh", Math.round(speed * 3.6 * 10) / 10.0).put("sats", 9).put("hdop", 0.9).put("simulated", true))
            try { Thread.sleep((dt * 1000).toLong()) } catch (_: InterruptedException) { break }
        }
    }

    private fun lapCornerKey(seg: Int) = seg

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.first); val p2 = Math.toRadians(b.first)
        val dp = p2 - p1; val dl = Math.toRadians(b.second - a.second)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
