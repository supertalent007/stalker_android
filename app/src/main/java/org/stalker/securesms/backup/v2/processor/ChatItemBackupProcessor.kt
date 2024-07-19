/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.processor

import org.signal.core.util.logging.Log
import org.stalker.securesms.backup.v2.BackupState
import org.stalker.securesms.backup.v2.ExportState
import org.stalker.securesms.backup.v2.database.ChatItemImportInserter
import org.stalker.securesms.backup.v2.database.createChatItemInserter
import org.stalker.securesms.backup.v2.database.getMessagesForBackup
import org.stalker.securesms.backup.v2.proto.Frame
import org.stalker.securesms.backup.v2.stream.BackupFrameEmitter
import org.stalker.securesms.database.SignalDatabase

object ChatItemBackupProcessor {
  val TAG = Log.tag(ChatItemBackupProcessor::class.java)

  fun export(exportState: ExportState, emitter: BackupFrameEmitter) {
    SignalDatabase.messages.getMessagesForBackup(exportState.backupTime, exportState.allowMediaBackup).use { chatItems ->
      for (chatItem in chatItems) {
        if (exportState.threadIds.contains(chatItem.chatId)) {
          emitter.emit(Frame(chatItem = chatItem))
        }
      }
    }
  }

  fun beginImport(backupState: BackupState): ChatItemImportInserter {
    return SignalDatabase.messages.createChatItemInserter(backupState)
  }
}
