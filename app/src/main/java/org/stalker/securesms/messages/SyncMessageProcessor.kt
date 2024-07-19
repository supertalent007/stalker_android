package org.stalker.securesms.messages

import ProtoUtil.isNotEmpty
import android.content.Context
import com.mobilecoin.lib.exceptions.SerializationException
import okio.ByteString
import org.signal.core.util.Hex
import org.signal.core.util.orNull
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.util.Pair
import org.signal.ringrtc.CallException
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallLinkRootKey
import org.stalker.securesms.attachments.Attachment
import org.stalker.securesms.attachments.DatabaseAttachment
import org.stalker.securesms.attachments.TombstoneAttachment
import org.stalker.securesms.components.emoji.EmojiUtil
import org.stalker.securesms.contactshare.Contact
import org.stalker.securesms.crypto.SecurityEvent
import org.stalker.securesms.database.CallLinkTable
import org.stalker.securesms.database.CallTable
import org.stalker.securesms.database.GroupReceiptTable
import org.stalker.securesms.database.GroupTable
import org.stalker.securesms.database.MessageTable
import org.stalker.securesms.database.MessageTable.MarkedMessageInfo
import org.stalker.securesms.database.NoSuchMessageException
import org.stalker.securesms.database.PaymentMetaDataUtil
import org.stalker.securesms.database.SentStorySyncManifest
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.DistributionListId
import org.stalker.securesms.database.model.Mention
import org.stalker.securesms.database.model.MessageRecord
import org.stalker.securesms.database.model.MmsMessageRecord
import org.stalker.securesms.database.model.ParentStoryId
import org.stalker.securesms.database.model.ParentStoryId.DirectReply
import org.stalker.securesms.database.model.ParentStoryId.GroupReply
import org.stalker.securesms.database.model.StoryType
import org.stalker.securesms.database.model.databaseprotos.BodyRangeList
import org.stalker.securesms.database.model.databaseprotos.GiftBadge
import org.stalker.securesms.database.model.toBodyRangeList
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.groups.BadGroupIdException
import org.stalker.securesms.groups.GroupChangeBusyException
import org.stalker.securesms.groups.GroupId
import org.stalker.securesms.jobs.AttachmentDownloadJob
import org.stalker.securesms.jobs.MultiDeviceBlockedUpdateJob
import org.stalker.securesms.jobs.MultiDeviceConfigurationUpdateJob
import org.stalker.securesms.jobs.MultiDeviceContactSyncJob
import org.stalker.securesms.jobs.MultiDeviceContactUpdateJob
import org.stalker.securesms.jobs.MultiDeviceKeysUpdateJob
import org.stalker.securesms.jobs.MultiDeviceStickerPackSyncJob
import org.stalker.securesms.jobs.PushProcessEarlyMessagesJob
import org.stalker.securesms.jobs.RefreshCallLinkDetailsJob
import org.stalker.securesms.jobs.RefreshOwnProfileJob
import org.stalker.securesms.jobs.StickerPackDownloadJob
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.linkpreview.LinkPreview
import org.stalker.securesms.messages.MessageContentProcessor.Companion.log
import org.stalker.securesms.messages.MessageContentProcessor.Companion.warn
import org.stalker.securesms.messages.SignalServiceProtoUtil.expireTimerDuration
import org.stalker.securesms.messages.SignalServiceProtoUtil.groupId
import org.stalker.securesms.messages.SignalServiceProtoUtil.groupMasterKey
import org.stalker.securesms.messages.SignalServiceProtoUtil.hasGroupContext
import org.stalker.securesms.messages.SignalServiceProtoUtil.hasRemoteDelete
import org.stalker.securesms.messages.SignalServiceProtoUtil.isEmptyGroupV2Message
import org.stalker.securesms.messages.SignalServiceProtoUtil.isEndSession
import org.stalker.securesms.messages.SignalServiceProtoUtil.isExpirationUpdate
import org.stalker.securesms.messages.SignalServiceProtoUtil.isGroupV2Update
import org.stalker.securesms.messages.SignalServiceProtoUtil.isMediaMessage
import org.stalker.securesms.messages.SignalServiceProtoUtil.isUnidentified
import org.stalker.securesms.messages.SignalServiceProtoUtil.serviceIdsToUnidentifiedStatus
import org.stalker.securesms.messages.SignalServiceProtoUtil.toMobileCoinMoney
import org.stalker.securesms.messages.SignalServiceProtoUtil.toPointer
import org.stalker.securesms.messages.SignalServiceProtoUtil.toPointersWithinLimit
import org.stalker.securesms.messages.SignalServiceProtoUtil.toSignalServiceAttachmentPointer
import org.stalker.securesms.messages.SignalServiceProtoUtil.type
import org.stalker.securesms.mms.MmsException
import org.stalker.securesms.mms.OutgoingMessage
import org.stalker.securesms.mms.QuoteModel
import org.stalker.securesms.notifications.MarkReadReceiver
import org.stalker.securesms.payments.MobileCoinPublicAddress
import org.stalker.securesms.ratelimit.RateLimitUtil
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.service.webrtc.links.CallLinkCredentials
import org.stalker.securesms.service.webrtc.links.CallLinkRoomId
import org.stalker.securesms.service.webrtc.links.SignalCallLinkState
import org.stalker.securesms.storage.StorageSyncHelper
import org.stalker.securesms.stories.Stories
import org.stalker.securesms.util.EarlyMessageCacheEntry
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.IdentityUtil
import org.stalker.securesms.util.MediaUtil
import org.stalker.securesms.util.MessageConstraintsUtil
import org.stalker.securesms.util.TextSecurePreferences
import org.stalker.securesms.util.Util
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.push.DistributionId
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.api.push.ServiceId.PNI
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.storage.StorageKey
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.EditMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.StoryMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import org.whispersystems.signalservice.internal.push.SyncMessage.Blocked
import org.whispersystems.signalservice.internal.push.SyncMessage.CallLinkUpdate
import org.whispersystems.signalservice.internal.push.SyncMessage.CallLogEvent
import org.whispersystems.signalservice.internal.push.SyncMessage.Configuration
import org.whispersystems.signalservice.internal.push.SyncMessage.FetchLatest
import org.whispersystems.signalservice.internal.push.SyncMessage.MessageRequestResponse
import org.whispersystems.signalservice.internal.push.SyncMessage.Read
import org.whispersystems.signalservice.internal.push.SyncMessage.Request
import org.whispersystems.signalservice.internal.push.SyncMessage.Sent
import org.whispersystems.signalservice.internal.push.SyncMessage.StickerPackOperation
import org.whispersystems.signalservice.internal.push.SyncMessage.ViewOnceOpen
import org.whispersystems.signalservice.internal.push.Verified
import java.io.IOException
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

object SyncMessageProcessor {

  fun process(
    context: Context,
    senderRecipient: Recipient,
    envelope: Envelope,
    content: Content,
    metadata: EnvelopeMetadata,
    earlyMessageCacheEntry: EarlyMessageCacheEntry?
  ) {
    val syncMessage = content.syncMessage!!

    when {
      syncMessage.sent != null -> handleSynchronizeSentMessage(context, envelope, content, metadata, syncMessage.sent!!, senderRecipient, earlyMessageCacheEntry)
      syncMessage.request != null -> handleSynchronizeRequestMessage(context, syncMessage.request!!, envelope.timestamp!!)
      syncMessage.read.isNotEmpty() -> handleSynchronizeReadMessage(context, syncMessage.read, envelope.timestamp!!, earlyMessageCacheEntry)
      syncMessage.viewed.isNotEmpty() -> handleSynchronizeViewedMessage(context, syncMessage.viewed, envelope.timestamp!!)
      syncMessage.viewOnceOpen != null -> handleSynchronizeViewOnceOpenMessage(context, syncMessage.viewOnceOpen!!, envelope.timestamp!!, earlyMessageCacheEntry)
      syncMessage.verified != null -> handleSynchronizeVerifiedMessage(context, syncMessage.verified!!)
      syncMessage.stickerPackOperation.isNotEmpty() -> handleSynchronizeStickerPackOperation(syncMessage.stickerPackOperation, envelope.timestamp!!)
      syncMessage.configuration != null -> handleSynchronizeConfigurationMessage(context, syncMessage.configuration!!, envelope.timestamp!!)
      syncMessage.blocked != null -> handleSynchronizeBlockedListMessage(syncMessage.blocked!!)
      syncMessage.fetchLatest?.type != null -> handleSynchronizeFetchMessage(syncMessage.fetchLatest!!.type!!, envelope.timestamp!!)
      syncMessage.messageRequestResponse != null -> handleSynchronizeMessageRequestResponse(syncMessage.messageRequestResponse!!, envelope.timestamp!!)
      syncMessage.outgoingPayment != null -> handleSynchronizeOutgoingPayment(syncMessage.outgoingPayment!!, envelope.timestamp!!)
      syncMessage.keys?.storageService != null -> handleSynchronizeKeys(syncMessage.keys!!.storageService!!, envelope.timestamp!!)
      syncMessage.contacts != null -> handleSynchronizeContacts(syncMessage.contacts!!, envelope.timestamp!!)
      syncMessage.callEvent != null -> handleSynchronizeCallEvent(syncMessage.callEvent!!, envelope.timestamp!!)
      syncMessage.callLinkUpdate != null -> handleSynchronizeCallLink(syncMessage.callLinkUpdate!!, envelope.timestamp!!)
      syncMessage.callLogEvent != null -> handleSynchronizeCallLogEvent(syncMessage.callLogEvent!!, envelope.timestamp!!)
      else -> warn(envelope.timestamp!!, "Contains no known sync types...")
    }
  }

