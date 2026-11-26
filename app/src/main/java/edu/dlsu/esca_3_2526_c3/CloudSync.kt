package edu.dlsu.esca_3_2526_c3

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Firebase disallows periods and other punctuation in path keys; escape them reversibly. */
fun firebaseEmailKey(email: String): String = email.trim().lowercase(Locale.ROOT)
    .replace("%", "%25").replace("-", "%2D").replace(".", "-").replace("#", "%23")
    .replace("$", "%24").replace("[", "%5B").replace("]", "%5D")
    .replace("/", "%2F")

/** The project's Realtime Database (one place for the address). */
const val FIREBASE_DB_URL = "https://esca-3-2526-c3-testing-default-rtdb.asia-southeast1.firebasedatabase.app"

/**
 * Firebase for the driver, the supervisor and the monitoring service. Database layout (firebase/DATABASE.md):
 *
 *   accounts/<uid>                        email, name, role (driver / supervisor, set by a supervisor), requestedRole,
 *                                         requestedAt, lastSignIn (readable times) + ...Ms copies
 *   drivers/<uid>/name, email
 *   drivers/<uid>/live                    status right now (every 2 s while monitoring); updated = readable time
 *   drivers/<uid>/trips/<start time>      e.g. 2026-11-27_17-30-05: started, ended, minutes, alerts, highAlerts, maxLevel
 *   drivers/<uid>/alerts/<time_behaviour> e.g. 2026-11-27_17-42-10-123_eye-closure: time, trip, behaviour, level,
 *                                         sentToSupervisor, test, timestampMs (for sorting)
 *   supervisorPhones/<uid>                push-notification address of each supervisor phone
 *   setup/firstSupervisor                 who claimed the first supervisor role (written once)
 *
 * <uid> = the Firebase account id (the same in accounts/ and drivers/). Alerts and trip summaries wait on the
 * phone while offline (pendingFor shows what a History session still has to send).
 */
