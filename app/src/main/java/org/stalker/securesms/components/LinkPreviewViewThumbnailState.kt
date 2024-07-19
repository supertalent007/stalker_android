package org.stalker.securesms.components

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.stalker.securesms.mms.SlidesClickedListener
import org.stalker.securesms.util.views.Stub

@Parcelize
data class LinkPreviewViewThumbnailState(
  val cornerTopLeft: Int = 0,
  val cornerTopRight: Int = 0,
  val cornerBottomRight: Int = 0,
  val cornerBottomLeft: Int = 0,
  @IgnoredOnParcel
  val downloadListener: SlidesClickedListener? = null
) : Parcelable {
  fun withDownloadListener(downloadListener: SlidesClickedListener?): LinkPreviewViewThumbnailState {
    return copy(downloadListener = downloadListener)
  }

  fun applyState(thumbnail: Stub<OutlinedThumbnailView>) {
    if (thumbnail.resolved()) {
      thumbnail.get().setCorners(cornerTopLeft, cornerTopRight, cornerBottomRight, cornerBottomLeft)
      thumbnail.get().setStartTransferClickListener(downloadListener)
    }
  }
}