  @Throws(StorageFailedException::class, BadGroupIdException::class, IOException::class, GroupChangeBusyException::class)
  private fun handleSynchronizeSentMessage(
    context: Context,
    envelope: Envelope,
    content: Content,
    metadata: EnvelopeMetadata,
    sent: Sent,
    senderRecipient: Recipient,
    earlyMessageCacheEntry: EarlyMessageCacheEntry?
  ) {
    log(envelope.timestamp!!, "Processing sent transcript for message with ID ${sent.timestamp!!}")

    try {
      handlePniIdentityKeys(envelope, sent)

      if (sent.storyMessage != null || sent.storyMessageRecipients.isNotEmpty()) {
        handleSynchronizeSentStoryMessage(envelope, sent)
        return
      }

      if (sent.editMessage != null) {
        handleSynchronizeSentEditMessage(context, envelope, sent, senderRecipient, earlyMessageCacheEntry)
        return
      }

      if (sent.isRecipientUpdate == true) {
        handleGroupRecipientUpdate(sent, envelope.timestamp!!)
        return
      }

      val dataMessage = if (sent.message != null) {
        sent.message!!
      } else {
        warn(envelope.timestamp!!, "Sync message missing nested message to sync")
        return
      }

      val groupId: GroupId.V2? = if (dataMessage.hasGroupContext) GroupId.v2(dataMessage.groupV2!!.groupMasterKey) else null

      if (groupId != null) {
        if (MessageContentProcessor.handleGv2PreProcessing(context, envelope.timestamp!!, content, metadata, groupId, dataMessage.groupV2!!, senderRecipient) == MessageContentProcessor.Gv2PreProcessResult.IGNORE) {
          return
        }
      }

      var threadId: Long = -1
      when {
        dataMessage.isEndSession -> threadId = handleSynchronizeSentEndSessionMessage(context, sent, envelope.timestamp!!)
        dataMessage.isGroupV2Update -> {
          handleSynchronizeSentGv2Update(context, envelope, sent)
          threadId = SignalDatabase.threads.getOrCreateThreadIdFor(getSyncMessageDestination(sent))
        }
        dataMessage.groupCallUpdate != null -> DataMessageProcessor.handleGroupCallUpdateMessage(envelope, dataMessage, senderRecipient.id, groupId)
        dataMessage.isEmptyGroupV2Message -> warn(envelope.timestamp!!, "Empty GV2 message! Doing nothing.")
        dataMessage.isExpirationUpdate -> threadId = handleSynchronizeSentExpirationUpdate(sent)
        dataMessage.storyContext != null -> threadId = handleSynchronizeSentStoryReply(sent, envelope.timestamp!!)
        dataMessage.reaction != null -> {
          DataMessageProcessor.handleReaction(context, envelope, dataMessage, senderRecipient.id, earlyMessageCacheEntry)
          threadId = SignalDatabase.threads.getOrCreateThreadIdFor(getSyncMessageDestination(sent))
        }
        dataMessage.hasRemoteDelete -> DataMessageProcessor.handleRemoteDelete(context, envelope, dataMessage, senderRecipient.id, earlyMessageCacheEntry)
        dataMessage.isMediaMessage -> threadId = handleSynchronizeSentMediaMessage(context, sent, envelope.timestamp!!)
        else -> threadId = handleSynchronizeSentTextMessage(sent, envelope.timestamp!!)
      }

      if (groupId != null && SignalDatabase.groups.isUnknownGroup(groupId)) {
        DataMessageProcessor.handleUnknownGroupMessage(envelope.timestamp!!, dataMessage.groupV2!!)
      }

      if (dataMessage.profileKey.isNotEmpty()) {
        val recipient: Recipient = getSyncMessageDestination(sent)
        if (!recipient.isSystemContact && !recipient.isProfileSharing) {
          SignalDatabase.recipients.setProfileSharing(recipient.id, true)
        }
      }

      if (threadId != -1L) {
        SignalDatabase.threads.setRead(threadId, true)
        ApplicationDependencies.getMessageNotifier().updateNotification(context)
      }

      if (SignalStore.rateLimit().needsRecaptcha()) {
        log(envelope.timestamp!!, "Got a sent transcript while in reCAPTCHA mode. Assuming we're good to message again.")
        RateLimitUtil.retryAllRateLimitedMessages(context)
      }

      ApplicationDependencies.getMessageNotifier().setLastDesktopActivityTimestamp(sent.timestamp!!)
    } catch (e: MmsException) {
      throw StorageFailedException(e, metadata.sourceServiceId.toString(), metadata.sourceDeviceId)
    }
  }

  private fun handlePniIdentityKeys(envelope: Envelope, sent: Sent) {
    for (status in sent.unidentifiedStatus) {
      if (status.destinationIdentityKey == null) {
        continue
      }

      val pni = PNI.parsePrefixedOrNull(status.destinationServiceId)
      if (pni == null) {
        continue
      }

      val address = SignalProtocolAddress(pni.toString(), SignalServiceAddress.DEFAULT_DEVICE_ID)

      if (ApplicationDependencies.getProtocolStore().aci().identities().getIdentity(address) != null) {
        log(envelope.timestamp!!, "Ignoring identity on sent transcript for $pni because we already have one.")
        continue
      }

      try {
        log(envelope.timestamp!!, "Saving identity from sent transcript for $pni")
        val identityKey = IdentityKey(status.destinationIdentityKey!!.toByteArray())
        ApplicationDependencies.getProtocolStore().aci().identities().saveIdentity(address, identityKey)
      } catch (e: InvalidKeyException) {
        warn(envelope.timestamp!!, "Failed to deserialize identity key for $pni")
      }
    }
  }

  private fun getSyncMessageDestination(message: Sent): Recipient {
    return if (message.message.hasGroupContext) {
      Recipient.externalPossiblyMigratedGroup(GroupId.v2(message.message!!.groupV2!!.groupMasterKey))
    } else {
      Recipient.externalPush(SignalServiceAddress(ServiceId.parseOrThrow(message.destinationServiceId!!), message.destinationE164))
    }
  }

  @Throws(MmsException::class)
  private fun handleSynchronizeSentEditMessage(
    context: Context,
    envelope: Envelope,
    sent: Sent,
    senderRecipient: Recipient,
    earlyMessageCacheEntry: EarlyMessageCacheEntry?
  ) {
    val editMessage: EditMessage = sent.editMessage!!
    val targetSentTimestamp: Long = editMessage.targetSentTimestamp!!
    val targetMessage: MessageRecord? = SignalDatabase.messages.getMessageFor(targetSentTimestamp, senderRecipient.id)
    val senderRecipientId = senderRecipient.id

    if (targetMessage == null) {
      warn(envelope.timestamp!!, "[handleSynchronizeSentEditMessage] Could not find matching message! targetTimestamp: $targetSentTimestamp  author: $senderRecipientId")
      if (earlyMessageCacheEntry != null) {
        ApplicationDependencies.getEarlyMessageCache().store(senderRecipientId, targetSentTimestamp, earlyMessageCacheEntry)
        PushProcessEarlyMessagesJob.enqueue()
      }
    } else if (MessageConstraintsUtil.isValidEditMessageReceive(targetMessage, senderRecipient, envelope.serverTimestamp!!)) {
      val message: DataMessage = editMessage.dataMessage!!
      val toRecipient: Recipient = if (message.hasGroupContext) {
        Recipient.externalPossiblyMigratedGroup(GroupId.v2(message.groupV2!!.groupMasterKey))
      } else {
        Recipient.externalPush(ServiceId.parseOrThrow(sent.destinationServiceId!!))
      }

      if (message.isMediaMessage) {
        handleSynchronizeSentEditMediaMessage(context, targetMessage, toRecipient, sent, message, envelope.timestamp!!)
      } else {
        handleSynchronizeSentEditTextMessage(targetMessage, toRecipient, sent, message, envelope.timestamp!!)
      }
    } else {
      warn(envelope.timestamp!!, "[handleSynchronizeSentEditMessage] Invalid message edit! editTime: ${envelope.serverTimestamp}, targetTime: ${targetMessage.serverTimestamp}, sendAuthor: $senderRecipientId, targetAuthor: ${targetMessage.fromRecipient.id}")
    }
  }

