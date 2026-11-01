/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.utils

import androidx.annotation.WorkerThread
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.Config
import org.linphone.core.ChatMessage
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.FriendList
import org.linphone.core.RegistrationState
import org.linphone.core.tools.Log

/**
 * One phone, two servers (two employers, navrhy/DVA-SERVERY-JEDEN-TELEFON.md): a sign-in link from a second server adds
 * an account next to the first one instead of overwriting it. Remote provisioning cannot do that – the profile always
 * writes proxy_0 – so the app downloads the profile itself and builds the account through the API. The server's own
 * values (directory, uploads, support…) travel in the profile's securelink_account section and stay with the account.
 */
object SecureLinkAccounts {
    private const val TAG = "[SecureLink Accounts]"
    private const val SECTION = "securelink_account"
    private const val PARAM_PREFIX = "sl_"
    private val KEYS = listOf(
        "domain", "org_name", "dir_url", "upload_url", "admin_uri", "support_email", "privacy_url", "terms_url", "request_url",
        "my_name"
    )

    /** Debug builds only until the whole feature is done (navrhy/DVA-SERVERY-JEDEN-TELEFON.md). */
    val enabled: Boolean
        get() = BuildConfig.DEBUG

    /** Downloads the profile behind a sign-in link and adds (or, for the same server, replaces) its account. */
    fun addFromProvisioning(url: String, done: (Boolean) -> Unit) {
        Thread {
            val xml = try {
                download(url)
            } catch (e: Exception) {
                Log.e("$TAG Downloading the profile failed: $e")
                null
            }
            coreContext.postOnCoreThread { core ->
                val added = xml != null && addAccount(core, xml)
                done(added)
            }
        }.start()
    }

    /** The server value [key] of the account (securelink_account in its profile), "" when it has none. */
    @WorkerThread
    fun setting(account: Account?, key: String): String {
        return account?.params?.getCustomParam(PARAM_PREFIX + key).orEmpty()
    }

    /**
     * "Block and wipe" (v2, 1. 11. 2026): the server refused this account's sign-in. Ask the server (/wipe next to the
     * directory, with the account's own login – a blocked account may still ask there) whether the administrator wants
     * the phone wiped. Only an explicit "wipe" answer counts: a plain block, a new invitation or a server fault never
     * deletes anything. [done] gets true on the core thread when the account and its data are gone.
     */
    @WorkerThread
    fun wipeIfAsked(core: Core, account: Account, done: (Boolean) -> Unit) {
        val dir = setting(account, "dir_url").ifEmpty { core.config.getString("misc", "contacts-vcard-list", "").orEmpty() }
        val auth = account.findAuthInfo()
        if (!dir.endsWith("/dir") || auth == null) {
            done(false)
            return
        }
        val url = dir.removeSuffix("/dir") + "/wipe"
        val user = auth.username.orEmpty()
        val ha1 = if (auth.algorithm.orEmpty().uppercase() == "SHA-256") auth.ha1.orEmpty() else ""
        val password = auth.password.orEmpty()
        Thread {
            val answer = try {
                getWithDigest(url, user, ha1, password)?.trim()
            } catch (e: Exception) {
                Log.w("$TAG Wipe check failed: $e")
                null
            }
            coreContext.postOnCoreThread { c ->
                if (answer != "wipe" || c.accountList.none { it == account }) {
                    done(false)
                    return@postOnCoreThread
                }
                Log.w("$TAG The administrator blocked this phone to be wiped – deleting the account and its data")
                wipeAccount(c, account)
                done(true)
            }
        }.start()
    }

    /** The account, its conversations, call logs and directory go; the last account takes the rest along (as "sign out"). */
    @WorkerThread
    fun wipeAccount(core: Core, account: Account) {
        val directory = setting(account, "dir_url")
        val wasLast = core.accountList.size <= 1
        val auth = account.findAuthInfo()
        core.removeAccountWithData(account)
        // blocked: no unregister will come to take the credentials along
        if (auth != null) core.removeAuthInfo(auth)
        if (directory.isNotEmpty()) core.friendsLists.filter { it.uri == directory }.forEach { core.removeFriendList(it) }
        if (wasLast) {
            core.clearAllAuthInfo()
            SecureLinkAdmin.scheduleZrtpCacheWipe(core)
            core.config.cleanEntry("misc", "contacts-vcard-list")
            core.config.cleanEntry("misc", "fetch_contacts_vcard_list_at_startup")
            core.config.sync()
            for (list in core.friendsLists) {
                if (list.type == FriendList.Type.VCard4 || list.type == FriendList.Type.CardDAV) {
                    core.removeFriendList(list)
                } else {
                    for (friend in list.friends) list.removeFriend(friend)
                }
            }
        }
        coreContext.contactsManager.notifyContactsListChanged()
    }

