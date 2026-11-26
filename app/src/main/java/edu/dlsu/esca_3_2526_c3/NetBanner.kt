package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * CPECAPS: a thin red strip under the header while the phone has no internet
 * ("alerts are kept and sent when the connection is back"). DMS-NET itself has no internet;
 * the phone normally uses mobile data for Firebase while it is joined to DMS-NET.
 */
class NetBanner(private val activity: Activity, root: LinearLayout, index: Int, message: String) {
    private val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val view = TextView(activity).apply {
        text = message
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(Ui.red)
        setPadding(Ui.dp(activity, 12), Ui.dp(activity, 6), Ui.dp(activity, 12), Ui.dp(activity, 6))
        visibility = View.GONE
        contentDescription = message
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    init {
        root.addView(view, index, LinearLayout.LayoutParams(-1, -2))
        try { cm?.registerDefaultNetworkCallback(callback) } catch (_: Exception) {}
        refresh()
    }

    private fun online(): Boolean = try {
        val caps = cm?.getNetworkCapabilities(InternetRouter.internetNetwork() ?: cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { true }

    fun refresh() {
        val show = !online()
        activity.runOnUiThread { view.visibility = if (show) View.VISIBLE else View.GONE }
    }

    fun close() { try { cm?.unregisterNetworkCallback(callback) } catch (_: Exception) {} }
}
