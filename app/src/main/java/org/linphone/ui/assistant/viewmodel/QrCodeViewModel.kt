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
package org.linphone.ui.assistant.viewmodel

import android.content.Intent
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.ConfiguringState
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event
import org.linphone.R
import org.linphone.core.GlobalState
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.SecureLinkAccounts
import org.linphone.utils.SecureLinkAdmin

class QrCodeViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Qr Code Scanner ViewModel]"
    }

    val remoteProvisioningSuccessfulEvent = MutableLiveData<Event<Boolean>>()

    val onErrorEvent = MutableLiveData<Event<Boolean>>()

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onConfiguringStatus(core: Core, status: ConfiguringState, message: String?) {
            Log.i("$TAG Configuring state is [$status]")
            if (status == ConfiguringState.Failed) {
                Log.e("$TAG Failure applying remote provisioning: $message")
                showRedToast(R.string.remote_provisioning_config_failed_toast, R.drawable.warning_circle)
                onErrorEvent.postValue(Event(true))
            }
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (state == GlobalState.On) {
                if (core.accountList.isEmpty()) {
                    Log.w("$TAG Provisioning was successful but no account has been configured yet, staying in assistant")
                    // Remote provisioning didn't contain any account
                    // and there wasn't at least one configured before either
                    remoteProvisioningSuccessfulEvent.postValue(Event(false))
                } else {
                    Log.i("$TAG At least an account exists in Core, leaving assistant")
                    remoteProvisioningSuccessfulEvent.postValue(Event(true))
                }
            }
        }

        @WorkerThread
        override fun onQrcodeFound(core: Core, result: String?) {
            Log.i("$TAG QR Code found: [$result]")
            if (result == null) {
                showRedToast(R.string.assistant_qr_code_invalid_toast, R.drawable.warning_circle)
            } else {
                // SecureLink: a personal invitation (https://<install host>/i/<token>, asks for the code sent by SMS) and
                // the review page (/r/<code>) are web pages, not logins – open them in the browser (as iOS)
                val page = result.toUri()
                if (LinphoneUtils.isAllowedProvisioning(result) &&
                    (page.path.orEmpty().startsWith("/i/") || page.path.orEmpty().startsWith("/r/"))
                ) {
                    Log.i("$TAG QR code is an invitation or review page, opening it in the browser")
                    coreContext.postOnMainThread {
                        try {
                            coreContext.context.startActivity(
                                Intent(Intent.ACTION_VIEW, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            Log.e("$TAG No browser: $e")
                        }
                    }
                    return
                }
                val url = LinphoneUtils.getRemoteProvisioningUrlFromUri(result)
                if (url == null) {
                    Log.e("$TAG The content of the QR Code [$result] doesn't seem to be a valid web URL")
                    showRedToast(R.string.assistant_qr_code_invalid_toast, R.drawable.warning_circle)
                    return
                }

                // SecureLink: already signed in – the QR code of a second server adds its account like the sign-in link,
                // remote provisioning would overwrite the first account (proxy_0)
                if (SecureLinkAccounts.enabled && core.accountList.isNotEmpty()) {
                    Log.i("$TAG Already signed in, adding the QR code's account next to the others")
                    core.isQrcodeVideoPreviewEnabled = false
                    SecureLinkAccounts.addFromProvisioning(url) { added ->
                        if (added) {
                            coreContext.provisioningAppliedEvent.postValue(Event(true))
                            showGreenToast(R.string.remote_provisioning_config_applied_toast, R.drawable.smiley)
                            remoteProvisioningSuccessfulEvent.postValue(Event(true))
                        } else {
                            showRedToast(R.string.remote_provisioning_config_failed_toast, R.drawable.warning_circle)
                        }
                    }
                    return
                }

                Log.i(
                    "$TAG Setting QR code URL [$url], restarting the Core outside of iterate() loop to apply configuration changes"
                )
                core.nativePreviewWindowId = null
                core.isVideoPreviewEnabled = false
                core.isQrcodeVideoPreviewEnabled = false
                core.provisioningUri = url

                coreContext.postOnCoreThread { core ->
                    Log.i("$TAG Stopping Core")
                    core.stop()
                    SecureLinkAdmin.wipeZrtpCacheIfScheduled(core.config) // SecureLink: new account, no inherited trust
                    Log.i("$TAG Core has been stopped, restarting it")
                    core.start()
                    Log.i("$TAG Core has been restarted")
                }
            }
        }
    }

    init {
        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)

            val coreGlobalState = core.globalState
            if (coreGlobalState != GlobalState.On) {
                Log.e("$TAG Core isn't ON (current state is [$coreGlobalState]), video preview won't work!")
            }
        }
    }

    @UiThread
    override fun onCleared() {
        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
        }
        super.onCleared()
    }

    @UiThread
    fun setBackCamera() {
        coreContext.postOnCoreThread { core ->
            // Just in case, on some devices such as Xiaomi Redmi Note 5
            // this is required right after granting the CAMERA permission
            Log.i("$TAG Reloading video devices, might be needed if CAMERA permission was granted very recently")
            core.reloadVideoDevices()

            if (!coreContext.setBackCamera()) {
                for (camera in core.videoDevicesList) {
                    if (camera != "StaticImage: Static picture") {
                        Log.w("$TAG No back facing camera found, using first one available [$camera]")
                        coreContext.core.videoDevice = camera
                        return@postOnCoreThread
                    }
                }

                Log.e("$TAG No camera device found!")
            }
        }
    }
}
