package edu.dlsu.esca_3_2526_c3

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.google.firebase.database.FirebaseDatabase

/**
 * Wi-Fi and mobile data at the same time, without phone settings.
 *
 * On DMS-NET (the boards' Wi-Fi, no internet) Android sends the app's traffic to that Wi-Fi, so Firebase
 * (supervisor live status) and the map could not reach the internet. While the Wi-Fi has no internet, this asks
 * Android to keep mobile data up and makes it the app's network for everything. The camera and the sensor board
 * are still reached over Wi-Fi, because those requests choose the Wi-Fi network explicitly (WifiNet,
 * DeviceStatusMonitor, SettingsActivity). On a normal Wi-Fi with internet nothing changes.
 */
object InternetRouter {
    private var cm: ConnectivityManager? = null
    private var cellularRequest: ConnectivityManager.NetworkCallback? = null
    @Volatile var cellular: Network? = null
        private set
    @Volatile private var bound: Network? = null

    fun start(context: Context) {
        if (cm != null) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        cm = manager
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update()
                override fun onLost(network: Network) = update()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
            })
        } catch (_: Exception) { }
        update()
    }

    /** The network the app uses for the internet right now (for "online?" checks). */
    fun internetNetwork(): Network? = bound ?: cm?.activeNetwork

    private fun wifiWithoutInternet(): Boolean {
        val m = cm ?: return false
        val caps = m.getNetworkCapabilities(m.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Synchronized
    private fun update() {
        val m = cm ?: return
        if (wifiWithoutInternet()) {
            if (cellularRequest == null) {
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { cellular = network; bind(network) }
                    override fun onLost(network: Network) { if (cellular == network) { cellular = null; bind(null) } }
                }
                cellularRequest = cb
                try {
                    m.requestNetwork(NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
                } catch (_: Exception) { cellularRequest = null }
            } else cellular?.let { bind(it) }
        } else {
            cellularRequest?.let { try { m.unregisterNetworkCallback(it) } catch (_: Exception) { } }
            cellularRequest = null
            cellular = null
            bind(null)
        }
    }

    private fun bind(network: Network?) {
        val m = cm ?: return
        if (bound == network) return
        bound = network
        try { m.bindProcessToNetwork(network) } catch (_: Exception) { }
        // Reconnect Firebase on the new network at once (instead of waiting for its own retry).
        try {
            val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
            db.goOffline(); db.goOnline()
        } catch (_: Exception) { }
    }
}