    /**
     * A conversation whose record the chat server dropped (fewer metadata, securelink-metadata-cleanup) and the other
     * side started again: liblinphone keeps its subscription to that conversation's events as "terminated" and refuses
     * the message at once (NotDelivered) until the conference list is subscribed again – what entering the background
     * and the foreground does. So: resubscribe, then send the same message once more (as the robot, 1. 11. 2026).
     */
    @WorkerThread
    fun retryIfRefused(core: Core, message: ChatMessage) {
        if (message.state != ChatMessage.State.NotDelivered) return
        if (!message.chatRoom.hasCapability(ChatRoom.Capabilities.Encrypted.toInt())) return
        Log.w("$TAG Message refused at once – subscribing to the conversations again, then sending it once more")
        core.enterBackground()
        core.enterForeground()
        coreContext.postOnCoreThreadDelayed({ _ ->
            if (message.state == ChatMessage.State.NotDelivered) {
                Log.i("$TAG Sending the refused message once more")
                message.send()
            }
        }, 4000)
    }

    /**
     * The person's own name: the directory leaves everyone out of their own list, so the server sends it in the profile
     * (my_name, 1. 11. 2026); an account from remote provisioning finds it in the phone-wide section. "" when unknown.
     */
    @WorkerThread
    fun ownName(core: Core, account: Account): String {
        val identity = account.params.identityAddress ?: return ""
        coreContext.contactsManager.findContactByAddress(identity)?.name?.let { if (it.isNotEmpty()) return it }
        setting(account, "my_name").let { if (it.isNotEmpty()) return it }
        if (core.config.getString(SECTION, "domain", "") != identity.domain) return ""
        return core.config.getString(SECTION, "my_name", "").orEmpty()
    }

    /** The account keeps its server values itself (two servers): an empty one then means "this server has none". */
    @WorkerThread
    fun hasOwnValues(account: Account?): Boolean = setting(account, "domain").isNotEmpty()

    @WorkerThread
    private fun addAccount(core: Core, xml: String): Boolean {
        val profile = Factory.instance().createConfigFromString("")
        if (profile.loadFromXmlString(xml) != 0 || profile.hasSection("proxy_0") == 0) {
            Log.e("$TAG The sign-in link brought no account (expired or already used)")
            return false
        }
        val factory = Factory.instance()
        val identity = factory.createAddress(profile.getString("proxy_0", "reg_identity", "").orEmpty())
        val server = factory.createAddress(profile.getString("proxy_0", "reg_proxy", "").orEmpty())
        val username = profile.getString("auth_info_0", "username", null)
        val password = profile.getString("auth_info_0", "passwd", null)
        if (identity == null || server == null || username == null || password == null) {
            Log.e("$TAG The profile has an incomplete account")
            return false
        }
        val domain = identity.domain.orEmpty()

        // the same server again (a new invitation): the same person gets the new password in place (removed and added
        // again, the old account's unregister cleared the binding the new one had just made – the phone stayed offline);
        // another account of that server goes, the other server's accounts stay
        val same = core.accountList.find {
            it.params.identityAddress?.domain == domain && it.params.identityAddress?.username == username
        }
        var replacedDefault = false
        core.accountList.filter { it.params.identityAddress?.domain == domain && it != same }.forEach { old ->
            Log.w("$TAG Replacing the account [${old.params.identityAddress?.asStringUriOnly()}]")
            if (old == core.defaultAccount) replacedDefault = true
            // unregister first (removed at once, the server kept the old binding for a year and its push kept ringing
            // this phone), then its credentials and its data go too
            val notRegistered = old.state != RegistrationState.Ok && old.state != RegistrationState.Progress
            val auth = old.findAuthInfo()
            core.removeAccountWithData(old)
            if (notRegistered && auth != null) core.removeAuthInfo(auth) // no unregister will come to remove it
        }

        // the first account came by remote provisioning: its server values are in the phone-wide sections
        core.accountList.forEach { adoptPhoneWideValues(core, it) }

        val params = same?.params?.clone() ?: core.createAccountParams()
        params.identityAddress = identity
        params.serverAddress = server
        profile.getString("proxy_0", "reg_route", null)?.let { route ->
            factory.createAddress(route)?.let { params.setRoutesAddresses(arrayOf(it)) }
        }
        params.expires = profile.getInt("proxy_0", "reg_expires", 3600)
        params.isRegisterEnabled = true
        params.isPublishEnabled = false
        params.realm = profile.getString("proxy_0", "realm", domain)
        params.conferenceFactoryUri = profile.getString("proxy_0", "conference_factory_uri", null)
        params.limeServerUrl = profile.getString("proxy_0", "lime_server_url", null)
        params.limeAlgo = profile.getString("proxy_0", "lime_algo", null)
        params.instantMessagingEncryptionMandatory = true
        if (same == null) {
            // push as the first account has it (the app sets it up at the first sign-in)
            val first = core.defaultAccount?.params
            params.pushNotificationAllowed = first?.pushNotificationAllowed ?: core.isPushNotificationAvailable
            params.remotePushNotificationAllowed = first?.remotePushNotificationAllowed ?: false
        }
        copyServerValues(profile, params, domain)

        same?.findAuthInfo()?.let { core.removeAuthInfo(it) } // the old password goes
        val realm = profile.getString("auth_info_0", "realm", domain)
        core.addAuthInfo(
            factory.createAuthInfo(username, null, password, null, realm, domain, profile.getString("auth_info_0", "algorithm", "SHA-256"))
        )
        val account = if (same != null) {
            same.params = params
            core.refreshRegisters()
            Log.i("$TAG Signed in again as [${identity.asStringUriOnly()}] of [${setting(same, "org_name")}]")
            same
        } else {
            val added = core.createAccount(params)
            core.addAccount(added)
            Log.i("$TAG Added the account [${identity.asStringUriOnly()}] of [${setting(added, "org_name")}]")
            added
        }
        if (replacedDefault || core.defaultAccount == null) {
            // after CoreContext.onAccountRemoved, which picks another default a moment later
            coreContext.postOnCoreThread { it.defaultAccount = account }
        }

        refreshDirectories(core)
        return true
    }