  private fun handleSynchronizeSentEditTextMessage(
    targetMessage: MessageRecord,
    toRecipient: Recipient,
    sent: Sent,
    message: DataMessage,
    envelopeTimestamp: Long
  ) {
    log(envelopeTimestamp, "Synchronize sent edit text message for message: ${targetMessage.id}")

    val body = message.body ?: ""
    val bodyRanges = message.bodyRanges.filter { it.mentionAci == null }.toBodyRangeList()

    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(toRecipient)
    val isGroup = toRecipient.isGroup
    val messageId: Long

    if (isGroup) {
      val outgoingMessage = OutgoingMessage(
        recipient = toRecipient,
        body = body,
        timestamp = sent.timestamp!!,
        expiresIn = targetMessage.expiresIn,
        isSecure = true,
        bodyRanges = bodyRanges,
        messageToEdit = targetMessage.id
      )

      messageId = SignalDatabase.messages.insertMessageOutbox(outgoingMessage, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null)
      updateGroupReceiptStatus(sent, messageId, toRecipient.requireGroupId())
    } else {
      val outgoingTextMessage = OutgoingMessage(
        threadRecipient = toRecipient,
        sentTimeMillis = sent.timestamp!!,
        body = body,
        expiresIn = targetMessage.expiresIn,
        isUrgent = true,
        isSecure = true,
        bodyRanges = bodyRanges,
        messageToEdit = targetMessage.id
      )
      messageId = SignalDatabase.messages.insertMessageOutbox(outgoingTextMessage, threadId, false, null)
      SignalDatabase.messages.markUnidentified(messageId, sent.isUnidentified(toRecipient.serviceId.orNull()))
    }

    SignalDatabase.messages.markAsSent(messageId, true)
    if (targetMessage.expireStarted > 0) {
      SignalDatabase.messages.markExpireStarted(messageId, targetMessage.expireStarted)
      ApplicationDependencies.getExpiringMessageManager().scheduleDeletion(messageId, true, targetMessage.expireStarted, targetMessage.expireStarted)
    }

    if (toRecipient.isSelf) {
      SignalDatabase.messages.incrementDeliveryReceiptCount(sent.timestamp!!, toRecipient.id, System.currentTimeMillis())
      SignalDatabase.messages.incrementReadReceiptCount(sent.timestamp!!, toRecipient.id, System.currentTimeMillis())
    }
  }

  private fun handleSynchronizeSentEditMediaMessage(
    context: Context,
    targetMessage: MessageRecord,
    toRecipient: Recipient,
    sent: Sent,
    message: DataMessage,
    envelopeTimestamp: Long
  ) {
    log(envelopeTimestamp, "Synchronize sent edit media message for: ${targetMessage.id}")

    val quote: QuoteModel? = DataMessageProcessor.getValidatedQuote(context, envelopeTimestamp, message)
    val sharedContacts: List<Contact> = DataMessageProcessor.getContacts(message)
    val previews: List<LinkPreview> = DataMessageProcessor.getLinkPreviews(message.preview, message.body ?: "", false)
    val mentions: List<Mention> = DataMessageProcessor.getMentions(message.bodyRanges)
    val viewOnce: Boolean = message.isViewOnce == true
    val bodyRanges: BodyRangeList? = message.bodyRanges.toBodyRangeList()

    val syncAttachments = message.attachments.toPointersWithinLimit().filter {
      MediaUtil.SlideType.LONG_TEXT == MediaUtil.getSlideTypeFromContentType(it.contentType)
    }

    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(toRecipient)
    val mediaMessage = OutgoingMessage(
      recipient = toRecipient,
      body = message.body ?: "",
      attachments = syncAttachments.ifEmpty { (targetMessage as? MmsMessageRecord)?.slideDeck?.asAttachments() ?: emptyList() },
      timestamp = sent.timestamp!!,
      expiresIn = targetMessage.expiresIn,
      viewOnce = viewOnce,
      quote = quote,
      contacts = sharedContacts,
      previews = previews,
      mentions = mentions,
      bodyRanges = bodyRanges,
      isSecure = true,
      messageToEdit = targetMessage.id
    )

    val messageId: Long = SignalDatabase.messages.insertMessageOutbox(mediaMessage, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null)

    if (toRecipient.isGroup) {
      updateGroupReceiptStatus(sent, messageId, toRecipient.requireGroupId())
    } else {
      SignalDatabase.messages.markUnidentified(messageId, sent.isUnidentified(toRecipient.serviceId.orNull()))
    }

    SignalDatabase.messages.markAsSent(messageId, true)

    val attachments: List<DatabaseAttachment> = SignalDatabase.attachments.getAttachmentsForMessage(messageId)

    if (targetMessage.expireStarted > 0) {
      SignalDatabase.messages.markExpireStarted(messageId, targetMessage.expireStarted)
      ApplicationDependencies.getExpiringMessageManager().scheduleDeletion(messageId, true, targetMessage.expireStarted, targetMessage.expireStarted)
    }

    if (toRecipient.isSelf) {
      SignalDatabase.messages.incrementDeliveryReceiptCount(sent.timestamp!!, toRecipient.id, System.currentTimeMillis())
      SignalDatabase.messages.incrementReadReceiptCount(sent.timestamp!!, toRecipient.id, System.currentTimeMillis())
    }

    if (syncAttachments.isNotEmpty()) {
      SignalDatabase.runPostSuccessfulTransaction {
        for (attachment in attachments) {
          ApplicationDependencies.getJobManager().add(AttachmentDownloadJob(messageId, attachment.attachmentId, false))
        }
      }
    }
  }

  @Throws(MmsException::class)
  private fun handleSynchronizeSentStoryMessage(envelope: Envelope, sent: Sent) {
    log(envelope.timestamp!!, "Synchronize sent story message for " + sent.timestamp)

    val manifest = SentStorySyncManifest.fromRecipientsSet(sent.storyMessageRecipients)

    if (sent.isRecipientUpdate == true) {
      log(envelope.timestamp!!, "Processing recipient update for story message and exiting...")
      SignalDatabase.storySends.applySentStoryManifest(manifest, sent.timestamp!!)
      return
    }

    val storyMessage: StoryMessage = sent.storyMessage!!
    val distributionIds: Set<DistributionId> = manifest.getDistributionIdSet()
    val groupId: GroupId.V2? = storyMessage.group?.groupId
    val textStoryBody: String? = StoryMessageProcessor.serializeTextAttachment(storyMessage)
    val bodyRanges: BodyRangeList? = storyMessage.bodyRanges.toBodyRangeList()
    val storyType: StoryType = storyMessage.type

    val linkPreviews: List<LinkPreview> = DataMessageProcessor.getLinkPreviews(
      previews = listOfNotNull(storyMessage.textAttachment?.preview),
      body = "",
      isStoryEmbed = true
    )

    val attachments: List<Attachment> = listOfNotNull(storyMessage.fileAttachment?.toPointer())

    for (distributionId in distributionIds) {
      val distributionRecipientId = SignalDatabase.distributionLists.getOrCreateByDistributionId(distributionId, manifest)
      val distributionListRecipient = Recipient.resolved(distributionRecipientId)
      insertSentStoryMessage(sent, distributionListRecipient, null, textStoryBody, attachments, sent.timestamp!!, storyType, linkPreviews, bodyRanges)
    }

    if (groupId != null) {
      val groupRecipient: Optional<RecipientId> = SignalDatabase.recipients.getByGroupId(groupId)
      if (groupRecipient.isPresent) {
        insertSentStoryMessage(sent, Recipient.resolved(groupRecipient.get()), groupId, textStoryBody, attachments, sent.timestamp!!, storyType, linkPreviews, bodyRanges)
      }
    }

    SignalDatabase.storySends.applySentStoryManifest(manifest, sent.timestamp!!)
  }

