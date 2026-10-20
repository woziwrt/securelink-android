/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.utils

import androidx.annotation.WorkerThread
import org.linphone.core.ChatRoom
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.tools.Log

/**
 * SecureLink rules that the server cannot switch off (same as the iOS app, SecureLinkAdmin in LinphoneUtils.swift).
 */
object SecureLinkAdmin {
    private const val TAG = "[SecureLink]"

    /** v1 agreement: messages disappear 7 days after they are read; a user may shorten it, never switch it off. */
    const val DISAPPEAR_AFTER_SECONDS = 7L * 24 * 3600

    @WorkerThread
    fun setting(core: Core, key: String): String {
        return core.config.getString("app", key, "").orEmpty()
    }

    /** The administrator's conversation keeps reports and requests as a record – nothing disappears there. */
    @WorkerThread
    fun isAdminRoom(core: Core, room: ChatRoom): Boolean {
        val uri = setting(core, "admin_uri")
        if (uri.isEmpty()) return false
        val admin = Factory.instance().createAddress(uri) ?: return false
        return room.participants.any { it.address.weakEqual(admin) }
    }

    /** The lifetime a conversation may have: 0 (off) only for the administrator, otherwise 1 s … 7 days. */
    @WorkerThread
    fun allowedLifetime(core: Core, room: ChatRoom, wanted: Long): Long {
        if (isAdminRoom(core, room)) return 0L
        if (wanted <= 0L || wanted > DISAPPEAR_AFTER_SECONDS) return DISAPPEAR_AFTER_SECONDS
        return wanted
    }

    @WorkerThread
    fun enforceDisappearing(core: Core, room: ChatRoom) {
        if (isAdminRoom(core, room)) {
            if (room.isEphemeralEnabled) {
                room.isEphemeralEnabled = false
                Log.i("$TAG Disappearing messages switched off in an administrator conversation")
            }
            return
        }
        val current = if (room.isEphemeralEnabled) room.ephemeralLifetime else 0L
        if (current <= 0L || current > DISAPPEAR_AFTER_SECONDS) {
            room.ephemeralLifetime = DISAPPEAR_AFTER_SECONDS
            room.isEphemeralEnabled = true
            Log.i("$TAG Disappearing messages (7 days) switched on in a conversation")
        }
    }

    /**
     * Messages are end-to-end encrypted or not sent at all: with "encryption mandatory" every screen creates an
     * encrypted (LIME) conversation or none, never the plain "Basic" one linphone falls back to when LIME is missing.
     */
    @WorkerThread
    fun enforceEncryptedMessaging(core: Core) {
        for (account in core.accountList) {
            if (!account.params.instantMessagingEncryptionMandatory) {
                val params = account.params.clone()
                params.instantMessagingEncryptionMandatory = true
                account.params = params
                Log.i("$TAG Encrypted messaging made mandatory for [${params.identityAddress?.asStringUriOnly()}]")
            }
        }
    }

    /** At every start: conversations others started (or older ones) get the 7 days too. */
    @WorkerThread
    fun enforceDisappearing(core: Core) {
        for (room in core.chatRooms) {
            enforceDisappearing(core, room)
        }
    }
}