    /** The account of the server [remote] belongs to (each server knows only its own people), else the default one. */
    @WorkerThread
    fun accountFor(core: Core, remote: Address?): Account? {
        return core.accountList.find { it.params.identityAddress?.domain == remote?.domain } ?: core.defaultAccount
    }

    /**
     * The account a group with [members] goes through, null when they belong to different companies: a group lives on
     * one server's conference service, and each server lets in only its own people.
     */
    @WorkerThread
    fun accountForGroup(core: Core, members: List<Address>): Account? {
        if (members.map { it.domain }.distinct().size > 1) return null
        return accountFor(core, members.firstOrNull())
    }

    /** The upload server is one for the whole core: before sending, point it at the server of [room]'s account. */
    @WorkerThread
    fun useUploadServerOf(core: Core, room: ChatRoom) {
        val url = setting(room.account, "upload_url")
        if (url.isNotEmpty() && core.fileTransferServer != url) {
            Log.i("$TAG Attachments now go to [$url]")
            core.fileTransferServer = url
        }
    }

    /** More than one server: directories are downloaded by the app (liblinphone cannot pick the right password). */
    @WorkerThread
    fun isMultiServer(core: Core): Boolean = core.accountList.size > 1

    /** Gives an account from remote provisioning its server values (securelink_account / [app] / [misc] of the phone). */
    @WorkerThread
    private fun adoptPhoneWideValues(core: Core, account: Account) {
        if (setting(account, "domain").isNotEmpty()) return
        val config = core.config
        val domain = account.params.identityAddress?.domain.orEmpty()
        val params = account.params.clone()
        if (config.getString(SECTION, "domain", "") == domain) {
            for (key in KEYS) params.addCustomParam(PARAM_PREFIX + key, config.getString(SECTION, key, "").orEmpty())
        } else { // a profile from before securelink_account (31. 10. 2026)
            params.addCustomParam(PARAM_PREFIX + "domain", domain)
            params.addCustomParam(PARAM_PREFIX + "org_name", domain)
            params.addCustomParam(PARAM_PREFIX + "dir_url", config.getString("misc", "contacts-vcard-list", "").orEmpty())
            params.addCustomParam(PARAM_PREFIX + "upload_url", core.fileTransferServer.orEmpty())
            for (key in listOf("admin_uri", "support_email", "privacy_url", "terms_url", "request_url")) {
                params.addCustomParam(PARAM_PREFIX + key, config.getString("app", key, "").orEmpty())
            }
        }
        account.params = params
        // the phone-wide directory list would keep failing with two passwords – the app takes it over
        config.cleanEntry("misc", "contacts-vcard-list")
        config.cleanEntry("misc", "fetch_contacts_vcard_list_at_startup")
        Log.i("$TAG The account [${account.params.identityAddress?.asStringUriOnly()}] keeps its server values itself")
    }