  @Throws(MmsException::class)
  private fun insertSentStoryMessage(
    sent: Sent,
    recipient: Recipient,
    groupId: GroupId.V2?,
    textStoryBody: String?,
    pendingAttachments: List<Attachment>,
    sentAtTimestamp: Long,
    storyType: StoryType,
    linkPreviews: List<LinkPreview>,
    bodyRanges: BodyRangeList?
  ) {
    if (SignalDatabase.messages.isOutgoingStoryAlreadyInDatabase(recipient.id, sentAtTimestamp)) {
      warn(sentAtTimestamp, "Already inserted this story.")
      return
    }

    val mediaMessage = OutgoingMessage(
      recipient = recipient,
      body = textStoryBody,
      attachments = pendingAttachments,
      timestamp = sentAtTimestamp,
      storyType = storyType,
      previews = linkPreviews,
      bodyRanges = bodyRanges,
      isSecure = true
    )

    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId: Long = SignalDatabase.messages.insertMessageOutbox(mediaMessage, threadId, false, GroupReceiptTable.STATUS_UNDELIVERED, null)

    if (groupId != null) {
      updateGroupReceiptStatus(sent, messageId, recipient.requireGroupId())
    } else if (recipient.distributionListId.isPresent) {
      updateGroupReceiptStatusForDistributionList(sent, messageId, recipient.distributionListId.get())
    } else {
      SignalDatabase.messages.markUnidentified(messageId, sent.isUnidentified(recipient.serviceId.orNull()))
    }

    SignalDatabase.messages.markAsSent(messageId, true)

    val allAttachments = SignalDatabase.attachments.getAttachmentsForMessage(messageId)
    val attachments: List<DatabaseAttachment> = allAttachments.filterNot { it.isSticker }

    if (recipient.isSelf) {
      SignalDatabase.messages.incrementDeliveryReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
      SignalDatabase.messages.incrementReadReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
    }

    SignalDatabase.runPostSuccessfulTransaction {
      for (attachment in attachments) {
        ApplicationDependencies.getJobManager().add(AttachmentDownloadJob(messageId, attachment.attachmentId, false))
      }
    }
  }

  private fun handleGroupRecipientUpdate(sent: Sent, envelopeTimestamp: Long) {
    log(envelopeTimestamp, "Group recipient update.")

    val recipient = getSyncMessageDestination(sent)
    if (!recipient.isGroup) {
      warn("Got recipient update for a non-group message! Skipping.")
      return
    }

    val record = SignalDatabase.messages.getMessageFor(sent.timestamp!!, Recipient.self().id)
    if (record == null) {
      warn("Got recipient update for non-existing message! Skipping.")
      return
    }

    updateGroupReceiptStatus(sent, record.id, recipient.requireGroupId())
  }

  private fun updateGroupReceiptStatus(sent: Sent, messageId: Long, groupString: GroupId) {
    val messageRecipientIds: Map<RecipientId, Boolean> = sent.serviceIdsToUnidentifiedStatus.mapKeys { RecipientId.from(it.key) }
    val members: List<RecipientId> = SignalDatabase.groups.getGroupMembers(groupString, GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF).map { it.id }
    val localReceipts: Map<RecipientId, Int> = SignalDatabase.groupReceipts.getGroupReceiptInfo(messageId).associate { it.recipientId to it.status }

    for (messageRecipientId in messageRecipientIds.keys) {
      if ((localReceipts[messageRecipientId] ?: GroupReceiptTable.STATUS_UNKNOWN) < GroupReceiptTable.STATUS_UNDELIVERED) {
        SignalDatabase.groupReceipts.update(messageRecipientId, messageId, GroupReceiptTable.STATUS_UNDELIVERED, sent.timestamp!!)
      } else if (!localReceipts.containsKey(messageRecipientId)) {
        SignalDatabase.groupReceipts.insert(listOf(messageRecipientId), messageId, GroupReceiptTable.STATUS_UNDELIVERED, sent.timestamp!!)
      }
    }

    val unidentifiedStatus = members.map { Pair(it, messageRecipientIds[it] ?: false) }

    SignalDatabase.groupReceipts.setUnidentified(unidentifiedStatus, messageId)
  }

  private fun updateGroupReceiptStatusForDistributionList(sent: Sent, messageId: Long, distributionListId: DistributionListId) {
    val messageRecipientIds: Map<RecipientId, Boolean> = sent.serviceIdsToUnidentifiedStatus.mapKeys { RecipientId.from(it.key) }
    val members: List<RecipientId> = SignalDatabase.distributionLists.getMembers(distributionListId)
    val localReceipts: Map<RecipientId, Int> = SignalDatabase.groupReceipts.getGroupReceiptInfo(messageId).associate { it.recipientId to it.status }

    for (messageRecipientId in messageRecipientIds.keys) {
      if ((localReceipts[messageRecipientId] ?: GroupReceiptTable.STATUS_UNKNOWN) < GroupReceiptTable.STATUS_UNDELIVERED) {
        SignalDatabase.groupReceipts.update(messageRecipientId, messageId, GroupReceiptTable.STATUS_UNDELIVERED, sent.timestamp!!)
      } else if (!localReceipts.containsKey(messageRecipientId)) {
        SignalDatabase.groupReceipts.insert(listOf(messageRecipientId), messageId, GroupReceiptTable.STATUS_UNDELIVERED, sent.timestamp!!)
      }
    }

    val unidentifiedStatus = members.map { Pair(it, messageRecipientIds[it] ?: false) }

    SignalDatabase.groupReceipts.setUnidentified(unidentifiedStatus, messageId)
  }

  @Throws(MmsException::class)
  private fun handleSynchronizeSentEndSessionMessage(context: Context, sent: Sent, envelopeTimestamp: Long): Long {
    log(envelopeTimestamp, "Synchronize end session message.")

    val recipient: Recipient = getSyncMessageDestination(sent)
    val outgoingEndSessionMessage: OutgoingMessage = OutgoingMessage.endSessionMessage(recipient, sent.timestamp!!)
    val threadId: Long = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

    if (!recipient.isGroup) {
      ApplicationDependencies.getProtocolStore().aci().deleteAllSessions(recipient.requireServiceId().toString())
      SecurityEvent.broadcastSecurityUpdateEvent(context)
      val messageId = SignalDatabase.messages.insertMessageOutbox(
        outgoingEndSessionMessage,
        threadId,
        false,
        null
      )

      SignalDatabase.messages.markAsSent(messageId, true)
    }

    return threadId
  }

  @Throws(IOException::class, GroupChangeBusyException::class)
  private fun handleSynchronizeSentGv2Update(context: Context, envelope: Envelope, sent: Sent) {
    log(envelope.timestamp!!, "Synchronize sent GV2 update for message with timestamp " + sent.timestamp!!)

    val dataMessage: DataMessage = sent.message!!
    val groupId: GroupId.V2? = dataMessage.groupV2?.groupId

    if (groupId == null) {
      warn(envelope.timestamp!!, "GV2 update missing group id")
      return
    }

    if (MessageContentProcessor.updateGv2GroupFromServerOrP2PChange(context, envelope.timestamp!!, dataMessage.groupV2!!, SignalDatabase.groups.getGroup(groupId)) == null) {
      log(envelope.timestamp!!, "Ignoring GV2 message for group we are not currently in $groupId")
    }
  }

  @Throws(MmsException::class)
  private fun handleSynchronizeSentExpirationUpdate(sent: Sent, sideEffect: Boolean = false): Long {
    log(sent.timestamp!!, "Synchronize sent expiration update.")

    val groupId: GroupId? = getSyncMessageDestination(sent).groupId.orNull()

    if (groupId != null && groupId.isV2) {
      warn(sent.timestamp!!, "Expiration update received for GV2. Ignoring.")
      return -1
    }

    val recipient: Recipient = getSyncMessageDestination(sent)
    val expirationUpdateMessage: OutgoingMessage = OutgoingMessage.expirationUpdateMessage(recipient, if (sideEffect) sent.timestamp!! - 1 else sent.timestamp!!, sent.message!!.expireTimerDuration.inWholeMilliseconds)
    val threadId: Long = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId: Long = SignalDatabase.messages.insertMessageOutbox(expirationUpdateMessage, threadId, false, null)

    SignalDatabase.messages.markAsSent(messageId, true)

    SignalDatabase.recipients.setExpireMessages(recipient.id, sent.message!!.expireTimerDuration.inWholeSeconds.toInt())

    return threadId
  }

