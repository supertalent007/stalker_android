/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.registration.v2.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.navigation.ActivityNavigator
import org.signal.core.util.logging.Log
import org.stalker.securesms.BaseActivity
import org.stalker.securesms.MainActivity
import org.stalker.securesms.R
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.lock.v2.CreateSvrPinActivity
import org.stalker.securesms.profiles.AvatarHelper
import org.stalker.securesms.profiles.edit.CreateProfileActivity
import org.stalker.securesms.recipients.Recipient

/**
 * Activity to hold the entire registration process.
 */
class RegistrationV2Activity : BaseActivity() {

  private val TAG = Log.tag(RegistrationV2Activity::class.java)

  val sharedViewModel: RegistrationV2ViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_registration_navigation_v2)
    sharedViewModel.uiState.observe(this) {
      if (it.registrationCheckpoint == RegistrationCheckpoint.SERVICE_REGISTRATION_COMPLETED) {
        handleSuccessfulVerify()
      }
    }
  }

  private fun handleSuccessfulVerify() {
    // TODO [regv2]: add functionality of [RegistrationCompleteFragment]
    val isProfileNameEmpty = Recipient.self().profileName.isEmpty
    val isAvatarEmpty = !AvatarHelper.hasAvatar(this, Recipient.self().id)
    val needsProfile = isProfileNameEmpty || isAvatarEmpty
    val needsPin = !sharedViewModel.hasPin()

    Log.i(TAG, "Pin restore flow not required. Profile name: $isProfileNameEmpty | Profile avatar: $isAvatarEmpty | Needs PIN: $needsPin")

    SignalStore.internalValues().setForceEnterRestoreV2Flow(true)

    if (!needsProfile && !needsPin) {
      sharedViewModel.completeRegistration()
    }
    sharedViewModel.setInProgress(false)

    val startIntent = MainActivity.clearTop(this).apply {
      if (needsPin) {
        putExtra("next_intent", CreateSvrPinActivity.getIntentForPinCreate(this@RegistrationV2Activity))
      }

      if (needsProfile) {
        putExtra("next_intent", CreateProfileActivity.getIntentForUserProfile(this@RegistrationV2Activity))
      }
    }

    Log.d(TAG, "Launching ${startIntent.component}")
    startActivity(startIntent)
    finish()
    ActivityNavigator.applyPopAnimationsToPendingTransition(this)
  }

  companion object {

    @JvmStatic
    fun newIntentForNewRegistration(context: Context, originalIntent: Intent): Intent {
      return Intent(context, RegistrationV2Activity::class.java).apply {
        setData(originalIntent.data)
      }
    }
  }
}
