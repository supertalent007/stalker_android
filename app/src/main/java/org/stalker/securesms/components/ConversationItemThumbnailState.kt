/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components

import android.graphics.Color
import android.os.Parcelable
import android.view.View
import android.view.View.OnLongClickListener
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.stalker.securesms.mms.SlideClickListener
import org.stalker.securesms.mms.SlidesClickedListener
import org.stalker.securesms.util.views.Stub

/**
 * Parcelable state object for [ConversationItemThumbnail]
 * This allows us to manage inputs for [ThumbnailView] and [AlbumThumbnailView] without
 * actually having them inflated. When the views are finally inflated, we 'apply'
 */
@Parcelize
data class ConversationItemThumbnailState(
  val thumbnailViewState: ThumbnailViewState = ThumbnailViewState(),
  val albumViewState: AlbumViewState = AlbumViewState()
) : Parcelable {

  @Parcelize
  data class ThumbnailViewState(
    private val alpha: Float = 0f,
    private val focusable: Boolean = true,
    private val clickable: Boolean = true,
    @IgnoredOnParcel
    private val clickListener: SlideClickListener? = null,
    @IgnoredOnParcel
    private val startTransferClickListener: SlidesClickedListener? = null,
    @IgnoredOnParcel
    private val cancelTransferClickListener: SlidesClickedListener? = null,
    @IgnoredOnParcel
    private val playVideoClickListener: SlideClickListener? = null,
    @IgnoredOnParcel
    private val longClickListener: OnLongClickListener? = null,
    private val visibility: Int = View.GONE,
    private val minWidth: Int = -1,
    private val maxWidth: Int = -1,
    private val minHeight: Int = -1,
    private val maxHeight: Int = -1,
    private val cornerTopLeft: Int = 0,
    private val cornerTopRight: Int = 0,
    private val cornerBottomRight: Int = 0,
    private val cornerBottomLeft: Int = 0
  ) : Parcelable {

    fun applyState(thumbnailView: Stub<ThumbnailView>) {
      thumbnailView.visibility = visibility
      if (visibility == View.GONE) {
        return
      }

      thumbnailView.get().alpha = alpha
      thumbnailView.get().isFocusable = focusable
      thumbnailView.get().isClickable = clickable
      thumbnailView.get().setRadii(cornerTopLeft, cornerTopRight, cornerBottomRight, cornerBottomLeft)
      thumbnailView.get().setThumbnailClickListener(clickListener)
      thumbnailView.get().setStartTransferClickListener(startTransferClickListener)
      thumbnailView.get().setCancelTransferClickListener(cancelTransferClickListener)
      thumbnailView.get().setPlayVideoClickListener(playVideoClickListener)
      thumbnailView.get().setOnLongClickListener(longClickListener)
      thumbnailView.get().setBounds(minWidth, maxWidth, minHeight, maxHeight)
    }
  }

  @Parcelize
  data class AlbumViewState(
    private val focusable: Boolean = true,
    private val clickable: Boolean = true,
    @IgnoredOnParcel
    private val clickListener: SlideClickListener? = null,
    @IgnoredOnParcel
    private val startTransferClickListener: SlidesClickedListener? = null,
    @IgnoredOnParcel
    private val cancelTransferClickListener: SlidesClickedListener? = null,
    @IgnoredOnParcel
    private val playVideoClickListener: SlideClickListener? = null,
    @IgnoredOnParcel
    private val longClickListener: OnLongClickListener? = null,
    private val visibility: Int = View.GONE,
    private val cellBackgroundColor: Int = Color.TRANSPARENT,
    private val cornerTopLeft: Int = 0,
    private val cornerTopRight: Int = 0,
    private val cornerBottomRight: Int = 0,
    private val cornerBottomLeft: Int = 0
  ) : Parcelable {

    fun applyState(albumView: Stub<AlbumThumbnailView>) {
      albumView.visibility = visibility
      if (visibility == View.GONE) {
        return
      }

      albumView.get().isFocusable = focusable
      albumView.get().isClickable = clickable
      albumView.get().setRadii(cornerTopLeft, cornerTopRight, cornerBottomRight, cornerBottomLeft)
      albumView.get().setThumbnailClickListener(clickListener)
      albumView.get().setStartTransferClickListener(startTransferClickListener)
      albumView.get().setCancelTransferClickListener(cancelTransferClickListener)
      albumView.get().setPlayVideoClickListener(playVideoClickListener)
      albumView.get().setOnLongClickListener(longClickListener)
      albumView.get().setCellBackgroundColor(cellBackgroundColor)
    }
  }

  fun applyState(thumbnailView: Stub<ThumbnailView>, albumView: Stub<AlbumThumbnailView>) {
    thumbnailViewState.applyState(thumbnailView)
    albumViewState.applyState(albumView)
  }
}