    /** Downloads every server's directory with that account's own login and puts it into the account's contact list. */
    @WorkerThread
    fun refreshDirectories(core: Core) {
        for (account in core.accountList) {
            val url = setting(account, "dir_url")
            val auth = account.findAuthInfo()
            if (url.isEmpty() || auth == null) continue
            val user = auth.username.orEmpty()
            val ha1 = if (auth.algorithm.orEmpty().uppercase() == "SHA-256") auth.ha1.orEmpty() else ""
            val password = auth.password.orEmpty()
            val name = setting(account, "org_name").ifEmpty { account.params.identityAddress?.domain.orEmpty() }
            Thread {
                val body = try {
                    getWithDigest(url, user, ha1, password)
                } catch (e: Exception) {
                    Log.w("$TAG Directory of [$name] failed: $e")
                    null
                }
                if (body != null) coreContext.postOnCoreThread { c -> fillDirectory(c, url, name, body) }
            }.start()
        }
    }

    @WorkerThread
    private fun fillDirectory(core: Core, url: String, name: String, vcards: String) {
        // a new list each time: the list liblinphone made from the first profile does not let its friends go one by one
        core.friendsLists.filter { it.uri == url }.forEach { core.removeFriendList(it) }
        val list = core.createFriendList()
        list.displayName = name
        list.uri = url
        list.type = FriendList.Type.VCard4
        core.addFriendList(list)
        val count = if (vcards.isBlank()) 0 else list.importFriendsFromVcard4Buffer(vcards)
        // two employers: the company goes with the name, so a contact, a call, a message and a notification all say it
        if (isMultiServer(core)) {
            for (friend in list.friends) {
                friend.edit()
                friend.name = "${friend.name} · $name"
                friend.done()
            }
        }
        Log.i("$TAG Directory of [$name]: $count people")
        core.friendsLists.forEach { Log.i("$TAG   list [${it.displayName}] ${it.type} [${it.uri}]: ${it.friends.size}") }
        coreContext.contactsManager.onDirectoryRefreshed(list)
    }

    private fun getWithDigest(link: String, user: String, storedHa1: String, password: String): String? {
        val url = URL(link)
        var answer = get(url, null)
        if (answer.first == 401 && answer.second != null) {
            val challenge = answer.second.orEmpty()
            val realm = Regex("realm=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1).orEmpty()
            val ha1 = storedHa1.ifEmpty { if (password.isEmpty()) "" else SecureLinkAdmin.sha256("$user:$realm:$password") }
            val cnonce = UUID.randomUUID().toString().replace("-", "")
            val header = SecureLinkAdmin.digestAuthorization(challenge, user, ha1, url.path, cnonce, "GET")
            if (header != null) answer = get(url, header)
        }
        if (answer.first != 200) Log.w("$TAG [$link] answered ${answer.first}")
        return if (answer.first == 200) answer.third else null
    }

    /** (HTTP status, WWW-Authenticate challenge, body) */
    private fun get(url: URL, authorization: String?): Triple<Int, String?, String> {
        val connection = url.openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection) connection.sslSocketFactory = tls.socketFactory
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "text/vcard")
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.bufferedReader().use { it.readText() } else ""
            return Triple(code, connection.getHeaderField("WWW-Authenticate"), body)
        } finally {
            connection.disconnect()
        }
    }

    @WorkerThread
    private fun copyServerValues(profile: Config, params: org.linphone.core.AccountParams, domain: String) {
        if (profile.hasSection(SECTION) != 0) {
            for (key in KEYS) {
                params.addCustomParam(PARAM_PREFIX + key, profile.getString(SECTION, key, "").orEmpty())
            }
            return
        }
        // a server from before securelink_account (31. 10. 2026): its values are in the profile's own [app] / [misc] –
        // never leave them empty, the first company's phone-wide values would stand in for them
        params.addCustomParam(PARAM_PREFIX + "domain", domain)
        params.addCustomParam(PARAM_PREFIX + "org_name", domain)
        params.addCustomParam(PARAM_PREFIX + "dir_url", profile.getString("misc", "contacts-vcard-list", "").orEmpty())
        params.addCustomParam(PARAM_PREFIX + "upload_url", profile.getString("misc", "file_transfer_server_url", "").orEmpty())
        for (key in listOf("admin_uri", "support_email", "privacy_url", "terms_url", "request_url")) {
            params.addCustomParam(PARAM_PREFIX + key, profile.getString("app", key, "").orEmpty())
        }
    }

    // our CA (as liblinphone with sip/root_ca) and the system ones (install pages, Let's Encrypt)
    private val tls: SSLContext by lazy {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        File(corePreferences.rootCaPath).inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).forEachIndexed { i, cert ->
                trust.setCertificateEntry("securelink-$i", cert)
            }
        }
        KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }.let { system ->
            for (alias in system.aliases()) trust.setCertificateEntry(alias, system.getCertificate(alias))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    private fun download(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection) connection.sslSocketFactory = tls.socketFactory // the lab VM: plain http
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            val code = connection.responseCode
            if (code != 200) {
                Log.e("$TAG The server answered $code for the profile")
                return null
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
