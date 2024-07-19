/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.ui.subscription

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.signal.core.ui.Buttons
import org.signal.core.ui.Previews
import org.signal.core.ui.Scaffolds
import org.signal.core.ui.theme.SignalTheme
import org.stalker.securesms.R

/**
 * Educational content which allows user to proceed to set up automatic backups
 * or navigate to a support page to learn more.
 */
@Composable
fun MessageBackupsEducationScreen(
  onNavigationClick: () -> Unit,
  onEnableBackups: () -> Unit,
  onLearnMore: () -> Unit
) {
  Scaffolds.Settings(
    onNavigationClick = onNavigationClick,
    navigationIconPainter = painterResource(id = R.drawable.symbol_x_24),
    title = "Chat backups" // TODO [message-backups] Finalized copy
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(it)
        .padding(horizontal = dimensionResource(id = R.dimen.core_ui__gutter))
    ) {
      LazyColumn(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      ) {
        item {
          Image(
            painter = painterResource(id = R.drawable.ic_signal_logo_large), // TODO [message-backups] Final image asset
            contentDescription = null,
            modifier = Modifier
              .padding(top = 48.dp)
              .size(88.dp)
          )
        }

        item {
          Text(
            text = "Chat Backups", // TODO [message-backups] Finalized copy
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 15.dp)
          )
        }

        item {
          Text(
            text = "Back up your messages and media and using Signal’s secure, end-to-end encrypted storage service. Never lose a message when you get a new phone or reinstall Signal.", // TODO [message-backups] Finalized copy
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
          )
        }

        item {
          Column(
            modifier = Modifier.padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
          ) {
            NotableFeatureRow(
              painter = painterResource(id = R.drawable.symbol_lock_compact_20),
              text = "End-to-end Encrypted" // TODO [message-backups] Finalized copy
            )

            NotableFeatureRow(
              painter = painterResource(id = R.drawable.symbol_check_square_compact_20),
              text = "Optional, always" // TODO [message-backups] Finalized copy
            )

            NotableFeatureRow(
              painter = painterResource(id = R.drawable.symbol_trash_compact_20),
              text = "Delete your backup anytime" // TODO [message-backups] Finalized copy
            )
          }
        }
      }

      Buttons.LargePrimary(
        onClick = onEnableBackups,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "Enable backups" // TODO [message-backups] Finalized copy
        )
      }

      TextButton(
        onClick = onLearnMore,
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 16.dp)
      ) {
        Text(
          text = "Learn more" // TODO [message-backups] Finalized copy
        )
      }
    }
  }
}

@Preview
@Composable
private fun MessageBackupsEducationSheetPreview() {
  Previews.Preview {
    MessageBackupsEducationScreen(
      onNavigationClick = {},
      onEnableBackups = {},
      onLearnMore = {}
    )
  }
}

@Preview
@Composable
private fun NotableFeatureRowPreview() {
  Previews.Preview {
    NotableFeatureRow(
      painter = painterResource(id = R.drawable.symbol_lock_compact_20),
      text = "Notable feature information"
    )
  }
}

@Composable
private fun NotableFeatureRow(
  painter: Painter,
  text: String
) {
  Row(
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      painter = painter,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .padding(end = 8.dp)
        .size(32.dp)
        .background(color = SignalTheme.colors.colorSurface2, shape = CircleShape)
        .padding(6.dp)
    )

    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}
