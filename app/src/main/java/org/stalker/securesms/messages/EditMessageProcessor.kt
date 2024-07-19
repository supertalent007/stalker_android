package org.stalker.securesms.messages

import android.content.Context
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.orNull
import org.stalker.securesms.database.MessageTable.InsertResult
import org.stalker.securesms.database.MessageType
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.MessageId
import org.stalker.securesms.database.model.MmsMessageRecord
import org.stalker.securesms.database.model.databaseprotos.BodyRangeList
import org.stalker.securesms.database.model.toBodyRangeList
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.groups.GroupId
import org.stalker.securesms.jobs.AttachmentDownloadJob
import org.stalker.securesms.jobs.PushProcessEarlyMessagesJob
import org.stalker.securesms.jobs.SendDeliveryReceiptJob
import org.stalker.securesms.messages.MessageContentProcessor.Companion.log
import org.stalker.securesms.messages.MessageContentProcessor.Companion.warn
import org.stalker.securesms.messages.SignalServiceProtoUtil.groupId
import org.stalker.securesms.messages.SignalServiceProtoUtil.isMediaMessage
import org.stalker.securesms.messages.SignalServiceProtoUtil.toPointersWithinLimit
import org.stalker.securesms.mms.IncomingMessage
import org.stalker.securesms.mms.QuoteModel
import org.stalker.securesms.notifications.v2.ConversationId.Companion.forConversation
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.EarlyMessageCacheEntry
import org.stalker.securesms.util.MediaUtil
import org.stalker.securesms.util.MessageConstraintsUtil
import org.stalker.securesms.util.hasAudio
import org.stalker.securesms.util.hasSharedContact
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope

object EditMessageProcessor {
  fun process(
    context: Context,
    senderRecipient: Recipient,
    threadRecipient: Recipient,
    envelope: Envelope,
    content: Content,
    metadata: EnvelopeMetadata,
    earlyMessageCacheEntry: EarlyMessageCacheEntry?
  ) {
    val editMessage = content.editMessage!!

    log(envelope.timestamp!!, "[handleEditMessage] Edit message for " + editMessage.targetSentTimestamp)

    var targetMessage: MmsMessageRecord? = SignalDatabase.messages.getMessageFor(editMessage.targetSentTimestamp!!, senderRecipient.id) as? MmsMessageRecord
    val targetThreadRecipient: Recipient? = if (targetMessage != null) SignalDatabase.threads.getRecipientForThreadId(targetMessage.threadId) else null

    if (targetMessage == null || targetThreadRecipient == null) {
      warn(envelope.timestamp!!, "[handleEditMessage] Could not find matching message! timestamp: ${editMessage.targetSentTimestamp}  author: ${senderRecipient.id}")

      if (earlyMessageCacheEntry != null) {
        ApplicationDependencies.getEarlyMessageCache().store(senderRecipient.id, editMessage.targetSentTimestamp!!, earlyMessageCacheEntry)
        PushProcessEarlyMessagesJob.enqueue()
      }

      return
    }

    val message = editMessage.dataMessage!!
    val isMediaMessage = message.isMediaMessage
    val groupId: GroupId.V2? = message.groupV2?.groupId

    val originalMessage = targetMessage.originalMessageId?.let { SignalDatabase.messages.getMessageRecord(it.id) } ?: targetMessage
    val validTiming = MessageConstraintsUtil.isValidEditMessageReceive(originalMessage, senderRecipient, envelope.serverTimestamp!!)
    val validAuthor = senderRecipient.id == originalMessage.fromRecipient.id
    val validGroup = groupId == targetThreadRecipient.groupId.orNull()
    val validTarget = !originalMessage.isViewOnce && !originalMessage.hasAudio() && !originalMessage.hasSharedContact()

    if (!validTiming || !validAuthor || !validGroup || !validTarget) {
      warn(envelope.timestamp!!, "[handleEditMessage] Invalid message edit! editTime: ${envelope.serverTimestamp}, targetTime: ${originalMessage.serverTimestamp}, editAuthor: ${senderRecipient.id}, targetAuthor: ${originalMessage.fromRecipient.id}, editThread: ${threadRecipient.id}, targetThread: ${targetThreadRecipient.id}, validity: (timing: $validTiming, author: $validAuthor, group: $validGroup, target: $validTarget)")
      return
    }

    if (groupId != null && MessageContentProcessor.handleGv2PreProcessing(context, envelope.timestamp!!, content, metadata, groupId, message.groupV2!!, senderRecipient) == MessageContentProcessor.Gv2PreProcessResult.IGNORE) {
      warn(envelope.timestamp!!, "[handleEditMessage] Group processor indicated we should ignore this.")
      return
    }

    DataMessageProcessor.notifyTypingStoppedFromIncomingMessage(context, senderRecipient, threadRecipient.id, metadata.sourceDeviceId)

    targetMessage = targetMessage.withAttachments(SignalDatabase.attachments.getAttachmentsForMessage(targetMessage.id))

    val insertResult: InsertResult? = if (isMediaMessage || targetMessage.quote != null || targetMessage.slideDeck.slides.isNotEmpty()) {
      handleEditMediaMessage(senderRecipient.id, groupId, envelope, metadata, message, targetMessage)
    } else {
      handleEditTextMessage(senderRecipient.id, groupId, envelope, metadata, message, targetMessage)
    }

    if (insertResult != null) {
      SignalExecutors.BOUNDED.execute {
        ApplicationDependencies.getJobManager().add(SendDeliveryReceiptJob(senderRecipient.id, message.timestamp!!, MessageId(insertResult.messageId)))
      }

      if (targetMessage.expireStarted > 0) {
        ApplicationDependencies.getExpiringMessageManager()
          .scheduleDeletion(
            insertResult.messageId,
            true,
            targetMessage.expireStarted,
            targetMessage.expiresIn
          )
      }

      ApplicationDependencies.getMessageNotifier().updateNotification(context, forConversation(insertResult.threadId))
    }
  }

