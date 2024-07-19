/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.database

import android.database.Cursor
import okio.ByteString.Companion.toByteString
import org.signal.core.util.Base64
import org.signal.core.util.Base64.decode
import org.signal.core.util.Base64.decodeOrThrow
import org.signal.core.util.logging.Log
import org.signal.core.util.requireBlob
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireString
import org.stalker.securesms.attachments.Cdn
import org.stalker.securesms.attachments.DatabaseAttachment
import org.stalker.securesms.backup.v2.BackupRepository.getMediaName
import org.stalker.securesms.backup.v2.proto.ChatItem
import org.stalker.securesms.backup.v2.proto.ChatUpdateMessage
import org.stalker.securesms.backup.v2.proto.ExpirationTimerChatUpdate
import org.stalker.securesms.backup.v2.proto.FilePointer
import org.stalker.securesms.backup.v2.proto.GroupCall
import org.stalker.securesms.backup.v2.proto.IndividualCall
import org.stalker.securesms.backup.v2.proto.MessageAttachment
import org.stalker.securesms.backup.v2.proto.ProfileChangeChatUpdate
import org.stalker.securesms.backup.v2.proto.Quote
import org.stalker.securesms.backup.v2.proto.Reaction
import org.stalker.securesms.backup.v2.proto.RemoteDeletedMessage
import org.stalker.securesms.backup.v2.proto.SendStatus
import org.stalker.securesms.backup.v2.proto.SessionSwitchoverChatUpdate
import org.stalker.securesms.backup.v2.proto.SimpleChatUpdate
import org.stalker.securesms.backup.v2.proto.StandardMessage
import org.stalker.securesms.backup.v2.proto.Text
import org.stalker.securesms.backup.v2.proto.ThreadMergeChatUpdate
import org.stalker.securesms.database.AttachmentTable
import org.stalker.securesms.database.CallTable
import org.stalker.securesms.database.GroupReceiptTable
import org.stalker.securesms.database.MessageTable
import org.stalker.securesms.database.MessageTypes
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.SignalDatabase.Companion.calls
import org.stalker.securesms.database.documents.IdentityKeyMismatchSet
import org.stalker.securesms.database.documents.NetworkFailureSet
import org.stalker.securesms.database.model.GroupCallUpdateDetailsUtil
import org.stalker.securesms.database.model.GroupsV2UpdateMessageConverter
import org.stalker.securesms.database.model.Mention
import org.stalker.securesms.database.model.ReactionRecord
import org.stalker.securesms.database.model.databaseprotos.BodyRangeList
import org.stalker.securesms.database.model.databaseprotos.DecryptedGroupV2Context
import org.stalker.securesms.database.model.databaseprotos.MessageExtras
import org.stalker.securesms.database.model.databaseprotos.ProfileChangeDetails
import org.stalker.securesms.database.model.databaseprotos.SessionSwitchoverEvent
import org.stalker.securesms.database.model.databaseprotos.ThreadMergeEvent
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.mms.QuoteModel
import org.stalker.securesms.util.JsonUtils
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.api.util.UuidUtil
import org.whispersystems.signalservice.api.util.toByteArray
import java.io.Closeable
import java.io.IOException
import java.util.LinkedList
import java.util.Queue
import org.stalker.securesms.backup.v2.proto.BodyRange as BackupBodyRange

/**
 * An iterator for chat items with a clever performance twist: rather than do the extra queries one at a time (for reactions,
 * attachments, etc), this will populate items in batches, doing bulk lookups to improve throughput. We keep these in a buffer
 * and only do more queries when the buffer is empty.
 *
 * All of this complexity is hidden from the user -- they just get a normal iterator interface.
 */
class ChatItemExportIterator(private val cursor: Cursor, private val batchSize: Int, private val archiveMedia: Boolean) : Iterator<ChatItem>, Closeable {

  companion object {
    private val TAG = Log.tag(ChatItemExportIterator::class.java)

    const val COLUMN_BASE_TYPE = "base_type"
  }

