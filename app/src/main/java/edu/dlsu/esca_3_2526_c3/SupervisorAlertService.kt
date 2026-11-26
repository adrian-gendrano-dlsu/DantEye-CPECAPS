package edu.dlsu.esca_3_2526_c3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CPECAPS: keeps watching the drivers in the background on the supervisor's phone and shows a loud
 * notification when
 *   - a driver's phone records a HIGH alert (alerts/<driver>/<id>, level HIGH), or
 *   - a driver's live risk turns HIGH (drivers/<driver>/live/risk).
 * Works without the cloud function (no Firebase Blaze plan needed) as long as the supervisor is signed in;
 * the cloud-function push stays as a second route (same notification, so no duplicates).
 */
class SupervisorAlertService : Service() {
    private val db by lazy { FirebaseDatabase.getInstance(FIREBASE_DB_URL) }
    private var driversListener: ValueEventListener? = null
    private val alertListeners = mutableMapOf<String, Pair<Query, ChildEventListener>>()
    private val names = mutableMapOf<String, String>()
    private val highNow = mutableSetOf<String>()
    private var startedMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() soon after startForegroundService(), even when we stop right away.
        startForegroundCompat()
        if (intent?.action == STOP || FirebaseAuth.getInstance().currentUser == null) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf(); return START_NOT_STICKY
        }
        if (driversListener == null) watch()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val m = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) m.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Watching drivers", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 1, Intent(this, SupervisorActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_WATCH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Watching your drivers")
            .setContentText("You get an alert when a driver is at HIGH risk")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(WATCH_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(WATCH_ID, n)
    }

    private fun watch() {
        startedMs = System.currentTimeMillis()
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (d in snapshot.children) {
                    val id = d.key ?: continue
                    val name = d.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: id
                    names[id] = name
                    watchAlerts(id)
                    val live = d.child("live")
                    val risk = live.child("risk").getValue(String::class.java) ?: ""
                    val ts = live.child("timestampMs").getValue(Long::class.java) ?: 0L
                    val test = live.child("test").getValue(Boolean::class.java) == true
                    val fresh = System.currentTimeMillis() - ts < 60_000L
                    if (Ui.riskLevel(risk) == "HIGH" && fresh && !test) {
                        if (highNow.add(id)) {
                            val event = live.child("event").getValue(String::class.java) ?: "High risk"
                            val kmh = live.child("speedKph").getValue(Double::class.java)
                            SupervisorNotifier.show(this@SupervisorAlertService, id, "HIGH risk: $name",
                                listOfNotNull(Ui.alertCause(event, false), kmh?.let { String.format(Locale.US, "%.0f km/h", it) },
                                    time(ts)).joinToString(" · ") + "\nTap to see the driver on the live map.")
                        }
                    } else highNow.remove(id)
                }
            }
            override fun onCancelled(error: DatabaseError) { stopSelf() }
        }
        db.getReference("drivers").addValueEventListener(listener)
        driversListener = listener
    }

    /** New alerts of one driver from now on (older ones were seen before or are stale). */
    private fun watchAlerts(driverId: String) {
        if (alertListeners.containsKey(driverId)) return
        val query = db.getReference("drivers").child(driverId).child("alerts").orderByChild("timestampMs").startAt(startedMs.toDouble())
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val level = snapshot.child("level").getValue(String::class.java) ?: return
                if (level != "HIGH") return
                val ms = snapshot.child("timestampMs").getValue(Long::class.java) ?: return
                if (System.currentTimeMillis() - ms > 120_000L) return
                val test = snapshot.child("test").getValue(Boolean::class.java) == true
                val behaviour = (snapshot.child("behaviour").getValue(String::class.java) ?: snapshot.child("behavior").getValue(String::class.java)) ?: "Alert"
                val name = names[driverId] ?: driverId
                SupervisorNotifier.show(this@SupervisorAlertService, driverId,
                    if (test) "TEST high-risk alert: $name" else "HIGH-risk alert: $name",
                    "${Ui.alertCause(behaviour, test)} · ${time(ms)}\nTap to see the driver on the live map.", test)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        query.addChildEventListener(l)
        alertListeners[driverId] = query to l
    }

    private fun time(ms: Long) = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(ms))

    override fun onDestroy() {
        driversListener?.let { db.getReference("drivers").removeEventListener(it) }
        for ((q, l) in alertListeners.values) q.removeEventListener(l)
        alertListeners.clear()
        super.onDestroy()
    }

    companion object {
        const val STOP = "edu.dlsu.esca_3_2526_c3.STOP_SUPERVISOR_ALERTS"
        private const val CH_WATCH = "danteye_watch"
        private const val WATCH_ID = 4711

        fun enabled(c: Context) = c.getSharedPreferences("supervisor_prefs", Context.MODE_PRIVATE).getBoolean("background_alerts", true)

        fun start(c: Context) {
            if (!enabled(c)) return
            val i = Intent(c, SupervisorAlertService::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (_: Exception) {}
        }

        fun stop(c: Context) { c.stopService(Intent(c, SupervisorAlertService::class.java)) }
    }
}
