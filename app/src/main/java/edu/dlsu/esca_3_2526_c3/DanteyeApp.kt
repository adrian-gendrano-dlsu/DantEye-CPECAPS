package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import edu.dlsu.esca_3_2526_c3.monitor.EventLog

class DanteyeApp : Application() {
    companion object {
        /** CPECAPS: true while one of the app's screens is visible (logged in events.jsonl). */
        @Volatile var inFront = false
            private set
    }

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.initializeApp(this) != null) {
            // Set once and before any database references are opened.
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        }
        // Wi-Fi (boards) and mobile data (Firebase, map) at the same time on DMS-NET.
        InternetRouter.start(this)
        // CPECAPS: note when the app goes to the background and back (to check background monitoring).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                if (started == 1) { inFront = true; EventLog.note("app_front", "screen" to a.javaClass.simpleName) }
            }
            override fun onActivityStopped(a: Activity) {
                started = maxOf(0, started - 1)
                if (started == 0) { inFront = false; EventLog.note("app_background", "screen" to a.javaClass.simpleName) }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}
