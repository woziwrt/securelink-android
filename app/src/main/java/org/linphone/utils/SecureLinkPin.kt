/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Core
import org.linphone.core.tools.Log

/**
 * SecureLink PIN lock (optional, Petr 2. 11. 2026, as iOS): the user's own PIN instead of the phone's screen lock, and an
 * emergency PIN that quietly erases everything and leaves an empty app. Only salted PBKDF2-SHA256 hashes are kept, in
 * preferences encrypted with a key in the Android Keystore – never the PINs.
 */
object SecureLinkPin {
    private const val TAG = "[SecureLink PIN]"
    private const val PREFS = "securelink_pin"
    private const val ROUNDS = 200_000

    enum class Result { NORMAL, EMERGENCY, WRONG }

    private fun prefs(context: Context): SharedPreferences? = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e("$TAG Encrypted preferences unavailable: $e")
        null
    }

    fun isEnabled(context: Context = coreContext.context): Boolean = prefs(context)?.getString("normal", null) != null

    fun valid(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun set(context: Context, normal: String, emergency: String): Boolean {
        if (normal == emergency || !valid(normal) || !valid(emergency)) return false
        val p = prefs(context) ?: return false
        return p.edit().putString("normal", hash(normal)).putString("emergency", hash(emergency)).commit()
    }

    fun disable(context: Context) {
        prefs(context)?.edit(commit = true) { remove("normal"); remove("emergency") }
    }

    fun check(context: Context, pin: String): Result {
        val p = prefs(context) ?: return Result.WRONG
        if (matches(pin, p.getString("normal", null))) return Result.NORMAL
        if (matches(pin, p.getString("emergency", null))) return Result.EMERGENCY
        return Result.WRONG
    }

    private fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(salt + derive(pin, salt), Base64.NO_WRAP)
    }

    private fun matches(pin: String, stored: String?): Boolean {
        val raw = stored?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        if (raw.size != 48) return false
        return MessageDigest.isEqual(derive(pin, raw.copyOfRange(0, 16)), raw.copyOfRange(16, 48))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

/**
 * Emergency PIN: every account goes with its data (conversations, calls, directory, LIME identity), then the app's data
 * files (databases, logs, cache, downloads, the user configuration) are deleted, the database key destroyed and the PIN
 * switched off – the app looks freshly installed. What stays: the resources the SDK unpacks (share/) and our CA.
 */
object EmergencyWipe {
    private const val TAG = "[Emergency Wipe]"
    private val DATA = listOf(
        Regex("""linphone\.db.*"""), Regex("""friends\.db.*"""), Regex("""call-history\.db.*"""),
        Regex("""x3dh.*\.sqlite3.*"""), Regex("""zrtp-secrets.*"""), Regex(""".*\.log""")
    )

    @WorkerThread
    fun run(core: Core, context: Context) {
        for (account in core.accountList.toList()) SecureLinkAccounts.wipeAccount(core, account)
        core.clearAllAuthInfo()
        var removed = 0
        context.filesDir.listFiles()?.forEach { f ->
            if (f.isFile && DATA.any { it.matches(f.name) } && f.delete()) removed++
        }
        listOfNotNull(context.cacheDir, context.getExternalFilesDir(null), File(context.filesDir, "Downloads"))
            .forEach { dir -> dir.listFiles()?.forEach { if (it.deleteRecursively()) removed++ } }
        for (section in listOf("securelink_account", SecureLinkAccounts.NAMES_SECTION, "app")) core.config.cleanSection(section)
        core.config.sync()
        SecureLinkPin.disable(context)
        // what may be left of the (encrypted) databases on the flash memory becomes unreadable
        org.linphone.core.VFS.destroyKey(context)
        // the user configuration is encrypted with that key too: without it the next start cannot read it and crashes
        // (emulator 2. 11. 2026) – it goes as well and is created again from the defaults, as after installation. The
        // caller then restarts the process so that the running core cannot write it back with the old key
        context.filesDir.listFiles()?.forEach { if (it.name.startsWith(".linphonerc") && it.delete()) removed++ }
        // the app's own settings too (Panel 2: flags and names stayed behind) – the PIN block state included
        for (name in listOf("securelink", "org.linphone.ui.main.MainActivity")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        }
        Log.w("$TAG Done: accounts removed, $removed data files deleted, database key destroyed")
    }
}

/** Turning the SecureLink PIN lock on (normal PIN twice, emergency PIN twice) or off (normal PIN) – two plain dialogs */
object SecureLinkPinSetup {
    fun show(activity: android.app.Activity, done: () -> Unit) {
        val ctx = activity

        fun field(hint: Int) = android.widget.EditText(ctx).apply {
            setHint(hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }

        fun column(vararg views: android.view.View) = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            views.forEach { addView(it) }
        }

        fun toast(text: Int) = android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()

        if (SecureLinkPin.isEnabled(ctx)) {
            val pin = field(org.linphone.R.string.securelink_pin_enter)
            android.app.AlertDialog.Builder(ctx)
                .setTitle(org.linphone.R.string.securelink_pin_off_title)
                .setView(column(pin))
                .setPositiveButton(org.linphone.R.string.securelink_pin_off_button) { _, _ ->
                    // the normal PIN only – the emergency PIN here is refused (no wipe from Settings)
                    if (SecureLinkPin.check(ctx, pin.text.toString()) == SecureLinkPin.Result.NORMAL) {
                        SecureLinkPin.disable(ctx)
                        done()
                    } else {
                        toast(org.linphone.R.string.securelink_pin_wrong)
                    }
                }
                .setNegativeButton(org.linphone.R.string.dialog_cancel, null)
                .show()
            return
        }
        val n1 = field(org.linphone.R.string.securelink_pin_new)
        val n2 = field(org.linphone.R.string.securelink_pin_new_again)
        val e1 = field(org.linphone.R.string.securelink_pin_emergency)
        val e2 = field(org.linphone.R.string.securelink_pin_emergency_again)
        val text = android.widget.TextView(ctx).apply { setText(org.linphone.R.string.securelink_pin_setup_text) }
        android.app.AlertDialog.Builder(ctx)
            .setTitle(org.linphone.R.string.securelink_pin_setup_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(column(text, n1, n2, e1, e2)) })
            .setPositiveButton(org.linphone.R.string.securelink_pin_setup_start) { _, _ ->
                val a = n1.text.toString(); val b = n2.text.toString(); val c = e1.text.toString(); val d = e2.text.toString()
                when {
                    !SecureLinkPin.valid(a) || !SecureLinkPin.valid(c) -> toast(org.linphone.R.string.securelink_pin_rules)
                    a != b || c != d -> toast(org.linphone.R.string.securelink_pin_mismatch)
                    a == c -> toast(org.linphone.R.string.securelink_pin_same)
                    SecureLinkPin.set(ctx, a, c) -> done()
                    else -> toast(org.linphone.R.string.securelink_pin_rules)
                }
            }
            .setNegativeButton(org.linphone.R.string.dialog_cancel, null)
            .show()
    }
}
