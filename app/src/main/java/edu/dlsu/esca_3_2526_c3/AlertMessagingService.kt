package edu.dlsu.esca_3_2526_c3

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Firebase push from the highRiskAlert cloud function (shown through the same notifier as the background watcher). */
class AlertMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Real HIGH alerts (test = "false") and developer-mode tests (test = "true").
        val test = message.data["test"] == "true"
        if (!test && message.data["source"] != "device") return
        val driverId = message.data["driverId"] ?: ""
        val name = message.data["driverName"] ?: "driver"
        val behaviour = message.data["behavior"] ?: "Alert"
        SupervisorNotifier.show(this, driverId,
            if (test) "TEST high-risk alert: $name" else "HIGH-risk alert: $name",
            "${Ui.alertCause(behaviour, test)}\nTap to see the driver on the live map.", test)
    }
}
