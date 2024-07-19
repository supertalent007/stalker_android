/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.calls.links

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.ringrtc.CallLinkState
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobs.CallLinkUpdateSendJob
import org.stalker.securesms.service.webrtc.links.CallLinkCredentials
import org.stalker.securesms.service.webrtc.links.SignalCallLinkManager
import org.stalker.securesms.service.webrtc.links.UpdateCallLinkResult

/**
 * Repository for performing update operations on call links:
 * <ul>
 *   <li>Set name</li>
 *   <li>Set restrictions</li>
 *   <li>Revoke link</li>
 * </ul>
 *
 * All of these will delegate to the [SignalCallLinkManager] but will additionally update the database state.
 */
class UpdateCallLinkRepository(
  private val callLinkManager: SignalCallLinkManager = ApplicationDependencies.getSignalCallManager().callLinkManager
) {
  fun setCallName(credentials: CallLinkCredentials, name: String): Single<UpdateCallLinkResult> {
    return callLinkManager
      .updateCallLinkName(
        credentials = credentials,
        name = name
      )
      .doOnSuccess(updateState(credentials))
      .subscribeOn(Schedulers.io())
  }

  fun setCallRestrictions(credentials: CallLinkCredentials, restrictions: CallLinkState.Restrictions): Single<UpdateCallLinkResult> {
    return callLinkManager
      .updateCallLinkRestrictions(
        credentials = credentials,
        restrictions = restrictions
      )
      .doOnSuccess(updateState(credentials))
      .subscribeOn(Schedulers.io())
  }

  fun deleteCallLink(credentials: CallLinkCredentials): Single<UpdateCallLinkResult> {
    return callLinkManager
      .deleteCallLink(credentials)
      .doOnSuccess(updateState(credentials))
      .subscribeOn(Schedulers.io())
  }

  private fun updateState(credentials: CallLinkCredentials): (UpdateCallLinkResult) -> Unit {
    return { result ->
      when (result) {
        is UpdateCallLinkResult.Update -> {
          SignalDatabase.callLinks.updateCallLinkState(credentials.roomId, result.state)
          ApplicationDependencies.getJobManager().add(CallLinkUpdateSendJob(credentials.roomId))
        }
        is UpdateCallLinkResult.Delete -> {
          SignalDatabase.callLinks.markRevoked(credentials.roomId)
          ApplicationDependencies.getJobManager().add(CallLinkUpdateSendJob(credentials.roomId))
        }
        else -> {}
      }
    }
  }
}
