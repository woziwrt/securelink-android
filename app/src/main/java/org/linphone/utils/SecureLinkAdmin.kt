/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.utils

import androidx.annotation.WorkerThread
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Address
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

    /** Report of a contact: name and user@domain, then the reason */
    fun reportContactDraft(name: String, address: Address): String {
        val plain = blockKey(address)?.removePrefix("sip:") ?: address.asStringUriOnly()
        return coreContext.context.getString(R.string.securelink_report_contact_draft, name.ifEmpty { plain }, plain)
    }

    /**
     * Opens (or creates) the encrypted conversation with the administrator and leaves [draft] for its message field.
     * [onReady] gets the conversation id once the conversation exists (an encrypted one has to be joined first).
     */
    @WorkerThread
    fun openAdminConversation(
        core: Core,
        draft: String,
        onReady: (String) -> Unit,
        onError: () -> Unit,
        sendNow: Boolean = false
    ) {
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
            if (sendNow) {
                room.createMessageFromUtf8(draft).send()
                Log.i("$TAG Message sent to the administrator")
            } else {
                pendingDraft = Pair(id, draft)
            }
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

    // --- blocking on this phone (App Review 1.2, as iOS): no calls ring, messages are dropped, the one-to-one
    // conversation leaves the list at once, and the administrator gets an automatic report
    private const val BLOCKED_KEY = "blocked_uris"

    @WorkerThread
    fun blockedList(core: Core): List<String> {
        return core.config.getString("securelink", BLOCKED_KEY, "").orEmpty().split(",").filter { it.isNotEmpty() }
    }

    /** "sip:user@domain" – whatever the device (;gr=) or scheme */
    fun blockKey(address: Address?): String? {
        val user = address?.username ?: return null
        val domain = address.domain ?: return null
        return "sip:$user@$domain"
    }

    @WorkerThread
    fun isBlocked(core: Core, address: Address?): Boolean {
        val key = blockKey(address) ?: return false
        return blockedList(core).contains(key)
    }

    /** a one-to-one conversation with a contact blocked on this phone: not shown in the list */
    @WorkerThread
    fun isBlockedRoom(core: Core, room: ChatRoom): Boolean {
        if (!room.hasCapability(ChatRoom.Capabilities.OneToOne.toInt()) || room.participants.size != 1) return false
        return isBlocked(core, room.participants.first().address)
    }

    /** an incoming message from a contact blocked on this phone */
    @WorkerThread
    fun isBlockedMessage(core: Core, message: ChatMessage): Boolean {
        return !message.isOutgoing && isBlocked(core, message.fromAddress)
    }

    /** Calling or writing to a contact blocked on this phone is refused with a toast – unblock first (as iOS) */
    @WorkerThread
    fun refuseIfBlocked(core: Core, address: Address?): Boolean {
        if (!isBlocked(core, address)) return false
        Log.i("$TAG Refusing to call or write to a contact blocked on this phone")
        coreContext.showRedToastEvent.postValue(
            Event(Pair(R.string.securelink_contact_is_blocked_toast, R.drawable.warning_circle))
        )
        return true
    }

    @WorkerThread
    fun setBlocked(core: Core, address: Address, blocked: Boolean, name: String) {
        val key = blockKey(address) ?: return
        val list = blockedList(core).filter { it != key }.toMutableList()
        if (blocked) list.add(key)
        core.config.setString("securelink", BLOCKED_KEY, list.joinToString(","))
        for (room in core.chatRooms) {
            if (room.participants.size == 1 && room.participants.first().address.weakEqual(address)) {
                room.muted = blocked
                if (blocked) room.markAsRead() // its unread messages would keep a badge the user can no longer clear
            }
        }
        Log.i("$TAG $key ${if (blocked) "blocked" else "unblocked"} on this phone")
        // the report goes to the administrator – unless the administrator is the one blocked, or the one blocking
        val adminKey = blockKey(Factory.instance().createAddress(setting(core, "admin_uri")))
        val ownKey = blockKey(core.defaultAccount?.params?.identityAddress)
        if (blocked && key != adminKey && ownKey != adminKey) {
            val draft = coreContext.context.getString(R.string.securelink_block_report_draft, name, key.removePrefix("sip:"))
            openAdminConversation(core, draft, { }, { Log.e("$TAG Block report could not be sent") }, sendNow = true)
        }
    }

    // --- account deletion (App Review 5.1.1(v), as iOS): one tap sends a signed request to the server ([app] request_url,
    // HTTP digest SHA-256 with the SIP account – the same check as attachments); the administrator deletes within 7 days

    private fun sha256(text: String): String {
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** RFC 7616 digest (SHA-256, qop=auth) answering [challenge] – the server is /opt/securelink/www/sl-auth.php */
    fun digestAuthorization(challenge: String, user: String, ha1: String, uri: String, cnonce: String): String? {
        val fields = Regex("(\\w+)=\"?([^\",]*)\"?").findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = fields["realm"].orEmpty()
        val nonce = fields["nonce"].orEmpty()
        val opaque = fields["opaque"].orEmpty()
        if (ha1.isEmpty() || nonce.isEmpty()) return null
        val nc = "00000001"
        val response = sha256("$ha1:$nonce:$nc:$cnonce:auth:" + sha256("POST:$uri"))
        return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", " +
            "algorithm=SHA-256, qop=auth, nc=$nc, cnonce=\"$cnonce\", response=\"$response\", opaque=\"$opaque\""
    }

    @WorkerThread
    fun requestDeletion(core: Core, done: (Boolean) -> Unit) {
        val link = setting(core, "request_url")
        val auth = core.defaultAccount?.findAuthInfo()
        val user = auth?.username.orEmpty()
        if (link.isEmpty() || auth == null || user.isEmpty()) {
            Log.e("$TAG Deletion request: no request_url or no credentials")
            done(false)
            return
        }
        val storedHa1 = if (auth.algorithm.orEmpty().uppercase() == "SHA-256") auth.ha1.orEmpty() else ""
        val password = auth.password.orEmpty()
        Thread {
            val sent = try {
                val url = URL(link)
                var answer = postDeletion(url, null)
                if (answer.first == 401 && answer.second != null) {
                    val challenge = answer.second.orEmpty()
                    val realm = Regex("realm=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1).orEmpty()
                    val ha1 = storedHa1.ifEmpty { if (password.isEmpty()) "" else sha256("$user:$realm:$password") }
                    val cnonce = UUID.randomUUID().toString().replace("-", "")
                    val header = digestAuthorization(challenge, user, ha1, url.path, cnonce)
                    if (header != null) answer = postDeletion(url, header)
                }
                Log.i("$TAG Deletion request answered [${answer.first}]")
                answer.first == 200
            } catch (e: Exception) {
                Log.e("$TAG Deletion request failed: $e")
                false
            }
            coreContext.postOnCoreThread { c ->
                if (sent) {
                    Log.i("$TAG Account deletion requested")
                    c.config.setInt("securelink", "deletion_requested_$user", (System.currentTimeMillis() / 1000).toInt())
                } else {
                    Log.e("$TAG Deletion request was not accepted")
                }
                done(sent)
            }
        }.start()
    }

    /** (HTTP status, WWW-Authenticate challenge) */
    private fun postDeletion(url: URL, authorization: String?): Pair<Int, String?> {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            connection.outputStream.use { it.write("type=delete".toByteArray()) }
            return Pair(connection.responseCode, connection.getHeaderField("WWW-Authenticate"))
        } finally {
            connection.disconnect()
        }
    }

    /** When this account asked for its deletion (seconds since 1970), 0 = never – the button then says so (as iOS) */
    @WorkerThread
    fun deletionRequestedAt(core: Core): Long {
        val user = core.defaultAccount?.findAuthInfo()?.username.orEmpty()
        if (user.isEmpty()) return 0L
        return core.config.getInt("securelink", "deletion_requested_$user", 0).toLong()
    }

    /** At every start: conversations others started (or older ones) get the 7 days too. */
    @WorkerThread
    fun enforceDisappearing(core: Core) {
        for (room in core.chatRooms) {
            enforceDisappearing(core, room)
        }
    }
}
