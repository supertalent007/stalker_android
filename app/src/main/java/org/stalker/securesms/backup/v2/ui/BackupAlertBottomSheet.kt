/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.ui

import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import kotlinx.parcelize.Parcelize
import org.signal.core.ui.BottomSheets
import org.signal.core.ui.Buttons
import org.signal.core.ui.Icons
import org.signal.core.ui.Previews
import org.signal.core.ui.SignalPreview
import org.stalker.securesms.R
import org.stalker.securesms.compose.ComposeBottomSheetDialogFragment

/**
 * Notifies the user of an issue with their backup.
 */
class BackupAlertBottomSheet : ComposeBottomSheetDialogFragment() {

  companion object {
    private const val ARG_ALERT = "alert"

    fun create(backupAlert: BackupAlert): BackupAlertBottomSheet {
      return BackupAlertBottomSheet().apply {
        arguments = bundleOf(ARG_ALERT to backupAlert)
      }
    }
  }

  private val backupAlert: BackupAlert by lazy(LazyThreadSafetyMode.NONE) {
    BundleCompat.getParcelable(requireArguments(), ARG_ALERT, BackupAlert::class.java)!!
  }

  @Composable
  override fun SheetContent() {
    BackupAlertSheetContent(
      backupAlert = backupAlert,
      onPrimaryActionClick = this::performPrimaryAction,
      onSecondaryActionClick = this::performSecondaryAction
    )
  }

  @Stable
  private fun performPrimaryAction() {
    when (backupAlert) {
      BackupAlert.GENERIC -> {
        // TODO [message-backups] -- Back up now
      }
      BackupAlert.PAYMENT_PROCESSING -> {
        // TODO [message-backups] -- Silence
      }
      BackupAlert.MEDIA_BACKUPS_ARE_OFF -> {
        // TODO [message-backups] -- Download media now
      }
      BackupAlert.MEDIA_WILL_BE_DELETED_TODAY -> {
        // TODO [message-backups] -- Download media now
      }
    }

    dismissAllowingStateLoss()
  }

  @Stable
  private fun performSecondaryAction() {
    when (backupAlert) {
      BackupAlert.GENERIC -> {
        // TODO [message-backups] - Dismiss and notify later
      }
      BackupAlert.PAYMENT_PROCESSING -> error("PAYMENT_PROCESSING state does not support a secondary action.")
      BackupAlert.MEDIA_BACKUPS_ARE_OFF -> {
        // TODO [message-backups] - Silence and remind on last day
      }
      BackupAlert.MEDIA_WILL_BE_DELETED_TODAY -> {
        // TODO [message-backups] - Silence forever
      }
    }

    dismissAllowingStateLoss()
  }
}

@Composable
private fun BackupAlertSheetContent(
  backupAlert: BackupAlert,
  onPrimaryActionClick: () -> Unit,
  onSecondaryActionClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = dimensionResource(id = R.dimen.core_ui__gutter))
  ) {
    BottomSheets.Handle()

    Spacer(modifier = Modifier.size(26.dp))

    val iconColors = rememberBackupsIconColors(backupAlert = backupAlert)
    Icons.BrushedForeground(
      painter = painterResource(id = R.drawable.symbol_backup_light), // TODO [message-backups] final asset
      contentDescription = null,
      foregroundBrush = iconColors.foreground,
      modifier = Modifier
        .size(88.dp)
        .background(color = iconColors.background, shape = CircleShape)
        .padding(20.dp)
    )

    Text(
      text = stringResource(id = rememberTitleResource(backupAlert = backupAlert)),
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )

    when (backupAlert) {
      BackupAlert.GENERIC -> GenericBody()
      BackupAlert.PAYMENT_PROCESSING -> PaymentProcessingBody()
      BackupAlert.MEDIA_BACKUPS_ARE_OFF -> MediaBackupsAreOffBody()
      BackupAlert.MEDIA_WILL_BE_DELETED_TODAY -> MediaWillBeDeletedTodayBody()
    }

    val secondaryActionResource = rememberSecondaryActionResource(backupAlert = backupAlert)
    val padBottom = if (secondaryActionResource > 0) 16.dp else 56.dp

    Buttons.LargeTonal(
      onClick = onPrimaryActionClick,
      modifier = Modifier
        .defaultMinSize(minWidth = 220.dp)
        .padding(top = 60.dp, bottom = padBottom)
    ) {
      Text(text = stringResource(id = rememberPrimaryActionResource(backupAlert = backupAlert)))
    }

    if (secondaryActionResource > 0) {
      TextButton(onClick = onSecondaryActionClick, modifier = Modifier.padding(bottom = 32.dp)) {
        Text(text = stringResource(id = secondaryActionResource))
      }
    }
  }
}

