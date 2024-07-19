/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.registration.v2.ui.grantpermissions

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.navArgs
import org.signal.core.util.logging.Log
import org.stalker.securesms.compose.ComposeFragment
import org.stalker.securesms.registration.compose.GrantPermissionsScreen
import org.stalker.securesms.registration.fragments.WelcomePermissions
import org.stalker.securesms.registration.v2.ui.RegistrationCheckpoint
import org.stalker.securesms.registration.v2.ui.RegistrationV2ViewModel
import org.stalker.securesms.restore.RestoreActivity
import org.stalker.securesms.util.BackupUtil
import org.stalker.securesms.util.navigation.safeNavigate

/**
 * Screen in account registration that provides rationales for the suggested runtime permissions.
 */
@RequiresApi(23)
class GrantPermissionsV2Fragment : ComposeFragment() {

  private val sharedViewModel by activityViewModels<RegistrationV2ViewModel>()
  private val args by navArgs<GrantPermissionsV2FragmentArgs>()
  private val isSearchingForBackup = mutableStateOf(false)

  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
    ::onPermissionsGranted
  )

  private val launchRestoreActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
    when (val resultCode = result.resultCode) {
      Activity.RESULT_OK -> {
        sharedViewModel.onBackupSuccessfullyRestored()
        NavHostFragment.findNavController(this).safeNavigate(GrantPermissionsV2FragmentDirections.actionEnterPhoneNumber())
      }
      Activity.RESULT_CANCELED -> Log.w(TAG, "Backup restoration canceled.")
      else -> Log.w(TAG, "Backup restoration activity ended with unknown result code: $resultCode")
    }
  }

  private lateinit var welcomeAction: WelcomeAction

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    welcomeAction = args.welcomeAction
  }

  @Composable
  override fun FragmentContent() {
    val isSearchingForBackup by this.isSearchingForBackup

    GrantPermissionsScreen(
      deviceBuildVersion = Build.VERSION.SDK_INT,
      isSearchingForBackup = isSearchingForBackup,
      isBackupSelectionRequired = BackupUtil.isUserSelectionRequired(LocalContext.current),
      onNextClicked = this::launchPermissionRequests,
      onNotNowClicked = this::proceedToNextScreen
    )
  }

  private fun launchPermissionRequests() {
    val isUserSelectionRequired = BackupUtil.isUserSelectionRequired(requireContext())

    val neededPermissions = WelcomePermissions.getWelcomePermissions(isUserSelectionRequired).filterNot {
      ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    if (neededPermissions.isEmpty()) {
      proceedToNextScreen()
    } else {
      requestPermissionLauncher.launch(neededPermissions.toTypedArray())
    }
  }

  private fun onPermissionsGranted(permissions: Map<String, Boolean>) {
    permissions.forEach {
      Log.d(TAG, "${it.key} = ${it.value}")
    }
    sharedViewModel.setRegistrationCheckpoint(RegistrationCheckpoint.PERMISSIONS_GRANTED)
    proceedToNextScreen()
  }

  private fun proceedToNextScreen() {
    when (welcomeAction) {
      WelcomeAction.CONTINUE -> NavHostFragment.findNavController(this).safeNavigate(GrantPermissionsV2FragmentDirections.actionEnterPhoneNumber())
      WelcomeAction.RESTORE_BACKUP -> {
        val restoreIntent = RestoreActivity.getIntentForRestore(requireActivity())
        launchRestoreActivity.launch(restoreIntent)
      }
    }
  }

  /**
   * Which welcome action the user selected which prompted this
   * screen.
   */
  enum class WelcomeAction {
    CONTINUE,
    RESTORE_BACKUP
  }

  companion object {
    private val TAG = Log.tag(GrantPermissionsV2Fragment::class.java)
  }
}
