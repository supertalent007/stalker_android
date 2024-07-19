/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.storage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.signal.core.util.concurrent.SignalExecutors
import org.stalker.securesms.database.MediaTable
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.SignalDatabase.Companion.media
import org.stalker.securesms.database.ThreadTable
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.keyvalue.KeepMessagesDuration
import org.stalker.securesms.keyvalue.SignalStore

class ManageStorageSettingsViewModel : ViewModel() {

  private val store = MutableStateFlow(
    ManageStorageState(
      keepMessagesDuration = SignalStore.settings().keepMessagesDuration,
      lengthLimit = if (SignalStore.settings().isTrimByLengthEnabled) SignalStore.settings().threadTrimLength else ManageStorageState.NO_LIMIT
    )
  )
  val state = store.asStateFlow()

  fun refresh() {
    viewModelScope.launch {
      val breakdown: MediaTable.StorageBreakdown = media.getStorageBreakdown()
      store.update { it.copy(breakdown = breakdown) }
    }
  }

  fun deleteChatHistory() {
    viewModelScope.launch {
      SignalDatabase.threads.deleteAllConversations()
      ApplicationDependencies.getMessageNotifier().updateNotification(ApplicationDependencies.getApplication())
    }
  }

  fun setKeepMessagesDuration(newDuration: KeepMessagesDuration) {
    SignalStore.settings().setKeepMessagesForDuration(newDuration)
    ApplicationDependencies.getTrimThreadsByDateManager().scheduleIfNecessary()

    store.update { it.copy(keepMessagesDuration = newDuration) }
  }

  fun showConfirmKeepDurationChange(newDuration: KeepMessagesDuration): Boolean {
    return newDuration.ordinal > state.value.keepMessagesDuration.ordinal
  }

  fun setChatLengthLimit(newLimit: Int) {
    val restrictingChange = isRestrictingLengthLimitChange(newLimit)

    SignalStore.settings().setThreadTrimByLengthEnabled(newLimit != ManageStorageState.NO_LIMIT)
    SignalStore.settings().threadTrimLength = newLimit
    store.update { it.copy(lengthLimit = newLimit) }

    if (SignalStore.settings().isTrimByLengthEnabled && restrictingChange) {
      SignalExecutors.BOUNDED.execute {
        val keepMessagesDuration = SignalStore.settings().keepMessagesDuration

        val trimBeforeDate = if (keepMessagesDuration != KeepMessagesDuration.FOREVER) {
          System.currentTimeMillis() - keepMessagesDuration.duration
        } else {
          ThreadTable.NO_TRIM_BEFORE_DATE_SET
        }

        SignalDatabase.threads.trimAllThreads(newLimit, trimBeforeDate)
      }
    }
  }

  fun showConfirmSetChatLengthLimit(newLimit: Int): Boolean {
    return isRestrictingLengthLimitChange(newLimit)
  }

  private fun isRestrictingLengthLimitChange(newLimit: Int): Boolean {
    return state.value.lengthLimit == ManageStorageState.NO_LIMIT || (newLimit != ManageStorageState.NO_LIMIT && newLimit < state.value.lengthLimit)
  }

  @Immutable
  data class ManageStorageState(
    val keepMessagesDuration: KeepMessagesDuration = KeepMessagesDuration.FOREVER,
    val lengthLimit: Int = NO_LIMIT,
    val breakdown: MediaTable.StorageBreakdown? = null
  ) {
    companion object {
      const val NO_LIMIT = 0
    }
  }
}
