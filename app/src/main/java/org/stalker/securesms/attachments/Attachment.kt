/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.stalker.securesms.attachments

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.core.os.ParcelCompat
import org.stalker.securesms.audio.AudioHash
import org.stalker.securesms.blurhash.BlurHash
import org.stalker.securesms.database.AttachmentTable
import org.stalker.securesms.database.AttachmentTable.TransformProperties
import org.stalker.securesms.stickers.StickerLocator
import org.stalker.securesms.util.ParcelUtil

/**
 * Note: We have to use our own Parcelable implementation because we need to do custom stuff to preserve
 * subclass information.
 */
abstract class Attachment(
  @JvmField
  val contentType: String,
  @JvmField
  val transferState: Int,
  @JvmField
  val size: Long,
  @JvmField
  val fileName: String?,
  @JvmField
  val cdn: Cdn,
  @JvmField
  val remoteLocation: String?,
  @JvmField
  val remoteKey: String?,
  @JvmField
  val remoteDigest: ByteArray?,
  @JvmField
  val incrementalDigest: ByteArray?,
  @JvmField
  val fastPreflightId: String?,
  @JvmField
  val voiceNote: Boolean,
  @JvmField
  val borderless: Boolean,
  @JvmField
  val videoGif: Boolean,
  @JvmField
  val width: Int,
  @JvmField
  val height: Int,
  @JvmField
  val incrementalMacChunkSize: Int,
  @JvmField
  val quote: Boolean,
  @JvmField
  val uploadTimestamp: Long,
  @JvmField
  val caption: String?,
  @JvmField
  val stickerLocator: StickerLocator?,
  @JvmField
  val blurHash: BlurHash?,
  @JvmField
  val audioHash: AudioHash?,
  @JvmField
  val transformProperties: TransformProperties?
) : Parcelable {

  abstract val uri: Uri?
  abstract val publicUri: Uri?
  abstract val thumbnailUri: Uri?

  protected constructor(parcel: Parcel) : this(
    contentType = parcel.readString()!!,
    transferState = parcel.readInt(),
    size = parcel.readLong(),
    fileName = parcel.readString(),
    cdn = Cdn.deserialize(parcel.readInt()),
    remoteLocation = parcel.readString(),
    remoteKey = parcel.readString(),
    remoteDigest = ParcelUtil.readByteArray(parcel),
    incrementalDigest = ParcelUtil.readByteArray(parcel),
    fastPreflightId = parcel.readString(),
    voiceNote = ParcelUtil.readBoolean(parcel),
    borderless = ParcelUtil.readBoolean(parcel),
    videoGif = ParcelUtil.readBoolean(parcel),
    width = parcel.readInt(),
    height = parcel.readInt(),
    incrementalMacChunkSize = parcel.readInt(),
    quote = ParcelUtil.readBoolean(parcel),
    uploadTimestamp = parcel.readLong(),
    caption = parcel.readString(),
    stickerLocator = ParcelCompat.readParcelable(parcel, StickerLocator::class.java.classLoader, StickerLocator::class.java),
    blurHash = ParcelCompat.readParcelable(parcel, BlurHash::class.java.classLoader, BlurHash::class.java),
    audioHash = ParcelCompat.readParcelable(parcel, AudioHash::class.java.classLoader, AudioHash::class.java),
    transformProperties = ParcelCompat.readParcelable(parcel, TransformProperties::class.java.classLoader, TransformProperties::class.java)
  )

  override fun writeToParcel(dest: Parcel, flags: Int) {
    AttachmentCreator.writeSubclass(dest, this)
    dest.writeString(contentType)
    dest.writeInt(transferState)
    dest.writeLong(size)
    dest.writeString(fileName)
    dest.writeInt(cdn.serialize())
    dest.writeString(remoteLocation)
    dest.writeString(remoteKey)
    ParcelUtil.writeByteArray(dest, remoteDigest)
    ParcelUtil.writeByteArray(dest, incrementalDigest)
    dest.writeString(fastPreflightId)
    ParcelUtil.writeBoolean(dest, voiceNote)
    ParcelUtil.writeBoolean(dest, borderless)
    ParcelUtil.writeBoolean(dest, videoGif)
    dest.writeInt(width)
    dest.writeInt(height)
    dest.writeInt(incrementalMacChunkSize)
    ParcelUtil.writeBoolean(dest, quote)
    dest.writeLong(uploadTimestamp)
    dest.writeString(caption)
    dest.writeParcelable(stickerLocator, 0)
    dest.writeParcelable(blurHash, 0)
    dest.writeParcelable(audioHash, 0)
    dest.writeParcelable(transformProperties, 0)
  }

  override fun describeContents(): Int {
    return 0
  }

  val isInProgress: Boolean
    get() = transferState != AttachmentTable.TRANSFER_PROGRESS_DONE && transferState != AttachmentTable.TRANSFER_PROGRESS_FAILED && transferState != AttachmentTable.TRANSFER_PROGRESS_PERMANENT_FAILURE && transferState != AttachmentTable.TRANSFER_RESTORE_OFFLOADED

  val isPermanentlyFailed: Boolean
    get() = transferState == AttachmentTable.TRANSFER_PROGRESS_PERMANENT_FAILURE

  val isSticker: Boolean
    get() = stickerLocator != null

  fun getIncrementalDigest(): ByteArray? {
    return if (incrementalDigest != null && incrementalDigest.size > 0) {
      incrementalDigest
    } else {
      null
    }
  }

  companion object {
    @JvmField
    val CREATOR: Parcelable.Creator<Attachment> = AttachmentCreator
  }
}