@Composable
private fun GenericBody() {
  Text(text = "TODO")
}

@Composable
private fun PaymentProcessingBody() {
  Text(text = "TODO")
}

@Composable
private fun MediaBackupsAreOffBody() {
  Text(text = "TODO")
}

@Composable
private fun MediaWillBeDeletedTodayBody() {
  Text(text = "TODO")
}

@Composable
private fun rememberBackupsIconColors(backupAlert: BackupAlert): BackupsIconColors {
  return remember(backupAlert) {
    when (backupAlert) {
      BackupAlert.GENERIC, BackupAlert.PAYMENT_PROCESSING -> BackupsIconColors.Warning
      BackupAlert.MEDIA_BACKUPS_ARE_OFF, BackupAlert.MEDIA_WILL_BE_DELETED_TODAY -> BackupsIconColors.Error
    }
  }
}

@Composable
@StringRes
private fun rememberTitleResource(backupAlert: BackupAlert): Int {
  return remember(backupAlert) {
    when (backupAlert) {
      BackupAlert.GENERIC -> R.string.default_error_msg // TODO [message-backups] -- Finalized copy
      BackupAlert.PAYMENT_PROCESSING -> R.string.default_error_msg // TODO [message-backups] -- Finalized copy
      BackupAlert.MEDIA_BACKUPS_ARE_OFF -> R.string.default_error_msg // TODO [message-backups] -- Finalized copy
      BackupAlert.MEDIA_WILL_BE_DELETED_TODAY -> R.string.default_error_msg // TODO [message-backups] -- Finalized copy
    }
  }
}

@Composable
private fun rememberPrimaryActionResource(backupAlert: BackupAlert): Int {
  return remember(backupAlert) {
    when (backupAlert) {
      BackupAlert.GENERIC -> android.R.string.ok // TODO [message-backups] -- Finalized copy
      BackupAlert.PAYMENT_PROCESSING -> android.R.string.ok // TODO [message-backups] -- Finalized copy
      BackupAlert.MEDIA_BACKUPS_ARE_OFF -> android.R.string.ok // TODO [message-backups] -- Finalized copy
      BackupAlert.MEDIA_WILL_BE_DELETED_TODAY -> android.R.string.ok // TODO [message-backups] -- Finalized copy
    }
  }
}

@Composable
private fun rememberSecondaryActionResource(backupAlert: BackupAlert): Int {
  return remember(backupAlert) {
    when (backupAlert) {
      BackupAlert.GENERIC -> android.R.string.cancel // TODO [message-backups] -- Finalized copy
      BackupAlert.PAYMENT_PROCESSING -> -1
      BackupAlert.MEDIA_BACKUPS_ARE_OFF -> android.R.string.cancel // TODO [message-backups] -- Finalized copy
      BackupAlert.MEDIA_WILL_BE_DELETED_TODAY -> android.R.string.cancel // TODO [message-backups] -- Finalized copy
    }
  }
}

@SignalPreview
@Composable
private fun BackupAlertSheetContentPreviewGeneric() {
  Previews.BottomSheetPreview {
    BackupAlertSheetContent(
      backupAlert = BackupAlert.GENERIC,
      onPrimaryActionClick = {},
      onSecondaryActionClick = {}
    )
  }
}

@SignalPreview
@Composable
private fun BackupAlertSheetContentPreviewPayment() {
  Previews.BottomSheetPreview {
    BackupAlertSheetContent(
      backupAlert = BackupAlert.PAYMENT_PROCESSING,
      onPrimaryActionClick = {},
      onSecondaryActionClick = {}
    )
  }
}

@SignalPreview
@Composable
private fun BackupAlertSheetContentPreviewMedia() {
  Previews.BottomSheetPreview {
    BackupAlertSheetContent(
      backupAlert = BackupAlert.MEDIA_BACKUPS_ARE_OFF,
      onPrimaryActionClick = {},
      onSecondaryActionClick = {}
    )
  }
}

@SignalPreview
@Composable
private fun BackupAlertSheetContentPreviewDelete() {
  Previews.BottomSheetPreview {
    BackupAlertSheetContent(
      backupAlert = BackupAlert.MEDIA_WILL_BE_DELETED_TODAY,
      onPrimaryActionClick = {},
      onSecondaryActionClick = {}
    )
  }
}

@Parcelize
enum class BackupAlert : Parcelable {
  GENERIC,
  PAYMENT_PROCESSING,
  MEDIA_BACKUPS_ARE_OFF,
  MEDIA_WILL_BE_DELETED_TODAY
}
