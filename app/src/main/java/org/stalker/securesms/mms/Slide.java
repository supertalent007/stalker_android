/** 
 * Copyright (C) 2011 Whisper Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.stalker.securesms.mms;

import android.content.Context;
import android.content.res.Resources.Theme;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.stalker.securesms.attachments.Attachment;
import org.stalker.securesms.attachments.UriAttachment;
import org.stalker.securesms.audio.AudioHash;
import org.stalker.securesms.blurhash.BlurHash;
import org.stalker.securesms.database.AttachmentTable;
import org.stalker.securesms.stickers.StickerLocator;
import org.stalker.securesms.util.MediaUtil;
import org.stalker.securesms.util.Util;

import java.security.SecureRandom;
import java.util.Optional;

public abstract class Slide {

  protected final Attachment attachment;

  public Slide(@NonNull Attachment attachment) {
    this.attachment = attachment;
  }

  public String getContentType() {
    return attachment.contentType;
  }

  @Nullable
  public Uri getThumbnailUri() {
    return attachment.getThumbnailUri();
  }

  @Nullable
  public Uri getUri() {
    Uri attachmentUri = attachment.getUri();
    if (attachmentUri != null) {
      return attachmentUri;
    }
    return attachment.getThumbnailUri();
  }

  public @Nullable Uri getPublicUri() {
    if (Build.VERSION.SDK_INT >= 28) {
      return attachment.getPublicUri();
    } else {
      return attachment.getUri();
    }
  }

  @NonNull
  public Optional<String> getBody() {
    return Optional.empty();
  }

  @NonNull
  public Optional<String> getCaption() {
    return Optional.ofNullable(attachment.caption);
  }

  @NonNull
  public Optional<String> getFileName() {
    return Optional.ofNullable(attachment.fileName);
  }

  @Nullable
  public String getFastPreflightId() {
    return attachment.fastPreflightId;
  }

  public long getFileSize() {
    return attachment.size;
  }

  public boolean hasImage() {
    return false;
  }

  public boolean hasSticker() { return false; }

  public boolean hasVideo() {
    return false;
  }

  public boolean hasAudio() {
    return false;
  }

  public boolean hasDocument() {
    return false;
  }

  public boolean hasLocation() {
    return false;
  }

  public boolean hasViewOnce() {
    return false;
  }

  public boolean isBorderless() {
    return false;
  }

  public boolean isVideoGif() {
    return hasVideo() && attachment.videoGif;
  }

  public @NonNull String getContentDescription(@NonNull Context context) { return ""; }

  public @NonNull Attachment asAttachment() {
    return attachment;
  }

  public boolean isInProgress() {
    return attachment.isInProgress();
  }

  public boolean isPendingDownload() {
    return getTransferState() == AttachmentTable.TRANSFER_PROGRESS_FAILED ||
           getTransferState() == AttachmentTable.TRANSFER_PROGRESS_PENDING;
  }

  public int getTransferState() {
    return attachment.transferState;
  }

  public @DrawableRes int getPlaceholderRes(Theme theme) {
    throw new AssertionError("getPlaceholderRes() called for non-drawable slide");
  }

  public @Nullable BlurHash getPlaceholderBlur() {
    return attachment.blurHash;
  }

  public boolean hasPlaceholder() {
    return false;
  }

  public boolean hasPlayOverlay() {
    return false;
  }

  protected static Attachment constructAttachmentFromUri(@NonNull  Context        context,
                                                         @NonNull  Uri            uri,
                                                         @NonNull  String         defaultMime,
                                                                   long           size,
                                                                   int            width,
                                                                   int            height,
                                                                   boolean        hasThumbnail,
                                                         @Nullable String         fileName,
                                                         @Nullable String         caption,
                                                         @Nullable StickerLocator stickerLocator,
                                                         @Nullable BlurHash       blurHash,
                                                         @Nullable AudioHash      audioHash,
                                                                   boolean        voiceNote,
                                                                   boolean        borderless,
                                                                   boolean        gif,
                                                                   boolean        quote)
  {
    return constructAttachmentFromUri(context, uri, defaultMime, size, width, height, hasThumbnail, fileName, caption, stickerLocator, blurHash, audioHash, voiceNote, borderless, gif, quote, null);
  }

  protected static Attachment constructAttachmentFromUri(@NonNull  Context        context,
                                                         @NonNull  Uri            uri,
                                                         @NonNull  String         defaultMime,
                                                                   long           size,
                                                                   int            width,
                                                                   int            height,
                                                                   boolean        hasThumbnail,
                                                         @Nullable String         fileName,
                                                         @Nullable String         caption,
                                                         @Nullable StickerLocator stickerLocator,
                                                         @Nullable BlurHash       blurHash,
                                                         @Nullable AudioHash      audioHash,
                                                                   boolean        voiceNote,
                                                                   boolean        borderless,
                                                                   boolean        gif,
                                                                   boolean        quote,
                                                         @Nullable AttachmentTable.TransformProperties transformProperties)
  {
    String                 resolvedType    = Optional.ofNullable(MediaUtil.getMimeType(context, uri)).orElse(defaultMime);
    String                 fastPreflightId = String.valueOf(new SecureRandom().nextLong());
    return new UriAttachment(uri,
                             resolvedType,
                             AttachmentTable.TRANSFER_PROGRESS_STARTED,
                             size,
                             width,
                             height,
                             fileName,
                             fastPreflightId,
                             voiceNote,
                             borderless,
                             gif,
                             quote,
                             caption,
                             stickerLocator,
                             blurHash,
                             audioHash,
                             transformProperties);
  }

  public @NonNull Optional<String> getFileType(@NonNull Context context) {
    Optional<String> fileName = getFileName();

    if (fileName.isPresent()) {
      String fileType = getFileType(fileName);
      if (!fileType.isEmpty()) {
        return Optional.of(fileType);
      }
    }

    return Optional.ofNullable(MediaUtil.getExtension(context, getUri()));
  }

  private static @NonNull String getFileType(Optional<String> fileName) {
    if (!fileName.isPresent()) return "";

    String[] parts = fileName.get().split("\\.");

    if (parts.length < 2) {
      return "";
    }

    String suffix = parts[parts.length - 1];

    if (suffix.length() <= 3) {
      return suffix;
    }

    return "";
  }

  @Override
  public boolean equals(Object other) {
    if (other == null)             return false;
    if (!(other instanceof Slide)) return false;

    Slide that = (Slide)other;

    return Util.equals(this.getContentType(), that.getContentType()) &&
           this.hasAudio() == that.hasAudio()                        &&
           this.hasImage() == that.hasImage()                        &&
           this.hasVideo() == that.hasVideo()                        &&
           this.getTransferState() == that.getTransferState()        &&
           Util.equals(this.getUri(), that.getUri());
  }

  @Override
  public int hashCode() {
    return Util.hashCode(getContentType(), hasAudio(), hasImage(),
                         hasVideo(), getUri(), getTransferState());
  }
}
