/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.transfercontrols

import android.view.View
import org.stalker.securesms.attachments.Attachment
import org.stalker.securesms.mms.Slide

data class TransferControlViewState(
  val isVisible: Boolean = true,
  val isFocusable: Boolean = true,
  val isClickable: Boolean = true,
  val slides: List<Slide> = emptyList(),
  val startTransferClickListener: View.OnClickListener? = null,
  val cancelTransferClickedListener: View.OnClickListener? = null,
  val instantPlaybackClickListener: View.OnClickListener? = null,
  val showSecondaryText: Boolean = true,
  val networkProgress: Map<Attachment, TransferControlView.Progress> = HashMap(),
  val compressionProgress: Map<Attachment, TransferControlView.Progress> = HashMap(),
  val playableWhileDownloading: Boolean = false,
  val isUpload: Boolean = false
)