  /**
   * A queue of already-parsed ChatItems. Processing in batches means that we read ahead in the cursor and put
   * the pending items here.
   */
  private val buffer: Queue<ChatItem> = LinkedList()

  override fun hasNext(): Boolean {
    return buffer.isNotEmpty() || (cursor.count > 0 && !cursor.isLast && !cursor.isAfterLast)
  }

  override fun next(): ChatItem {
    if (buffer.isNotEmpty()) {
      return buffer.remove()
    }

    val records: LinkedHashMap<Long, BackupMessageRecord> = linkedMapOf()

    for (i in 0 until batchSize) {
      if (cursor.moveToNext()) {
        val record = cursor.toBackupMessageRecord()
        records[record.id] = record
      } else {
        break
      }
    }

    val reactionsById: Map<Long, List<ReactionRecord>> = SignalDatabase.reactions.getReactionsForMessages(records.keys)
    val mentionsById: Map<Long, List<Mention>> = SignalDatabase.mentions.getMentionsForMessages(records.keys)
    val attachmentsById: Map<Long, List<DatabaseAttachment>> = SignalDatabase.attachments.getAttachmentsForMessages(records.keys)
    val groupReceiptsById: Map<Long, List<GroupReceiptTable.GroupReceiptInfo>> = SignalDatabase.groupReceipts.getGroupReceiptInfoForMessages(records.keys)

    for ((id, record) in records) {
      val builder = record.toBasicChatItemBuilder(groupReceiptsById[id])

      when {
        record.remoteDeleted -> builder.remoteDeletedMessage = RemoteDeletedMessage()
        MessageTypes.isJoinedType(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.JOINED_SIGNAL))
        MessageTypes.isIdentityUpdate(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.IDENTITY_UPDATE))
        MessageTypes.isIdentityVerified(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.IDENTITY_VERIFIED))
        MessageTypes.isIdentityDefault(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.IDENTITY_DEFAULT))
        MessageTypes.isChangeNumber(record.type) -> {
          builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.CHANGE_NUMBER))
          builder.sms = false
        }
        MessageTypes.isBoostRequest(record.type) -> {
          builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.BOOST_REQUEST))
          builder.sms = false
        }
        MessageTypes.isEndSessionType(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.END_SESSION))
        MessageTypes.isChatSessionRefresh(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.CHAT_SESSION_REFRESH))
        MessageTypes.isBadDecryptType(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.BAD_DECRYPT))
        MessageTypes.isPaymentsActivated(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.PAYMENTS_ACTIVATED))
        MessageTypes.isPaymentsRequestToActivate(record.type) -> builder.updateMessage = ChatUpdateMessage(simpleUpdate = SimpleChatUpdate(type = SimpleChatUpdate.Type.PAYMENT_ACTIVATION_REQUEST))
        MessageTypes.isExpirationTimerUpdate(record.type) -> {
          builder.updateMessage = ChatUpdateMessage(expirationTimerChange = ExpirationTimerChatUpdate(record.expiresIn.toInt()))
          builder.expiresInMs = 0
        }
        MessageTypes.isProfileChange(record.type) -> {
          if (record.body == null) continue
          builder.updateMessage = ChatUpdateMessage(
            profileChange = try {
              val decoded: ByteArray = Base64.decode(record.body!!)
              val profileChangeDetails = ProfileChangeDetails.ADAPTER.decode(decoded)
              if (profileChangeDetails.profileNameChange != null) {
                ProfileChangeChatUpdate(previousName = profileChangeDetails.profileNameChange.previous, newName = profileChangeDetails.profileNameChange.newValue)
              } else {
                ProfileChangeChatUpdate()
              }
            } catch (e: IOException) {
              Log.w(TAG, "Profile name change details could not be read", e)
              ProfileChangeChatUpdate()
            }
          )
          builder.sms = false
        }
        MessageTypes.isSessionSwitchoverType(record.type) -> {
          builder.updateMessage = ChatUpdateMessage(
            sessionSwitchover = try {
              val event = SessionSwitchoverEvent.ADAPTER.decode(decodeOrThrow(record.body!!))
              SessionSwitchoverChatUpdate(event.e164.e164ToLong()!!)
            } catch (e: Exception) {
              SessionSwitchoverChatUpdate()
            }
          )
        }
        MessageTypes.isThreadMergeType(record.type) -> {
          builder.updateMessage = ChatUpdateMessage(
            threadMerge = try {
              val event = ThreadMergeEvent.ADAPTER.decode(decodeOrThrow(record.body!!))
              ThreadMergeChatUpdate(event.previousE164.e164ToLong()!!)
            } catch (e: Exception) {
              ThreadMergeChatUpdate()
            }
          )
        }
        MessageTypes.isGroupV2(record.type) && MessageTypes.isGroupUpdate(record.type) -> {
          val groupChange = record.messageExtras?.gv2UpdateDescription?.groupChangeUpdate
          if (groupChange != null) {
            builder.updateMessage = ChatUpdateMessage(
              groupChange = groupChange
            )
          } else if (record.body != null) {
            try {
              val decoded: ByteArray = decode(record.body)
              val context = DecryptedGroupV2Context.ADAPTER.decode(decoded)
              builder.updateMessage = ChatUpdateMessage(
                groupChange = GroupsV2UpdateMessageConverter.translateDecryptedChange(selfIds = SignalStore.account().getServiceIds(), context)
              )
            } catch (e: IOException) {
              continue
            }
          } else {
            continue
          }
        }
        MessageTypes.isCallLog(record.type) -> {
          builder.sms = false
          val call = calls.getCallByMessageId(record.id)
          if (call != null) {
            if (call.type == CallTable.Type.GROUP_CALL) {
              builder.updateMessage = ChatUpdateMessage(
                groupCall = GroupCall(
                  callId = record.id,
                  state = when (call.event) {
                    CallTable.Event.MISSED -> GroupCall.State.MISSED
                    CallTable.Event.ONGOING -> GroupCall.State.GENERIC
                    CallTable.Event.ACCEPTED -> GroupCall.State.ACCEPTED
                    CallTable.Event.NOT_ACCEPTED -> GroupCall.State.GENERIC
                    CallTable.Event.MISSED_NOTIFICATION_PROFILE -> GroupCall.State.MISSED_NOTIFICATION_PROFILE
                    CallTable.Event.DELETE -> continue
                    CallTable.Event.GENERIC_GROUP_CALL -> GroupCall.State.GENERIC
                    CallTable.Event.JOINED -> GroupCall.State.JOINED
                    CallTable.Event.RINGING -> GroupCall.State.RINGING
                    CallTable.Event.DECLINED -> GroupCall.State.DECLINED
                    CallTable.Event.OUTGOING_RING -> GroupCall.State.OUTGOING_RING
                  },
                  ringerRecipientId = call.ringerRecipient?.toLong(),
                  startedCallAci = if (call.ringerRecipient != null) SignalDatabase.recipients.getRecord(call.ringerRecipient).aci?.toByteString() else null,
                  startedCallTimestamp = call.timestamp
                )
              )
            } else if (call.type != CallTable.Type.AD_HOC_CALL) {
              builder.updateMessage = ChatUpdateMessage(
                individualCall = IndividualCall(
                  callId = call.callId,
                  type = if (call.type == CallTable.Type.VIDEO_CALL) IndividualCall.Type.VIDEO_CALL else IndividualCall.Type.AUDIO_CALL,
                  direction = if (call.direction == CallTable.Direction.INCOMING) IndividualCall.Direction.INCOMING else IndividualCall.Direction.OUTGOING,
                  state = when (call.event) {
                    CallTable.Event.MISSED -> IndividualCall.State.MISSED
                    CallTable.Event.MISSED_NOTIFICATION_PROFILE -> IndividualCall.State.MISSED_NOTIFICATION_PROFILE
                    CallTable.Event.ACCEPTED -> IndividualCall.State.ACCEPTED
                    CallTable.Event.NOT_ACCEPTED -> IndividualCall.State.NOT_ACCEPTED
                    else -> IndividualCall.State.UNKNOWN_STATE
                  },
                  startedCallTimestamp = call.timestamp
                )
              )
            } else {
              continue
            }
          } else {
            when {
              MessageTypes.isMissedAudioCall(record.type) -> {
                builder.updateMessage = ChatUpdateMessage(
                  individualCall = IndividualCall(
                    type = IndividualCall.Type.AUDIO_CALL,
                    state = IndividualCall.State.MISSED,
                    direction = IndividualCall.Direction.INCOMING
                  )
                )
              }
              MessageTypes.isMissedVideoCall(record.type) -> {
                builder.updateMessage = ChatUpdateMessage(
                  individualCall = IndividualCall(
                    type = IndividualCall.Type.VIDEO_CALL,
                    state = IndividualCall.State.MISSED,
                    direction = IndividualCall.Direction.INCOMING
                  )
                )
              }
              MessageTypes.isIncomingAudioCall(record.type) -> {
                builder.updateMessage = ChatUpdateMessage(
                  individualCall = IndividualCall(
                    type = IndividualCall.Type.AUDIO_CALL,
                    state = IndividualCall.State.ACCEPTED,
                    direction = IndividualCall.Direction.INCOMING
                  )
                )
              }
              MessageTypes.isIncomingVideoCall(record.type) -> {
                builder.updateMessage = ChatUpdateMessage(
                  individualCall = IndividualCall(
                    type = IndividualCall.Type.VIDEO_CALL,
                    state = IndividualCall.State.ACCEPTED,
                    direction = IndividualCall.Direction.INCOMING
                  )
                )
              }
              MessageTypes.isOutgoingAudioCall(record.type) -> {
                builder.updateMessage = ChatUpdateMessage(
                  individualCall = IndividualCall(
                    type = IndividualCall.Type.AUDIO_CALL,
                    state = IndividualCall.State.ACCEPTED,
                    direction = IndividualCall.Direction.OUTGOING
                  )
                )
              }
              MessageTypes.isOutgoingVideoCall(record.type) -> {
                builder.updateMessage = ChatUpdateMessage(
                  individualCall = IndividualCall(
                    type = IndividualCall.Type.VIDEO_CALL,
                    state = IndividualCall.State.ACCEPTED,
                    direction = IndividualCall.Direction.OUTGOING
                  )
                )
              }
              MessageTypes.isGroupCall(record.type) -> {
                try {
                  val groupCallUpdateDetails = GroupCallUpdateDetailsUtil.parse(record.body)
                  builder.updateMessage = ChatUpdateMessage(
                    groupCall = GroupCall(
                      state = GroupCall.State.GENERIC,
                      startedCallAci = ACI.from(UuidUtil.parseOrThrow(groupCallUpdateDetails.startedCallUuid)).toByteString(),
                      startedCallTimestamp = groupCallUpdateDetails.startedCallTimestamp,
                      endedCallTimestamp = groupCallUpdateDetails.endedCallTimestamp
                    )
                  )
                } catch (exception: java.lang.Exception) {
                  continue
                }
              }
            }
          }
        }
        record.body == null && !attachmentsById.containsKey(record.id) -> {
          Log.w(TAG, "Record missing a body and doesnt have attachments, skipping")
          continue
        }
        else -> builder.standardMessage = record.toStandardMessage(reactionsById[id], mentions = mentionsById[id], attachments = attachmentsById[record.id])
      }

      buffer += builder.build()
    }

    return if (buffer.isNotEmpty()) {
      buffer.remove()
    } else {
      throw NoSuchElementException()
    }
  }

  override fun close() {
    cursor.close()
  }

  private fun String.e164ToLong(): Long? {
    val fixed = if (this.startsWith("+")) {
      this.substring(1)
    } else {
      this
    }

    return fixed.toLongOrNull()
  }

  private fun BackupMessageRecord.toBasicChatItemBuilder(groupReceipts: List<GroupReceiptTable.GroupReceiptInfo>?): ChatItem.Builder {
    val record = this

    return ChatItem.Builder().apply {
      chatId = record.threadId
      authorId = record.fromRecipientId
      dateSent = record.dateSent
      expireStartDate = if (record.expireStarted > 0) record.expireStarted else 0
      expiresInMs = if (record.expiresIn > 0) record.expiresIn else 0
      revisions = emptyList()
      sms = !MessageTypes.isSecureType(record.type)
      if (MessageTypes.isCallLog(record.type)) {
        directionless = ChatItem.DirectionlessMessageDetails()
      } else if (MessageTypes.isOutgoingMessageType(record.type)) {
        outgoing = ChatItem.OutgoingMessageDetails(
          sendStatus = record.toBackupSendStatus(groupReceipts)
        )
      } else {
        incoming = ChatItem.IncomingMessageDetails(
          dateServerSent = record.dateServer,
          dateReceived = record.dateReceived,
          read = record.read,
          sealedSender = record.sealedSender
        )
      }
    }
  }

  private fun BackupMessageRecord.toStandardMessage(reactionRecords: List<ReactionRecord>?, mentions: List<Mention>?, attachments: List<DatabaseAttachment>?): StandardMessage {
    val text = if (body == null) {
      null
    } else {
      Text(
        body = this.body,
        bodyRanges = (this.bodyRanges?.toBackupBodyRanges() ?: emptyList()) + (mentions?.toBackupBodyRanges() ?: emptyList())
      )
    }
    val quotedAttachments = attachments?.filter { it.quote } ?: emptyList()
    val messageAttachments = attachments?.filter { !it.quote } ?: emptyList()
    return StandardMessage(
      quote = this.toQuote(quotedAttachments),
      text = text,
      attachments = messageAttachments.toBackupAttachments(),
      // TODO Link previews!
      linkPreview = emptyList(),
      longText = null,
      reactions = reactionRecords.toBackupReactions()
    )
  }

  private fun BackupMessageRecord.toQuote(attachments: List<DatabaseAttachment>? = null): Quote? {
    return if (this.quoteTargetSentTimestamp != MessageTable.QUOTE_NOT_PRESENT_ID && this.quoteAuthor > 0) {
      val type = QuoteModel.Type.fromCode(this.quoteType)
      Quote(
        targetSentTimestamp = this.quoteTargetSentTimestamp.takeIf { !this.quoteMissing && it != MessageTable.QUOTE_TARGET_MISSING_ID },
        authorId = this.quoteAuthor,
        text = this.quoteBody,
        attachments = attachments?.toBackupQuoteAttachments() ?: emptyList(),
        bodyRanges = this.quoteBodyRanges?.toBackupBodyRanges() ?: emptyList(),
        type = when (type) {
          QuoteModel.Type.NORMAL -> Quote.Type.NORMAL
          QuoteModel.Type.GIFT_BADGE -> Quote.Type.GIFTBADGE
        }
      )
    } else {
      null
    }
  }

  private fun List<DatabaseAttachment>.toBackupQuoteAttachments(): List<Quote.QuotedAttachment> {
    return this.map { attachment ->
      Quote.QuotedAttachment(
        contentType = attachment.contentType,
        fileName = attachment.fileName,
        thumbnail = attachment.toBackupAttachment()
      )
    }
  }

  private fun DatabaseAttachment.toBackupAttachment(): MessageAttachment {
    val builder = FilePointer.Builder()
    builder.contentType = contentType
    builder.incrementalMac = incrementalDigest?.toByteString()
    builder.incrementalMacChunkSize = incrementalMacChunkSize
    builder.fileName = fileName
    builder.width = width
    builder.height = height
    builder.caption = caption
    builder.blurHash = blurHash?.hash

    if (remoteKey.isNullOrBlank() || remoteDigest == null || size == 0L) {
      builder.invalidAttachmentLocator = FilePointer.InvalidAttachmentLocator()
    } else {
      if (archiveMedia) {
        builder.backupLocator = FilePointer.BackupLocator(
          mediaName = archiveMediaName ?: this.getMediaName().toString(),
          cdnNumber = if (archiveMediaName != null) archiveCdn else Cdn.CDN_3.cdnNumber, // TODO (clark): Update when new proto with optional cdn is landed
          key = decode(remoteKey).toByteString(),
          size = this.size.toInt(),
          digest = remoteDigest.toByteString()
        )
      } else {
        if (remoteLocation.isNullOrBlank()) {
          builder.invalidAttachmentLocator = FilePointer.InvalidAttachmentLocator()
        } else {
          builder.attachmentLocator = FilePointer.AttachmentLocator(
            cdnKey = this.remoteLocation,
            cdnNumber = this.cdn.cdnNumber,
            uploadTimestamp = this.uploadTimestamp,
            key = decode(remoteKey).toByteString(),
            size = this.size.toInt(),
            digest = remoteDigest.toByteString()
          )
        }
      }
    }
    return MessageAttachment(
      pointer = builder.build(),
      wasDownloaded = this.transferState == AttachmentTable.TRANSFER_PROGRESS_DONE || this.transferState == AttachmentTable.TRANSFER_NEEDS_RESTORE,
      flag = if (voiceNote) MessageAttachment.Flag.VOICE_MESSAGE else if (videoGif) MessageAttachment.Flag.GIF else if (borderless) MessageAttachment.Flag.BORDERLESS else MessageAttachment.Flag.NONE
    )
  }

  private fun List<DatabaseAttachment>.toBackupAttachments(): List<MessageAttachment> {
    return this.map { attachment ->
      attachment.toBackupAttachment()
    }
  }

  private fun List<Mention>.toBackupBodyRanges(): List<BackupBodyRange> {
    return this.map {
      BackupBodyRange(
        start = it.start,
        length = it.length,
        mentionAci = SignalDatabase.recipients.getRecord(it.recipientId).aci?.toByteString()
      )
    }
  }

  private fun ByteArray.toBackupBodyRanges(): List<BackupBodyRange> {
    val decoded: BodyRangeList = try {
      BodyRangeList.ADAPTER.decode(this)
    } catch (e: IOException) {
      Log.w(TAG, "Failed to decode BodyRangeList!")
      return emptyList()
    }

    return decoded.ranges.map {
      BackupBodyRange(
        start = it.start,
        length = it.length,
        mentionAci = it.mentionUuid?.let { uuid -> UuidUtil.parseOrThrow(uuid) }?.toByteArray()?.toByteString(),
        style = it.style?.toBackupBodyRangeStyle()
      )
    }
  }

  private fun BodyRangeList.BodyRange.Style.toBackupBodyRangeStyle(): BackupBodyRange.Style {
    return when (this) {
      BodyRangeList.BodyRange.Style.BOLD -> BackupBodyRange.Style.BOLD
      BodyRangeList.BodyRange.Style.ITALIC -> BackupBodyRange.Style.ITALIC
      BodyRangeList.BodyRange.Style.STRIKETHROUGH -> BackupBodyRange.Style.STRIKETHROUGH
      BodyRangeList.BodyRange.Style.MONOSPACE -> BackupBodyRange.Style.MONOSPACE
      BodyRangeList.BodyRange.Style.SPOILER -> BackupBodyRange.Style.SPOILER
    }
  }

  private fun List<ReactionRecord>?.toBackupReactions(): List<Reaction> {
    return this
      ?.map {
        Reaction(
          emoji = it.emoji,
          authorId = it.author.toLong(),
          sentTimestamp = it.dateSent,
          receivedTimestamp = it.dateReceived
        )
      } ?: emptyList()
  }

  private fun BackupMessageRecord.toBackupSendStatus(groupReceipts: List<GroupReceiptTable.GroupReceiptInfo>?): List<SendStatus> {
    if (!MessageTypes.isOutgoingMessageType(this.type)) {
      return emptyList()
    }

    if (!groupReceipts.isNullOrEmpty()) {
      return groupReceipts.toBackupSendStatus(this.networkFailureRecipientIds, this.identityMismatchRecipientIds)
    }

    val status: SendStatus.Status = when {
      this.viewed -> SendStatus.Status.VIEWED
      this.hasReadReceipt -> SendStatus.Status.READ
      this.hasDeliveryReceipt -> SendStatus.Status.DELIVERED
      this.baseType == MessageTypes.BASE_SENT_TYPE -> SendStatus.Status.SENT
      MessageTypes.isFailedMessageType(this.type) -> SendStatus.Status.FAILED
      else -> SendStatus.Status.PENDING
    }

    return listOf(
      SendStatus(
        recipientId = this.toRecipientId,
        deliveryStatus = status,
        lastStatusUpdateTimestamp = this.receiptTimestamp,
        sealedSender = this.sealedSender,
        networkFailure = this.networkFailureRecipientIds.contains(this.toRecipientId),
        identityKeyMismatch = this.identityMismatchRecipientIds.contains(this.toRecipientId)
      )
    )
  }

  private fun List<GroupReceiptTable.GroupReceiptInfo>.toBackupSendStatus(networkFailureRecipientIds: Set<Long>, identityMismatchRecipientIds: Set<Long>): List<SendStatus> {
    return this.map {
      SendStatus(
        recipientId = it.recipientId.toLong(),
        deliveryStatus = it.status.toBackupDeliveryStatus(),
        sealedSender = it.isUnidentified,
        lastStatusUpdateTimestamp = it.timestamp,
        networkFailure = networkFailureRecipientIds.contains(it.recipientId.toLong()),
        identityKeyMismatch = identityMismatchRecipientIds.contains(it.recipientId.toLong())
      )
    }
  }

  private fun Int.toBackupDeliveryStatus(): SendStatus.Status {
    return when (this) {
      GroupReceiptTable.STATUS_UNDELIVERED -> SendStatus.Status.PENDING
      GroupReceiptTable.STATUS_DELIVERED -> SendStatus.Status.DELIVERED
      GroupReceiptTable.STATUS_READ -> SendStatus.Status.READ
      GroupReceiptTable.STATUS_VIEWED -> SendStatus.Status.VIEWED
      GroupReceiptTable.STATUS_SKIPPED -> SendStatus.Status.SKIPPED
      else -> SendStatus.Status.SKIPPED
    }
  }

  private fun String?.parseNetworkFailures(): Set<Long> {
    if (this.isNullOrBlank()) {
      return emptySet()
    }

    return try {
      JsonUtils.fromJson(this, NetworkFailureSet::class.java).items.map { it.recipientId.toLong() }.toSet()
    } catch (e: IOException) {
      emptySet()
    }
  }

  private fun String?.parseIdentityMismatches(): Set<Long> {
    if (this.isNullOrBlank()) {
      return emptySet()
    }

    return try {
      JsonUtils.fromJson(this, IdentityKeyMismatchSet::class.java).items.map { it.recipientId.toLong() }.toSet()
    } catch (e: IOException) {
      emptySet()
    }
  }

  private fun ByteArray?.parseMessageExtras(): MessageExtras? {
    if (this == null) {
      return null
    }
    return try {
      MessageExtras.ADAPTER.decode(this)
    } catch (e: java.lang.Exception) {
      null
    }
  }

  private fun Cursor.toBackupMessageRecord(): BackupMessageRecord {
    return BackupMessageRecord(
      id = this.requireLong(MessageTable.ID),
      dateSent = this.requireLong(MessageTable.DATE_SENT),
      dateReceived = this.requireLong(MessageTable.DATE_RECEIVED),
      dateServer = this.requireLong(MessageTable.DATE_SERVER),
      type = this.requireLong(MessageTable.TYPE),
      threadId = this.requireLong(MessageTable.THREAD_ID),
      body = this.requireString(MessageTable.BODY),
      bodyRanges = this.requireBlob(MessageTable.MESSAGE_RANGES),
      fromRecipientId = this.requireLong(MessageTable.FROM_RECIPIENT_ID),
      toRecipientId = this.requireLong(MessageTable.TO_RECIPIENT_ID),
      expiresIn = this.requireLong(MessageTable.EXPIRES_IN),
      expireStarted = this.requireLong(MessageTable.EXPIRE_STARTED),
      remoteDeleted = this.requireBoolean(MessageTable.REMOTE_DELETED),
      sealedSender = this.requireBoolean(MessageTable.UNIDENTIFIED),
      quoteTargetSentTimestamp = this.requireLong(MessageTable.QUOTE_ID),
      quoteAuthor = this.requireLong(MessageTable.QUOTE_AUTHOR),
      quoteBody = this.requireString(MessageTable.QUOTE_BODY),
      quoteMissing = this.requireBoolean(MessageTable.QUOTE_MISSING),
      quoteBodyRanges = this.requireBlob(MessageTable.QUOTE_BODY_RANGES),
      quoteType = this.requireInt(MessageTable.QUOTE_TYPE),
      originalMessageId = this.requireLong(MessageTable.ORIGINAL_MESSAGE_ID),
      latestRevisionId = this.requireLong(MessageTable.LATEST_REVISION_ID),
      hasDeliveryReceipt = this.requireBoolean(MessageTable.HAS_DELIVERY_RECEIPT),
      viewed = this.requireBoolean(MessageTable.VIEWED_COLUMN),
      hasReadReceipt = this.requireBoolean(MessageTable.HAS_READ_RECEIPT),
      read = this.requireBoolean(MessageTable.READ),
      receiptTimestamp = this.requireLong(MessageTable.RECEIPT_TIMESTAMP),
      networkFailureRecipientIds = this.requireString(MessageTable.NETWORK_FAILURES).parseNetworkFailures(),
      identityMismatchRecipientIds = this.requireString(MessageTable.MISMATCHED_IDENTITIES).parseIdentityMismatches(),
      baseType = this.requireLong(COLUMN_BASE_TYPE),
      messageExtras = this.requireBlob(MessageTable.MESSAGE_EXTRAS).parseMessageExtras()
    )
  }

  private class BackupMessageRecord(
    val id: Long,
    val dateSent: Long,
    val dateReceived: Long,
    val dateServer: Long,
    val type: Long,
    val threadId: Long,
    val body: String?,
    val bodyRanges: ByteArray?,
    val fromRecipientId: Long,
    val toRecipientId: Long,
    val expiresIn: Long,
    val expireStarted: Long,
    val remoteDeleted: Boolean,
    val sealedSender: Boolean,
    val quoteTargetSentTimestamp: Long,
    val quoteAuthor: Long,
    val quoteBody: String?,
    val quoteMissing: Boolean,
    val quoteBodyRanges: ByteArray?,
    val quoteType: Int,
    val originalMessageId: Long,
    val latestRevisionId: Long,
    val hasDeliveryReceipt: Boolean,
    val hasReadReceipt: Boolean,
    val viewed: Boolean,
    val receiptTimestamp: Long,
    val read: Boolean,
    val networkFailureRecipientIds: Set<Long>,
    val identityMismatchRecipientIds: Set<Long>,
    val baseType: Long,
    val messageExtras: MessageExtras?
  )
}
