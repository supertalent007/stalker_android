/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.calls.links.details

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.concurrent.MaybeCompat
import org.signal.core.util.orNull
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.service.webrtc.links.CallLinkRoomId
import org.stalker.securesms.service.webrtc.links.ReadCallLinkResult
import org.stalker.securesms.service.webrtc.links.SignalCallLinkManager

class CallLinkDetailsRepository(
  private val callLinkManager: SignalCallLinkManager = ApplicationDependencies.getSignalCallManager().callLinkManager
) {
  fun refreshCallLinkState(callLinkRoomId: CallLinkRoomId): Disposable {
    return MaybeCompat.fromCallable { SignalDatabase.callLinks.getCallLinkByRoomId(callLinkRoomId) }
      .flatMapSingle { callLinkManager.readCallLink(it.credentials!!) }
      .subscribeOn(Schedulers.io())
      .subscribeBy { result ->
        when (result) {
          is ReadCallLinkResult.Success -> SignalDatabase.callLinks.updateCallLinkState(callLinkRoomId, result.callLinkState)
          is ReadCallLinkResult.Failure -> Unit
        }
      }
  }

  fun watchCallLinkRecipient(callLinkRoomId: CallLinkRoomId): Observable<Recipient> {
    return MaybeCompat.fromCallable { SignalDatabase.recipients.getByCallLinkRoomId(callLinkRoomId).orNull() }
      .flatMapObservable { Recipient.observable(it) }
      .distinctUntilChanged { a, b -> a.hasSameContent(b) }
      .subscribeOn(Schedulers.io())
  }
}
