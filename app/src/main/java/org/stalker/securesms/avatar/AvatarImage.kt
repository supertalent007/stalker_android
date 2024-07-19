/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import org.stalker.securesms.components.AvatarImageView
import org.stalker.securesms.recipients.Recipient

@Composable
fun AvatarImage(
  recipient: Recipient,
  modifier: Modifier = Modifier
) {
  if (LocalInspectionMode.current) {
    Spacer(
      modifier = modifier
        .background(color = Color.Red, shape = CircleShape)
    )
  } else {
    AndroidView(
      factory = ::AvatarImageView,
      modifier = modifier.background(color = Color.Transparent, shape = CircleShape)
    ) {
      it.setAvatarUsingProfile(recipient)
    }
  }
}
