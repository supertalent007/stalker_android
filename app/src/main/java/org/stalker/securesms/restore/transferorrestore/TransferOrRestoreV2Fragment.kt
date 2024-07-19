/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.restore.transferorrestore

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import org.signal.core.util.logging.Log
import org.stalker.securesms.LoggingFragment
import org.stalker.securesms.R
import org.stalker.securesms.components.ViewBinderDelegate
import org.stalker.securesms.databinding.FragmentTransferRestoreV2Binding
import org.stalker.securesms.devicetransfer.newdevice.BackupRestorationType
import org.stalker.securesms.registration.fragments.RegistrationViewDelegate
import org.stalker.securesms.restore.RestoreViewModel
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.SpanUtil
import org.stalker.securesms.util.navigation.safeNavigate
import org.stalker.securesms.util.visible

/**
 * This presents a list of options for the user to restore (or skip) a backup.
 */
class TransferOrRestoreV2Fragment : LoggingFragment(R.layout.fragment_transfer_restore_v2) {
  private val sharedViewModel by activityViewModels<RestoreViewModel>()
  private val binding: FragmentTransferRestoreV2Binding by ViewBinderDelegate(FragmentTransferRestoreV2Binding::bind)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    RegistrationViewDelegate.setDebugLogSubmitMultiTapView(binding.transferOrRestoreTitle)
    binding.transferOrRestoreFragmentTransfer.setOnClickListener { sharedViewModel.onTransferFromAndroidDeviceSelected() }
    binding.transferOrRestoreFragmentRestore.setOnClickListener { sharedViewModel.onRestoreFromLocalBackupSelected() }
    binding.transferOrRestoreFragmentRestoreRemote.setOnClickListener { sharedViewModel.onRestoreFromRemoteBackupSelected() }
    binding.transferOrRestoreFragmentNext.setOnClickListener { launchSelection(sharedViewModel.getBackupRestorationType()) }
    binding.transferOrRestoreFragmentMoreOptions.setOnClickListener {
      Log.w(TAG, "Not yet implemented!", NotImplementedError()) // TODO [regv2]
    }

    binding.transferOrRestoreFragmentRestoreRemoteCard.visible = FeatureFlags.messageBackups()
    binding.transferOrRestoreFragmentMoreOptions.visible = FeatureFlags.messageBackups()

    val description = getString(R.string.TransferOrRestoreFragment__transfer_your_account_and_messages_from_your_old_android_device)
    val toBold = getString(R.string.TransferOrRestoreFragment__you_need_access_to_your_old_device)

    binding.transferOrRestoreFragmentTransferDescription.text = SpanUtil.boldSubstring(description, toBold)

    sharedViewModel.uiState.observe(viewLifecycleOwner) { state ->
      updateSelection(state.restorationType)
    }

    // TODO [regv2]: port backup file detection to here
  }

  private fun updateSelection(restorationType: BackupRestorationType) {
    binding.transferOrRestoreFragmentTransferCard.isSelected = restorationType == BackupRestorationType.DEVICE_TRANSFER
    binding.transferOrRestoreFragmentRestoreCard.isSelected = restorationType == BackupRestorationType.LOCAL_BACKUP
    binding.transferOrRestoreFragmentRestoreRemoteCard.isSelected = restorationType == BackupRestorationType.REMOTE_BACKUP
  }

  private fun launchSelection(restorationType: BackupRestorationType) {
    when (restorationType) {
      BackupRestorationType.DEVICE_TRANSFER -> {
        NavHostFragment.findNavController(this).safeNavigate(TransferOrRestoreV2FragmentDirections.actionNewDeviceTransferInstructions())
      }
      BackupRestorationType.LOCAL_BACKUP -> {
        NavHostFragment.findNavController(this).safeNavigate(TransferOrRestoreV2FragmentDirections.actionTransferOrRestoreToRestore())
      }
      BackupRestorationType.REMOTE_BACKUP -> {
        // TODO [regv2]
        Log.w(TAG, "Not yet implemented!", NotImplementedError())
        Toast.makeText(requireContext(), "Not yet implemented!", Toast.LENGTH_LONG).show()
      }
      else -> {
        throw IllegalArgumentException()
      }
    }
  }

  companion object {
    private val TAG = Log.tag(TransferOrRestoreV2Fragment::class.java)
  }
}
