/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.settings.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.AccountDevice
import org.linphone.core.AccountManagerServices
import org.linphone.core.AccountManagerServicesRequest
import org.linphone.core.AccountManagerServicesRequestListenerStub
import org.linphone.core.Address
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.DialPlan
import org.linphone.core.Dictionary
import org.linphone.core.Factory
import org.linphone.core.FriendList
import org.linphone.core.RegistrationState
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.ui.main.model.AccountModel
import org.linphone.ui.main.model.isEndToEndEncryptionMandatory
import org.linphone.ui.main.settings.model.AccountDeviceModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.SecureLinkAccounts
import org.linphone.utils.SecureLinkAdmin

class AccountProfileViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Account Profile ViewModel]"
    }

    val accountModel = MutableLiveData<AccountModel>()

    val sipAddress = MutableLiveData<String>()

    val displayName = MutableLiveData<String>()

    val dialPlansLabelList = arrayListOf<String>()

    val dialPlansList = arrayListOf<DialPlan>()

    val selectedDialPlan = MutableLiveData<Int>()

    val registerEnabled = MutableLiveData<Boolean>()

    val isCurrentlySelectedModeSecure = MutableLiveData<Boolean>()

    val devices = MutableLiveData<ArrayList<AccountDeviceModel>>()

    val accountFoundEvent = MutableLiveData<Event<Boolean>>()

    val expandDetails = MutableLiveData<Boolean>()

    val expandDevices = MutableLiveData<Boolean>()

    val isOnDefaultDomain = MutableLiveData<Boolean>()

    val emptyDevices = MediatorLiveData<Boolean>()

    val devicesFetchInProgress = MutableLiveData<Boolean>()

    val hideAccountSettings = MutableLiveData<Boolean>()

    val hideSipAddresses = MutableLiveData<Boolean>()

    val deviceId = MutableLiveData<String>()

    val showDeviceId = MutableLiveData<Boolean>()

    val accountRemovedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // SecureLink: "Request account deletion" – empty until asked, then the date it was asked (as iOS)
    val deletionRequestedOn = MutableLiveData<String>()

    val deletionRequestedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    private lateinit var account: Account

    private lateinit var accountManagerServices: AccountManagerServices

    private val accountManagerServicesListener = object : AccountManagerServicesRequestListenerStub() {
        @WorkerThread
        override fun onDevicesListFetched(
            request: AccountManagerServicesRequest,
            accountDevices: Array<out AccountDevice>
        ) {
            Log.i("$TAG Fetched [${accountDevices.size}] devices for our account")
            val devicesList = arrayListOf<AccountDeviceModel>()
            for (accountDevice in accountDevices) {
                devicesList.add(
                    AccountDeviceModel(accountDevice) { model, device ->
                        if (::accountManagerServices.isInitialized) {
                            val identityAddress = account.params.identityAddress
                            if (identityAddress != null) {
                                Log.i(
                                    "$TAG Removing device with name [${device.name}] and uuid [${device.uuid}]"
                                )
                                val deleteRequest = accountManagerServices.createDeleteDeviceRequest(
                                    identityAddress,
                                    device
                                )
                                deleteRequest.addListener(this)
                                deleteRequest.submit()

                                val newList = arrayListOf<AccountDeviceModel>()
                                newList.addAll(devices.value.orEmpty())
                                newList.remove(model)
                                devices.postValue(newList)
                            } else {
                                Log.e("$TAG Account identity address is null, can't delete device!")
                            }
                        }
                    }
                )
            }
            devices.postValue(devicesList)
            devicesFetchInProgress.postValue(false)
        }

        override fun onRequestSuccessful(request: AccountManagerServicesRequest, data: String?) {
            if (request.type == AccountManagerServicesRequest.Type.DeleteDevice) {
                Log.i("$TAG Device successfully deleted: $data")
            }
        }

        @WorkerThread
        override fun onRequestError(
            request: AccountManagerServicesRequest,
            statusCode: Int,
            errorMessage: String?,
            parameterErrors: Dictionary?
        ) {
            Log.e(
                "$TAG Request [${request.type}] returned an error with status code [$statusCode] and message [$errorMessage]"
            )
            if (!errorMessage.isNullOrEmpty()) {
                when (request.type) {
                    AccountManagerServicesRequest.Type.GetDevicesList, AccountManagerServicesRequest.Type.DeleteDevice -> {
                        showFormattedRedToast(errorMessage, R.drawable.warning_circle)
                        devicesFetchInProgress.postValue(false)
                    }
                    else -> {}
                }
            }
        }
    }

    init {
        expandDetails.value = true
        expandDevices.value = false
        showDeviceId.value = false
        devicesFetchInProgress.value = true
        isOnDefaultDomain.value = false

        emptyDevices.value = true
        emptyDevices.addSource(devices) { list ->
            emptyDevices.value = list.orEmpty().isEmpty()
        }

        coreContext.postOnCoreThread { core ->
            hideAccountSettings.postValue(corePreferences.hideAccountSettings)
            hideSipAddresses.postValue(corePreferences.hideSipAddresses)
            dialPlansLabelList.add("") // To allow removing selected dial plan

            val dialPlans = Factory.instance().dialPlans.toList()
            for (dialPlan in dialPlans) {
                dialPlansList.add(dialPlan)
                dialPlansLabelList.add(
                    "${dialPlan.flag} ${dialPlan.country} | +${dialPlan.countryCallingCode}"
                )
            }
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread {
            accountModel.value?.destroy()
        }
    }

    @UiThread
    fun findAccountMatchingIdentity(identity: String) {
        coreContext.postOnCoreThread { core ->
            val found = core.accountList.find {
                it.params.identityAddress?.asStringUriOnly() == identity
            }
            if (found != null) {
                Log.i("$TAG Found matching account [$found]")
                account = found
                // SecureLink: per account – only once the account is known (init runs before this and crashed on it)
                refreshDeletionRequested(core)
                accountModel.postValue(AccountModel(account))
                isCurrentlySelectedModeSecure.postValue(isEndToEndEncryptionMandatory())
                registerEnabled.postValue(account.params.isRegisterEnabled)

                sipAddress.postValue(account.params.identityAddress?.asStringUriOnly())
                // SecureLink: the name others see comes from the administrator's directory (read-only here)
                val identity = account.params.identityAddress
                displayName.postValue(
                    identity?.let { coreContext.contactsManager.findContactByAddress(it)?.name }
                        ?: identity?.displayName ?: identity?.username
                )
                showDeviceId.postValue(false)

                val identityAddress = account.params.identityAddress
                if (identityAddress != null) {
                    val domain = identityAddress.domain
                    val defaultDomain = corePreferences.defaultDomain
                    isOnDefaultDomain.postValue(domain == defaultDomain)
                    if (domain == defaultDomain) {
                        requestDevicesList(identityAddress)
                    } else {
                        Log.i(
                            "$TAG Account with domain [$domain] can't get devices list, only works with [$defaultDomain] domain"
                        )
                    }
                } else {
                    Log.e("$TAG No identity address found!")
                }

                val prefix = account.params.internationalPrefix
                val isoCountryCode = account.params.internationalPrefixIsoCountryCode
                if (!prefix.isNullOrEmpty() || !isoCountryCode.isNullOrEmpty()) {
                    Log.i(
                        "$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] prefix is [$prefix]($isoCountryCode)"
                    )
                    val dialPlan = Factory.instance().dialPlans.find {
                        it.isoCountryCode == isoCountryCode
                    } ?: Factory.instance().dialPlans.find {
                        it.countryCallingCode == prefix
                    }
                    if (dialPlan != null) {
                        val index = dialPlansList.indexOf(dialPlan) + 1
                        Log.i(
                            "$TAG Found matching dial plan [${dialPlan.country}] at index [$index]"
                        )
                        selectedDialPlan.postValue(index)
                    }
                }
                deviceId.postValue(account.contactAddress?.getUriParam("gr"))

                accountFoundEvent.postValue(Event(true))
            } else {
                accountFoundEvent.postValue(Event(false))
            }
        }
    }

    @UiThread
    fun deleteAccount() {
        coreContext.postOnCoreThread { core ->
            if (::account.isInitialized) {
                val identity = account.params.identityAddress?.asStringUriOnly()
                Log.w("$TAG Removing account [$identity] and all related data (auth info, conferences, conversations, call logs)")
                val wasLastAccount = core.accountList.size <= 1 // the removal may complete later (unregister first)
                val directory = SecureLinkAccounts.setting(account, "dir_url")
                core.removeAccountWithData(account)
                if (!wasLastAccount && directory.isNotEmpty()) {
                    // SecureLink, two servers: signing out of one company takes its people along, the other stays
                    core.friendsLists.filter { it.uri == directory }.forEach { core.removeFriendList(it) }
                    coreContext.contactsManager.notifyContactsListChanged()
                }
                if (wasLastAccount) {
                    // SecureLink "sign out = delete everything" (as iOS): no credentials, no directory address and no
                    // directory people may survive – a new QR must not list the previous account's contacts.
                    // The credentials go only once the unregister is done: removed at once, the unregister got no
                    // answer to the server's challenge and the server kept the phone as signed in (as iOS c2abdd7c8).
                    clearAuthInfoAfterUnregister(core)
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
                    coreContext.contactsManager.notifyContactsListChanged()
                }
                accountRemovedEvent.postValue(Event(true))

                if (core.accountList.isEmpty()) {
                    Log.w("$TAG No more account found in Core")
                    if (!core.provisioningUri.isNullOrEmpty()) {
                        Log.w("$TAG Removing remote provisioning URI")
                        core.provisioningUri = null
                    }
                }
            }
        }
    }

    @WorkerThread
    private fun refreshDeletionRequested(core: Core) {
        val at = SecureLinkAdmin.deletionRequestedAt(core, account)
        deletionRequestedOn.postValue(
            if (at > 0L) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at * 1000)) else ""
        )
    }

    @WorkerThread
    private fun clearAuthInfoAfterUnregister(core: Core) {
        var done = false
        val clear = {
            if (!done) {
                done = true
                core.clearAllAuthInfo()
                Log.i("$TAG SecureLink: credentials removed after sign-out")
            }
        }
        val listener = object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(
                core: Core,
                account: Account,
                state: RegistrationState?,
                message: String
            ) {
                if (state == RegistrationState.Cleared || state == RegistrationState.Failed) {
                    core.removeListener(this)
                    clear()
                }
            }
        }
        core.addListener(listener)
        // never wait for ever: no network, or the server does not answer
        coreContext.postOnCoreThreadDelayed({ c ->
            c.removeListener(listener)
            clear()
        }, 10_000L)
    }

    @UiThread
    fun requestDeletion() {
        coreContext.postOnCoreThread { core ->
            SecureLinkAdmin.requestDeletion(core, account) { sent ->
                if (sent) {
                    refreshDeletionRequested(core)
                    // the request itself is on the server; the administrator also learns about it on the phone
                    SecureLinkAdmin.openAdminConversation(
                        core,
                        AppUtils.getString(R.string.securelink_deletion_notice_to_admin),
                        { },
                        { Log.e("$TAG Deletion notice to the administrator could not be sent") },
                        sendNow = true,
                        account = account
                    )
                }
                deletionRequestedEvent.postValue(Event(sent))
            }
        }
    }

    @UiThread
    fun setNewPicturePath(path: String) {
        coreContext.postOnCoreThread {
            if (::account.isInitialized) {
                val params = account.params
                val copy = params.clone()

                if (path.isNotEmpty() && path != params.pictureUri) {
                    Log.i("$TAG New account profile picture [$path]")
                    copy.pictureUri = path
                } else {
                    Log.i("$TAG Account profile picture removed")
                    copy.pictureUri = null
                }

                // Create a new model to force UI to update
                val newModel = AccountModel(account)
                newModel.picturePath.postValue(path)
                accountModel.postValue(newModel)

                // Also update friend & contact avatar model for ourselves
                val model = coreContext.contactsManager.getContactAvatarModelForAddress(
                    params.identityAddress
                )
                model.friend.photo = path
                model.picturePath.postValue(path)

                account.params = copy
                account.refreshRegister()
            }
        }
    }

    @UiThread
    fun saveChangesWhenLeaving() {
        coreContext.postOnCoreThread {
            if (::account.isInitialized) {
                val params = account.params
                val copy = params.clone()

                val address = params.identityAddress?.clone()
                if (address != null) {
                    val newValue = displayName.value.orEmpty().trim()
                    address.displayName = newValue
                    copy.identityAddress = address
                    // This will trigger a REGISTER, so account display name will be updated by
                    // CoreListener.onAccountRegistrationStateChanged everywhere in the app
                    Log.i(
                        "$TAG Updated account [${params.identityAddress?.asStringUriOnly()}] identity address display name [$newValue]"
                    )
                }

                account.params = copy
                account.refreshRegister()
            }
        }
    }

    @UiThread
    fun toggleDetailsExpand() {
        expandDetails.value = expandDetails.value == false
    }

    @UiThread
    fun toggleDevicesExpand() {
        expandDevices.value = expandDevices.value == false
    }

    @UiThread
    fun toggleRegister() {
        coreContext.postOnCoreThread { core ->
            val params = account.params
            val copy = params.clone()
            copy.isRegisterEnabled = !params.isRegisterEnabled
            Log.i(
                "$TAG Account registration is now [${if (copy.isRegisterEnabled) "enabled" else "disabled"}] for account [${account.params.identityAddress?.asStringUriOnly()}]"
            )
            account.params = copy
            registerEnabled.postValue(account.params.isRegisterEnabled)

            if (!core.isNetworkReachable) {
                Log.w("$TAG Network is not reachable, updating registration state to reflect that")
                // To reflect the difference between Disabled & Disconnected
                accountModel.value?.updateRegistrationState()
            }
        }
    }

    @UiThread
    fun setDialPlan(dialPlan: DialPlan) {
        coreContext.postOnCoreThread {
            val params = account.params
            val copy = params.clone()
            copy.internationalPrefix = dialPlan.countryCallingCode
            copy.internationalPrefixIsoCountryCode = dialPlan.isoCountryCode
            account.params = copy
            Log.i(
                "$TAG Updated international prefix for account [${account.params.identityAddress?.asStringUriOnly()}] to [${copy.internationalPrefix} (${copy.internationalPrefixIsoCountryCode})]"
            )
        }
    }

    @UiThread
    fun removeDialPlan() {
        coreContext.postOnCoreThread {
            val params = account.params
            val copy = params.clone()
            copy.internationalPrefix = ""
            copy.internationalPrefixIsoCountryCode = ""
            account.params = copy
            Log.i(
                "$TAG Removed international prefix for account [${account.params.identityAddress?.asStringUriOnly()}]"
            )
        }
    }

    @UiThread
    fun showDebugInfo(): Boolean {
        showDeviceId.value = true
        return true
    }

    @WorkerThread
    private fun requestDevicesList(identityAddress: Address) {
        Log.i(
            "$TAG Request devices list for identity address [${identityAddress.asStringUriOnly()}]"
        )
        accountManagerServices = coreContext.core.createAccountManagerServices()
        accountManagerServices.language = Locale.getDefault().language // Returns en, fr, etc...
        val request = accountManagerServices.createGetDevicesListRequest(
            identityAddress
        )
        request.addListener(accountManagerServicesListener)
        request.submit()
    }
}
