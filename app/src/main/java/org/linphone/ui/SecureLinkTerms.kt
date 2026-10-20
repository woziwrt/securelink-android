/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.tools.Log

/**
 * SecureLink terms (as iOS SecureLinkTermsView): adults only (18+), no objectionable content, reports answered within
 * 24 h – accepted once after the first login (App Review 1.2). Shown below the app lock.
 */
@MainThread
object SecureLinkTerms {
    private const val TAG = "[SecureLink Terms]"
    private const val VIEW_TAG = "securelink_terms"

    private fun accepted(): Boolean = corePreferences.config.getBool("securelink", "terms_accepted", false)

    fun showIfNeeded(activity: GenericActivity, termsUrl: String) {
        val decor = activity.window.decorView as ViewGroup
        if (accepted() || decor.findViewWithTag<View>(VIEW_TAG) != null) return
        Log.i("$TAG Showing the terms of use")
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                activity.moveTaskToBack(true)
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        decor.addView(
            createView(activity, termsUrl) {
                corePreferences.config.setBool("securelink", "terms_accepted", true)
                corePreferences.config.sync()
                Log.i("$TAG Terms of use accepted")
                back.remove()
                decor.findViewWithTag<View>(VIEW_TAG)?.let { decor.removeView(it) }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun createView(activity: GenericActivity, termsUrl: String, onAccept: () -> Unit): View {
        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
        }
        val blue = ContextCompat.getColor(activity, R.color.securelink_main_500)
        val text = Color.parseColor("#22272E")

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        column.addView(
            TextView(activity).apply {
                setText(R.string.securelink_terms_title)
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        column.addView(
            TextView(activity).apply {
                setText(R.string.securelink_terms_text)
                setTextColor(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(20), 0, 0)
            }
        )
        if (termsUrl.isNotEmpty()) {
            column.addView(
                TextView(activity).apply {
                    setText(R.string.securelink_terms_read_full)
                    setTextColor(blue)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setPadding(0, dp(20), 0, 0)
                    setOnClickListener {
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(termsUrl)))
                        } catch (e: Exception) {
                            Log.e("$TAG Cannot open [$termsUrl]: $e")
                        }
                    }
                }
            )
        }
        column.addView(
            Button(activity).apply {
                setText(R.string.securelink_terms_accept)
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                backgroundTintList = ColorStateList.valueOf(blue)
                setOnClickListener { onAccept() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            }
        )

        val scroll = ScrollView(activity).apply { addView(column) }
        return FrameLayout(activity).apply {
            tag = VIEW_TAG
            setBackgroundColor(Color.WHITE)
            isClickable = true // nothing below can be touched
            isFocusable = true
            elevation = 500f // below the app lock (1000)
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }
}