class CloudSync(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("cloud_sync_v2", Context.MODE_PRIVATE)
    private var connectionRef: DatabaseReference? = null
    private var connectionListener: ValueEventListener? = null
    private var roleRef: DatabaseReference? = null
    private var roleListener: ValueEventListener? = null
    private var online = false
    var role = ""
        private set
    /** True once Firebase has answered (online, or from its offline copy): only then can "no role" mean removed. */
    var roleChecked = false
        private set

    companion object {
        private const val ROLE_PREFS = "role_cache_v1"
        /** The last role this account had on this phone ("" = none): the app opens it directly, also offline. */
        fun cachedRole(context: Context, uid: String): String =
            context.getSharedPreferences(ROLE_PREFS, Context.MODE_PRIVATE).getString(uid, "") ?: ""
        fun cacheRole(context: Context, uid: String, role: String) {
            context.getSharedPreferences(ROLE_PREFS, Context.MODE_PRIVATE).edit().putString(uid, role).apply()
        }
    }
    val configured: Boolean get() = FirebaseApp.getApps(app).isNotEmpty()
    val signedIn: Boolean get() = configured && FirebaseAuth.getInstance().currentUser != null
    val email: String get() = if (signedIn) FirebaseAuth.getInstance().currentUser?.email ?: "" else ""
    val ready: Boolean get() = online && role.isNotBlank() && signedIn
    val lastSync: Long get() = prefs.getLong("lastSyncMs", 0L)
    val pendingCount: Int get() = readPending().length()
    val connectionLabel: String get() = when {
        !configured -> "Firebase not configured (copy google-services.json)"
        !signedIn -> "Not signed in"
        role.isEmpty() -> "This account has no role yet"
        online -> "Connected as $role"
        else -> "Offline: data are sent when the internet is back"
    }

    var onChange: (() -> Unit)? = null

    private fun db() = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
    private fun uid() = FirebaseAuth.getInstance().currentUser!!.uid
    private fun driverId() = uid() // drivers/<uid>: the same id as accounts/<uid>

    fun driversRef(): DatabaseReference = db().getReference("drivers")
    fun driverRef(driverId: String = driverId()): DatabaseReference = driversRef().child(driverId)
    fun alertsRef(driverId: String = driverId()): DatabaseReference = driverRef(driverId).child("alerts")

    fun start() {
        if (!configured) { onChange?.invoke(); return }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.email.isNullOrBlank()) { onChange?.invoke(); return }
        role = cachedRole(app, uid()) // until Firebase answers (e.g. offline): the last known role
        roleRef = db().getReference("accounts").child(uid()).child("role")
        roleListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                role = snapshot.getValue(String::class.java) ?: ""
                roleChecked = true
                cacheRole(app, uid(), role)
                onChange?.invoke()
                if (role == "driver" && online) flush()
            }
            override fun onCancelled(error: DatabaseError) {
                role = ""
                toast("Firebase role error: ${error.message}")
                onChange?.invoke()
            }
        }
        roleRef!!.addValueEventListener(roleListener!!)
        connectionRef = db().getReference(".info/connected")
        connectionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                online = snapshot.getValue(Boolean::class.java) == true
                onChange?.invoke()
                if (online && role == "driver") flush()
            }
            override fun onCancelled(error: DatabaseError) { online = false; onChange?.invoke() }
        }
        connectionRef!!.addValueEventListener(connectionListener!!)
        onChange?.invoke()
    }

    fun stop() {
        connectionListener?.let { connectionRef?.removeEventListener(it) }
        roleListener?.let { roleRef?.removeEventListener(it) }
        connectionListener = null; roleListener = null
        connectionRef = null; roleRef = null
    }

    fun signOut() {
        if (signedIn) cacheRole(app, uid(), "")
        stop(); if (configured) FirebaseAuth.getInstance().signOut()
        role = ""; online = false; onChange?.invoke()
    }

    /** Safe from any thread and from the service. */
    fun toast(message: String) { main.post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() } }

    // ------------------------------------------------------------------ queue (works offline)

    private fun readPending(): JSONArray = try { JSONArray(prefs.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized private fun enqueue(item: JSONObject) {
        val queue = readPending()
        queue.put(item.put("driver", if (signedIn) driverId() else ""))
        while (queue.length() > 2000) queue.remove(0) // a very long offline period cannot fill the phone
        prefs.edit().putString("pending", queue.toString()).apply()
        onChange?.invoke()
        if (ready && role == "driver") flush()
    }

    /** Items of one session still waiting to be sent (History shows "Synced" at 0). */
    fun pendingFor(sessionId: String): Int {
        val queue = readPending()
        return (0 until queue.length()).count { queue.optJSONObject(it)?.optString("sessionId") == sessionId }
    }

    private fun level(severity: String) = when (Ui.riskLevel(severity)) { "HIGH" -> "HIGH"; "MEDIUM" -> "MEDIUM"; else -> "LOW" }

    /**
     * One alert. Real alerts: test = false, push = the alert state machine's push flag (once per HIGH
     * alert, at most one per minute). Developer-mode tests: test = true.
     */
    fun record(behavior: String, severity: String, sessionId: String, test: Boolean = true, push: Boolean = false) {
        val now = System.currentTimeMillis()
        val slug = behavior.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "alert" }
        val id = stamp(now, "yyyy-MM-dd_HH-mm-ss-SSS") + "_" + slug  // readable key, e.g. 2026-11-27_17-42-10-123_eye-closure
        val trip = tripKey(sessionId)
        enqueue(JSONObject().put("type", "alert").put("id", id).put("sessionId", sessionId).put("trip", trip).put("data", JSONObject()
            .put("time", stamp(now, "yyyy-MM-dd HH:mm:ss")).put("timestampMs", now).put("trip", trip)
            .put("behaviour", behavior).put("level", level(severity)).put("sentToSupervisor", push).put("test", test)))
    }

    private fun stamp(ms: Long, pattern: String): String = java.text.SimpleDateFormat(pattern, Locale.US).format(Date(ms))

    /** The readable trip key of a session (its start time), saved when the trip starts. */
    private fun tripKey(sessionId: String): String = prefs.getString("trip_$sessionId", null) ?: sessionId

    /** Session start / end summary (merged into drivers/<driver>/sessions/<session>). */
    fun recordSession(sessionId: String, fields: Map<String, Any?>) {
        val data = JSONObject()
        for ((k, v) in fields) data.put(k, v ?: JSONObject.NULL)
        (fields["startedMs"] as? Number)?.toLong()?.let { ms ->
            prefs.edit().putString("trip_$sessionId", stamp(ms, "yyyy-MM-dd_HH-mm-ss")).apply()
            data.put("started", stamp(ms, "yyyy-MM-dd HH:mm:ss"))
        }
        (fields["endedMs"] as? Number)?.toLong()?.let { data.put("ended", stamp(it, "yyyy-MM-dd HH:mm:ss")) }
        (fields["durationS"] as? Number)?.toLong()?.let { data.put("minutes", Math.round(it / 6.0) / 10.0) }
        enqueue(JSONObject().put("type", "session").put("id", UUID.randomUUID().toString())
            .put("sessionId", sessionId).put("trip", tripKey(sessionId)).put("data", data))
    }

    /** CPECAPS: the name the supervisor sees (Firebase profile + drivers/<d>/name). */
    fun updateName(name: String, done: (Boolean, String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser ?: run { done(false, "Not signed in"); return }
        user.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name).build())
            .addOnCompleteListener { t ->
                if (t.isSuccessful) db().getReference("accounts").child(uid()).child("name").setValue(name)
                if (t.isSuccessful && role == "driver") driverRef().updateChildren(mapOf("name" to name))
                done(t.isSuccessful, if (t.isSuccessful) "Name saved" else (t.exception?.message ?: "Could not save the name"))
            }
    }

    val shownName: String get() = displayName()

    private fun displayName(): String =
        FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() } ?: email

    /** Name, email and the given live fields in one update (paths "live/<field>"). */
    private fun updateLive(fields: Map<String, Any?>) {
        val changes = mutableMapOf<String, Any?>("name" to displayName(), "email" to email)
        for ((k, v) in fields) changes["live/$k"] = v
        changes["live/updated"] = stamp(System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss")
        driverRef().updateChildren(changes)
    }

    /** Developer-mode test status (marked test = true for the supervisor). */
    fun publish(statuses: Map<String, String>, sessionId: String, tripSeconds: Long) {
        if (!ready || role != "driver" || email.isBlank()) return
        val severe = statuses.values.any { it == "High" }
        val medium = statuses.values.any { it == "Warning" }
        val headline = statuses.entries.firstOrNull { it.value == "High" }
            ?: statuses.entries.firstOrNull { it.value == "Warning" }
        updateLive(mapOf(
            "risk" to (if (severe) "High risk" else if (medium) "Medium risk" else "No risk"),
            "event" to (headline?.key ?: "Normal"), "statuses" to statuses,
            "timestampMs" to System.currentTimeMillis(), "sessionId" to sessionId,
            "tripSeconds" to tripSeconds, "test" to true))
    }

    /** Live status measured by the detection program (sent by the monitoring service every 2 s). */
    fun publishLive(statuses: Map<String, String>, risk: String, riskScore: Double, event: String, alertState: String,
                    sessionId: String, tripSeconds: Long, latitude: Double?, longitude: Double?, speedKph: Double?,
                    locationSource: String?) {
        if (!ready || role != "driver" || email.isBlank()) return
        val valid = latitude != null && longitude != null
        updateLive(mapOf(
            "risk" to risk, "riskScore" to Math.round(riskScore * 10) / 10.0, "event" to event, "alertState" to alertState,
            "statuses" to statuses, "timestampMs" to System.currentTimeMillis(), "sessionId" to sessionId,
            "tripSeconds" to tripSeconds, "test" to false,
            "lat" to latitude, "lng" to longitude, "locationTimestampMs" to if (valid) System.currentTimeMillis() else null,
            "speedKph" to speedKph, "locationSource" to if (valid) locationSource else null))
    }

    /** Remove a previously entered test location when monitoring is not running. */
    fun clearLocation() {
        if (ready && role == "driver") updateLive(mapOf("lat" to null, "lng" to null, "locationTimestampMs" to null,
            "speedKph" to null, "locationSource" to null))
    }

    /** Tell the supervisor that monitoring stopped (instead of waiting for "offline"). */
    fun publishStopped(sessionId: String, tripSeconds: Long) {
        if (!ready || role != "driver" || email.isBlank()) return
        updateLive(mapOf("risk" to "Not monitoring", "event" to "Monitoring stopped", "alertState" to "STOPPED",
            "timestampMs" to System.currentTimeMillis(), "sessionId" to sessionId, "tripSeconds" to tripSeconds))
    }

    private fun toMap(o: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) { val k = keys.next(); map[k] = if (o.isNull(k)) null else o.get(k) }
        return map
    }

    private var flushing = false

    /** Send waiting items one by one, oldest first. */
    fun flush() {
        if (!ready || role != "driver" || flushing || email.isBlank()) return
        val item = readPending().optJSONObject(0) ?: run { onChange?.invoke(); return }
        flushing = true
        val driver = item.optString("driver").ifBlank { driverId() }
        val data = toMap(item.optJSONObject("data") ?: JSONObject())
        val task = if (item.optString("type") == "session")
            driverRef(driver).child("trips").child(item.optString("trip").ifBlank { item.optString("sessionId") }).updateChildren(data)
        else alertsRef(driver).child(item.optString("id")).setValue(data)
        task.addOnCompleteListener { result ->
            flushing = false
            if (result.isSuccessful) {
                synchronized(this) {
                    val current = readPending()
                    val remaining = JSONArray()
                    for (i in 0 until current.length()) {
                        val other = current.optJSONObject(i) ?: continue
                        if (other.optString("id") != item.optString("id")) remaining.put(other)
                    }
                    prefs.edit().putString("pending", remaining.toString())
                        .putLong("lastSyncMs", System.currentTimeMillis()).apply()
                }
                onChange?.invoke()
                flush()
            } else {
                toast("Sync failed: ${result.exception?.localizedMessage}")
                onChange?.invoke()
            }
        }
    }

    fun lastSyncText() = if (lastSync == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(lastSync))

    /** Stop push notifications to this phone (Settings switch off, or sign-out). */
    fun unregisterSupervisorPush() {
        if (!signedIn || role != "supervisor") return
        db().getReference("supervisorPhones").child(uid()).setValue(null)
            .addOnFailureListener { toast("Could not turn notifications off: ${it.localizedMessage}") }
    }

    fun registerSupervisorPush() {
        if (!ready || role != "supervisor" || email.isBlank()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            // Restricted in the database rules to verified supervisor accounts.
            db().getReference("supervisorPhones").child(uid()).setValue(token)
                .addOnFailureListener { toast("Push registration failed: ${it.localizedMessage}") }
        }.addOnFailureListener { toast("FCM token unavailable: ${it.localizedMessage}") }
    }
}
