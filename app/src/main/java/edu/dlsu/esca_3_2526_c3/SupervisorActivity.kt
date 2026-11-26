package edu.dlsu.esca_3_2526_c3

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Supervisor screen, three tabs:
 *  - Drivers: summary counts, every driver sorted by urgency, and a driver's page (risk, alarm,
 *    behaviours, speed, map, alerts by type and recent alerts);
 *  - Alerts: the newest alerts of all drivers (all or HIGH only), with a "new" count on the tab;
 *  - Settings: account, push notifications on / off, night mode, help.
 * Data (see CloudSync): drivers/<driver>/{name, email, live, sessions} and alerts/<driver>/<alert>
 * (the newest 100 alerts of each driver are read).
 */
class SupervisorActivity : Activity() {
    private data class Driver(
        val id: String, val name: String, val risk: String, val event: String,
        val tripSeconds: Long, val speedKph: Double?, val lat: Double?, val lng: Double?,
        val locationTime: Long, val locationSource: String?, val lastUpdate: Long,
        val simulated: Boolean, val alertState: String, val riskScore: Double?, val statuses: Map<String, String>,
        val alerts: List<AlertRow>,
        val sessions: List<SessionRow>,
    )

    private data class SessionRow(val id: String, val startedMs: Long, val durationS: Long, val alerts: Int,
                                  val highAlerts: Int, val maxLevel: String, val active: Boolean)

    private data class AlertRow(val driverId: String, val driverName: String, val name: String, val ms: Long,
                                val level: String, val simulated: Boolean, val session: String)