  private fun handleEditMediaMessage(
    senderRecipientId: RecipientId,
    groupId: GroupId.V2?,
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    message: DataMessage,
    targetMessage: MmsMessageRecord
  ): InsertResult? {
    val messageRanges: BodyRangeList? = message.bodyRanges.filter { it.mentionAci == null }.toList().toBodyRangeList()
    val targetQuote = targetMessage.quote
    val quote: QuoteModel? = if (targetQuote != null && message.quote != null) {
      QuoteModel(
        targetQuote.id,
        targetQuote.author,
        targetQuote.displayText.toString(),
        targetQuote.isOriginalMissing,
        emptyList(),
        null,
        targetQuote.quoteType,
        null
      )
    } else {
      null
    }
    val attachments = message.attachments.toPointersWithinLimit()
    attachments.filter {
      MediaUtil.SlideType.LONG_TEXT == MediaUtil.getSlideTypeFromContentType(it.contentType)
    }
    val mediaMessage = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderRecipientId,
      sentTimeMillis = message.timestamp!!,
      serverTimeMillis = envelope.serverTimestamp!!,
      receivedTimeMillis = targetMessage.dateReceived,
      expiresIn = targetMessage.expiresIn,
      isViewOnce = message.isViewOnce == true,
      isUnidentified = metadata.sealedSender,
      body = message.body,
      groupId = groupId,
      attachments = attachments,
      quote = quote,
      sharedContacts = emptyList(),
      linkPreviews = DataMessageProcessor.getLinkPreviews(message.preview, message.body ?: "", false),
      mentions = DataMessageProcessor.getMentions(message.bodyRanges),
      serverGuid = envelope.serverGuid,
      messageRanges = messageRanges
    )

    val insertResult = SignalDatabase.messages.insertEditMessageInbox(mediaMessage, targetMessage).orNull()
    if (insertResult?.insertedAttachments != null) {
      SignalDatabase.runPostSuccessfulTransaction {
        val downloadJobs: List<AttachmentDownloadJob> = insertResult.insertedAttachments.mapNotNull { (_, attachmentId) ->
          AttachmentDownloadJob(insertResult.messageId, attachmentId, false)
        }
        ApplicationDependencies.getJobManager().addAll(downloadJobs)
      }
    }
    return insertResult
  }

  private fun handleEditTextMessage(
    senderRecipientId: RecipientId,
    groupId: GroupId.V2?,
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    message: DataMessage,
    targetMessage: MmsMessageRecord
  ): InsertResult? {
    val textMessage = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderRecipientId,
      sentTimeMillis = envelope.timestamp!!,
      serverTimeMillis = envelope.timestamp!!,
      receivedTimeMillis = targetMessage.dateReceived,
      body = message.body,
      groupId = groupId,
      expiresIn = targetMessage.expiresIn,
      isUnidentified = metadata.sealedSender,
      serverGuid = envelope.serverGuid
    )

    return SignalDatabase.messages.insertEditMessageInbox(textMessage, targetMessage).orNull()
  }
}
