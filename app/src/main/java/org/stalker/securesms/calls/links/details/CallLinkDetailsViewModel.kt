/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.calls.links.details

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.signal.ringrtc.CallLinkState
import org.stalker.securesms.calls.links.CallLinks
import org.stalker.securesms.calls.links.UpdateCallLinkRepository
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.service.webrtc.links.CallLinkRoomId
import org.stalker.securesms.service.webrtc.links.UpdateCallLinkResult

class CallLinkDetailsViewModel(
  callLinkRoomId: CallLinkRoomId,
  repository: CallLinkDetailsRepository = CallLinkDetailsRepository(),
  private val mutationRepository: UpdateCallLinkRepository = UpdateCallLinkRepository()
) : ViewModel() {
  private val disposables = CompositeDisposable()

  private val _state: MutableState<CallLinkDetailsState> = mutableStateOf(CallLinkDetailsState())
  val state: State<CallLinkDetailsState> = _state
  val nameSnapshot: String
    get() = state.value.callLink?.state?.name ?: error("Call link not loaded yet.")

  val rootKeySnapshot: ByteArray
    get() = state.value.callLink?.credentials?.linkKeyBytes ?: error("Call link not loaded yet.")

  private val recipientSubject = BehaviorSubject.create<Recipient>()
  val recipientSnapshot: Recipient?
    get() = recipientSubject.value

  init {
    disposables += repository.refreshCallLinkState(callLinkRoomId)
    disposables += CallLinks.watchCallLink(callLinkRoomId).subscribeBy {
      _state.value = _state.value.copy(callLink = it)
    }

    disposables += repository
      .watchCallLinkRecipient(callLinkRoomId)
      .subscribeBy(onNext = recipientSubject::onNext)
  }

  override fun onCleared() {
    super.onCleared()
    disposables.dispose()
  }

  fun setDisplayRevocationDialog(displayRevocationDialog: Boolean) {
    _state.value = _state.value.copy(displayRevocationDialog = displayRevocationDialog)
  }

  fun setApproveAllMembers(approveAllMembers: Boolean): Single<UpdateCallLinkResult> {
    val credentials = _state.value.callLink?.credentials ?: error("User cannot change the name of this call.")
    return mutationRepository.setCallRestrictions(credentials, if (approveAllMembers) CallLinkState.Restrictions.ADMIN_APPROVAL else CallLinkState.Restrictions.NONE)
  }

  fun setName(name: String): Single<UpdateCallLinkResult> {
    val credentials = _state.value.callLink?.credentials ?: error("User cannot change the name of this call.")
    return mutationRepository.setCallName(credentials, name)
  }

  fun delete(): Single<UpdateCallLinkResult> {
    val credentials = _state.value.callLink?.credentials ?: error("User cannot change the name of this call.")
    return mutationRepository.deleteCallLink(credentials)
  }

  class Factory(private val callLinkRoomId: CallLinkRoomId) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(CallLinkDetailsViewModel(callLinkRoomId)) as T
    }
  }
}