  @Throws(MmsException::class, BadGroupIdException::class)
  private fun handleSynchronizeSentStoryReply(sent: Sent, envelopeTimestamp: Long): Long {
    log(envelopeTimestamp, "Synchronize sent story reply for " + sent.timestamp!!)

    try {
      val dataMessage: DataMessage = sent.message!!
      val storyContext: DataMessage.StoryContext = dataMessage.storyContext!!

      val reaction: DataMessage.Reaction? = dataMessage.reaction
      val parentStoryId: ParentStoryId
      val authorServiceId: ServiceId = ServiceId.parseOrThrow(storyContext.authorAci!!)
      val sentTimestamp: Long = storyContext.sentTimestamp!!
      val recipient: Recipient = getSyncMessageDestination(sent)
      var quoteModel: QuoteModel? = null
      var expiresInMillis = 0L
      val storyAuthorRecipient: RecipientId = RecipientId.from(authorServiceId)
      val storyMessageId: Long = SignalDatabase.messages.getStoryId(storyAuthorRecipient, sentTimestamp).id
      val story: MmsMessageRecord = SignalDatabase.messages.getMessageRecord(storyMessageId) as MmsMessageRecord
      val threadRecipientId: RecipientId? = SignalDatabase.threads.getRecipientForThreadId(story.threadId)?.id
      val groupStory: Boolean = threadRecipientId != null && (SignalDatabase.groups.getGroup(threadRecipientId).orNull()?.isActive ?: false)
      var bodyRanges: BodyRangeList? = null

      val body: String? = if (EmojiUtil.isEmoji(reaction?.emoji)) {
        reaction!!.emoji
      } else if (dataMessage.body != null) {
        bodyRanges = dataMessage.bodyRanges.toBodyRangeList()
        dataMessage.body
      } else {
        null
      }

      if (dataMessage.hasGroupContext) {
        parentStoryId = GroupReply(storyMessageId)
      } else if (groupStory || story.storyType.isStoryWithReplies) {
        parentStoryId = DirectReply(storyMessageId)

        var quoteBody = ""
        var bodyBodyRanges: BodyRangeList? = null
        if (story.storyType.isTextStory) {
          quoteBody = story.body
          bodyBodyRanges = story.messageRanges
        }
        quoteModel = QuoteModel(sentTimestamp, storyAuthorRecipient, quoteBody, false, story.slideDeck.asAttachments(), emptyList(), QuoteModel.Type.NORMAL, bodyBodyRanges)
        expiresInMillis = dataMessage.expireTimerDuration.inWholeMilliseconds
      } else {
        warn(envelopeTimestamp, "Story has replies disabled. Dropping reply.")
        return -1L
      }

      val mediaMessage = OutgoingMessage(
        recipient = recipient,
        body = body,
        timestamp = sent.timestamp!!,
        expiresIn = expiresInMillis,
        parentStoryId = parentStoryId,
        isStoryReaction = reaction != null,
        quote = quoteModel,
        mentions = DataMessageProcessor.getMentions(dataMessage.bodyRanges),
        bodyRanges = bodyRanges,
        isSecure = true
      )

      if (recipient.expiresInSeconds != dataMessage.expireTimerDuration.inWholeSeconds.toInt()) {
        handleSynchronizeSentExpirationUpdate(sent, sideEffect = true)
      }

      val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
      val messageId: Long = SignalDatabase.messages.insertMessageOutbox(mediaMessage, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null)

      if (recipient.isGroup) {
        updateGroupReceiptStatus(sent, messageId, recipient.requireGroupId())
      } else {
        SignalDatabase.messages.markUnidentified(messageId, sent.isUnidentified(recipient.serviceId.orNull()))
      }

      SignalDatabase.messages.markAsSent(messageId, true)
      if (dataMessage.expireTimerDuration > Duration.ZERO) {
        SignalDatabase.messages.markExpireStarted(messageId, sent.expirationStartTimestamp ?: 0)

        ApplicationDependencies
          .getExpiringMessageManager()
          .scheduleDeletion(messageId, true, sent.expirationStartTimestamp ?: 0, dataMessage.expireTimerDuration.inWholeMilliseconds)
      }
      if (recipient.isSelf) {
        SignalDatabase.messages.incrementDeliveryReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
        SignalDatabase.messages.incrementReadReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
      }

      return threadId
    } catch (e: NoSuchMessageException) {
      warn(envelopeTimestamp, "Couldn't find story for reply.", e)
      return -1L
    }
  }

  @Throws(MmsException::class, BadGroupIdException::class)
  private fun handleSynchronizeSentMediaMessage(context: Context, sent: Sent, envelopeTimestamp: Long): Long {
    log(envelopeTimestamp, "Synchronize sent media message for " + sent.timestamp!!)

    val recipient: Recipient = getSyncMessageDestination(sent)
    val dataMessage: DataMessage = sent.message!!
    val quote: QuoteModel? = DataMessageProcessor.getValidatedQuote(context, envelopeTimestamp, dataMessage)
    val sticker: Attachment? = DataMessageProcessor.getStickerAttachment(envelopeTimestamp, dataMessage)
    val sharedContacts: List<Contact> = DataMessageProcessor.getContacts(dataMessage)
    val previews: List<LinkPreview> = DataMessageProcessor.getLinkPreviews(dataMessage.preview, dataMessage.body ?: "", false)
    val mentions: List<Mention> = DataMessageProcessor.getMentions(dataMessage.bodyRanges)
    val giftBadge: GiftBadge? = if (dataMessage.giftBadge?.receiptCredentialPresentation != null) GiftBadge.Builder().redemptionToken(dataMessage.giftBadge!!.receiptCredentialPresentation!!).build() else null
    val viewOnce: Boolean = dataMessage.isViewOnce == true
    val bodyRanges: BodyRangeList? = dataMessage.bodyRanges.toBodyRangeList()
    val syncAttachments: List<Attachment> = listOfNotNull(sticker) + if (viewOnce) listOf<Attachment>(TombstoneAttachment(MediaUtil.VIEW_ONCE, false)) else dataMessage.attachments.toPointersWithinLimit()

    val mediaMessage = OutgoingMessage(
      recipient = recipient,
      body = dataMessage.body ?: "",
      attachments = syncAttachments,
      timestamp = sent.timestamp!!,
      expiresIn = dataMessage.expireTimerDuration.inWholeMilliseconds,
      viewOnce = viewOnce,
      quote = quote,
      contacts = sharedContacts,
      previews = previews,
      mentions = mentions,
      giftBadge = giftBadge,
      bodyRanges = bodyRanges,
      isSecure = true
    )

    if (recipient.expiresInSeconds != dataMessage.expireTimerDuration.inWholeSeconds.toInt()) {
      handleSynchronizeSentExpirationUpdate(sent, sideEffect = true)
    }

    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId: Long = SignalDatabase.messages.insertMessageOutbox(mediaMessage, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null)

    if (recipient.isGroup) {
      updateGroupReceiptStatus(sent, messageId, recipient.requireGroupId())
    } else {
      SignalDatabase.messages.markUnidentified(messageId, sent.isUnidentified(recipient.serviceId.orNull()))
    }

    SignalDatabase.messages.markAsSent(messageId, true)

    val attachments: List<DatabaseAttachment> = SignalDatabase.attachments.getAttachmentsForMessage(messageId)

    if (dataMessage.expireTimerDuration > Duration.ZERO) {
      SignalDatabase.messages.markExpireStarted(messageId, sent.expirationStartTimestamp ?: 0)

      ApplicationDependencies.getExpiringMessageManager().scheduleDeletion(messageId, true, sent.expirationStartTimestamp ?: 0, dataMessage.expireTimerDuration.inWholeMilliseconds)
    }
    if (recipient.isSelf) {
      SignalDatabase.messages.incrementDeliveryReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
      SignalDatabase.messages.incrementReadReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
    }

    SignalDatabase.runPostSuccessfulTransaction {
      val downloadJobs: List<AttachmentDownloadJob> = attachments.map { AttachmentDownloadJob(messageId, it.attachmentId, false) }
      for (attachment in attachments) {
        ApplicationDependencies.getJobManager().addAll(downloadJobs)
      }
    }

    return threadId
  }

  @Throws(MmsException::class, BadGroupIdException::class)
  private fun handleSynchronizeSentTextMessage(sent: Sent, envelopeTimestamp: Long): Long {
    log(envelopeTimestamp, "Synchronize sent text message for " + sent.timestamp!!)

    val recipient = getSyncMessageDestination(sent)
    val dataMessage: DataMessage = sent.message!!
    val body = dataMessage.body ?: ""
    val expiresInMillis = dataMessage.expireTimerDuration.inWholeMilliseconds
    val bodyRanges = dataMessage.bodyRanges.filter { it.mentionAci == null }.toBodyRangeList()

    if (recipient.expiresInSeconds != dataMessage.expireTimerDuration.inWholeSeconds.toInt()) {
      handleSynchronizeSentExpirationUpdate(sent, sideEffect = true)
    }

    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val isGroup = recipient.isGroup
    val messageId: Long

    if (isGroup) {
      val outgoingMessage = OutgoingMessage(
        recipient = recipient,
        body = body,
        timestamp = sent.timestamp!!,
        expiresIn = expiresInMillis,
        isSecure = true,
        bodyRanges = bodyRanges
      )

      messageId = SignalDatabase.messages.insertMessageOutbox(outgoingMessage, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null)
      updateGroupReceiptStatus(sent, messageId, recipient.requireGroupId())
    } else {
      val outgoingTextMessage = OutgoingMessage.text(threadRecipient = recipient, body = body, expiresIn = expiresInMillis, sentTimeMillis = sent.timestamp!!, bodyRanges = bodyRanges)
      messageId = SignalDatabase.messages.insertMessageOutbox(outgoingTextMessage, threadId, false, null)
      SignalDatabase.messages.markUnidentified(messageId, sent.isUnidentified(recipient.serviceId.orNull()))
    }
    SignalDatabase.messages.markAsSent(messageId, true)
    if (expiresInMillis > 0) {
      SignalDatabase.messages.markExpireStarted(messageId, sent.expirationStartTimestamp ?: 0)
      ApplicationDependencies.getExpiringMessageManager().scheduleDeletion(messageId, isGroup, sent.expirationStartTimestamp ?: 0, expiresInMillis)
    }

    if (recipient.isSelf) {
      SignalDatabase.messages.incrementDeliveryReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
      SignalDatabase.messages.incrementReadReceiptCount(sent.timestamp!!, recipient.id, System.currentTimeMillis())
    }

    return threadId
  }

