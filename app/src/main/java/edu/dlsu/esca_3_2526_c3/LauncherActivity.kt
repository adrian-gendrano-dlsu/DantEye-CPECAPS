package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * One entry point for both roles. The role is read from Firebase (accounts/<uid>/role). A new account asks for access
 * (accounts/<uid>/requestedRole) and a supervisor approves it in the app (Supervisor > Settings > People).
 */
class LauncherActivity : Activity() {
    private lateinit var content: LinearLayout
    private var signingUp = false
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.nightMode = Ui.nightPreference(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.canvas); isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@LauncherActivity, 16), Ui.dp(this@LauncherActivity, 20),
                Ui.dp(this@LauncherActivity, 16), Ui.dp(this@LauncherActivity, 24))
        }
        scroll.addView(content)
        // Navy top bar behind the Android status bar, then the scrolling page.
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.canvas) }
        val header = Ui.header(this, "Danteye")
        screen.addView(header)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        Ui.fitSystemBars(this, screen, header)
        if (FirebaseApp.getApps(this).isEmpty()) showMessage("Firebase is not configured on this device.")
        else if (FirebaseAuth.getInstance().currentUser != null) {
            // Access already granted on this phone: open that screen directly (also without internet).
            // The screen checks the role again when online and signs out if it was removed.
            val user = FirebaseAuth.getInstance().currentUser!!
            when (CloudSync.cachedRole(this, user.uid)) {
                "driver" -> { startActivity(Intent(this, MainActivity::class.java)); finish() }
                "supervisor" -> { startActivity(Intent(this, SupervisorActivity::class.java)); finish() }
                else -> checkRole()
            }
        }
        else showLogin()
    }

    private fun showMessage(message: String) {
        content.removeAllViews()
        addBrand()
        val card = Ui.card(this)
        card.addView(Ui.note(this, message))
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            card.addView(button("Check account role") { checkRole() })
            card.addView(button("Use another account") {
                FirebaseAuth.getInstance().signOut()
                showLogin()
            })
        }
        content.addView(card, Ui.blockParams(this))
    }

    /** App name and logo at the top of the sign-in screen. */
    private fun addBrand() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(this@LauncherActivity, 8))
        }
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_legacy)
            contentDescription = "Danteye logo"
        }, LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@LauncherActivity, 12), 0, 0, 0)
        }
        names.addView(Ui.title(this, "Welcome").apply { setPadding(0, 0, 0, 0) })
        names.addView(Ui.note(this, "Driver monitoring and safety alerts"))
        row.addView(names)
        content.addView(row)
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun field(hintText: String, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or if (password)
            InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setSingleLine(true)
        if (password) {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        textSize = 14f
        if (Ui.nightMode) {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
    }

    private fun showLogin() {
        content.removeAllViews()
        addBrand()

        val card = Ui.card(this)
        card.addView(Ui.title(this, if (signingUp) "Create account" else "Sign in"))

        val email = field("Email address")
        val password = field("Password", true)
        card.addView(email)
        card.addView(password)

        val confirmation = if (signingUp) field("Confirm password", true) else null
        confirmation?.let { card.addView(it) }

        // CPECAPS: remember the email, show/hide the password.
        val loginPrefs = getSharedPreferences("login_prefs", MODE_PRIVATE)
        email.setText(loginPrefs.getString("email", ""))
        val remember = android.widget.CheckBox(this).apply {
            text = "Remember my email"
            isChecked = loginPrefs.getBoolean("remember", true)
            if (Ui.nightMode) setTextColor(Color.WHITE)
        }
        val showPassword = android.widget.CheckBox(this).apply {
            text = "Show password"
            if (Ui.nightMode) setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on ->
                val type = if (on) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                for (f in listOfNotNull(password, confirmation)) { f.inputType = type; f.setSelection(f.text.length) }
            }
        }
        card.addView(showPassword)
        card.addView(remember)

        val feedback = Ui.note(this, "")
        card.addView(feedback)

        val submit = button(if (signingUp) "Create account" else "Sign in") {
            val address = email.text.toString().trim()
            val secret = password.text.toString()
            when {
                FirebaseApp.getApps(this).isEmpty() -> feedback.text = "Firebase is unavailable."
                !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() -> feedback.text = "Enter a valid email."
                secret.length < 6 -> feedback.text = "Use at least 6 password characters."
                signingUp && confirmation?.text.toString() != secret -> feedback.text = "Passwords do not match."
                else -> {
                    feedback.text = "Checking account…"
                    loginPrefs.edit().putBoolean("remember", remember.isChecked)
                        .putString("email", if (remember.isChecked) address else "").apply()
                    val auth = FirebaseAuth.getInstance()
                    val task = if (signingUp) auth.createUserWithEmailAndPassword(address, secret)
                    else auth.signInWithEmailAndPassword(address, secret)
                    task.addOnCompleteListener(this) { result ->
                        if (result.isSuccessful) checkRole()
                        else feedback.text = friendlyError(result.exception?.localizedMessage)
                    }
                }
            }
        }
        card.addView(submit)
        card.addView(button(if (signingUp) "Already have an account? Sign in" else "Create an account") {
            signingUp = !signingUp
            showLogin()
        })
        if (!signingUp) card.addView(button("Forgot password?") {
            val address = email.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                feedback.text = "Enter your email first."
            } else FirebaseAuth.getInstance().sendPasswordResetEmail(address)
                .addOnSuccessListener { feedback.text = "Check your email for reset instructions." }
                .addOnFailureListener { feedback.text = it.localizedMessage ?: "Could not send reset email." }
        })
        content.addView(card, Ui.blockParams(this))
    }

    /** CPECAPS: plain-language versions of the most common Firebase sign-in errors. */
    private fun friendlyError(msg: String?): String {
        val m = msg ?: return "Sign-in failed. Try again."
        return when {
            m.contains("password is invalid", true) || m.contains("INVALID_LOGIN_CREDENTIALS", true) ||
                    m.contains("incorrect", true) || m.contains("malformed or has expired", true) -> "Wrong email or password."
            m.contains("no user record", true) -> "No account with this email. Create an account first."
            m.contains("already in use", true) -> "This email already has an account. Sign in instead."
            m.contains("network", true) -> "No internet connection. Check Wi-Fi or mobile data."
            m.contains("blocked", true) || m.contains("too many", true) -> "Too many tries. Wait a few minutes, then try again."
            else -> m
        }
    }

    /** No role yet: ask for one. A supervisor approves it in the app; the first supervisor can claim the role. */
    private fun showRequestAccess(user: com.google.firebase.auth.FirebaseUser) {
        content.removeAllViews()
        addBrand()
        val users = FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("accounts").child(user.uid)
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Ask for access"))
        card.addView(Ui.note(this, "Signed in as ${user.email}. Choose what you are; a supervisor then gives you access in Danteye."))
        val feedback = Ui.note(this, "")
        fun request(role: String) {
            feedback.text = "Sending…"
            val now = System.currentTimeMillis()
            users.updateChildren(mapOf("email" to (user.email ?: ""), "requestedRole" to role, "requestedAt" to readable(now),
                "requestedAtMs" to now, "lastSignIn" to readable(now), "lastSignInMs" to now))
                .addOnSuccessListener { feedback.text = "Request sent: $role. Ask your supervisor to approve it, then press Check again." }
                .addOnFailureListener { feedback.text = "Could not send the request (${it.localizedMessage}). The new database rules may not be deployed yet." }
        }
        card.addView(button("I am a driver") { request("driver") })
        card.addView(button("I am a supervisor") { request("supervisor") })
        card.addView(feedback)
        card.addView(button("Check again") { checkRole() })
        card.addView(button("Use another account") { FirebaseAuth.getInstance().signOut(); showLogin() })
        content.addView(card, Ui.blockParams(this))
        users.child("requestedRole").get().addOnSuccessListener { snap ->
            snap.getValue(String::class.java)?.let { if (feedback.text.isNullOrEmpty()) feedback.text = "Request sent earlier: $it. Waiting for a supervisor." }
        }

        val first = Ui.card(this)
        first.addView(Ui.title(this, "Setting up Danteye for the first time?"))
        val firstNote = Ui.note(this, "If nobody has a role yet, the first person can become the supervisor. " +
                "After that, a supervisor approves everyone else.")
        first.addView(firstNote)
        first.addView(button("Become the first supervisor") {
            // One update: the role and the "first supervisor" mark (the rules allow it only while nobody has claimed it).
            FirebaseDatabase.getInstance(FIREBASE_DB_URL).reference.updateChildren(mapOf(
                "accounts/${user.uid}/role" to "supervisor", "accounts/${user.uid}/email" to (user.email ?: ""),
                "setup/firstSupervisor" to user.uid, "setup/firstSupervisorEmail" to (user.email ?: ""),
                "setup/created" to readable(System.currentTimeMillis())))
                .addOnSuccessListener { checkRole() }
                .addOnFailureListener { firstNote.text = "Not possible: a supervisor already exists. Ask them to approve your request above." }
        })
        // Only while the database has no supervisor yet (setup/firstSupervisor is set once, by the first one).
        FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("setup").child("firstSupervisor").get()
            .addOnSuccessListener { snap ->
                if (!snap.exists() && !isFinishing && first.parent == null) content.addView(first, Ui.blockParams(this))
            }
    }

    private fun readable(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    private fun checkRole() {
        val user = FirebaseAuth.getInstance().currentUser ?: run { showLogin(); return }
        if (checking) return
        if (user.email.isNullOrBlank()) { showMessage("Sign in with an email account."); return }
        checking = true
        showMessage("Checking access for ${user.email ?: "your account"}…")
        // An account deleted in the Firebase console stays "signed in" on the phone for up to an hour:
        // ask Firebase first, and sign out if the account no longer exists.
        user.reload().addOnCompleteListener(this) { r ->
            val e = r.exception
            if (e is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                checking = false
                FirebaseAuth.getInstance().signOut()
                signingUp = true
                showLogin()
                android.widget.Toast.makeText(this, "That account no longer exists. Create a new account.", android.widget.Toast.LENGTH_LONG).show()
            } else readRole(user)
        }
    }

    private fun readRole(user: com.google.firebase.auth.FirebaseUser) {
        FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("accounts").child(user.uid).child("role").get()
            .addOnSuccessListener { snapshot ->
                checking = false
                if (isFinishing || isDestroyed || FirebaseAuth.getInstance().currentUser?.uid != user.uid) return@addOnSuccessListener
                CloudSync.cacheRole(this, user.uid, snapshot.getValue(String::class.java) ?: "")
                val destination = when (snapshot.getValue(String::class.java)) {
                    "driver" -> MainActivity::class.java
                    "supervisor" -> SupervisorActivity::class.java
                    else -> null
                }
                if (destination == null) showRequestAccess(user)
                else {
                    // Keep the profile the supervisor's People list shows (email, last sign-in).
                    val now = System.currentTimeMillis()
                    FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("accounts").child(user.uid)
                        .updateChildren(mapOf("email" to (user.email ?: ""), "lastSignIn" to readable(now), "lastSignInMs" to now))
                    startActivity(Intent(this, destination))
                    finish()
                }
            }
            .addOnFailureListener { error ->
                checking = false
                if (!isFinishing) showMessage(if (error.localizedMessage?.contains("ermission", true) == true)
                    "The database refused access (${error.localizedMessage}). Usually the new database rules are not published yet " +
                        "(Firebase console > Realtime Database > Rules), or this phone still has the old app version. " +
                        "If the account was deleted, tap Use another account."
                    else "Could not check your access. Check the internet and retry. ${error.localizedMessage}")
            }
    }
}
