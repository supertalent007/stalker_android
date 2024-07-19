package org.stalker.securesms.attachments

import android.net.Uri
import android.os.Parcel
import androidx.core.os.ParcelCompat
import org.stalker.securesms.audio.AudioHash
import org.stalker.securesms.blurhash.BlurHash
import org.stalker.securesms.database.AttachmentTable.TransformProperties
import org.stalker.securesms.mms.PartAuthority
import org.stalker.securesms.stickers.StickerLocator
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.ParcelUtil

class DatabaseAttachment : Attachment {

  @JvmField
  val attachmentId: AttachmentId

  @JvmField
  val mmsId: Long

  @JvmField
  val hasData: Boolean

  @JvmField
  val dataHash: String?

  @JvmField
  val archiveCdn: Int

  @JvmField
  val archiveThumbnailCdn: Int

  @JvmField
  val archiveMediaName: String?

  @JvmField
  val archiveMediaId: String?

  private val hasArchiveThumbnail: Boolean
  private val hasThumbnail: Boolean
  val displayOrder: Int

  constructor(
    attachmentId: AttachmentId,
    mmsId: Long,
    hasData: Boolean,
    hasThumbnail: Boolean,
    hasArchiveThumbnail: Boolean,
    contentType: String?,
    transferProgress: Int,
    size: Long,
    fileName: String?,
    cdn: Cdn,
    location: String?,
    key: String?,
    digest: ByteArray?,
    incrementalDigest: ByteArray?,
    incrementalMacChunkSize: Int,
    fastPreflightId: String?,
    voiceNote: Boolean,
    borderless: Boolean,
    videoGif: Boolean,
    width: Int,
    height: Int,
    quote: Boolean,
    caption: String?,
    stickerLocator: StickerLocator?,
    blurHash: BlurHash?,
    audioHash: AudioHash?,
    transformProperties: TransformProperties?,
    displayOrder: Int,
    uploadTimestamp: Long,
    dataHash: String?,
    archiveCdn: Int,
    archiveThumbnailCdn: Int,
    archiveMediaName: String?,
    archiveMediaId: String?
  ) : super(
    contentType = contentType!!,
    transferState = transferProgress,
    size = size,
    fileName = fileName,
    cdn = cdn,
    remoteLocation = location,
    remoteKey = key,
    remoteDigest = digest,
    incrementalDigest = incrementalDigest,
    fastPreflightId = fastPreflightId,
    voiceNote = voiceNote,
    borderless = borderless,
    videoGif = videoGif, width = width,
    height = height,
    incrementalMacChunkSize = incrementalMacChunkSize,
    quote = quote,
    uploadTimestamp = uploadTimestamp,
    caption = caption,
    stickerLocator = stickerLocator,
    blurHash = blurHash,
    audioHash = audioHash,
    transformProperties = transformProperties
  ) {
    this.attachmentId = attachmentId
    this.mmsId = mmsId
    this.hasData = hasData
    this.dataHash = dataHash
    this.hasThumbnail = hasThumbnail
    this.hasArchiveThumbnail = hasArchiveThumbnail
    this.displayOrder = displayOrder
    this.archiveCdn = archiveCdn
    this.archiveThumbnailCdn = archiveThumbnailCdn
    this.archiveMediaName = archiveMediaName
    this.archiveMediaId = archiveMediaId
  }

  constructor(parcel: Parcel) : super(parcel) {
    attachmentId = ParcelCompat.readParcelable(parcel, AttachmentId::class.java.classLoader, AttachmentId::class.java)!!
    hasData = ParcelUtil.readBoolean(parcel)
    dataHash = parcel.readString()
    hasThumbnail = ParcelUtil.readBoolean(parcel)
    mmsId = parcel.readLong()
    displayOrder = parcel.readInt()
    archiveCdn = parcel.readInt()
    archiveThumbnailCdn = parcel.readInt()
    archiveMediaName = parcel.readString()
    archiveMediaId = parcel.readString()
    hasArchiveThumbnail = ParcelUtil.readBoolean(parcel)
  }

  override fun writeToParcel(dest: Parcel, flags: Int) {
    super.writeToParcel(dest, flags)
    dest.writeParcelable(attachmentId, 0)
    ParcelUtil.writeBoolean(dest, hasData)
    dest.writeString(dataHash)
    ParcelUtil.writeBoolean(dest, hasThumbnail)
    dest.writeLong(mmsId)
    dest.writeInt(displayOrder)
    dest.writeInt(archiveCdn)
    dest.writeInt(archiveThumbnailCdn)
    dest.writeString(archiveMediaName)
    dest.writeString(archiveMediaId)
    ParcelUtil.writeBoolean(dest, hasArchiveThumbnail)
  }

  override val uri: Uri?
    get() = if (hasData || FeatureFlags.instantVideoPlayback() && getIncrementalDigest() != null) {
      PartAuthority.getAttachmentDataUri(attachmentId)
    } else {
      null
    }

  override val publicUri: Uri?
    get() = if (hasData) {
      PartAuthority.getAttachmentPublicUri(uri)
    } else {
      null
    }

  override val thumbnailUri: Uri?
    get() = if (hasArchiveThumbnail) {
      PartAuthority.getAttachmentThumbnailUri(attachmentId)
    } else {
      null
    }

  override fun equals(other: Any?): Boolean {
    return other != null &&
      other is DatabaseAttachment && other.attachmentId == attachmentId
  }

  override fun hashCode(): Int {
    return attachmentId.hashCode()
  }

  class DisplayOrderComparator : Comparator<DatabaseAttachment> {
    override fun compare(lhs: DatabaseAttachment, rhs: DatabaseAttachment): Int {
      return lhs.displayOrder.compareTo(rhs.displayOrder)
    }
  }
}
