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
package org.linphone.ui.main.help.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.HelpFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.main.fragment.GenericMainFragment
import org.linphone.ui.main.help.viewmodel.HelpViewModel
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.utils.DialogUtils
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider

@UiThread
class HelpFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[Help Fragment]"
    }

    private lateinit var binding: HelpFragmentBinding

    private lateinit var viewModel: HelpViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = HelpFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[HelpViewModel::class.java]
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        binding.setBackClickListener {
            goBack()
        }

        binding.setDebugClickListener {
            if (findNavController().currentDestination?.id == R.id.helpFragment) {
                val action = HelpFragmentDirections.actionHelpFragmentToDebugFragment()
                findNavController().navigate(action)
            }
        }

        // SecureLink (as iOS): no Belledonne links – the "user guide" row is our terms of use, the "translate" row writes
        // to support; privacy, terms and support address come from the server profile
        binding.setUserGuideClickListener {
            pickCircle("terms_url", viewModel.termsUrl.value.orEmpty()) { openUrlInBrowser(it) }
        }

        binding.setPrivacyPolicyClickListener {
            pickCircle("privacy_url", viewModel.privacyUrl.value.orEmpty()) { openUrlInBrowser(it) }
        }

        binding.setLicensesClickListener {
            // GPLv3: the source of this app is public
            openUrlInBrowser(getString(R.string.securelink_source_url))
        }

        binding.setTranslateClickListener {
            pickCircle("support_email", viewModel.supportEmail.value.orEmpty()) { email ->
                if (email.isNotEmpty()) {
                    try {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email?subject=SecureLink")))
                    } catch (e: Exception) {
                        Log.e("$TAG No e-mail app: $e")
                    }
                }
            }
        }

        // SecureLink: connection check – lines with ✓ / ✗ the user can copy and send to the administrator
        binding.setConnectionCheckClickListener {
            (requireActivity() as GenericActivity).showGreenToast(getString(R.string.securelink_check_running), R.drawable.info)
            viewModel.runConnectionCheck()
        }
        viewModel.connectionCheckEvent.observe(viewLifecycleOwner) {
            it.consume { text ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.securelink_check_dialog_title)
                    .setMessage(text + "\n\n" + getString(R.string.securelink_check_hint))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.securelink_check_copy) { _, _ ->
                        val clip = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                        clip?.setPrimaryClip(android.content.ClipData.newPlainText("SecureLink", text))
                    }
                    .show()
            }
        }

        viewModel.newVersionAvailableEvent.observe(viewLifecycleOwner) {
            it.consume { pair ->
                val version = pair.first
                val url = pair.second
                showUpdateAvailableDialog(version, url)
            }
        }

        viewModel.versionUpToDateEvent.observe(viewLifecycleOwner) {
            it.consume {
                (requireActivity() as GenericActivity).showGreenToast(
                    getString(R.string.help_version_up_to_date_toast_message),
                    R.drawable.info
                )
            }
        }

        viewModel.errorEvent.observe(viewLifecycleOwner) {
            it.consume {
                (requireActivity() as GenericActivity).showRedToast(
                    getString(R.string.help_error_checking_version_toast_message),
                    R.drawable.warning_circle
                )
            }
        }
    }

    private fun showUpdateAvailableDialog(version: String, url: String) {
        val message = getString(R.string.help_dialog_update_available_message, version)

        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getUpdateAvailableDialog(
            requireActivity(),
            model,
            message
        )

        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {
                openUrlInBrowser(url)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /** SecureLink: two circles with different values → ask which one; otherwise straight to the only (or default) one */
    private fun pickCircle(key: String, fallback: String, open: (String) -> Unit) {
        val choices = viewModel.perCircle[key].orEmpty()
        if (choices.size <= 1) {
            open(choices.firstOrNull()?.second ?: fallback)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.securelink_pick_circle)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> open(choices[which].second) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openUrlInBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(browserIntent)
        } catch (ise: IllegalStateException) {
            Log.e(
                "$TAG Can't start ACTION_VIEW intent for URL [$url], IllegalStateException: $ise"
            )
        } catch (anfe: ActivityNotFoundException) {
            Log.e(
                "$TAG Can't start ACTION_VIEW intent for URL [$url], ActivityNotFoundException: $anfe"
            )
        } catch (e: Exception) {
            Log.e(
                "$TAG Can't start ACTION_VIEW intent for URL [$url]: $e"
            )
        }
    }
}
