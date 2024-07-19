/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.chats.backups.type

import androidx.compose.runtime.Stable
import org.signal.donations.PaymentSourceType
import org.stalker.securesms.backup.v2.MessageBackupTier

@Stable
data class BackupsTypeSettingsState(
  val backupsTier: MessageBackupTier? = null,
  val paymentSourceType: PaymentSourceType = PaymentSourceType.Unknown,
  val nextRenewalTimestamp: Long = 0
)
