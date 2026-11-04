package edu.dlsu.danteye

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class DantEyeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.initializeApp(this) != null) {
            // Set once and before any database references are opened.
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        }
    }
}
