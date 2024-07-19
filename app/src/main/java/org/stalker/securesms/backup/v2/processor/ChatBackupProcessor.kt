/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.processor

import org.signal.core.util.logging.Log
import org.stalker.securesms.backup.v2.BackupState
import org.stalker.securesms.backup.v2.ExportState
import org.stalker.securesms.backup.v2.database.getThreadsForBackup
import org.stalker.securesms.backup.v2.database.restoreFromBackup
import org.stalker.securesms.backup.v2.proto.Chat
import org.stalker.securesms.backup.v2.proto.Frame
import org.stalker.securesms.backup.v2.stream.BackupFrameEmitter
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.recipients.RecipientId

object ChatBackupProcessor {
  val TAG = Log.tag(ChatBackupProcessor::class.java)

  fun export(exportState: ExportState, emitter: BackupFrameEmitter) {
    SignalDatabase.threads.getThreadsForBackup().use { reader ->
      for (chat in reader) {
        if (exportState.recipientIds.contains(chat.recipientId)) {
          exportState.threadIds.add(chat.id)
          emitter.emit(Frame(chat = chat))
        } else {
          Log.w(TAG, "dropping thread for deleted recipient ${chat.recipientId}")
        }
      }
    }
  }

  fun import(chat: Chat, backupState: BackupState) {
    val recipientId: RecipientId? = backupState.backupToLocalRecipientId[chat.recipientId]
    if (recipientId == null) {
      Log.w(TAG, "Missing recipient for chat ${chat.id}")
      return
    }

    SignalDatabase.threads.restoreFromBackup(chat, recipientId)?.let { threadId ->
      backupState.chatIdToLocalRecipientId[chat.id] = recipientId
      backupState.chatIdToLocalThreadId[chat.id] = threadId
      backupState.chatIdToBackupRecipientId[chat.id] = chat.recipientId
    }

    // TODO there's several fields in the chat that actually need to be restored on the recipient table
  }
}
