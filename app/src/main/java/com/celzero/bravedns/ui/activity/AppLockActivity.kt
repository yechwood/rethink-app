package com.celzero.bravedns.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

class AppLockActivity : BaseActivity(R.layout.activity_app_lock) {
    private val persistentState by inject<PersistentState>()

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var password: EditText
    private lateinit var confirmation: EditText
    private lateinit var error: TextView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var exit: Button

    private enum class Mode { SETUP, UNLOCK, CHANGE }
    private var mode = Mode.UNLOCK

    companion object {
        const val APP_LOCK_ALIAS = ".ui.activity.LauncherAliasAppLock"
        const val HOME_ALIAS = ".ui.LauncherAliasHome"
        private const val HASH_ITERATIONS = 30_000
        private const val LEGACY_HASH_ITERATIONS = 120_000
        private const val HASH_LENGTH_BITS = 256
        private const val SALT_BYTES = 16
        private const val MIN_PASSWORD_LENGTH = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        handleFrostEffectIfNeeded(persistentState.theme)
        if (isAtleastQ()) {
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        title = findViewById(R.id.password_title)
        subtitle = findViewById(R.id.password_subtitle)
        password = findViewById(R.id.password_field)
        confirmation = findViewById(R.id.password_confirm_field)
        error = findViewById(R.id.password_error)
        primary = findViewById(R.id.password_primary)
        secondary = findViewById(R.id.password_secondary)
        exit = findViewById(R.id.password_exit)

        exit.setOnClickListener { finishAffinity() }
        primary.setOnClickListener { handlePrimary() }
        secondary.setOnClickListener { handleSecondary() }

        if (persistentState.appLockPasswordHash.isBlank()) showSetup() else showUnlock()
    }

    override fun onBackPressed() {
        if (mode == Mode.CHANGE) showUnlock() else finishAffinity()
    }

    private fun showSetup() {
        mode = Mode.SETUP
        title.text = "Protect Rethink"
        subtitle.text = "Create a password to keep Rethink private. Use at least $MIN_PASSWORD_LENGTH characters."
        password.hint = "Create password"
        confirmation.hint = "Confirm password"
        confirmation.visibility = View.VISIBLE
        secondary.visibility = View.GONE
        primary.text = "Create password"
        exit.text = "Exit"
        password.setText("")
        confirmation.setText("")
        clearError()
        password.requestFocus()
    }

    private fun showUnlock() {
        mode = Mode.UNLOCK
        title.text = "Welcome back"
        subtitle.text = "Rethink is locked. Enter your password to continue."
        password.hint = "Password"
        confirmation.visibility = View.GONE
        secondary.visibility = View.VISIBLE
        secondary.text = "Change / Remove password"
        primary.text = "Unlock"
        exit.text = "Exit"
        password.setText("")
        clearError()
        password.requestFocus()
    }

    private fun showChange() {
        mode = Mode.CHANGE
        title.text = "Change password"
        subtitle.text = "Choose a new password, or remove protection from this device."
        password.hint = "New password"
        confirmation.hint = "Confirm new password"
        confirmation.visibility = View.VISIBLE
        secondary.visibility = View.VISIBLE
        secondary.text = "Remove password"
        primary.text = "Save password"
        exit.text = "Cancel"
        password.setText("")
        confirmation.setText("")
        clearError()
        password.requestFocus()
    }

    private fun handlePrimary() {
        val value = password.text?.toString() ?: ""
        when (mode) {
            Mode.SETUP -> {
                val confirm = confirmation.text?.toString() ?: ""
                when {
                    value.length < MIN_PASSWORD_LENGTH -> showError("Password must be at least $MIN_PASSWORD_LENGTH characters.")
                    value != confirm -> showError("Passwords do not match.")
                    else -> {
                        savePassword(value)
                        startHomeActivity()
                    }
                }
            }
            Mode.UNLOCK -> {
                if (value.isBlank()) {
                    showError("Enter your password.")
                    return
                }
                setBusy(true)
                verifyPasswordAsync(value) { valid ->
                    setBusy(false)
                    if (valid) startHomeActivity() else {
                        password.setText("")
                        showError("Incorrect password. Try again.")
                    }
                }
            }
            Mode.CHANGE -> {
                val confirm = confirmation.text?.toString() ?: ""
                when {
                    value.length < MIN_PASSWORD_LENGTH -> showError("Password must be at least $MIN_PASSWORD_LENGTH characters.")
                    value != confirm -> showError("Passwords do not match.")
                    else -> {
                        savePassword(value)
                        startHomeActivity()
                    }
                }
            }
        }
    }

    private fun handleSecondary() {
        if (mode == Mode.UNLOCK) {
            val current = password.text?.toString() ?: ""
            if (current.isBlank()) {
                showError("Enter your current password first.")
                return
            }
            setBusy(true)
            verifyPasswordAsync(current) { valid ->
                setBusy(false)
                if (valid) showChange() else {
                    password.setText("")
                    showError("Incorrect password. Try again.")
                }
            }
        } else if (mode == Mode.CHANGE) {
            persistentState.appLockPasswordHash = ""
            persistentState.appLockPasswordSalt = ""
            startHomeActivity()
        }
    }

    private fun setBusy(busy: Boolean) {
        primary.isEnabled = !busy
        secondary.isEnabled = !busy
        exit.isEnabled = !busy
        primary.text = if (busy) "Checking…" else when (mode) {
            Mode.SETUP -> "Create password"
            Mode.UNLOCK -> "Unlock"
            Mode.CHANGE -> "Save password"
        }
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun clearError() {
        error.text = ""
        error.visibility = View.GONE
    }

    private fun savePassword(value: String) {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        persistentState.appLockPasswordSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        persistentState.appLockPasswordHash = hashPassword(value, salt, HASH_ITERATIONS)
    }

    private fun verifyPasswordAsync(value: String, result: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.Default) {
            val valid = verifyPassword(value)
            withContext(Dispatchers.Main) { result(valid) }
        }
    }

    private fun verifyPassword(value: String): Boolean = try {
        val salt = Base64.decode(persistentState.appLockPasswordSalt, Base64.NO_WRAP)
        val expected = Base64.decode(persistentState.appLockPasswordHash, Base64.NO_WRAP)
        val current = Base64.decode(hashPassword(value, salt, HASH_ITERATIONS), Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, current)) true
        else {
            val legacy = Base64.decode(hashPassword(value, salt, LEGACY_HASH_ITERATIONS), Base64.NO_WRAP)
            MessageDigest.isEqual(expected, legacy)
        }
    } catch (_: Exception) {
        false
    }

    private fun hashPassword(value: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(value.toCharArray(), salt, iterations, HASH_LENGTH_BITS)
        return try {
            Base64.encodeToString(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                Base64.NO_WRAP
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun startHomeActivity() {
        startActivity(Intent(this, HomeScreenActivity::class.java).apply {
            putExtras(intent)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
