package com.celzero.bravedns.ui.activity

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

class AppLockActivity : BaseActivity(R.layout.activity_app_lock) {
    private val persistentState by inject<PersistentState>()

    companion object {
        const val APP_LOCK_ALIAS = ".ui.activity.LauncherAliasAppLock"
        const val HOME_ALIAS = ".ui.LauncherAliasHome"
        private const val HASH_ITERATIONS = 120_000
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
        if (persistentState.appLockPasswordHash.isBlank()) showPasswordSetup() else showPasswordPrompt()
    }

    override fun onBackPressed() {
        finishAffinity()
    }

    private fun showPasswordSetup() {
        val first = passwordField("Create password")
        val confirm = passwordField("Confirm password")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(first)
            addView(confirm)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Protect Rethink")
            .setMessage("Create a password to open this app. Use at least $MIN_PASSWORD_LENGTH characters.")
            .setView(box).setCancelable(false)
            .setPositiveButton("Save", null)
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = first.text?.toString() ?: ""
                val confirmation = confirm.text?.toString() ?: ""
                when {
                    password.length < MIN_PASSWORD_LENGTH -> first.error = "Password is too short"
                    password != confirmation -> confirm.error = "Passwords do not match"
                    else -> { savePassword(password); dialog.dismiss(); startHomeActivity() }
                }
            }
            first.requestFocus()
        }
        dialog.show()
    }

    private fun showPasswordPrompt() {
        val field = passwordField("Password")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rethink is locked")
            .setMessage("Enter your password to continue.")
            .setView(box).setCancelable(false)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = field.text?.toString() ?: ""
                if (verifyPassword(password)) {
                    dialog.dismiss()
                    startHomeActivity()
                } else {
                    field.text?.clear()
                    field.error = "Incorrect password"
                }
            }
            field.requestFocus()
        }
        dialog.show()
    }

    private fun passwordField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun savePassword(password: String) {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        persistentState.appLockPasswordSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        persistentState.appLockPasswordHash = hashPassword(password, salt)
    }

    private fun verifyPassword(password: String): Boolean = try {
        val salt = Base64.decode(persistentState.appLockPasswordSalt, Base64.NO_WRAP)
        val expected = Base64.decode(persistentState.appLockPasswordHash, Base64.NO_WRAP)
        val actual = Base64.decode(hashPassword(password, salt), Base64.NO_WRAP)
        MessageDigest.isEqual(expected, actual)
    } catch (_: Exception) { false }

    private fun hashPassword(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, HASH_LENGTH_BITS)
        return try {
            Base64.encodeToString(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                Base64.NO_WRAP
            )
        } finally { spec.clearPassword() }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