    private val offlineAfterMs = 90_000L // live data comes every 2 s while monitoring, at least every 30 s without a picture
    private lateinit var cloud: CloudSync
    private var driversReference: DatabaseReference? = null
    private var driversListener: ValueEventListener? = null
    private var driverNodes = emptyList<DataSnapshot>()
    // alerts/<driver>: one listener per driver (newest 100), rows by driver id
    private val alertListeners = mutableMapOf<String, Pair<com.google.firebase.database.Query, ValueEventListener>>()
    private val alertRows = mutableMapOf<String, List<AlertRow>>()
    private var drivers = emptyList<Driver>()
    private var tab = "drivers"
    private var selectedId: String? = null
    private var highOnly = false
    // CPECAPS: search, filters and sorting (the search box is kept while the page refreshes every 2 s)
    private var driverFilter = "all"     // all | live | risk | offline
    private var driverSort = "urgent"    // urgent | name | recent
    private var alertPeriod = "week"     // today | week | all
    private var searchText = ""
    private val searchBox: android.widget.EditText by lazy {
        android.widget.EditText(this).apply {
            hint = "Search drivers or alerts"
            setSingleLine()
            textSize = 15f
            if (Ui.nightMode) { setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.LTGRAY) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: android.text.Editable?) {
                    val t = e?.toString()?.trim().orEmpty()
                    if (t != searchText) { searchText = t; render() }
                }
            })
        }
    }

    /** The shared search box, moved into the given card. */
    private fun addSearch(card: LinearLayout) {
        val hadFocus = searchBox.hasFocus()
        (searchBox.parent as? ViewGroup)?.removeView(searchBox)
        card.addView(searchBox, LinearLayout.LayoutParams(-1, -2))
        if (hadFocus) searchBox.post { searchBox.requestFocus() }
    }

    private fun matches(vararg fields: String) = searchText.isEmpty() || fields.any { it.contains(searchText, ignoreCase = true) }
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabs: LinearLayout
    private lateinit var driversTab: TextView
    private lateinit var alertsTab: TextView
    private lateinit var settingsTab: TextView
    private var liveMap: LiveMap? = null      // CPECAPS: moving map, kept across page refreshes
    private var liveMapDriver: String? = null
    private var netBanner: NetBanner? = null  // CPECAPS: "No internet" strip
    private val handler = Handler(Looper.getMainLooper())
    private val ageLabels = mutableMapOf<String, TextView>()
    private var shownStates = emptyMap<String, String>()
    private val prefs by lazy { getSharedPreferences("supervisor_prefs", MODE_PRIVATE) }
    // Roles in the app (Settings > People)
    private val people by lazy { PeopleManager(this) { if (!isFinishing) { if (tab == "settings" || (tab == "drivers" && selectedId == null)) render() } } }

    private val refreshAges = object : Runnable {
        override fun run() {
            if (!isFinishing && ::cloud.isInitialized && cloud.role == "supervisor") {
                // A driver going offline changes the page; otherwise only the "x s ago" labels.
                if (drivers.associate { it.id to stateOf(it) } != shownStates) render() else updateAgeLabels()
            }
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = intent.getStringExtra("driver_id")?.takeIf { it.isNotBlank() }
        Ui.nightMode = Ui.nightPreference(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.canvas) }
        val header = Ui.header(this, "Supervisor Dashboard")
        root.addView(header)
        netBanner = NetBanner(this, root, root.childCount, "No internet · driver updates and alerts will arrive when it is back")
        scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SupervisorActivity, 16), Ui.dp(this@SupervisorActivity, 16),
                Ui.dp(this@SupervisorActivity, 16), Ui.dp(this@SupervisorActivity, 24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tabs = LinearLayout(this).apply {
            setBackgroundColor(Ui.surface)
            setPadding(Ui.dp(this@SupervisorActivity, 12), Ui.dp(this@SupervisorActivity, 8),
                Ui.dp(this@SupervisorActivity, 12), Ui.dp(this@SupervisorActivity, 8))
        }
        driversTab = Ui.tab(this, "Drivers") { open("drivers") }.apply { textSize = 13f }
        alertsTab = Ui.tab(this, "Alerts") { open("alerts") }.apply { textSize = 13f }
        settingsTab = Ui.tab(this, "⚙  Settings") { open("settings") }.apply { textSize = 13f }
        for (t in listOf(driversTab, alertsTab, settingsTab)) tabs.addView(t, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        root.addView(tabs)
        setContentView(root)
        Ui.fitSystemBars(this, root, header, tabs)

        cloud = CloudSync(this)
        cloud.onChange = roleChanged@{
            if (cloud.roleChecked && cloud.signedIn && cloud.role.isEmpty()) { accessRemoved(); return@roleChanged }
            if (cloud.role == "driver") {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return@roleChanged
            }
            if (cloud.role == "supervisor" && cloud.signedIn) {
                attachDrivers()
                if (cloud.ready && pushEnabled()) cloud.registerSupervisorPush()
            } else detachDrivers()
            if (!isFinishing) render()
        }
        cloud.start()
        render()
    }

    /** A supervisor removed this account's access (checked once online): tell the user, stop, sign out. */
    private var removedShown = false
    private fun accessRemoved() {
        if (removedShown || isFinishing) return
        removedShown = true
        detachDrivers()
        android.app.AlertDialog.Builder(this)
            .setTitle("Access removed")
            .setMessage("A supervisor removed your access to Danteye. Ask your supervisor if this is a mistake.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                cloud.signOut()
                startActivity(Intent(this, LauncherActivity::class.java))
                finish()
            }.show()
    }

    /** Notification taps open the corresponding driver's page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedId = intent.getStringExtra("driver_id")?.takeIf { it.isNotBlank() }
        tab = "drivers"
        render()
    }

    override fun onResume() {
        super.onResume()
        Ui.nightMode = Ui.nightPreference(this)
        root.setBackgroundColor(Ui.canvas)
        tabs.setBackgroundColor(Ui.surface)
        handler.post(refreshAges)
    }

    override fun onPause() { handler.removeCallbacks(refreshAges); super.onPause() }

    override fun onDestroy() {
        detachDrivers()
        (liveMap?.view?.parent as? ViewGroup)?.removeView(liveMap?.view)
        liveMap?.destroy()
        liveMap = null
        netBanner?.close()
        cloud.stop()
        super.onDestroy()
    }

    @Deprecated("Use Back navigation")
    override fun onBackPressed() {
        when {
            selectedId != null -> { selectedId = null; render() }
            tab != "drivers" -> open("drivers")
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun open(next: String) {
        tab = next
        if (next != "drivers") selectedId = null
        if (next == "alerts") prefs.edit().putLong("alerts_seen_ms", System.currentTimeMillis()).apply()
        render(scrollTop = true)
    }

    private fun pushEnabled() = prefs.getBoolean("push", true)

    // ------------------------------------------------------------------ Firebase

    /** The roster comes from Firebase (never hard-coded drivers); alerts are read from the same snapshot. */
    private fun attachDrivers() {
        if (driversListener != null || cloud.role != "supervisor") return
        people.attach()
        driversReference = cloud.driversRef()
        driversListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                driverNodes = snapshot.children.toList()
                driverNodes.mapNotNull { it.key }.forEach { attachAlerts(it) }
                drivers = driverNodes.mapNotNull { parseDriver(it) }
                // Settings is not rebuilt by live data (only the Alerts count on the tab changes).
                if (!isFinishing) { if (tab != "settings") render() else updateAlertsTab() }
            }
            override fun onCancelled(error: DatabaseError) {
                cloud.toast("Driver list unavailable: ${error.message}")
            }
        }
        driversReference!!.addValueEventListener(driversListener!!)
        if (Build.VERSION.SDK_INT >= 33 && pushEnabled() &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        if (pushEnabled()) cloud.registerSupervisorPush()
        // CPECAPS: background watcher for HIGH risk (independent of the cloud function).
        if (pushEnabled()) SupervisorAlertService.start(this)
    }

    /** Newest 100 alerts of one driver; re-parses that driver's row when they change. */
    private fun attachAlerts(driverId: String) {
        if (alertListeners.containsKey(driverId)) return
        val query = cloud.alertsRef(driverId).orderByChild("timestampMs").limitToLast(100)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                alertRows[driverId] = snapshot.children.mapNotNull { e ->
                    val ms = e.child("timestampMs").getValue(Long::class.java) ?: return@mapNotNull null
                    AlertRow(driverId, "", (e.child("behaviour").getValue(String::class.java) ?: e.child("behavior").getValue(String::class.java) ?: "Alert"), ms,
                        e.child("level").getValue(String::class.java) ?: "MEDIUM",
                        e.child("test").getValue(Boolean::class.java) == true,
                        e.child("trip").getValue(String::class.java) ?: "")
                }
                drivers = driverNodes.mapNotNull { parseDriver(it) }
                if (!isFinishing) { if (tab != "settings") render() else updateAlertsTab() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        query.addValueEventListener(listener)
        alertListeners[driverId] = query to listener
    }

    private fun detachDrivers() {
        people.detach()
        driversListener?.let { driversReference?.removeEventListener(it) }
        driversReference = null; driversListener = null
        alertListeners.values.forEach { (q, l) -> q.removeEventListener(l) }
        alertListeners.clear(); alertRows.clear(); driverNodes = emptyList()
        drivers = emptyList()
    }

    private fun parseDriver(node: DataSnapshot): Driver? {
        // New layout: drivers/<uid>. A driver whose live status never reached Firebase (for example no internet on
        // DMS-NET) is still listed (as OFFLINE) with the trips and alerts that were synced.
        val live = node.child("live")
        val id = node.key ?: return null
        if (!live.exists() && !node.child("trips").exists() && !node.child("alerts").exists() &&
            !node.child("name").exists() && !node.child("email").exists()) return null
        val lat = live.child("lat").getValue(Double::class.java)
        val lng = live.child("lng").getValue(Double::class.java)
        val valid = lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
                lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
        val name = node.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() }
            ?: node.child("email").getValue(String::class.java) ?: "Driver"
        // Alerts, oldest first; a MEDIUM test escalated to HIGH within 12 s shows once as HIGH.
        val rows = mutableListOf<AlertRow>()
        (alertRows[id] ?: emptyList()).map { it.copy(driverName = name) }.sortedBy { it.ms }.forEach { row ->
            val prior = rows.indexOfLast { it.session == row.session && row.session.isNotBlank() && it.name == row.name &&
                    Ui.riskLevel(it.level) == "MEDIUM" && Ui.riskLevel(row.level) == "HIGH" && row.ms - it.ms in 0..12_000L }
            if (prior >= 0) rows[prior] = rows[prior].copy(level = row.level) else rows.add(row)
        }
        val sessions = node.child("trips").children.mapNotNull { sn ->
            val started = sn.child("startedMs").getValue(Long::class.java) ?: return@mapNotNull null
            SessionRow(sn.key ?: "", started, sn.child("durationS").getValue(Long::class.java) ?: 0L,
                sn.child("alerts").getValue(Long::class.java)?.toInt() ?: 0,
                sn.child("highAlerts").getValue(Long::class.java)?.toInt() ?: 0,
                sn.child("maxLevel").getValue(String::class.java) ?: "",
                sn.child("active").getValue(Boolean::class.java) ?: false)
        }.sortedByDescending { it.startedMs }
        return Driver(
            id, name,
            live.child("risk").getValue(String::class.java) ?: "No risk",
            live.child("event").getValue(String::class.java) ?: "Normal",
            live.child("tripSeconds").getValue(Long::class.java) ?: 0L,
            live.child("speedKph").getValue(Double::class.java),
            if (valid) lat else null, if (valid) lng else null,
            live.child("locationTimestampMs").getValue(Long::class.java) ?: 0L,
            live.child("locationSource").getValue(String::class.java),
            live.child("timestampMs").getValue(Long::class.java) ?: 0L,
            live.child("test").getValue(Boolean::class.java) ?: false,
            live.child("alertState").getValue(String::class.java) ?: "",
            live.child("riskScore").getValue(Double::class.java),
            live.child("statuses").children.mapNotNull { st ->
                val k = st.key ?: return@mapNotNull null
                val v = st.getValue(String::class.java) ?: return@mapNotNull null
                k to v
            }.toMap(),
            rows.asReversed(),
            sessions,
        )
    }

    // ------------------------------------------------------------------ driver state

    private fun isOffline(d: Driver) = d.lastUpdate <= 0L || System.currentTimeMillis() - d.lastUpdate > offlineAfterMs

    /** OFFLINE, NOT MONITORING, NO CAMERA, CALIBRATING, or the risk level (NORMAL / LOW / MEDIUM / HIGH). */
    private fun stateOf(d: Driver): String = when {
        isOffline(d) -> "OFFLINE"
        d.alertState == "STOPPED" || d.risk.startsWith("Not monitoring") -> "NOT MONITORING"
        d.risk == "Unknown" || d.alertState == "UNAVAILABLE" -> "NO CAMERA"
        d.risk.startsWith("Calibrating") -> "CALIBRATING"
        else -> Ui.riskLevel(d.risk)
    }

    private fun stateColor(state: String) = when (state) {
        "HIGH", "MEDIUM", "LOW", "NORMAL" -> Ui.riskColor(state)
        "CALIBRATING" -> Ui.blue
        else -> Ui.muted
    }

    /** Sort order: most urgent first. */
    private fun urgency(d: Driver): Int = when (stateOf(d)) {
        "HIGH" -> if (d.alertState == "ALERTING") 0 else 1
        "MEDIUM" -> 2; "LOW" -> 3; "NO CAMERA" -> 4; "CALIBRATING" -> 5; "NORMAL" -> 6
        "NOT MONITORING" -> 7; else -> 8
    }

    private fun ageText(time: Long): String {
        if (time <= 0L) return "Last update: never"
        val seconds = ((System.currentTimeMillis() - time).coerceAtLeast(0L)) / 1000L
        return when {
            seconds < 60 -> "Last update: $seconds s ago"
            seconds < 3600 -> "Last update: ${seconds / 60} min ago"
            seconds < 86_400 -> "Last update: ${seconds / 3600} h ago"
            else -> "Last update: ${seconds / 86_400} days ago"
        }
    }

    private fun updateAgeLabels() {
        drivers.forEach { d ->
            ageLabels[d.id]?.apply {
                text = ageText(d.lastUpdate) + if (isOffline(d)) " · Offline" else ""
                setTextColor(if (isOffline(d)) Ui.muted else Ui.green)
            }
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun timeText(ms: Long): String = SimpleDateFormat(if (ms >= startOfToday()) "h:mm a" else "MMM d, h:mm a",
        Locale.getDefault()).format(Date(ms))

    /** "Alerts (3)": real alerts since the Alerts tab was last opened. */
    private fun updateAlertsTab() {
        if (tab == "alerts") prefs.edit().putLong("alerts_seen_ms", System.currentTimeMillis()).apply()
        val seen = prefs.getLong("alerts_seen_ms", 0L)
        val unseen = allAlerts().count { it.ms > seen && !it.simulated }
        alertsTab.text = if (unseen > 0 && tab != "alerts") "Alerts ($unseen)" else "Alerts"
    }

    private fun allAlerts(): List<AlertRow> = drivers.flatMap { it.alerts }.sortedByDescending { it.ms }

    // ------------------------------------------------------------------ pages

    private fun render(scrollTop: Boolean = false) {
        val scrollY = if (scrollTop) 0 else scroll.scrollY
        content.minimumHeight = if (scrollY > 0) content.height else 0 // no collapse -> no jump to the top
        content.removeAllViews()
        ageLabels.clear()
        Ui.activeTab(driversTab, tab == "drivers")
        Ui.activeTab(alertsTab, tab == "alerts")
        Ui.activeTab(settingsTab, tab == "settings")
        updateAlertsTab()
        shownStates = drivers.associate { it.id to stateOf(it) }

        if (tab == "settings") showSettings()
        else if (cloud.role != "supervisor") showSignInNeeded()
        else if (tab == "alerts") showAlerts()
        else selectedId?.let { showDriverDetails(it) } ?: showDriverList()
        updateAgeLabels()
        scroll.post { if (!isFinishing) { scroll.scrollTo(0, scrollY); content.minimumHeight = 0 } }
    }

    private fun showSignInNeeded() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Supervisor account needed"))
        card.addView(Ui.note(this, cloud.connectionLabel))
        card.addView(Ui.note(this, "Driver data appear after you sign in with an account that has the supervisor role."))
        card.addView(Button(this).apply {
            text = if (cloud.signedIn) "Use another account" else "Sign in"
            isAllCaps = false
            setOnClickListener {
                if (cloud.signedIn) { SupervisorAlertService.stop(this@SupervisorActivity); detachDrivers(); cloud.signOut() }
                startActivity(Intent(this@SupervisorActivity, LauncherActivity::class.java))
                finish()
            }
        })
        content.addView(card, Ui.blockParams(this))
    }

    private fun summaryTile(count: Int, label: String, color: Int) =
        Ui.statusPanel(this, "$count\n$label", color, 15f).apply { contentDescription = "$count $label" }

    private fun showDriverList() {
        people.addWaitingBanner(content) { open("settings") }
        // Fleet banner + number tiles (fleet-app style, Sui).
        val states = drivers.map { stateOf(it) }
        val monitoring = states.count { it !in setOf("OFFLINE", "NOT MONITORING") }
        val attention = states.count { it == "HIGH" || it == "MEDIUM" }
        val today = allAlerts().filter { it.ms >= startOfToday() && !it.simulated }
        val highToday = today.count { Ui.riskLevel(it.level) == "HIGH" }
        content.addView(Sui.hero(this, "FLEET NOW",
            when { attention > 0 -> "$attention need${if (attention == 1) "s" else ""} attention"; monitoring > 0 -> "All drivers OK"; else -> "No one is driving" },
            when { states.any { it == "HIGH" } -> Ui.red; attention > 0 -> Ui.orange; monitoring > 0 -> Ui.green; else -> Ui.navy },
            "$monitoring driving · ${today.size} alert${if (today.size == 1) "" else "s"} today" + if (highToday > 0) " ($highToday HIGH)" else ""),
            Ui.blockParams(this))
        content.addView(Sui.row(this, listOf(Sui.statTile(this, "$monitoring", "Driving", Ui.blue),
            Sui.statTile(this, "${states.count { it == "HIGH" }}", "High", Ui.red),
            Sui.statTile(this, "${states.count { it == "MEDIUM" }}", "Medium", Ui.orange),
            Sui.statTile(this, "${states.count { it == "OFFLINE" }}", "Offline", Ui.muted))), Ui.blockParams(this))

        val list = Ui.card(this)
        list.addView(Ui.title(this, "Drivers (${drivers.size})"))
        if (drivers.isEmpty()) list.addView(Ui.note(this,
            "No drivers yet. A driver appears here after signing in with a driver account and starting monitoring."))
        if (drivers.isNotEmpty()) {
            addSearch(list)
            list.addView(Ui.choiceRow(this, "Show", listOf("all" to "All", "live" to "Monitoring", "risk" to "At risk", "offline" to "Offline"),
                driverFilter) { driverFilter = it; render(scrollTop = true) })
            list.addView(Ui.choiceRow(this, "Sort by", listOf("urgent" to "Most urgent", "name" to "Name", "recent" to "Latest update"),
                driverSort) { driverSort = it; render(scrollTop = true) })
        }
        val shown = drivers.filter { d ->
            val st = stateOf(d)
            matches(d.name, d.id, d.event) && when (driverFilter) {
                "live" -> st !in setOf("OFFLINE", "NOT MONITORING")
                "risk" -> st in setOf("HIGH", "MEDIUM", "LOW")
                "offline" -> st == "OFFLINE"
                else -> true
            }
        }.sortedWith(when (driverSort) {
            "name" -> compareBy { it.name.lowercase(Locale.ROOT) }
            "recent" -> compareByDescending { it.lastUpdate }
            else -> compareBy<Driver> { urgency(it) }.thenBy { it.name.lowercase(Locale.ROOT) }
        })
        if (drivers.isNotEmpty() && shown.isEmpty()) list.addView(Ui.note(this, "No drivers match."))
        var lastGroup = ""
        shown.forEach { d ->
            val state = stateOf(d)
            val live = state !in setOf("OFFLINE", "NOT MONITORING")
            val detail = buildList {
                add(if (live) d.event else if (state == "OFFLINE") "No recent data" else "Not monitoring")
                if (live) add("${d.tripSeconds / 60} min")
                if (live && d.speedKph != null) add(String.format(Locale.US, "%.0f km/h", d.speedKph))
                if (live && d.alertState == "ALERTING") add("🔔 alarm sounding")
                if (d.simulated && live) add("test data")
            }.joinToString(" · ")
            val group = when (state) { "HIGH", "MEDIUM" -> "NEEDS ATTENTION"; "OFFLINE", "NOT MONITORING" -> "NOT DRIVING"; else -> "DRIVING" }
            if (driverSort == "urgent" && group != lastGroup) {
                lastGroup = group
                val n = shown.count { x -> when (stateOf(x)) { "HIGH", "MEDIUM" -> "NEEDS ATTENTION"; "OFFLINE", "NOT MONITORING" -> "NOT DRIVING"; else -> "DRIVING" } == group }
                list.addView(Sui.sectionHeader(this, group, n, when (group) { "NEEDS ATTENTION" -> Ui.red; "DRIVING" -> Ui.green; else -> Ui.muted }))
            }
            val age = Ui.note(this, "")
            ageLabels[d.id] = age
            val r = Sui.driverCard(this, d.name, state, stateColor(state), detail, age, live && d.alertState == "ALERTING")
            r.setOnClickListener { selectedId = d.id; render(scrollTop = true) }
            r.contentDescription = "${d.name}: $state. $detail. Open details"
            list.addView(r)
        }
        content.addView(list, Ui.blockParams(this))
    }

    private fun showDriverDetails(id: String) {
        content.addView(Ui.linkRow(this, "‹ All drivers", "") { selectedId = null; render(scrollTop = true) }, Ui.blockParams(this))
        val d = drivers.firstOrNull { it.id == id }
        if (d == null) {
            content.addView(Ui.note(this, "Waiting for this driver's first update."))
            return
        }
        val state = stateOf(d)

        // Driver banner + facts grid (Sui).
        val ageLabel = Ui.note(this, "")
        ageLabels[d.id] = ageLabel
        content.addView(Sui.hero(this, d.name.uppercase(), when (state) { "HIGH", "MEDIUM", "LOW" -> "$state RISK"; else -> state },
            stateColor(state), if (state == "OFFLINE") "Last known: ${d.event}" else d.event, ageLabel), Ui.blockParams(this))
        val todayCount = d.alerts.count { it.ms >= startOfToday() && !it.simulated }
        content.addView(Sui.factGrid(this, listOf(
            "Driving time" to "${d.tripSeconds / 60} min",
            "Speed" to (d.speedKph?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "No GPS"),
            "Alarm" to when (d.alertState) { "ALERTING" -> "🔔 Sounding"; "UNAVAILABLE" -> "No camera"; "STOPPED" -> "Stopped"; else -> "Off" },
            "Alerts today" to "$todayCount")), Ui.blockParams(this))
        if (d.simulated) content.addView(Ui.note(this, "Test values from the driver's phone, not from the camera."), Ui.blockParams(this))

        if (d.statuses.isNotEmpty()) {
            val behaviours = Ui.card(this)
            behaviours.addView(Ui.title(this, "Behaviours now"))
            behaviours.addView(Sui.behaviourGrid(this, d.statuses.map { (behaviour, level) -> Ui.alertCause(behaviour) to level }))
            content.addView(behaviours, Ui.blockParams(this))
        }

        val location = Ui.card(this)
        location.addView(Ui.title(this, "Location"))
        val lat = d.lat
        val lng = d.lng
        if (lat == null || lng == null || d.locationTime <= 0L) {
            location.addView(Ui.note(this, "No location yet (no GPS fix on the sensor board or the driver's phone)."))
        } else {
            val age = (System.currentTimeMillis() - d.locationTime).coerceAtLeast(0L)
            val label = when (d.locationSource) {
                "manual" -> "manual test location"; "phone" -> "phone GPS"; else -> "sensor-board GPS"
            }
            location.addView(Ui.note(this, if (age > 60_000L) "Last known position ($label) · ${age / 60_000} min old"
                else "Live position ($label), updated every 2 s"))
            // CPECAPS: one live map, reused while the page refreshes; a new driver starts a new trail.
            val map = liveMap ?: LiveMap(this).also { liveMap = it }
            if (liveMapDriver != d.id) { map.clearTrail(); liveMapDriver = d.id }
            (map.view.parent as? ViewGroup)?.removeView(map.view)
            map.update(lat, lng, d.speedKph, "${d.name} · ${if (state == "OFFLINE") "offline" else state}",
                if (state in setOf("HIGH", "MEDIUM", "LOW")) state else "NORMAL")
            location.addView(map.view, LinearLayout.LayoutParams(-1, Ui.dp(this, 320)))
            location.addView(Button(this).apply {
                text = "Open in Maps"
                isAllCaps = false
                setOnClickListener {
                    val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lng, lat, lng, Uri.encode(d.name)))
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { cloud.toast("No maps app installed") }
                }
            })
        }
        content.addView(location, Ui.blockParams(this))

        val sessionsCard = Ui.card(this)
        sessionsCard.addView(Ui.title(this, "Recent sessions"))
        if (d.sessions.isEmpty()) sessionsCard.addView(Ui.note(this, "No sessions synced yet."))
        d.sessions.take(10).forEach { sn ->
            val detail = if (sn.active) "In progress" else
                "${sn.durationS / 60} min ${sn.durationS % 60} s · ${sn.alerts} alerts" + if (sn.highAlerts > 0) " (${sn.highAlerts} HIGH)" else ""
            val level = if (sn.active) "LIVE" else sn.maxLevel.ifBlank { "NORMAL" }
            sessionsCard.addView(Sui.alertItem(this, timeText(sn.startedMs), detail, "", if (sn.active) "LIVE" else level,
                if (sn.active) Ui.blue else Ui.riskColor(level)))
        }
        content.addView(sessionsCard, Ui.blockParams(this))

        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Alerts"))
        val today = d.alerts.filter { it.ms >= startOfToday() && !it.simulated }
        alerts.addView(Ui.note(this, "Today: ${today.size} alerts (${today.count { Ui.riskLevel(it.level) == "HIGH" }} HIGH)"))
        today.groupBy { Ui.alertCause(it.name) }.entries.sortedByDescending { it.value.size }.forEach { (name, list) ->
            alerts.addView(Ui.pair(this, name, "${list.size}×"))
        }
        if (d.alerts.isEmpty()) alerts.addView(Ui.note(this, "No alerts from this driver yet."))
        else {
            alerts.addView(Ui.eyebrow(this, "RECENT"))
            d.alerts.take(15).forEach { a ->
                alerts.addView(Sui.alertItem(this, Ui.alertCause(a.name, a.simulated), "", timeText(a.ms), Ui.riskLevel(a.level), Ui.riskColor(a.level)))
            }
        }
        content.addView(alerts, Ui.blockParams(this))
    }

    private fun showAlerts() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Alerts from all drivers"))
        addSearch(card)
        card.addView(Ui.choiceRow(this, "Show", listOf("all" to "All alerts", "high" to "HIGH only"),
            if (highOnly) "high" else "all") { highOnly = it == "high"; render(scrollTop = true) })
        card.addView(Ui.choiceRow(this, "Period", listOf("today" to "Today", "week" to "7 days", "all" to "All"),
            alertPeriod) { alertPeriod = it; render(scrollTop = true) })
        val since = when (alertPeriod) { "today" -> startOfToday(); "week" -> startOfToday() - 6 * 86_400_000L; else -> 0L }
        val filtered = allAlerts().filter {
            (!highOnly || Ui.riskLevel(it.level) == "HIGH") && it.ms >= since && matches(it.driverName, it.name, Ui.alertCause(it.name, it.simulated))
        }
        card.addView(Ui.note(this, "${filtered.size} alert${if (filtered.size == 1) "" else "s"} · " +
                "${filtered.count { Ui.riskLevel(it.level) == "HIGH" }} HIGH · ${filtered.map { it.driverId }.distinct().size} driver(s)"))
        val rows = filtered.take(100)
        if (rows.isEmpty()) card.addView(Ui.note(this, if (searchText.isNotEmpty()) "No alerts match the search." else if (highOnly) "No HIGH alerts in this period." else "No alerts in this period."))
        var lastDay = ""
        rows.forEach { a ->
            val day = when {
                a.ms >= startOfToday() -> "Today"
                a.ms >= startOfToday() - 86_400_000L -> "Yesterday"
                else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(a.ms))
            }
            if (day != lastDay) { lastDay = day; card.addView(Sui.dayHeader(this, day)) }
            val r = Sui.alertItem(this, Ui.alertCause(a.name, a.simulated), a.driverName,
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(a.ms)), Ui.riskLevel(a.level), Ui.riskColor(a.level))
            r.isClickable = true
            r.setOnClickListener { tab = "drivers"; selectedId = a.driverId; render(scrollTop = true) }
            card.addView(r)
        }
        content.addView(card, Ui.blockParams(this))
    }

    private fun showSettings() {
        if (cloud.role == "supervisor") people.addCard(content)
        val account = Ui.card(this)
        account.addView(Ui.title(this, "Account"))
        account.addView(Ui.note(this, if (cloud.signedIn) "Signed in as ${cloud.email}" else "Not signed in"))
        account.addView(Ui.note(this, cloud.connectionLabel))
        account.addView(Button(this).apply {
            text = if (cloud.signedIn) "Sign out" else "Sign in"
            isAllCaps = false
            setOnClickListener {
                val leave = {
                    if (cloud.signedIn) { SupervisorAlertService.stop(this@SupervisorActivity); cloud.unregisterSupervisorPush(); detachDrivers(); cloud.signOut() }
                    startActivity(Intent(this@SupervisorActivity, LauncherActivity::class.java))
                    finish()
                }
                // CPECAPS: confirm before signing out (alerts stop on this phone).
                if (cloud.signedIn) android.app.AlertDialog.Builder(this@SupervisorActivity)
                    .setTitle("Sign out?")
                    .setMessage("You will stop getting HIGH-risk alerts on this phone until you sign in again.")
                    .setPositiveButton("Sign out") { _, _ -> leave() }
                    .setNegativeButton("Cancel", null)
                    .show()
                else leave()
            }
        })
        if (cloud.signedIn) account.addView(Button(this).apply {
            text = "Change password"
            isAllCaps = false
            setOnClickListener {
                com.google.firebase.auth.FirebaseAuth.getInstance().sendPasswordResetEmail(cloud.email)
                    .addOnCompleteListener { t -> cloud.toast(if (t.isSuccessful) "Password reset link sent to ${cloud.email}" else "Could not send the link: ${t.exception?.message}") }
            }
        })
        content.addView(account, Ui.blockParams(this))

        val notifications = Ui.card(this)
        notifications.addView(Ui.title(this, "Notifications"))
        notifications.addView(Ui.switchRow(this, "HIGH-risk alerts", "A phone notification for each real HIGH alert (at most one per driver per minute)",
            pushEnabled()) { on ->
            prefs.edit().putBoolean("push", on).apply()
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
                cloud.registerSupervisorPush()
                SupervisorAlertService.start(this)
            } else { cloud.unregisterSupervisorPush(); SupervisorAlertService.stop(this) }
        })
        // CPECAPS: background watcher, alarm sound and a test notification.
        notifications.addView(Ui.switchRow(this, "Keep watching in the background",
            "Alerts arrive even when this app is closed (shows a small \"Watching your drivers\" notification)",
            SupervisorAlertService.enabled(this)) { on ->
            prefs.edit().putBoolean("background_alerts", on).apply()
            if (on && pushEnabled()) SupervisorAlertService.start(this) else SupervisorAlertService.stop(this)
        })
        notifications.addView(Ui.switchRow(this, "Alarm sound", "Play the alarm sound for HIGH-risk alerts (off: vibration only)",
            SupervisorNotifier.soundOn(this)) { on -> prefs.edit().putBoolean("alert_sound", on).apply() })
        notifications.addView(Button(this).apply {
            text = "Send a test notification"
            isAllCaps = false
            setOnClickListener {
                SupervisorNotifier.show(this@SupervisorActivity, "", "TEST high-risk alert",
                    "This is how a HIGH-risk alert looks. Tap it to open the dashboard.", test = true)
                if (!pushEnabled()) cloud.toast("Turn on HIGH-risk alerts first")
            }
        })
        val allowed = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            notifications.addView(Ui.note(this, "Android notifications are blocked for Danteye."))
            notifications.addView(Button(this).apply {
                text = "Allow notifications"
                isAllCaps = false
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77) }
            })
        }
        content.addView(notifications, Ui.blockParams(this))

        val display = Ui.card(this)
        display.addView(Ui.title(this, "Display"))
        display.addView(Ui.choiceRow(this, "Night mode", listOf("off" to "Off", "on" to "On", "auto" to "Auto (6 pm-6 am)"),
            Ui.nightChoice(this)) { choice ->
            Ui.saveNightChoice(this, choice)
            Ui.nightMode = Ui.nightPreference(this)
            root.setBackgroundColor(Ui.canvas)
            tabs.setBackgroundColor(Ui.surface)
            render()
        })
        content.addView(display, Ui.blockParams(this))

        val help = Ui.card(this)
        help.addView(Ui.title(this, "What the states mean"))
        for ((state, meaning) in listOf(
            "HIGH" to "Dangerous signs (long eye closure, phone use, drowsiness).",
            "MEDIUM" to "Clear warning signs; the driver's buzzer beeps.",
            "LOW" to "Mild signs.",
            "NORMAL" to "Monitoring, no warning signs.",
            "CALIBRATING" to "Learning the driver's normal face (once per driver).",
            "NO CAMERA" to "The driver's app runs but gets no camera picture.",
            "NOT MONITORING" to "The driver stopped monitoring.",
            "OFFLINE" to "No update for 90 seconds (no signal, phone off or app closed).")) {
            help.addView(Ui.alert(this, meaning, "", state, stateColor(state)))
        }
        content.addView(help, Ui.blockParams(this))

        val about = Ui.card(this)
        about.addView(Ui.title(this, "About"))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        about.addView(Ui.pair(this, "Danteye", "version $version"))
        content.addView(about, Ui.blockParams(this))
    }
}
