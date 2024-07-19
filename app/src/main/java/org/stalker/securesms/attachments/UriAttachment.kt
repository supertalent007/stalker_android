package org.stalker.securesms.attachments

import android.net.Uri
import android.os.Parcel
import androidx.core.os.ParcelCompat
import org.stalker.securesms.audio.AudioHash
import org.stalker.securesms.blurhash.BlurHash
import org.stalker.securesms.database.AttachmentTable.TransformProperties
import org.stalker.securesms.stickers.StickerLocator
import java.util.Objects

class UriAttachment : Attachment {

  constructor(
    uri: Uri,
    contentType: String,
    transferState: Int,
    size: Long,
    fileName: String?,
    voiceNote: Boolean,
    borderless: Boolean,
    videoGif: Boolean,
    quote: Boolean,
    caption: String?,
    stickerLocator: StickerLocator?,
    blurHash: BlurHash?,
    audioHash: AudioHash?,
    transformProperties: TransformProperties?
  ) : this(
    dataUri = uri,
    contentType = contentType,
    transferState = transferState,
    size = size,
    width = 0,
    height = 0,
    fileName = fileName,
    fastPreflightId = null,
    voiceNote = voiceNote,
    borderless = borderless,
    videoGif = videoGif,
    quote = quote,
    caption = caption,
    stickerLocator = stickerLocator,
    blurHash = blurHash,
    audioHash = audioHash,
    transformProperties = transformProperties
  )

  constructor(
    dataUri: Uri,
    contentType: String,
    transferState: Int,
    size: Long,
    width: Int,
    height: Int,
    fileName: String?,
    fastPreflightId: String?,
    voiceNote: Boolean,
    borderless: Boolean,
    videoGif: Boolean,
    quote: Boolean,
    caption: String?,
    stickerLocator: StickerLocator?,
    blurHash: BlurHash?,
    audioHash: AudioHash?,
    transformProperties: TransformProperties?
  ) : super(
    contentType = contentType,
    transferState = transferState,
    size = size,
    fileName = fileName,
    cdn = Cdn.CDN_0,
    remoteLocation = null,
    remoteKey = null,
    remoteDigest = null,
    incrementalDigest = null,
    fastPreflightId = fastPreflightId,
    voiceNote = voiceNote,
    borderless = borderless,
    videoGif = videoGif,
    width = width,
    height = height,
    incrementalMacChunkSize = 0,
    quote = quote,
    uploadTimestamp = 0,
    caption = caption,
    stickerLocator = stickerLocator,
    blurHash = blurHash,
    audioHash = audioHash,
    transformProperties = transformProperties
  ) {
    uri = Objects.requireNonNull(dataUri)
  }

  constructor(parcel: Parcel) : super(parcel) {
    uri = ParcelCompat.readParcelable(parcel, Uri::class.java.classLoader, Uri::class.java)!!
  }

  override val uri: Uri
  override val publicUri: Uri? = null
  override val thumbnailUri: Uri? = null

  override fun writeToParcel(dest: Parcel, flags: Int) {
    super.writeToParcel(dest, flags)
    dest.writeParcelable(uri, 0)
  }

  override fun equals(other: Any?): Boolean {
    return other != null && other is UriAttachment && other.uri == uri
  }

  override fun hashCode(): Int {
    return uri.hashCode()
  }
}
