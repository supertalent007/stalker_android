/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.jobs

import androidx.annotation.WorkerThread
import okio.ByteString.Companion.toByteString
import org.stalker.securesms.database.CallTable
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.jobs.protos.CallLogEventSendJobData
import org.stalker.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Sends CallLogEvents to synced devices.
 */
class CallLogEventSendJob private constructor(
  parameters: Parameters,
  private val callLogEvent: SyncMessage.CallLogEvent
) : BaseJob(parameters) {

  companion object {
    const val KEY = "CallLogEventSendJob"

    @WorkerThread
    fun forClearHistory(
      call: CallTable.Call
    ) = CallLogEventSendJob(
      Parameters.Builder()
        .setQueue("CallLogEventSendJob")
        .setLifespan(TimeUnit.DAYS.toMillis(1))
        .setMaxAttempts(Parameters.UNLIMITED)
        .addConstraint(NetworkConstraint.KEY)
        .build(),
      SyncMessage.CallLogEvent(
        timestamp = call.timestamp,
        callId = call.callId,
        conversationId = Recipient.resolved(call.peer).requireCallConversationId().toByteString(),
        type = SyncMessage.CallLogEvent.Type.CLEAR
      )
    )

    @WorkerThread
    fun forMarkedAsRead(
      call: CallTable.Call
    ) = CallLogEventSendJob(
      Parameters.Builder()
        .setQueue("CallLogEventSendJob")
        .setLifespan(TimeUnit.DAYS.toMillis(1))
        .setMaxAttempts(Parameters.UNLIMITED)
        .addConstraint(NetworkConstraint.KEY)
        .build(),
      SyncMessage.CallLogEvent(
        timestamp = call.timestamp,
        callId = call.callId,
        conversationId = Recipient.resolved(call.peer).requireCallConversationId().toByteString(),
        type = SyncMessage.CallLogEvent.Type.MARKED_AS_READ
      )
    )

    @JvmStatic
    @WorkerThread
    fun forMarkedAsReadInConversation(
      call: CallTable.Call
    ) = CallLogEventSendJob(
      Parameters.Builder()
        .setQueue("CallLogEventSendJob")
        .setLifespan(TimeUnit.DAYS.toMillis(1))
        .setMaxAttempts(Parameters.UNLIMITED)
        .addConstraint(NetworkConstraint.KEY)
        .build(),
      SyncMessage.CallLogEvent(
        timestamp = call.timestamp,
        callId = call.callId,
        conversationId = Recipient.resolved(call.peer).requireCallConversationId().toByteString(),
        type = SyncMessage.CallLogEvent.Type.MARKED_AS_READ_IN_CONVERSATION
      )
    )
  }

  override fun serialize(): ByteArray = CallLogEventSendJobData.Builder()
    .callLogEvent(callLogEvent.encodeByteString())
    .build()
    .encode()

  override fun getFactoryKey(): String = KEY

  override fun onFailure() = Unit

  override fun onRun() {
    ApplicationDependencies.getSignalServiceMessageSender()
      .sendSyncMessage(
        SignalServiceSyncMessage.forCallLogEvent(callLogEvent),
        Optional.empty()
      )
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return when (e) {
      is ServerRejectedException -> false
      is PushNetworkException -> true
      else -> false
    }
  }

  class Factory : Job.Factory<CallLogEventSendJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CallLogEventSendJob {
      return CallLogEventSendJob(
        parameters,
        SyncMessage.CallLogEvent.ADAPTER.decode(
          CallLogEventSendJobData.ADAPTER.decode(serializedData!!).callLogEvent.toByteArray()
        )
      )
    }
  }
}
