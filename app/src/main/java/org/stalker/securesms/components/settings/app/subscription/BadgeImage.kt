/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import org.stalker.securesms.badges.BadgeImageView
import org.stalker.securesms.badges.models.Badge

enum class BadgeImageSize(val sizeCode: Int) {
  SMALL(0),
  MEDIUM(1),
  LARGE(2),
  X_LARGE(3),
  BADGE_64(4),
  BADGE_112(5)
}

@Composable
fun BadgeImage112(
  badge: Badge?,
  modifier: Modifier = Modifier
) {
  if (LocalInspectionMode.current) {
    Box(modifier = modifier.background(color = Color.Black, shape = CircleShape))
  } else {
    AndroidView(
      factory = {
        BadgeImageView(it, BadgeImageSize.BADGE_112)
      },
      update = {
        it.setBadge(badge)
      },
      modifier = modifier
    )
  }
}