  private fun handleSynchronizeRequestMessage(context: Context, message: Request, envelopeTimestamp: Long) {
    if (SignalStore.account().isPrimaryDevice) {
      log(envelopeTimestamp, "Synchronize request message.")
    } else {
      log(envelopeTimestamp, "Linked device ignoring synchronize request message.")
      return
    }

    when (message.type) {
      Request.Type.CONTACTS -> ApplicationDependencies.getJobManager().add(MultiDeviceContactUpdateJob(true))
      Request.Type.BLOCKED -> ApplicationDependencies.getJobManager().add(MultiDeviceBlockedUpdateJob())
      Request.Type.CONFIGURATION -> {
        ApplicationDependencies.getJobManager().add(
          MultiDeviceConfigurationUpdateJob(
            TextSecurePreferences.isReadReceiptsEnabled(context),
            TextSecurePreferences.isTypingIndicatorsEnabled(context),
            TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(context),
            SignalStore.settings().isLinkPreviewsEnabled
          )
        )
        ApplicationDependencies.getJobManager().add(MultiDeviceStickerPackSyncJob())
      }
      Request.Type.KEYS -> ApplicationDependencies.getJobManager().add(MultiDeviceKeysUpdateJob())
      else -> warn(envelopeTimestamp, "Unknown request type: ${message.type}")
    }
  }

  private fun handleSynchronizeReadMessage(
    context: Context,
    readMessages: List<Read>,
    envelopeTimestamp: Long,
    earlyMessageCacheEntry: EarlyMessageCacheEntry?
  ) {
    log(envelopeTimestamp, "Synchronize read message. Count: ${readMessages.size}, Timestamps: ${readMessages.map { it.timestamp }}")

    val threadToLatestRead: MutableMap<Long, Long> = HashMap()
    val unhandled: Collection<MessageTable.SyncMessageId> = SignalDatabase.messages.setTimestampReadFromSyncMessage(readMessages, envelopeTimestamp, threadToLatestRead)
    val markedMessages: List<MarkedMessageInfo> = SignalDatabase.threads.setReadSince(threadToLatestRead, false)

    if (Util.hasItems(markedMessages)) {
      log("Updating past SignalDatabase.messages: " + markedMessages.size)
      MarkReadReceiver.process(markedMessages)
    }

    for (id in unhandled) {
      warn(envelopeTimestamp, "[handleSynchronizeReadMessage] Could not find matching message! timestamp: ${id.timetamp}  author: ${id.recipientId}")
      if (earlyMessageCacheEntry != null) {
        ApplicationDependencies.getEarlyMessageCache().store(id.recipientId, id.timetamp, earlyMessageCacheEntry)
      }
    }

    if (unhandled.isNotEmpty() && earlyMessageCacheEntry != null) {
      PushProcessEarlyMessagesJob.enqueue()
    }

    ApplicationDependencies
      .getMessageNotifier()
      .apply {
        setLastDesktopActivityTimestamp(envelopeTimestamp)
        cancelDelayedNotifications()
        updateNotification(context)
      }
  }

  private fun handleSynchronizeViewedMessage(context: Context, viewedMessages: List<SyncMessage.Viewed>, envelopeTimestamp: Long) {
    log(envelopeTimestamp, "Synchronize view message. Count: ${viewedMessages.size}, Timestamps: ${viewedMessages.map { it.timestamp }}")

    val records = viewedMessages
      .mapNotNull { message ->
        val author = Recipient.externalPush(ServiceId.parseOrThrow(message.senderAci!!)).id
        if (message.timestamp != null) {
          SignalDatabase.messages.getMessageFor(message.timestamp!!, author)
        } else {
          warn(envelopeTimestamp, "Message timestamp null")
          null
        }
      }

    val toMarkViewed = records.map { it.id }

    val toEnqueueDownload = records
      .map { it as MmsMessageRecord }
      .filter { it.storyType.isStory && !it.storyType.isTextStory }

    for (mediaMmsMessageRecord in toEnqueueDownload) {
      Stories.enqueueAttachmentsFromStoryForDownloadSync(mediaMmsMessageRecord, false)
    }

    SignalDatabase.messages.setIncomingMessagesViewed(toMarkViewed)
    SignalDatabase.messages.setOutgoingGiftsRevealed(toMarkViewed)

    ApplicationDependencies.getMessageNotifier().apply {
      setLastDesktopActivityTimestamp(envelopeTimestamp)
      cancelDelayedNotifications()
      updateNotification(context)
    }
  }

  private fun handleSynchronizeViewOnceOpenMessage(context: Context, openMessage: ViewOnceOpen, envelopeTimestamp: Long, earlyMessageCacheEntry: EarlyMessageCacheEntry?) {
    log(envelopeTimestamp, "Handling a view-once open for message: " + openMessage.timestamp)

    val author: RecipientId = Recipient.externalPush(ServiceId.parseOrThrow(openMessage.senderAci!!)).id
    val timestamp: Long = if (openMessage.timestamp != null) {
      openMessage.timestamp!!
    } else {
      warn(envelopeTimestamp, "Open message missing timestamp")
      return
    }
    val record: MessageRecord? = SignalDatabase.messages.getMessageFor(timestamp, author)

    if (record != null) {
      SignalDatabase.attachments.deleteAttachmentFilesForViewOnceMessage(record.id)
    } else {
      warn(envelopeTimestamp.toString(), "Got a view-once open message for a message we don't have!")
      if (earlyMessageCacheEntry != null) {
        ApplicationDependencies.getEarlyMessageCache().store(author, timestamp, earlyMessageCacheEntry)
        PushProcessEarlyMessagesJob.enqueue()
      }
    }

    ApplicationDependencies.getMessageNotifier().apply {
      setLastDesktopActivityTimestamp(envelopeTimestamp)
      cancelDelayedNotifications()
      updateNotification(context)
    }
  }

  private fun handleSynchronizeVerifiedMessage(context: Context, verifiedMessage: Verified) {
    log("Synchronize verified message.")

    IdentityUtil.processVerifiedMessage(context, verifiedMessage)
  }

  private fun handleSynchronizeStickerPackOperation(stickerPackOperations: List<StickerPackOperation>, envelopeTimestamp: Long) {
    log(envelopeTimestamp, "Synchronize sticker pack operation.")

    val jobManager = ApplicationDependencies.getJobManager()

    for (operation in stickerPackOperations) {
      if (operation.packId != null && operation.packKey != null && operation.type != null) {
        val packId = Hex.toStringCondensed(operation.packId!!.toByteArray())
        val packKey = Hex.toStringCondensed(operation.packKey!!.toByteArray())

        when (operation.type!!) {
          StickerPackOperation.Type.INSTALL -> jobManager.add(StickerPackDownloadJob.forInstall(packId, packKey, false))
          StickerPackOperation.Type.REMOVE -> SignalDatabase.stickers.uninstallPack(packId)
        }
      } else {
        warn("Received incomplete sticker pack operation sync.")
      }
    }
  }

  private fun handleSynchronizeConfigurationMessage(context: Context, configurationMessage: Configuration, envelopeTimestamp: Long) {
    log(envelopeTimestamp, "Synchronize configuration message.")

    if (configurationMessage.readReceipts != null) {
      TextSecurePreferences.setReadReceiptsEnabled(context, configurationMessage.readReceipts!!)
    }

    if (configurationMessage.unidentifiedDeliveryIndicators != null) {
      TextSecurePreferences.setShowUnidentifiedDeliveryIndicatorsEnabled(context, configurationMessage.unidentifiedDeliveryIndicators!!)
    }

    if (configurationMessage.typingIndicators != null) {
      TextSecurePreferences.setTypingIndicatorsEnabled(context, configurationMessage.typingIndicators!!)
    }

    if (configurationMessage.linkPreviews != null) {
      SignalStore.settings().isLinkPreviewsEnabled = configurationMessage.linkPreviews!!
    }
  }

  private fun handleSynchronizeBlockedListMessage(blockMessage: Blocked) {
    val addresses: List<SignalServiceAddress> = blockMessage.acis.mapNotNull { SignalServiceAddress.fromRaw(it, null).orNull() }
    val groupIds: List<ByteArray> = blockMessage.groupIds.map { it.toByteArray() }

    SignalDatabase.recipients.applyBlockedUpdate(addresses, groupIds)
  }

