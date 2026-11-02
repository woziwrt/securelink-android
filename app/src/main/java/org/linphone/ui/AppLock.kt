/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.utils.EmergencyWipe
import org.linphone.utils.SecureLinkPin
import org.linphone.LinphoneApplication.Companion.coreContext
import android.content.Intent
import android.widget.GridLayout

/**
 * SecureLink app lock (as iOS AppLock in LinphoneApp.swift): the phone's own fingerprint / face / PIN, no PIN of our
 * own to forget. Locked at start, after 30 s in the background and after 5 min without a touch on screen. Never over
 * a call. A phone without a screen lock gets a warning on every opening and may continue unprotected – refusing to
 * open would lock out anyone without a screen lock.
 */
@MainThread
object AppLock {
    private const val TAG = "[App Lock]"
    private const val GRACE_MS = 30_000L
    const val IDLE_MS = 300_000L

    // SecureLink: debug builds (installed by cable for testing) never lock – release builds always do; BuildConfig.DEBUG
    // is false in every build that leaves the Mac, so this cannot be forgotten (Petr 2. 11. 2026)
    private val enabled = !org.linphone.BuildConfig.DEBUG

    // the SecureLink PIN (optional, chosen by the user) locks also in debug builds
    private val active: Boolean
        get() = enabled || SecureLinkPin.isEnabled()

    var locked = active
        private set

    private var wrongPins = 0
    private var pinBlockedUntil = 0L

    private var startedActivities = 0
    private var backgroundSince = 0L
    private var lastTouch = SystemClock.elapsedRealtime()
    private var authenticating = false

    fun activityStarted() {
        if (startedActivities == 0 && backgroundSince > 0L) {
            val away = SystemClock.elapsedRealtime() - backgroundSince
            if (away >= GRACE_MS && active) locked = true
            Log.i("$TAG Back in the foreground after [${away / 1000}] s, locked=$locked")
            backgroundSince = 0L
        }
        startedActivities += 1
    }

    fun activityStopped() {
        startedActivities -= 1
        if (startedActivities <= 0) {
            startedActivities = 0
            backgroundSince = SystemClock.elapsedRealtime()
        }
    }

    fun touched() {
        lastTouch = SystemClock.elapsedRealtime()
    }

    fun idleExpired(): Boolean {
        return active && !locked && SystemClock.elapsedRealtime() - lastTouch >= IDLE_MS
    }

    fun lockNow() {
        if (!active) return
        Log.i("$TAG No touch for [${IDLE_MS / 1000}] s – locking")
        locked = true
    }

    private fun unlock() {
        locked = false
        touched()
    }

    // ---- the lock screen: a view above the activity's whole window, so nothing the app shows can sit on top of it

    private const val LOCK_VIEW_TAG = "securelink_app_lock"

    fun isShown(activity: GenericActivity): Boolean {
        return (activity.window.decorView as ViewGroup).findViewWithTag<View>(LOCK_VIEW_TAG) != null
    }

