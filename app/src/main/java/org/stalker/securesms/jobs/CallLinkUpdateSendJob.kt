/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.jobs

import okio.ByteString.Companion.toByteString
import org.signal.core.util.logging.Log
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.jobs.protos.CallLinkUpdateSendJobData
import org.stalker.securesms.service.webrtc.links.CallLinkRoomId
import org.stalker.securesms.util.FeatureFlags
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException
import org.whispersystems.signalservice.internal.push.SyncMessage.CallLinkUpdate
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Sends a [CallLinkUpdate] message to linked devices.
 */
class CallLinkUpdateSendJob private constructor(
  parameters: Parameters,
  private val callLinkRoomId: CallLinkRoomId,
  private val callLinkUpdateType: CallLinkUpdate.Type
) : BaseJob(parameters) {

  companion object {
    const val KEY = "CallLinkUpdateSendJob"
    private val TAG = Log.tag(CallLinkUpdateSendJob::class.java)
  }

  constructor(
    callLinkRoomId: CallLinkRoomId,
    callLinkUpdateType: CallLinkUpdate.Type = CallLinkUpdate.Type.UPDATE
  ) : this(
    Parameters.Builder()
      .setQueue("CallLinkUpdateSendJob")
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .addConstraint(NetworkConstraint.KEY)
      .build(),
    callLinkRoomId,
    callLinkUpdateType
  )

  override fun serialize(): ByteArray = CallLinkUpdateSendJobData.Builder()
    .callLinkRoomId(callLinkRoomId.serialize())
    .type(
      when (callLinkUpdateType) {
        CallLinkUpdate.Type.UPDATE -> CallLinkUpdateSendJobData.Type.UPDATE
        CallLinkUpdate.Type.DELETE -> CallLinkUpdateSendJobData.Type.DELETE
      }
    )
    .build()
    .encode()

  override fun getFactoryKey(): String = KEY

  override fun onFailure() = Unit

  override fun onRun() {
    if (!FeatureFlags.adHocCalling()) {
      Log.i(TAG, "Call links are not enabled. Exiting.")
      return
    }

    val callLink = SignalDatabase.callLinks.getCallLinkByRoomId(callLinkRoomId)
    if (callLink?.credentials == null) {
      Log.i(TAG, "Call link not found or missing credentials. Exiting.")
      return
    }

    val callLinkUpdate = CallLinkUpdate(
      rootKey = callLink.credentials.linkKeyBytes.toByteString(),
      adminPassKey = callLink.credentials.adminPassBytes?.toByteString(),
      type = callLinkUpdateType
    )

    ApplicationDependencies.getSignalServiceMessageSender()
      .sendSyncMessage(SignalServiceSyncMessage.forCallLinkUpdate(callLinkUpdate), Optional.empty())

    if (callLinkUpdateType == CallLinkUpdate.Type.DELETE) {
      SignalDatabase.callLinks.deleteCallLink(callLinkRoomId)
    }
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return when (e) {
      is ServerRejectedException -> false
      is PushNetworkException -> true
      else -> false
    }
  }

  class Factory : Job.Factory<CallLinkUpdateSendJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CallLinkUpdateSendJob {
      val jobData = CallLinkUpdateSendJobData.ADAPTER.decode(serializedData!!)
      val type: CallLinkUpdate.Type = when (jobData.type) {
        CallLinkUpdateSendJobData.Type.UPDATE, null -> CallLinkUpdate.Type.UPDATE
        CallLinkUpdateSendJobData.Type.DELETE -> CallLinkUpdate.Type.DELETE
      }

      return CallLinkUpdateSendJob(
        parameters,
        CallLinkRoomId.DatabaseSerializer.deserialize(jobData.callLinkRoomId),
        type
      )
    }
  }
}