  private fun handleSynchronizeFetchMessage(fetchType: FetchLatest.Type, envelopeTimestamp: Long) {
    log(envelopeTimestamp, "Received fetch request with type: $fetchType")
    when (fetchType) {
      FetchLatest.Type.LOCAL_PROFILE -> ApplicationDependencies.getJobManager().add(RefreshOwnProfileJob())
      FetchLatest.Type.STORAGE_MANIFEST -> StorageSyncHelper.scheduleSyncForDataChange()
      FetchLatest.Type.SUBSCRIPTION_STATUS -> warn(envelopeTimestamp, "Dropping subscription status fetch message.")
      else -> warn(envelopeTimestamp, "Received a fetch message for an unknown type.")
    }
  }

  @Throws(BadGroupIdException::class)
  private fun handleSynchronizeMessageRequestResponse(response: MessageRequestResponse, envelopeTimestamp: Long) {
    log(envelopeTimestamp, "Synchronize message request response.")

    val recipient: Recipient = if (response.threadAci != null) {
      Recipient.externalPush(ServiceId.parseOrThrow(response.threadAci!!))
    } else if (response.groupId != null) {
      val groupId: GroupId = GroupId.push(response.groupId!!)
      Recipient.externalPossiblyMigratedGroup(groupId)
    } else {
      warn("Message request response was missing a thread recipient! Skipping.")
      return
    }

    val threadId: Long = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

    when (response.type) {
      MessageRequestResponse.Type.ACCEPT -> {
        SignalDatabase.recipients.setProfileSharing(recipient.id, true)
        SignalDatabase.recipients.setBlocked(recipient.id, false)
        SignalDatabase.messages.insertMessageOutbox(
          OutgoingMessage.messageRequestAcceptMessage(recipient, System.currentTimeMillis(), TimeUnit.SECONDS.toMillis(recipient.expiresInSeconds.toLong())),
          threadId,
          false,
          null
        )
      }
      MessageRequestResponse.Type.DELETE -> {
        SignalDatabase.recipients.setProfileSharing(recipient.id, false)
        if (threadId > 0) {
          SignalDatabase.threads.deleteConversation(threadId)
        }
      }
      MessageRequestResponse.Type.BLOCK -> {
        SignalDatabase.recipients.setBlocked(recipient.id, true)
        SignalDatabase.recipients.setProfileSharing(recipient.id, false)
      }
      MessageRequestResponse.Type.BLOCK_AND_DELETE -> {
        SignalDatabase.recipients.setBlocked(recipient.id, true)
        SignalDatabase.recipients.setProfileSharing(recipient.id, false)
        if (threadId > 0) {
          SignalDatabase.threads.deleteConversation(threadId)
        }
      }
      MessageRequestResponse.Type.SPAM -> {
        SignalDatabase.messages.insertMessageOutbox(
          OutgoingMessage.reportSpamMessage(recipient, System.currentTimeMillis(), TimeUnit.SECONDS.toMillis(recipient.expiresInSeconds.toLong())),
          threadId,
          false,
          null
        )
      }
      MessageRequestResponse.Type.BLOCK_AND_SPAM -> {
        SignalDatabase.recipients.setBlocked(recipient.id, true)
        SignalDatabase.recipients.setProfileSharing(recipient.id, false)
        SignalDatabase.messages.insertMessageOutbox(
          OutgoingMessage.reportSpamMessage(recipient, System.currentTimeMillis(), TimeUnit.SECONDS.toMillis(recipient.expiresInSeconds.toLong())),
          threadId,
          false,
          null
        )
      }
      else -> warn("Got an unknown response type! Skipping")
    }
  }

  private fun handleSynchronizeOutgoingPayment(outgoingPayment: SyncMessage.OutgoingPayment, envelopeTimestamp: Long) {
    log(envelopeTimestamp, "Synchronize outgoing payment.")

    val mobileCoin = if (outgoingPayment.mobileCoin != null) {
      outgoingPayment.mobileCoin!!
    } else {
      log(envelopeTimestamp, "Unknown outgoing payment, ignoring.")
      return
    }

    var recipientId: RecipientId? = ServiceId.parseOrNull(outgoingPayment.recipientServiceId)?.let { RecipientId.from(it) }

    var timestamp: Long = mobileCoin.ledgerBlockTimestamp ?: 0L
    if (timestamp == 0L) {
      timestamp = System.currentTimeMillis()
    }

    var address: MobileCoinPublicAddress? = if (mobileCoin.recipientAddress != null) {
      MobileCoinPublicAddress.fromBytes(mobileCoin.recipientAddress!!.toByteArray())
    } else {
      null
    }

    if (address == null && recipientId == null) {
      log(envelopeTimestamp, "Inserting defrag")
      address = ApplicationDependencies.getPayments().wallet.mobileCoinPublicAddress
      recipientId = Recipient.self().id
    }

    val uuid = UUID.randomUUID()
    try {
      SignalDatabase.payments
        .createSuccessfulPayment(
          uuid,
          recipientId,
          address!!,
          timestamp,
          mobileCoin.ledgerBlockIndex!!,
          outgoingPayment.note ?: "",
          mobileCoin.amountPicoMob!!.toMobileCoinMoney(),
          mobileCoin.feePicoMob!!.toMobileCoinMoney(),
          mobileCoin.receipt!!.toByteArray(),
          PaymentMetaDataUtil.fromKeysAndImages(mobileCoin.outputPublicKeys, mobileCoin.spentKeyImages)
        )
    } catch (e: SerializationException) {
      warn(envelopeTimestamp, "Ignoring synchronized outgoing payment with bad data.", e)
    }

    log("Inserted synchronized payment $uuid")
  }

  private fun handleSynchronizeKeys(storageKey: ByteString, envelopeTimestamp: Long) {
    if (SignalStore.account().isLinkedDevice) {
      log(envelopeTimestamp, "Synchronize keys.")
    } else {
      log(envelopeTimestamp, "Primary device ignores synchronize keys.")
      return
    }

    SignalStore.storageService().setStorageKeyFromPrimary(StorageKey(storageKey.toByteArray()))
  }

  @Throws(IOException::class)
  private fun handleSynchronizeContacts(contactsMessage: SyncMessage.Contacts, envelopeTimestamp: Long) {
    if (SignalStore.account().isLinkedDevice) {
      log(envelopeTimestamp, "Synchronize contacts.")
    } else {
      log(envelopeTimestamp, "Primary device ignores synchronize contacts.")
      return
    }

    if (contactsMessage.blob == null) {
      log(envelopeTimestamp, "Contact blob is null")
      return
    }

    val attachment: SignalServiceAttachmentPointer = contactsMessage.blob!!.toSignalServiceAttachmentPointer()

    ApplicationDependencies.getJobManager().add(MultiDeviceContactSyncJob(attachment))
  }

  private fun handleSynchronizeCallEvent(callEvent: SyncMessage.CallEvent, envelopeTimestamp: Long) {
    if (callEvent.id == null) {
      log(envelopeTimestamp, "Synchronize call event missing call id, ignoring. type: ${callEvent.type}")
      return
    }

    if (callEvent.type == SyncMessage.CallEvent.Type.GROUP_CALL || callEvent.type == SyncMessage.CallEvent.Type.AD_HOC_CALL) {
      handleSynchronizeGroupOrAdHocCallEvent(callEvent, envelopeTimestamp)
    } else {
      handleSynchronizeOneToOneCallEvent(callEvent, envelopeTimestamp)
    }
  }

  private fun handleSynchronizeCallLogEvent(callLogEvent: CallLogEvent, envelopeTimestamp: Long) {
    val timestamp = callLogEvent.timestamp
    val callId = callLogEvent.callId?.let { CallId(it) }
    val peer: RecipientId? = callLogEvent.conversationId?.let { byteString ->
      ACI.parseOrNull(byteString)?.let { RecipientId.from(it) }
        ?: GroupId.pushOrNull(byteString.toByteArray())?.let { SignalDatabase.recipients.getByGroupId(it).orNull() }
        ?: CallLinkRoomId.fromBytes(byteString.toByteArray()).let { SignalDatabase.recipients.getByCallLinkRoomId(it).orNull() }
    }

    if (callId != null && peer != null) {
      val call = SignalDatabase.calls.getCallById(callId.longValue(), peer)

      if (call != null) {
        log(envelopeTimestamp, "Synchronizing call log event with exact call data.")
        synchronizeCallLogEventViaTimestamp(envelopeTimestamp, callLogEvent.type, call.timestamp, peer)
        return
      }
    }

    if (timestamp != null) {
      warn(envelopeTimestamp, "Synchronize call log event using timestamp instead of exact values")
      synchronizeCallLogEventViaTimestamp(envelopeTimestamp, callLogEvent.type, timestamp, peer)
    } else {
      log(envelopeTimestamp, "Failed to synchronize call log event, not enough information.")
    }
  }

