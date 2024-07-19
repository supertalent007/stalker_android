package org.stalker.securesms.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.stalker.securesms.database.model.StoryType
import org.stalker.securesms.mms.OutgoingMessage
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId

/**
 * Helper methods for inserting an MMS message into the MMS table.
 */
object TestMms {

  fun insert(
    db: SQLiteDatabase,
    recipient: Recipient = Recipient.UNKNOWN,
    recipientId: RecipientId = Recipient.UNKNOWN.id,
    body: String = "body",
    sentTimeMillis: Long = System.currentTimeMillis(),
    receivedTimestampMillis: Long = System.currentTimeMillis(),
    expiresIn: Long = 0,
    viewOnce: Boolean = false,
    distributionType: Int = ThreadTable.DistributionTypes.DEFAULT,
    type: Long = MessageTypes.BASE_INBOX_TYPE,
    unread: Boolean = false,
    viewed: Boolean = false,
    threadId: Long = 1,
    storyType: StoryType = StoryType.NONE
  ): Long {
    val message = OutgoingMessage(
      recipient,
      body,
      emptyList(),
      sentTimeMillis,
      expiresIn,
      viewOnce,
      distributionType,
      storyType,
      null,
      false,
      null,
      emptyList(),
      emptyList(),
      emptyList(),
      emptySet(),
      emptySet(),
      null
    )

    return insert(
      db = db,
      message = message,
      recipientId = recipientId,
      body = body,
      type = type,
      unread = unread,
      viewed = viewed,
      threadId = threadId,
      receivedTimestampMillis = receivedTimestampMillis
    )
  }

  fun insert(
    db: SQLiteDatabase,
    message: OutgoingMessage,
    recipientId: RecipientId = message.threadRecipient.id,
    body: String = message.body,
    type: Long = MessageTypes.BASE_INBOX_TYPE,
    unread: Boolean = false,
    viewed: Boolean = false,
    threadId: Long = 1,
    receivedTimestampMillis: Long = System.currentTimeMillis()
  ): Long {
    val contentValues = ContentValues().apply {
      put(MessageTable.DATE_SENT, message.sentTimeMillis)

      put(MessageTable.TYPE, type)
      put(MessageTable.THREAD_ID, threadId)
      put(MessageTable.READ, if (unread) 0 else 1)
      put(MessageTable.DATE_RECEIVED, receivedTimestampMillis)
      put(MessageTable.SMS_SUBSCRIPTION_ID, message.subscriptionId)
      put(MessageTable.EXPIRES_IN, message.expiresIn)
      put(MessageTable.VIEW_ONCE, message.isViewOnce)
      put(MessageTable.FROM_RECIPIENT_ID, recipientId.serialize())
      put(MessageTable.TO_RECIPIENT_ID, recipientId.serialize())
      put(MessageTable.HAS_DELIVERY_RECEIPT, 0)
      put(MessageTable.RECEIPT_TIMESTAMP, 0)
      put(MessageTable.VIEWED_COLUMN, if (viewed) 1 else 0)
      put(MessageTable.STORY_TYPE, message.storyType.code)

      put(MessageTable.BODY, body)
      put(MessageTable.MENTIONS_SELF, 0)
    }

    return db.insert(MessageTable.TABLE_NAME, null, contentValues)
  }

  fun markAsRemoteDelete(db: SQLiteDatabase, messageId: Long) {
    val values = ContentValues()
    values.put(MessageTable.REMOTE_DELETED, 1)
    values.putNull(MessageTable.BODY)
    values.putNull(MessageTable.QUOTE_BODY)
    values.putNull(MessageTable.QUOTE_AUTHOR)
    values.put(MessageTable.QUOTE_TYPE, -1)
    values.putNull(MessageTable.QUOTE_ID)
    values.putNull(MessageTable.LINK_PREVIEWS)
    values.putNull(MessageTable.SHARED_CONTACTS)
    db.update(MessageTable.TABLE_NAME, values, DatabaseTable.ID_WHERE, arrayOf(messageId.toString()))
  }
}
