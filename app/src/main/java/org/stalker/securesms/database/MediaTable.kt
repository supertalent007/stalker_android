package org.stalker.securesms.database

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import androidx.compose.runtime.Immutable
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.stalker.securesms.attachments.DatabaseAttachment
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.MediaUtil
import org.stalker.securesms.util.MediaUtil.SlideType

@SuppressLint("RecipientIdDatabaseReferenceUsage", "ThreadIdDatabaseReferenceUsage") // Not a real table, just a view
class MediaTable internal constructor(context: Context?, databaseHelper: SignalDatabase?) : DatabaseTable(context, databaseHelper) {

  companion object {
    const val ALL_THREADS = -1
    private const val THREAD_RECIPIENT_ID = "THREAD_RECIPIENT_ID"
    private val BASE_MEDIA_QUERY = """
      SELECT 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.ID} AS ${AttachmentTable.ID}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.CONTENT_TYPE}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.MESSAGE_ID}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.TRANSFER_STATE}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DATA_SIZE}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.FILE_NAME}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DATA_FILE}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.THUMBNAIL_FILE}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.CDN_NUMBER},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.REMOTE_LOCATION},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.REMOTE_KEY},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.REMOTE_DIGEST}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.FAST_PREFLIGHT_ID},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.VOICE_NOTE}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.BORDERLESS}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.VIDEO_GIF}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.WIDTH}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.HEIGHT}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.QUOTE}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.STICKER_PACK_ID}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.STICKER_PACK_KEY}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.STICKER_ID}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.STICKER_EMOJI}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.BLUR_HASH}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.TRANSFORM_PROPERTIES}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DISPLAY_ORDER}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.CAPTION}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.UPLOAD_TIMESTAMP}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.REMOTE_INCREMENTAL_DIGEST}, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.REMOTE_INCREMENTAL_DIGEST_CHUNK_SIZE},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DATA_HASH_END},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.ARCHIVE_CDN},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.ARCHIVE_MEDIA_NAME},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.ARCHIVE_MEDIA_ID},
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.ARCHIVE_THUMBNAIL_CDN},
        ${MessageTable.TABLE_NAME}.${MessageTable.TYPE}, 
        ${MessageTable.TABLE_NAME}.${MessageTable.DATE_SENT}, 
        ${MessageTable.TABLE_NAME}.${MessageTable.DATE_RECEIVED}, 
        ${MessageTable.TABLE_NAME}.${MessageTable.DATE_SERVER}, 
        ${MessageTable.TABLE_NAME}.${MessageTable.THREAD_ID}, 
        ${MessageTable.TABLE_NAME}.${MessageTable.FROM_RECIPIENT_ID}, 
        ${ThreadTable.TABLE_NAME}.${ThreadTable.RECIPIENT_ID} as $THREAD_RECIPIENT_ID 
      FROM 
        ${AttachmentTable.TABLE_NAME} 
        LEFT JOIN ${MessageTable.TABLE_NAME} ON ${AttachmentTable.TABLE_NAME}.${AttachmentTable.MESSAGE_ID} = ${MessageTable.TABLE_NAME}.${MessageTable.ID} 
        LEFT JOIN ${ThreadTable.TABLE_NAME} ON ${ThreadTable.TABLE_NAME}.${ThreadTable.ID} = ${MessageTable.TABLE_NAME}.${MessageTable.THREAD_ID} 
      WHERE 
        ${AttachmentTable.MESSAGE_ID} IN (
          SELECT ${MessageTable.ID} 
          FROM ${MessageTable.TABLE_NAME} 
          WHERE ${MessageTable.THREAD_ID} __EQUALITY__ ?
        ) AND 
        (%s) AND 
        ${MessageTable.VIEW_ONCE} = 0 AND 
        ${MessageTable.STORY_TYPE} = 0 AND
        ${MessageTable.LATEST_REVISION_ID} IS NULL AND 
        ${AttachmentTable.QUOTE} = 0 AND 
        ${AttachmentTable.STICKER_PACK_ID} IS NULL AND 
        ${MessageTable.TABLE_NAME}.${MessageTable.FROM_RECIPIENT_ID} > 0 AND 
        $THREAD_RECIPIENT_ID > 0
      """

    private val UNIQUE_MEDIA_QUERY = """
        SELECT 
          MAX(${AttachmentTable.DATA_SIZE}) as ${AttachmentTable.DATA_SIZE}, 
          ${AttachmentTable.CONTENT_TYPE} 
        FROM 
          ${AttachmentTable.TABLE_NAME} 
        WHERE 
          ${AttachmentTable.STICKER_PACK_ID} IS NULL AND 
          ${AttachmentTable.TRANSFER_STATE} = ${AttachmentTable.TRANSFER_PROGRESS_DONE} 
        GROUP BY ${AttachmentTable.DATA_FILE}
      """

    private val GALLERY_MEDIA_QUERY = String.format(
      BASE_MEDIA_QUERY,
      """
        ${AttachmentTable.DATA_FILE} IS NOT NULL AND
        ${AttachmentTable.CONTENT_TYPE} NOT LIKE 'image/svg%' AND 
        (${AttachmentTable.CONTENT_TYPE} LIKE 'image/%' OR ${AttachmentTable.CONTENT_TYPE} LIKE 'video/%') AND
        ${MessageTable.LINK_PREVIEWS} IS NULL
      """
    )

    private val GALLERY_MEDIA_QUERY_INCLUDING_TEMP_VIDEOS = String.format(
      BASE_MEDIA_QUERY,
      """
        (${AttachmentTable.DATA_FILE} IS NOT NULL OR (${AttachmentTable.CONTENT_TYPE} LIKE 'video/%' AND ${AttachmentTable.REMOTE_INCREMENTAL_DIGEST} IS NOT NULL)) AND
        ${AttachmentTable.CONTENT_TYPE} NOT LIKE 'image/svg%' AND 
        (${AttachmentTable.CONTENT_TYPE} LIKE 'image/%' OR ${AttachmentTable.CONTENT_TYPE} LIKE 'video/%') AND
        ${MessageTable.LINK_PREVIEWS} IS NULL
      """
    )

    private val AUDIO_MEDIA_QUERY = String.format(
      BASE_MEDIA_QUERY,
      """
        ${AttachmentTable.DATA_FILE} IS NOT NULL AND
        ${AttachmentTable.CONTENT_TYPE} LIKE 'audio/%'
      """
    )

    private val ALL_MEDIA_QUERY = String.format(
      BASE_MEDIA_QUERY,
      """
        ${AttachmentTable.DATA_FILE} IS NOT NULL AND
        ${AttachmentTable.CONTENT_TYPE} NOT LIKE 'text/x-signal-plain' AND
        ${MessageTable.LINK_PREVIEWS} IS NULL
      """
    )

    private val DOCUMENT_MEDIA_QUERY = String.format(
      BASE_MEDIA_QUERY,
      """
        ${AttachmentTable.DATA_FILE} IS NOT NULL AND
        (
          ${AttachmentTable.CONTENT_TYPE} LIKE 'image/svg%' OR 
          (
            ${AttachmentTable.CONTENT_TYPE} NOT LIKE 'image/%' AND 
            ${AttachmentTable.CONTENT_TYPE} NOT LIKE 'video/%' AND 
            ${AttachmentTable.CONTENT_TYPE} NOT LIKE 'audio/%' AND 
            ${AttachmentTable.CONTENT_TYPE} NOT LIKE 'text/x-signal-plain'
          )
        )"""
    )
    private fun applyEqualityOperator(threadId: Long, query: String): String {
      return query.replace("__EQUALITY__", if (threadId == ALL_THREADS.toLong()) "!=" else "=")
    }
  }

