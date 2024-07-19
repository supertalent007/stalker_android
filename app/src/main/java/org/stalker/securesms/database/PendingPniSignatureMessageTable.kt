package org.stalker.securesms.database

import android.content.Context
import androidx.core.content.contentValuesOf
import org.signal.core.util.delete
import org.signal.core.util.deleteAll
import org.signal.core.util.exists
import org.signal.core.util.logging.Log
import org.signal.core.util.update
import org.signal.core.util.withinTransaction
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.LRUCache
import org.whispersystems.signalservice.api.messages.SendMessageResult
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Contains records of messages that have been sent with PniSignatures on them.
 * When we receive delivery receipts for these messages, we remove entries from the table and can clear
 * the `needsPniSignature` flag on the recipient when all are delivered.
 */
class PendingPniSignatureMessageTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper), RecipientIdDatabaseReference {

  companion object {
    private val TAG = Log.tag(PendingPniSignatureMessageTable::class.java)

    const val TABLE_NAME = "pending_pni_signature_message"

    private const val ID = "_id"
    private const val RECIPIENT_ID = "recipient_id"
    private const val SENT_TIMESTAMP = "sent_timestamp"
    private const val DEVICE_ID = "device_id"

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY,
        $RECIPIENT_ID INTEGER NOT NULL REFERENCES ${RecipientTable.TABLE_NAME} (${RecipientTable.ID}) ON DELETE CASCADE,
        $SENT_TIMESTAMP INTEGER NOT NULL,
        $DEVICE_ID INTEGER NOT NULL
      )
     """

    val CREATE_INDEXES = arrayOf(
      "CREATE UNIQUE INDEX pending_pni_recipient_sent_device_index ON $TABLE_NAME ($RECIPIENT_ID, $SENT_TIMESTAMP, $DEVICE_ID)"
    )
  }

  /**
   * Caches whether or not there are any pending PNI signature messages for a given recipient.
   * - If there are, there will be an entry with 'true' for that recipient.
   * - If there aren't, there will be an entry with 'false'.
   * - If the entry is null, we do not know, and you should check the database.
   *
   * This cache exists because this table is hit very frequently via delivery receipt handling.
   */
  private val pendingPniSignaturesCache: MutableMap<RecipientId, Boolean?> = LRUCache(1000)
  private val pendingPniSignaturesCacheLock = ReentrantReadWriteLock()

  fun insertIfNecessary(recipientId: RecipientId, sentTimestamp: Long, result: SendMessageResult) {
    if (!result.isSuccess || result.success.devices.isEmpty()) {
      return
    }

    pendingPniSignaturesCacheLock.writeLock().withLock {
      writableDatabase.withinTransaction { db ->
        for (deviceId in result.success.devices) {
          val values = contentValuesOf(
            RECIPIENT_ID to recipientId.serialize(),
            SENT_TIMESTAMP to sentTimestamp,
            DEVICE_ID to deviceId
          )

          db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
      }

      pendingPniSignaturesCache[recipientId] = true
    }
  }

  fun acknowledgeReceipts(recipientId: RecipientId, sentTimestamps: Collection<Long>, deviceId: Int) {
    pendingPniSignaturesCacheLock.readLock().withLock {
      if (pendingPniSignaturesCache[recipientId] == false) {
        return
      }
    }

    pendingPniSignaturesCacheLock.writeLock().withLock {
      writableDatabase.withinTransaction { db ->
        val count = db
          .delete(TABLE_NAME)
          .where("$RECIPIENT_ID = ? AND $SENT_TIMESTAMP IN (?) AND $DEVICE_ID = ?", recipientId, sentTimestamps.joinToString(separator = ","), deviceId)
          .run()

        if (count <= 0) {
          pendingPniSignaturesCache[recipientId] = false
          return@withinTransaction
        }

        val stillPending: Boolean = db.exists(TABLE_NAME).where("$RECIPIENT_ID = ? AND $SENT_TIMESTAMP = ?", recipientId, sentTimestamps).run()

        if (!stillPending) {
          Log.i(TAG, "All devices for ($recipientId, $sentTimestamps) have acked the PNI signature message. Clearing flag and removing any other pending receipts.")
          SignalDatabase.recipients.clearNeedsPniSignature(recipientId)

          db
            .delete(TABLE_NAME)
            .where("$RECIPIENT_ID = ?", recipientId)
            .run()
        }

        pendingPniSignaturesCache[recipientId] = stillPending
      }
    }
  }

  /**
   * Deletes all record of pending PNI verification messages. Should only be called after the user changes their number.
   */
  fun deleteAll() {
    writableDatabase.deleteAll(TABLE_NAME)
  }

  override fun remapRecipient(oldId: RecipientId, newId: RecipientId) {
    writableDatabase
      .update(TABLE_NAME)
      .values(RECIPIENT_ID to newId.serialize())
      .where("$RECIPIENT_ID = ?", oldId)
      .run()
  }
}
