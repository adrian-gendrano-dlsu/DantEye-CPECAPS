package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Roles in the app instead of the Firebase console (supervisor screen > Settings > People).
 *   accounts/<uid>  {email, name, role, requestedRole, requestedAt, lastSignIn}   one record per person
 * Each user writes their own email / request / sign-in time (LauncherActivity); only supervisors write "role"
 * (database rules). The very first supervisor can claim the role once (setup/firstSupervisor).
 */
class PeopleManager(private val activity: Activity, private val onChange: () -> Unit) {
    class Person(val uid: String, val email: String, val name: String, val role: String?, val requested: String?,
                 val requestedAtMs: Long, val lastSeenMs: Long)

    private val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
    private var users = emptyMap<String, DataSnapshot>()
    private var usersListener: ValueEventListener? = null
    var problem: String? = null
        private set

    fun attach() {
        if (usersListener != null) return
        usersListener = db.getReference("accounts").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) { users = snap.children.associateBy { it.key ?: "" }; problem = null; onChange() }
            override fun onCancelled(e: DatabaseError) { problem = e.message; onChange() }
        })
    }

    fun detach() {
        usersListener?.let { db.getReference("accounts").removeEventListener(it) }
        usersListener = null
    }

    val people: List<Person>
        get() = users.filterKeys { it.isNotEmpty() }.map { (uid, u) ->
            Person(uid, u.child("email").getValue(String::class.java) ?: "Account ${uid.take(6)}…",
                u.child("name").getValue(String::class.java) ?: "", u.child("role").getValue(String::class.java),
                u.child("requestedRole").getValue(String::class.java),
                u.child("requestedAtMs").getValue(Long::class.java) ?: 0L,
                u.child("lastSignInMs").getValue(Long::class.java) ?: 0L)
        }.sortedBy { it.email.lowercase() }

    val waiting: List<Person> get() = people.filter { it.role == null && it.requested != null }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    fun setRole(p: Person, role: String?) {
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (p.uid == me && role != "supervisor") { toast("You cannot remove your own supervisor role. Ask another supervisor."); return }
        val ref = db.getReference("accounts").child(p.uid).child("role")
        (if (role == null) ref.removeValue() else ref.setValue(role))
            .addOnSuccessListener {
                db.getReference("accounts").child(p.uid).child("requestedRole").removeValue()
                toast(if (role == null) "${p.email} has no access now" else "${p.email} is now a $role")
            }
            .addOnFailureListener { toast("Not saved: ${it.localizedMessage}. Deploy the new database rules first.") }
    }

    private fun decline(p: Person) {
        db.getReference("accounts").child(p.uid).child("requestedRole").removeValue()
            .addOnSuccessListener { toast("Request from ${p.email} declined") }
    }

    private fun ago(ms: Long): String {
        if (ms <= 0) return ""
        val min = (System.currentTimeMillis() - ms) / 60_000
        return when { min < 1 -> "just now"; min < 60 -> "$min min ago"; min < 1440 -> "${min / 60} h ago"; else -> "${min / 1440} d ago" }
    }

    /** Drivers tab: "2 people are waiting for access". */
    fun addWaitingBanner(parent: LinearLayout, open: () -> Unit) {
        val n = waiting.size
        if (n == 0) return
        val card = Ui.card(activity)
        card.addView(Ui.linkRow(activity, "$n ${if (n == 1) "person is" else "people are"} waiting for access",
            "Approve them in Settings > People") { open() })
        parent.addView(card, Ui.blockParams(activity))
    }

    /** Settings tab: waiting requests, drivers and supervisors, with approve / change / remove. */
    fun addCard(parent: LinearLayout) {
        val card = Ui.card(activity)
        card.addView(Ui.title(activity, "People"))
        problem?.let {
            card.addView(Ui.note(activity, "The people list cannot be read ($it). Deploy the new database rules once: " +
                    "firebase deploy --only database (in the app's firebase folder)."))
            parent.addView(card, Ui.blockParams(activity))
            return
        }
        card.addView(Ui.note(activity, "New accounts ask for access when they sign in. Approve them here; tap a person to change their role."))
        val waitingNow = waiting
        card.addView(Ui.eyebrow(activity, "WAITING FOR ACCESS (${waitingNow.size})"))
        if (waitingNow.isEmpty()) card.addView(Ui.note(activity, "Nobody is waiting."))
        for (p in waitingNow) {
            card.addView(Ui.pair(activity, p.email, "asks: ${p.requested} · ${ago(p.requestedAtMs)}"))
            val row = LinearLayout(activity)
            fun btn(label: String, click: () -> Unit) = Button(activity).apply { text = label; isAllCaps = false; setOnClickListener { click() } }
            row.addView(btn("Driver") { setRole(p, "driver") }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(btn("Supervisor") { setRole(p, "supervisor") }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(btn("Decline") { decline(p) }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
        }
        for ((title, role) in listOf("DRIVERS" to "driver", "SUPERVISORS" to "supervisor")) {
            val list = people.filter { it.role == role }
            card.addView(Ui.eyebrow(activity, "$title (${list.size})"))
            if (list.isEmpty()) card.addView(Ui.note(activity, "None yet."))
            for (p in list) {
                val seen = ago(p.lastSeenMs).let { if (it.isEmpty()) "" else " · signed in $it" }
                card.addView(Ui.linkRow(activity, p.email, (if (p.name.isNotBlank()) p.name + " · " else "") + role.replaceFirstChar { it.uppercase() } + seen) {
                    AlertDialog.Builder(activity)
                        .setTitle(p.email)
                        .setItems(arrayOf("Make driver", "Make supervisor", "Remove access")) { _, which ->
                            when (which) {
                                0 -> setRole(p, "driver")
                                1 -> setRole(p, "supervisor")
                                else -> AlertDialog.Builder(activity).setTitle("Remove access for ${p.email}?")
                                    .setMessage("They can sign in but see nothing until a supervisor gives them a role again.")
                                    .setPositiveButton("Remove") { _, _ -> setRole(p, null) }.setNegativeButton("Cancel", null).show()
                            }
                        }.show()
                })
            }
        }
        parent.addView(card, Ui.blockParams(activity))
    }
}