  @JvmOverloads
  fun getGalleryMediaForThread(threadId: Long, sorting: Sorting, limit: Int = 0): Cursor {
    var query = if (FeatureFlags.instantVideoPlayback()) {
      sorting.applyToQuery(applyEqualityOperator(threadId, GALLERY_MEDIA_QUERY_INCLUDING_TEMP_VIDEOS))
    } else {
      sorting.applyToQuery(applyEqualityOperator(threadId, GALLERY_MEDIA_QUERY))
    }
    val args = arrayOf(threadId.toString() + "")

    if (limit > 0) {
      query = "$query LIMIT $limit"
    }

    return readableDatabase.rawQuery(query, args)
  }

  fun getDocumentMediaForThread(threadId: Long, sorting: Sorting): Cursor {
    val query = sorting.applyToQuery(applyEqualityOperator(threadId, DOCUMENT_MEDIA_QUERY))
    val args = arrayOf(threadId.toString() + "")
    return readableDatabase.rawQuery(query, args)
  }

  fun getAudioMediaForThread(threadId: Long, sorting: Sorting): Cursor {
    val query = sorting.applyToQuery(applyEqualityOperator(threadId, AUDIO_MEDIA_QUERY))
    val args = arrayOf(threadId.toString() + "")
    return readableDatabase.rawQuery(query, args)
  }

