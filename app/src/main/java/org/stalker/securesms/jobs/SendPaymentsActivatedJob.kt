package org.stalker.securesms.jobs

import org.signal.core.util.logging.Log
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.mms.OutgoingMessage
import org.stalker.securesms.net.NotPushRegisteredException
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.sms.MessageSender

/**
 * Send payments activated message to all recipients of payment activation request
 */
class SendPaymentsActivatedJob(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    private val TAG = Log.tag(SendPaymentsActivatedJob::class.java)

    const val KEY = "SendPaymentsActivatedJob"
  }

  constructor() : this(parameters = Parameters.Builder().build())

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  @Suppress("UsePropertyAccessSyntax")
  override fun onRun() {
    if (!Recipient.self().isRegistered) {
      throw NotPushRegisteredException()
    }

    if (!SignalStore.paymentsValues().mobileCoinPaymentsEnabled()) {
      Log.w(TAG, "Payments aren't enabled, not going to attempt to send activation messages.")
      return
    }

    val threadIds: List<Long> = SignalDatabase.messages.getIncomingPaymentRequestThreads()

    for (threadId in threadIds) {
      val recipient = SignalDatabase.threads.getRecipientForThreadId(threadId)
      if (recipient != null) {
        MessageSender.send(
          context,
          OutgoingMessage.paymentsActivatedMessage(recipient, System.currentTimeMillis(), 0),
          threadId,
          MessageSender.SendType.SIGNAL,
          null,
          null
        )
      } else {
        Log.w(TAG, "Unable to send activation message for thread: $threadId")
      }
    }
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return false
  }

  override fun onFailure() {
    Log.w(TAG, "Failed to submit send of payments activated messages")
  }

  class Factory : Job.Factory<SendPaymentsActivatedJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): SendPaymentsActivatedJob {
      return SendPaymentsActivatedJob(parameters)
    }
  }
}
