package edu.dlsu.esca_3_2526_c3

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * CPECAPS: a live, moving map (like Google Maps / Waze): the car marker glides to each new
 * position, turns with the direction of travel, leaves a trail and follows the car.
 * The map page and Leaflet are bundled in assets/map/ and served from an https address.
 *
 * Map tiles: OpenStreetMap (no API key; CARTO's free tiles now need one). The page asks the app for
 * /tiles/z/x/y.png and the app fetches the tile itself over a network that really has internet
 * (mobile data while the phone is joined to DMS-NET, which has none), with an app name as the
 * tile servers require, and keeps it in a cache on the phone (tiles seen before work offline).
 */
@SuppressLint("SetJavaScriptEnabled")
class LiveMap(context: Context) {
    val view: WebView = WebView(context)
    private var ready = false
    private var pending: String? = null
    private var last: Triple<Double, Double, String>? = null
    private val appContext = context.applicationContext
    private val cacheDir = File(context.cacheDir, "map_tiles").apply { mkdirs() }

    init {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = view.settings.userAgentString + " Danteye-CPECAPS"
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != HOST) return null
                val path = url.path?.trimStart('/') ?: return null
                if (path.startsWith("tiles/")) return tile(path)
                val mime = when {
                    path.endsWith(".html") -> "text/html"
                    path.endsWith(".js") -> "application/javascript"
                    path.endsWith(".css") -> "text/css"
                    path.endsWith(".png") -> "image/png"
                    else -> "application/octet-stream"
                }
                return try { WebResourceResponse(mime, "utf-8", v.context.assets.open(path)) } catch (_: Exception) { null }
            }

            override fun onPageFinished(v: WebView, url: String) {
                ready = true
                pending?.let { v.evaluateJavascript(it, null) }
                pending = null
            }
        }
        // Let the map take drags and pinches instead of the scrolling page around it.
        view.setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        view.loadUrl("https://$HOST/map/livemap.html")
    }

    /** tiles/z/x/y.png: cache (up to 30 days) -> OpenStreetMap over an internet network -> old cache -> empty tile. */
    private fun tile(path: String): WebResourceResponse {
        val parts = path.removePrefix("tiles/").removeSuffix(".png").split("/")
        val (z, x, y) = parts.mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } ?: return empty()
        if (z !in 0..19) return empty()
        val file = File(cacheDir, "${z}_${x}_$y.png")
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3600 * 1000
        if (fresh) return png(file.readBytes())
        val bytes = try { download("https://tile.openstreetmap.org/$z/$x/$y.png") } catch (_: Exception) { null }
        if (bytes != null) {
            try { file.writeBytes(bytes); prune() } catch (_: Exception) { }
            return png(bytes)
        }
        return if (file.isFile) png(file.readBytes()) else empty()
    }

    private fun download(address: String): ByteArray {
        val target = URL(address)
        val net = internetNetwork()
        val c = (if (net != null) net.openConnection(target) else target.openConnection()) as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            // OpenStreetMap's tile policy: identify the app (no personal data) and the page.
            c.setRequestProperty("User-Agent", "Danteye-CPECAPS/1.0 (Android driver-monitoring prototype)")
            c.setRequestProperty("Referer", "https://$HOST/map/livemap.html")
            if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) error("tile HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** The phone's network that is checked to reach the internet (mobile data when Wi-Fi is DMS-NET). */
    private fun internetNetwork(): Network? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        fun ok(n: Network) = cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        cm.activeNetwork?.let { if (ok(it)) return it }
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { ok(it) }
    }

    /** Keep the cache under about 3000 tiles (~50 MB): remove the oldest. */
    private fun prune() {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= 3000) return
        files.sortedBy { it.lastModified() }.take(files.size - 2500).forEach { it.delete() }
    }

    private fun png(bytes: ByteArray) = WebResourceResponse("image/png", null, ByteArrayInputStream(bytes))

    private fun empty() = WebResourceResponse("image/png", null, 404, "No tile", mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)))

    /** New position; risk = NORMAL / LOW / MEDIUM / HIGH (marker colour). Repeated identical calls are ignored. */
    fun update(lat: Double, lng: Double, kmh: Double?, label: String, risk: String) {
        val key = Triple(lat, lng, "$kmh|$label|$risk")
        if (key == last) return
        last = key
        val js = String.format(Locale.US, "setPos(%.7f,%.7f,%s,%s,%s)", lat, lng,
            kmh?.let { String.format(Locale.US, "%.1f", it) } ?: "null", quote(label), quote(risk))
        if (ready) view.evaluateJavascript(js, null) else pending = js
    }

    fun clearTrail() { if (ready) view.evaluateJavascript("clearTrail()", null) }

    fun destroy() { try { view.destroy() } catch (_: Exception) {} }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    companion object { const val HOST = "appassets.androidplatform.net" }
}
