/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.chats.backups

import org.stalker.securesms.backup.v2.BackupFrequency
import org.stalker.securesms.backup.v2.BackupV2Event
import org.stalker.securesms.backup.v2.MessageBackupTier

data class RemoteBackupsSettingsState(
  val messageBackupsTier: MessageBackupTier? = null,
  val canBackUpUsingCellular: Boolean = false,
  val backupSize: Long = 0,
  val backupsFrequency: BackupFrequency = BackupFrequency.DAILY,
  val lastBackupTimestamp: Long = 0,
  val dialog: Dialog = Dialog.NONE,
  val snackbar: Snackbar = Snackbar.NONE,
  val backupProgress: BackupV2Event? = null
) {
  enum class Dialog {
    NONE,
    TURN_OFF_AND_DELETE_BACKUPS,
    BACKUP_FREQUENCY
  }

  enum class Snackbar {
    NONE,
    BACKUP_DELETED_AND_TURNED_OFF,
    BACKUP_TYPE_CHANGED_AND_SUBSCRIPTION_CANCELLED,
    SUBSCRIPTION_CANCELLED,
    DOWNLOAD_COMPLETE
  }
}
