/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.utils

import androidx.annotation.WorkerThread
import java.text.DateFormat
import java.util.Date
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.Conference
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

    // the report text waiting for the administrator's conversation to open (conversation id → text), handed over once
    @Volatile
    private var pendingDraft: Pair<String, String>? = null

    fun takeDraft(conversationId: String): String? {
        val draft = pendingDraft ?: return null
        if (draft.first != conversationId) return null
        pendingDraft = null
        return draft.second
    }

    /** Report of one received message: sender, time, the text as the reporter sees it, then the reason. */
    @WorkerThread
    fun reportMessageDraft(core: Core, message: ChatMessage): String {
        val from = message.fromAddress
        // user@domain only – no "sip:", no device parameters such as ;gr=… (as iOS plainAddress)
        val address = from.asStringUriOnly().removePrefix("sips:").removePrefix("sip:").substringBefore(";").substringBefore(">")
        val name = core.findFriend(from)?.name?.takeIf { it.isNotEmpty() } ?: address
        val sent = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.time * 1000))
        return coreContext.context.getString(R.string.securelink_report_message_draft, name, address, sent, message.utf8Text.orEmpty())
    }

    /**
     * Opens (or creates) the encrypted conversation with the administrator and leaves [draft] for its message field.
     * [onReady] gets the conversation id once the conversation exists (an encrypted one has to be joined first).
     */
    @WorkerThread
    fun openAdminConversation(core: Core, draft: String, onReady: (String) -> Unit, onError: () -> Unit) {
        val uri = setting(core, "admin_uri")
        val admin = if (uri.isEmpty()) null else Factory.instance().createAddress(uri)
        val account = core.defaultAccount
        if (admin == null || account == null) {
            Log.e("$TAG No administrator address in the profile – cannot open the conversation")
            onError()
            return
        }
        val params = core.createConferenceParams(null)
        params.isChatEnabled = true
        params.isGroupEnabled = false
        params.subject = AppUtils.getString(R.string.conversation_one_to_one_hidden_subject)
        params.account = account
        params.securityLevel = Conference.SecurityLevel.EndToEnd
        val chatParams = params.chatParams
        if (chatParams == null) {
            onError()
            return
        }
        chatParams.backend = ChatRoom.Backend.FlexisipChat
        chatParams.ephemeralLifetime = 0 // reports stay as a record

        val ready = { room: ChatRoom ->
            val id = LinphoneUtils.getConversationId(room)
            pendingDraft = Pair(id, draft)
            onReady(id)
        }
        val participants = arrayOf(admin)
        val existing = core.searchChatRoom(params, account.params.identityAddress, null, participants)
        if (existing != null) {
            ready(existing)
            return
        }
        val room = core.createChatRoom(params, participants)
        if (room == null) {
            Log.e("$TAG Cannot create the administrator conversation")
            onError()
            return
        }
        if (room.state == ChatRoom.State.Created) {
            ready(room)
            return
        }
        room.addListener(object : ChatRoomListenerStub() {
            override fun onStateChanged(chatRoom: ChatRoom, newState: ChatRoom.State?) {
                if (newState == ChatRoom.State.Created) {
                    chatRoom.removeListener(this)
                    ready(chatRoom)
                } else if (newState == ChatRoom.State.CreationFailed) {
                    chatRoom.removeListener(this)
                    Log.e("$TAG Administrator conversation could not be created")
                    onError()
                }
            }
        })
    }

    /** At every start: conversations others started (or older ones) get the 7 days too. */
    @WorkerThread
    fun enforceDisappearing(core: Core) {
        for (room in core.chatRooms) {
            enforceDisappearing(core, room)
        }
    }
}
