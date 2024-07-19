package org.stalker.securesms.mediasend

import android.content.Context
import androidx.annotation.WorkerThread
import org.stalker.securesms.database.AttachmentTable.TransformProperties
import org.stalker.securesms.mediasend.v2.videos.VideoTrimData
import org.stalker.securesms.mms.SentMediaQuality
import java.util.Optional

class VideoTrimTransform(private val data: VideoTrimData) : MediaTransform {
  @WorkerThread
  override fun transform(context: Context, media: Media): Media {
    return Media(
      media.uri,
      media.mimeType,
      media.date,
      media.width,
      media.height,
      media.size,
      media.duration,
      media.isBorderless,
      media.isVideoGif,
      media.bucketId,
      media.caption,
      Optional.of(TransformProperties(false, data.isDurationEdited, data.startTimeUs, data.endTimeUs, SentMediaQuality.STANDARD.code, false))
    )
  }
}