    fun show(activity: GenericActivity) {
        if (!active) return
        val decor = activity.window.decorView as ViewGroup
        if (decor.findViewWithTag<View>(LOCK_VIEW_TAG) != null) return
        Log.i("$TAG Showing the lock screen")
        val lockView = createLockView(activity)
        decor.addView(
            lockView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // "back" while locked leaves the app instead of navigating the screens underneath
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                activity.moveTaskToBack(true)
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        lockView.setTag(R.id.securelink_app_lock_back, back)
        // screen readers must not read the conversations under the lock either
        activity.findViewById<View>(android.R.id.content)?.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        authenticate(activity)
    }

    fun hide(activity: GenericActivity) {
        val decor = activity.window.decorView as ViewGroup
        decor.findViewWithTag<View>(LOCK_VIEW_TAG)?.let {
            (it.getTag(R.id.securelink_app_lock_back) as? OnBackPressedCallback)?.remove()
            decor.removeView(it)
            activity.findViewById<View>(android.R.id.content)?.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun deviceIsSecure(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true
    }

    fun authenticate(activity: GenericActivity) {
        if (SecureLinkPin.isEnabled(activity)) {
            refresh(activity, failed = false)   // the PIN pad is the lock
            return
        }
        if (authenticating) return
        if (!deviceIsSecure(activity)) {
            Log.w("$TAG The phone has no screen lock – warning shown")
            refresh(activity, failed = false)
            return
        }
        authenticating = true
        val done = { ok: Boolean ->
            authenticating = false
            if (ok) {
                Log.i("$TAG Unlocked")
                unlock()
                hide(activity)
            } else {
                refresh(activity, failed = true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.securelink_lock_prompt_title))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        done(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        Log.w("$TAG Authentication ended: [$errorCode] $errString")
                        done(false)
                    }
                }
            )
        } else {
            activity.confirmDeviceCredential { ok -> done(ok) }
        }
    }

    /** Continue on a phone without a screen lock (the warning comes back at every opening) */
    private fun continueWithoutScreenLock(activity: GenericActivity) {
        Log.w("$TAG Opened without a screen lock on the phone")
        unlock()
        hide(activity)
    }

    // ---- views

    private fun refresh(activity: GenericActivity, failed: Boolean) {
        val view = (activity.window.decorView as ViewGroup).findViewWithTag<ViewGroup>(LOCK_VIEW_TAG) ?: return
        val pin = SecureLinkPin.isEnabled(activity)
        val noScreenLock = !pin && !deviceIsSecure(activity)
        view.findViewWithTag<View>("warning").visibility = if (noScreenLock) View.VISIBLE else View.GONE
        view.findViewWithTag<View>("continue").visibility = if (noScreenLock) View.VISIBLE else View.GONE
        view.findViewWithTag<View>("unlock").visibility = if (failed && !noScreenLock && !pin) View.VISIBLE else View.GONE
        view.findViewWithTag<View>("pinpad").visibility = if (pin) View.VISIBLE else View.GONE
    }

    private fun createLockView(activity: GenericActivity): View {
        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
        }
        val blue = ContextCompat.getColor(activity, R.color.securelink_main_500)

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), 0, dp(30), 0)
        }
        column.addView(
            ImageView(activity).apply { setImageResource(R.drawable.securelink_white) }, // white logo, as iOS
            LinearLayout.LayoutParams(dp(90), dp(90))
        )
        column.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.app_name)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, 0)
            }
        )
        column.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.securelink_lock_locked)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
        )
        column.addView(
            TextView(activity).apply {
                tag = "warning"
                visibility = View.GONE
                text = activity.getString(R.string.securelink_lock_no_screen_lock)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            }
        )
        val button = { buttonTag: String, label: Int, onClick: () -> Unit ->
            Button(activity).apply {
                tag = buttonTag
                visibility = View.GONE
                text = activity.getString(label)
                isAllCaps = false
                setTextColor(blue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                setOnClickListener { onClick() }
            }
        }
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) }
        column.addView(
            button("continue", R.string.securelink_lock_continue) {
            continueWithoutScreenLock(activity)
        },
            buttonParams
        )
        column.addView(button("unlock", R.string.securelink_lock_unlock) { authenticate(activity) }, buttonParams)
        column.addView(createPinPad(activity, dp, blue))

        return FrameLayout(activity).apply {
            tag = LOCK_VIEW_TAG
            setBackgroundColor(blue)
            isClickable = true // nothing below can be touched
            isFocusable = true
            elevation = 1000f
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }

    // ---- SecureLink PIN pad (Petr 2. 11. 2026, as iOS)

    private fun createPinPad(activity: GenericActivity, dp: (Int) -> Int, blue: Int): View {
        val pad = LinearLayout(activity).apply {
            tag = "pinpad"
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        val entered = StringBuilder()
        val dots = TextView(activity).apply {
            text = activity.getString(R.string.securelink_pin_enter)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }
        val error = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        val show = {
            dots.text = if (entered.isEmpty()) activity.getString(R.string.securelink_pin_enter) else "●".repeat(entered.length)
        }
        pad.addView(dots)
        pad.addView(error)
        val grid = GridLayout(activity).apply { columnCount = 3; setPadding(0, dp(10), 0, 0) }
        for (key in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")) {
            grid.addView(
                Button(activity).apply {
                    text = key
                    isAllCaps = false
                    setTextColor(blue)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                    setOnClickListener {
                        when (key) {
                            "⌫" -> if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
                            "OK" -> if (entered.isNotEmpty()) {
                                val pin = entered.toString()
                                entered.setLength(0)
                                error.text = submitPin(activity, pin)
                            }
                            else -> if (entered.length < 8) entered.append(key)
                        }
                        show()
                    }
                },
                GridLayout.LayoutParams().apply { width = dp(84); height = dp(64); setMargins(dp(6), dp(6), dp(6), dp(6)) }
            )
        }
        pad.addView(grid)
        return pad
    }

    /** The normal PIN opens the app, the emergency PIN erases everything and opens an empty app; returns an error text */
    private fun submitPin(activity: GenericActivity, pin: String): String {
        if (SystemClock.elapsedRealtime() < pinBlockedUntil) return activity.getString(R.string.securelink_pin_wrong)
        return when (SecureLinkPin.check(activity, pin)) {
            SecureLinkPin.Result.NORMAL -> {
                wrongPins = 0
                unlock()
                hide(activity)
                ""
            }
            SecureLinkPin.Result.EMERGENCY -> {
                Log.w("$TAG Emergency PIN")
                val ctx = activity.applicationContext
                coreContext.postOnCoreThread { core ->
                    EmergencyWipe.run(core, ctx)
                    // a new process, as after installation: the running core still holds the old configuration and
                    // database key and would write them back – the app comes up empty, without accounts
                    val restart = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    if (restart != null) {
                        ctx.startActivity(Intent.makeRestartActivityTask(restart.component))
                    }
                    Runtime.getRuntime().exit(0)
                }
                ""
            }
            SecureLinkPin.Result.WRONG -> {
                wrongPins++
                if (wrongPins >= 5) {
                    pinBlockedUntil = SystemClock.elapsedRealtime() + 60_000
                    wrongPins = 0
                }
                activity.getString(R.string.securelink_pin_wrong)
            }
        }
    }
}