  fun getAllMediaForThread(threadId: Long, sorting: Sorting): Cursor {
    val query = sorting.applyToQuery(applyEqualityOperator(threadId, ALL_MEDIA_QUERY))
    val args = arrayOf(threadId.toString() + "")
    return readableDatabase.rawQuery(query, args)
  }

  fun getStorageBreakdown(): StorageBreakdown {
    var photoSize: Long = 0
    var videoSize: Long = 0
    var audioSize: Long = 0
    var documentSize: Long = 0

    readableDatabase.rawQuery(UNIQUE_MEDIA_QUERY, null).use { cursor ->
      while (cursor.moveToNext()) {
        val size: Int = cursor.requireInt(AttachmentTable.DATA_SIZE)
        val type: String = cursor.requireNonNullString(AttachmentTable.CONTENT_TYPE)

        when (MediaUtil.getSlideTypeFromContentType(type)) {
          SlideType.GIF,
          SlideType.IMAGE,
          SlideType.MMS -> {
            photoSize += size.toLong()
          }
          SlideType.VIDEO -> {
            videoSize += size.toLong()
          }
          SlideType.AUDIO -> {
            audioSize += size.toLong()
          }
          SlideType.LONG_TEXT,
          SlideType.DOCUMENT -> {
            documentSize += size.toLong()
          }
          else -> {}
        }
      }
    }

    return StorageBreakdown(
      photoSize = photoSize,
      videoSize = videoSize,
      audioSize = audioSize,
      documentSize = documentSize
    )
  }

  data class MediaRecord constructor(
    val attachment: DatabaseAttachment?,
    val recipientId: RecipientId,
    val threadRecipientId: RecipientId,
    val threadId: Long,
    val date: Long,
    val isOutgoing: Boolean
  ) {

    val contentType: String
      get() = attachment!!.contentType

    companion object {
      @JvmStatic
      fun from(cursor: Cursor): MediaRecord {
        val attachments = SignalDatabase.attachments.getAttachments(cursor)

        return MediaRecord(
          attachment = if (attachments.isNotEmpty()) attachments[0] else null,
          recipientId = RecipientId.from(cursor.requireLong(MessageTable.FROM_RECIPIENT_ID)),
          threadId = cursor.requireLong(MessageTable.THREAD_ID),
          threadRecipientId = RecipientId.from(cursor.requireLong(THREAD_RECIPIENT_ID)),
          date = if (MessageTypes.isPushType(cursor.requireLong(MessageTable.TYPE))) {
            cursor.requireLong(MessageTable.DATE_SENT)
          } else {
            cursor.requireLong(MessageTable.DATE_RECEIVED)
          },
          isOutgoing = MessageTypes.isOutgoingMessageType(cursor.requireLong(MessageTable.TYPE))
        )
      }
    }
  }

  enum class Sorting(order: String) {
    Newest(
      """
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.MESSAGE_ID} DESC, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DISPLAY_ORDER} DESC, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.ID} DESC
      """
    ),
    Oldest(
      """
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.MESSAGE_ID} ASC, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DISPLAY_ORDER} DESC, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.ID} ASC
      """
    ),
    Largest(
      """
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DATA_SIZE} DESC, 
        ${AttachmentTable.TABLE_NAME}.${AttachmentTable.DISPLAY_ORDER} DESC
      """
    );

    private val postFix: String

    init {
      postFix = " ORDER BY $order"
    }

    fun applyToQuery(query: String): String {
      return query + postFix
    }

    val isRelatedToFileSize: Boolean
      get() = this == Largest

    companion object {
      fun deserialize(code: Int): Sorting {
        return when (code) {
          0 -> Newest
          1 -> Oldest
          2 -> Largest
          else -> throw IllegalArgumentException("Unknown code: $code")
        }
      }
    }
  }

  @Immutable
  data class StorageBreakdown(
    val photoSize: Long,
    val videoSize: Long,
    val audioSize: Long,
    val documentSize: Long
  )
}
