/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.nicknames

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId

class ViewNoteSheetViewModel(
  recipientId: RecipientId
) : ViewModel() {
  private val internalNote = mutableStateOf("")
  val note: State<String> = internalNote

  private val recipientDisposable = Recipient.observable(recipientId)
    .map { it.note ?: "" }
    .subscribeOn(Schedulers.io())
    .observeOn(AndroidSchedulers.mainThread())
    .subscribeBy { internalNote.value = it }

  override fun onCleared() {
    recipientDisposable.dispose()
  }
}
