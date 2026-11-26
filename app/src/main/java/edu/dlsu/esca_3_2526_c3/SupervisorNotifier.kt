package edu.dlsu.esca_3_2526_c3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * CPECAPS: one place that shows the supervisor's HIGH-risk notifications, used by the Firebase push
 * (AlertMessagingService) and by the background watcher (SupervisorAlertService).
 * One notification per driver (tag "high", id from the driver id): a second report about the same
 * driver updates it instead of stacking a new one, and sounds again only after 30 s.
 */
object SupervisorNotifier {
    private const val CH_SOUND = "danteye_high_sound"
    private const val CH_QUIET = "danteye_high_quiet"
    private const val CH_TEST = "danteye_test_high"
    private val lastSound = mutableMapOf<String, Long>()

    private fun prefs(c: Context) = c.getSharedPreferences("supervisor_prefs", Context.MODE_PRIVATE)
    fun soundOn(c: Context) = prefs(c).getBoolean("alert_sound", true)

    private fun channels(c: Context, m: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        m.createNotificationChannel(NotificationChannel(CH_SOUND, "Driver HIGH risk (with sound)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A driver is at HIGH risk"
            setSound(alarm, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
        })
        m.createNotificationChannel(NotificationChannel(CH_QUIET, "Driver HIGH risk (silent)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true)
        })
        m.createNotificationChannel(NotificationChannel(CH_TEST, "Test HIGH alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    /** title e.g. "HIGH risk: Juan"; text e.g. "Eyes closed · 72 km/h · 10:42". */
    fun show(c: Context, driverId: String, title: String, text: String, test: Boolean = false) {
        if (!prefs(c).getBoolean("push", true)) return
        val m = c.getSystemService(NotificationManager::class.java) ?: return
        channels(c, m)
        val now = System.currentTimeMillis()
        val key = if (test) "test" else driverId
        val quietRepeat = now - (lastSound[key] ?: 0L) < 30_000L
        if (!quietRepeat) lastSound[key] = now
        val open = Intent(c, SupervisorActivity::class.java).apply {
            putExtra("driver_id", driverId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(c, driverId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (test) CH_TEST else if (soundOn(c)) CH_SOUND else CH_QUIET
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(Ui.red)
            .setOnlyAlertOnce(quietRepeat)
            .setAutoCancel(true)
            .setWhen(now)
            .setContentIntent(pi)
            .build()
        try { m.notify("high", key.hashCode(), n) } catch (_: SecurityException) {} // notifications not allowed
    }
}