  private fun synchronizeCallLogEventViaTimestamp(envelopeTimestamp: Long, eventType: CallLogEvent.Type?, timestamp: Long, peer: RecipientId?) {
    when (eventType) {
      CallLogEvent.Type.CLEAR -> {
        SignalDatabase.calls.deleteNonAdHocCallEventsOnOrBefore(timestamp)
        SignalDatabase.callLinks.deleteNonAdminCallLinksOnOrBefore(timestamp)
      }

      CallLogEvent.Type.MARKED_AS_READ -> {
        SignalDatabase.calls.markAllCallEventsRead(timestamp)
      }

      CallLogEvent.Type.MARKED_AS_READ_IN_CONVERSATION -> {
        if (peer == null) {
          warn(envelopeTimestamp, "Cannot synchronize conversation calls, missing peer.")
          return
        }

        SignalDatabase.calls.markAllCallEventsWithPeerBeforeTimestampRead(peer, timestamp)
      }

      else -> log(envelopeTimestamp, "Synchronize call log event has an invalid type $eventType, ignoring.")
    }
  }

  private fun handleSynchronizeCallLink(callLinkUpdate: CallLinkUpdate, envelopeTimestamp: Long) {
    if (callLinkUpdate.rootKey == null) {
      log(envelopeTimestamp, "Synchronize call link missing root key, ignoring.")
      return
    }

    val callLinkRootKey = try {
      CallLinkRootKey(callLinkUpdate.rootKey!!.toByteArray())
    } catch (e: CallException) {
      log(envelopeTimestamp, "Synchronize call link has invalid root key, ignoring.")
      return
    }

    val roomId = CallLinkRoomId.fromCallLinkRootKey(callLinkRootKey)
    if (callLinkUpdate.type == CallLinkUpdate.Type.DELETE) {
      log(envelopeTimestamp, "Synchronize call link deletion.")
      SignalDatabase.callLinks.deleteCallLink(roomId)

      return
    }

    if (SignalDatabase.callLinks.callLinkExists(roomId)) {
      log(envelopeTimestamp, "Synchronize call link for a link we already know about. Updating credentials.")
      SignalDatabase.callLinks.updateCallLinkCredentials(
        roomId,
        CallLinkCredentials(
          callLinkUpdate.rootKey!!.toByteArray(),
          callLinkUpdate.adminPassKey?.toByteArray()
        )
      )
    } else {
      log(envelopeTimestamp, "Synchronize call link for a link we do not know about. Inserting.")
      SignalDatabase.callLinks.insertCallLink(
        CallLinkTable.CallLink(
          recipientId = RecipientId.UNKNOWN,
          roomId = roomId,
          credentials = CallLinkCredentials(
            linkKeyBytes = callLinkRootKey.keyBytes,
            adminPassBytes = callLinkUpdate.adminPassKey?.toByteArray()
          ),
          state = SignalCallLinkState()
        )
      )
    }

    ApplicationDependencies.getJobManager().add(RefreshCallLinkDetailsJob(callLinkUpdate))
  }

  private fun handleSynchronizeOneToOneCallEvent(callEvent: SyncMessage.CallEvent, envelopeTimestamp: Long) {
    val callId: Long = callEvent.id!!
    val timestamp: Long = callEvent.timestamp ?: 0L
    val type: CallTable.Type? = CallTable.Type.from(callEvent.type)
    val direction: CallTable.Direction? = CallTable.Direction.from(callEvent.direction)
    val event: CallTable.Event? = CallTable.Event.from(callEvent.event)

    if (timestamp == 0L || type == null || direction == null || event == null || callEvent.conversationId == null) {
      warn(envelopeTimestamp, "Call event sync message is not valid, ignoring. timestamp: " + timestamp + " type: " + type + " direction: " + direction + " event: " + event + " hasPeer: " + (callEvent.conversationId != null))
      return
    }

    val aci = ACI.parseOrThrow(callEvent.conversationId!!)
    val recipientId = RecipientId.from(aci)

    log(envelopeTimestamp, "Synchronize call event call: $callId")

    val call = SignalDatabase.calls.getCallById(callId, recipientId)
    if (call != null) {
      val typeMismatch = call.type !== type
      val directionMismatch = call.direction !== direction
      val eventDowngrade = call.event === CallTable.Event.ACCEPTED && event !== CallTable.Event.ACCEPTED
      val peerMismatch = call.peer != recipientId

      if (typeMismatch || directionMismatch || eventDowngrade || peerMismatch) {
        warn(envelopeTimestamp, "Call event sync message is not valid for existing call record, ignoring. type: $type direction: $direction  event: $event peerMismatch: $peerMismatch")
      } else {
        SignalDatabase.calls.updateOneToOneCall(callId, event)
      }
    } else {
      SignalDatabase.calls.insertOneToOneCall(callId, timestamp, recipientId, type, direction, event)
    }
  }

  @Throws(BadGroupIdException::class)
  private fun handleSynchronizeGroupOrAdHocCallEvent(callEvent: SyncMessage.CallEvent, envelopeTimestamp: Long) {
    if (!FeatureFlags.adHocCalling() && callEvent.type == SyncMessage.CallEvent.Type.AD_HOC_CALL) {
      log(envelopeTimestamp, "Ad-Hoc calling is not currently supported by this client, ignoring.")
      return
    }

    val callId: Long = callEvent.id!!
    val timestamp: Long = callEvent.timestamp ?: 0L
    val type: CallTable.Type? = CallTable.Type.from(callEvent.type)
    val direction: CallTable.Direction? = CallTable.Direction.from(callEvent.direction)
    val event: CallTable.Event? = CallTable.Event.from(callEvent.event)
    val hasConversationId: Boolean = callEvent.conversationId != null

    if (timestamp == 0L || type == null || direction == null || event == null || !hasConversationId) {
      warn(envelopeTimestamp, "Group/Ad-hoc call event sync message is not valid, ignoring. timestamp: $timestamp type: $type direction: $direction event: $event hasPeer: $hasConversationId")
      return
    }

    val recipient: Recipient? = when (type) {
      CallTable.Type.AD_HOC_CALL -> {
        val callLinkRoomId = CallLinkRoomId.fromBytes(callEvent.conversationId!!.toByteArray())
        val callLink = SignalDatabase.callLinks.getOrCreateCallLinkByRoomId(callLinkRoomId)
        Recipient.resolved(callLink.recipientId)
      }
      CallTable.Type.GROUP_CALL -> {
        val groupId: GroupId = GroupId.push(callEvent.conversationId!!.toByteArray())
        Recipient.externalGroupExact(groupId)
      }
      else -> {
        warn(envelopeTimestamp, "Unexpected type $type. Ignoring.")
        null
      }
    }

    if (recipient == null) {
      warn(envelopeTimestamp, "Could not process conversation id.")
      return
    }

    val call = SignalDatabase.calls.getCallById(callId, recipient.id)

    if (call != null) {
      if (call.type !== type) {
        warn(envelopeTimestamp, "Group/Ad-hoc call event type mismatch, ignoring. timestamp: $timestamp type: $type direction: $direction event: $event hasPeer: $hasConversationId")
        return
      }
      when (event) {
        CallTable.Event.DELETE -> SignalDatabase.calls.deleteGroupCall(call)
        CallTable.Event.ACCEPTED -> {
          if (call.timestamp > timestamp) {
            SignalDatabase.calls.setTimestamp(call.callId, recipient.id, timestamp)
          }
          if (direction == CallTable.Direction.INCOMING) {
            SignalDatabase.calls.acceptIncomingGroupCall(call)
          } else {
            SignalDatabase.calls.acceptOutgoingGroupCall(call)
          }
        }
        CallTable.Event.NOT_ACCEPTED -> {
          if (call.timestamp > timestamp) {
            SignalDatabase.calls.setTimestamp(call.callId, recipient.id, timestamp)
          }
          if (callEvent.direction == SyncMessage.CallEvent.Direction.INCOMING) {
            SignalDatabase.calls.declineIncomingGroupCall(call)
          } else {
            warn(envelopeTimestamp, "Invalid direction OUTGOING for event NOT_ACCEPTED")
          }
        }
        else -> warn("Unsupported event type $event. Ignoring. timestamp: $timestamp type: $type direction: $direction event: $event hasPeer: $hasConversationId")
      }
    } else {
      when (event) {
        CallTable.Event.DELETE -> SignalDatabase.calls.insertDeletedGroupCallFromSyncEvent(callEvent.id!!, recipient.id, direction, timestamp)
        CallTable.Event.ACCEPTED -> SignalDatabase.calls.insertAcceptedGroupCall(callEvent.id!!, recipient.id, direction, timestamp)
        CallTable.Event.NOT_ACCEPTED -> {
          if (callEvent.direction == SyncMessage.CallEvent.Direction.INCOMING) {
            SignalDatabase.calls.insertDeclinedGroupCall(callEvent.id!!, recipient.id, timestamp)
          } else {
            warn(envelopeTimestamp, "Invalid direction OUTGOING for event NOT_ACCEPTED for non-existing call")
          }
        }
        else -> warn("Unsupported event type $event. Ignoring. timestamp: $timestamp type: $type direction: $direction event: $event hasPeer: $hasConversationId call: null")
      }
    }
  }
}
