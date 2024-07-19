package org.stalker.securesms.releasechannel

import org.stalker.securesms.attachments.Cdn
import org.stalker.securesms.attachments.PointerAttachment
import org.stalker.securesms.database.MessageTable
import org.stalker.securesms.database.MessageType
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.StoryType
import org.stalker.securesms.database.model.databaseprotos.BodyRangeList
import org.stalker.securesms.mms.IncomingMessage
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.MediaUtil
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import java.util.Optional
import java.util.UUID

/**
 * One stop shop for inserting Release Channel messages.
 */
object ReleaseChannel {

  fun insertReleaseChannelMessage(
    recipientId: RecipientId,
    body: String,
    threadId: Long,
    media: String? = null,
    mediaWidth: Int = 0,
    mediaHeight: Int = 0,
    mediaType: String = "image/webp",
    serverUuid: String? = UUID.randomUUID().toString(),
    messageRanges: BodyRangeList? = null,
    storyType: StoryType = StoryType.NONE
  ): MessageTable.InsertResult? {
    val attachments: Optional<List<SignalServiceAttachment>> = if (media != null) {
      val attachment = SignalServiceAttachmentPointer(
        Cdn.S3.cdnNumber,
        SignalServiceAttachmentRemoteId.S3,
        mediaType,
        null,
        Optional.empty(),
        Optional.empty(),
        mediaWidth,
        mediaHeight,
        Optional.empty(),
        Optional.empty(),
        0,
        Optional.of(media),
        false,
        false,
        MediaUtil.isVideo(mediaType),
        Optional.empty(),
        Optional.empty(),
        System.currentTimeMillis()
      )

      Optional.of(listOf(attachment))
    } else {
      Optional.empty()
    }

    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = recipientId,
      sentTimeMillis = System.currentTimeMillis(),
      serverTimeMillis = System.currentTimeMillis(),
      receivedTimeMillis = System.currentTimeMillis(),
      body = body,
      attachments = PointerAttachment.forPointers(attachments),
      serverGuid = serverUuid,
      messageRanges = messageRanges,
      storyType = storyType
    )

    return SignalDatabase.messages.insertMessageInbox(message, threadId).orElse(null)
  }
}
