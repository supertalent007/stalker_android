package org.stalker.securesms.jobs

import org.signal.core.util.logging.Log
import org.signal.ringrtc.CallId
import org.stalker.securesms.database.CallTable
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.JsonJobData
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.jobs.protos.CallSyncEventJobData
import org.stalker.securesms.jobs.protos.CallSyncEventJobRecord
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.ringrtc.RemotePeer
import org.stalker.securesms.service.webrtc.CallEventSyncMessageUtil
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Sends a sync event for the given call when the user first joins.
 */
class CallSyncEventJob private constructor(
  parameters: Parameters,
  private var events: List<CallSyncEventJobRecord>
) : BaseJob(parameters) {

  companion object {
    private val TAG = Log.tag(CallSyncEventJob::class.java)

    const val KEY = "CallSyncEventJob2"

    @JvmStatic
    fun createForJoin(conversationRecipientId: RecipientId, callId: Long, isIncoming: Boolean): CallSyncEventJob {
      return CallSyncEventJob(
        getParameters(),
        listOf(
          CallSyncEventJobRecord(
            recipientId = conversationRecipientId.toLong(),
            callId = callId,
            direction = CallTable.Direction.serialize(if (isIncoming) CallTable.Direction.INCOMING else CallTable.Direction.OUTGOING),
            event = CallTable.Event.serialize(CallTable.Event.ACCEPTED)
          )
        )
      )
    }

    @JvmStatic
    fun createForNotAccepted(conversationRecipientId: RecipientId, callId: Long, isIncoming: Boolean): CallSyncEventJob {
      return CallSyncEventJob(
        getParameters(),
        listOf(
          CallSyncEventJobRecord(
            recipientId = conversationRecipientId.toLong(),
            callId = callId,
            direction = CallTable.Direction.serialize(if (isIncoming) CallTable.Direction.INCOMING else CallTable.Direction.OUTGOING),
            event = CallTable.Event.serialize(CallTable.Event.NOT_ACCEPTED)
          )
        )
      )
    }

    private fun createForDelete(calls: List<CallTable.Call>): CallSyncEventJob {
      return CallSyncEventJob(
        getParameters(),
        calls.map {
          CallSyncEventJobRecord(
            recipientId = it.peer.toLong(),
            callId = it.callId,
            direction = CallTable.Direction.serialize(it.direction),
            event = CallTable.Event.serialize(CallTable.Event.DELETE)
          )
        }
      )
    }

    fun enqueueDeleteSyncEvents(deletedCalls: Set<CallTable.Call>) {
      deletedCalls.chunked(50).forEach {
        ApplicationDependencies.getJobManager().add(
          createForDelete(it)
        )
      }
    }

    private fun getParameters(): Parameters {
      return Parameters.Builder()
        .setQueue("CallSyncEventJob")
        .setLifespan(TimeUnit.DAYS.toMillis(1))
        .setMaxAttempts(Parameters.UNLIMITED)
        .addConstraint(NetworkConstraint.KEY)
        .build()
    }
  }

  override fun serialize(): ByteArray {
    return CallSyncEventJobData(events).encodeByteString().toByteArray()
  }

  override fun getFactoryKey(): String = KEY

  override fun onFailure() = Unit

  override fun onShouldRetry(e: Exception): Boolean = e is RetryableException

  override fun onRun() {
    val remainingEvents = events.mapNotNull(this::processEvent)

    if (remainingEvents.isEmpty()) {
      Log.i(TAG, "Successfully sent all sync messages.")
    } else {
      warn(TAG, "Failed to send sync messages for ${remainingEvents.size} events.")
      events = remainingEvents
      throw RetryableException()
    }
  }

  private fun processEvent(callSyncEvent: CallSyncEventJobRecord): CallSyncEventJobRecord? {
    val call = SignalDatabase.calls.getCallById(callSyncEvent.callId, callSyncEvent.deserializeRecipientId())
    if (call == null) {
      Log.w(TAG, "Cannot process event for call that does not exist. Dropping.")
      return null
    }

    val inputTimestamp = JsonJobData.deserialize(inputData).getLongOrDefault(GroupCallUpdateSendJob.KEY_SYNC_TIMESTAMP, System.currentTimeMillis())
    val syncTimestamp = if (inputTimestamp == 0L) System.currentTimeMillis() else inputTimestamp
    val syncMessage = createSyncMessage(syncTimestamp, callSyncEvent, call.type)

    return try {
      ApplicationDependencies.getSignalServiceMessageSender().sendSyncMessage(SignalServiceSyncMessage.forCallEvent(syncMessage), Optional.empty())
      null
    } catch (e: Exception) {
      Log.w(TAG, "Unable to send call event sync message for ${callSyncEvent.callId}", e)
      callSyncEvent
    }
  }

  private fun createSyncMessage(syncTimestamp: Long, callSyncEvent: CallSyncEventJobRecord, callType: CallTable.Type): SyncMessage.CallEvent {
    return when (callSyncEvent.deserializeEvent()) {
      CallTable.Event.ACCEPTED -> CallEventSyncMessageUtil.createAcceptedSyncMessage(
        remotePeer = RemotePeer(callSyncEvent.deserializeRecipientId(), CallId(callSyncEvent.callId)),
        timestamp = syncTimestamp,
        isOutgoing = callSyncEvent.deserializeDirection() == CallTable.Direction.OUTGOING,
        isVideoCall = callType != CallTable.Type.AUDIO_CALL
      )

      CallTable.Event.NOT_ACCEPTED -> CallEventSyncMessageUtil.createNotAcceptedSyncMessage(
        remotePeer = RemotePeer(callSyncEvent.deserializeRecipientId(), CallId(callSyncEvent.callId)),
        timestamp = syncTimestamp,
        isOutgoing = callSyncEvent.deserializeDirection() == CallTable.Direction.OUTGOING,
        isVideoCall = callType != CallTable.Type.AUDIO_CALL
      )

      CallTable.Event.DELETE -> CallEventSyncMessageUtil.createDeleteCallEvent(
        remotePeer = RemotePeer(callSyncEvent.deserializeRecipientId(), CallId(callSyncEvent.callId)),
        timestamp = syncTimestamp,
        isOutgoing = callSyncEvent.deserializeDirection() == CallTable.Direction.OUTGOING,
        isVideoCall = callType != CallTable.Type.AUDIO_CALL
      )

      else -> throw Exception("Unsupported event: ${callSyncEvent.event}")
    }
  }

  private fun CallSyncEventJobRecord.deserializeRecipientId(): RecipientId = RecipientId.from(recipientId)

  private fun CallSyncEventJobRecord.deserializeDirection(): CallTable.Direction = CallTable.Direction.deserialize(direction)

  private fun CallSyncEventJobRecord.deserializeEvent(): CallTable.Event = CallTable.Event.deserialize(event)

  class Factory : Job.Factory<CallSyncEventJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CallSyncEventJob {
      val events = CallSyncEventJobData.ADAPTER.decode(serializedData!!).records

      return CallSyncEventJob(
        parameters,
        events
      )
    }
  }

  private class RetryableException : Exception()
}
