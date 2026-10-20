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

    var locked = true
        private set

    private var startedActivities = 0
    private var backgroundSince = 0L
    private var lastTouch = SystemClock.elapsedRealtime()
    private var authenticating = false

    fun activityStarted() {
        if (startedActivities == 0 && backgroundSince > 0L) {
            val away = SystemClock.elapsedRealtime() - backgroundSince
            if (away >= GRACE_MS) locked = true
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
        return !locked && SystemClock.elapsedRealtime() - lastTouch >= IDLE_MS
    }

    fun lockNow() {
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
        val noScreenLock = !deviceIsSecure(activity)
        view.findViewWithTag<View>("warning").visibility = if (noScreenLock) View.VISIBLE else View.GONE
        view.findViewWithTag<View>("continue").visibility = if (noScreenLock) View.VISIBLE else View.GONE
        view.findViewWithTag<View>("unlock").visibility = if (failed && !noScreenLock) View.VISIBLE else View.GONE
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
            ImageView(activity).apply { setImageResource(R.mipmap.ic_launcher_round) },
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
}
