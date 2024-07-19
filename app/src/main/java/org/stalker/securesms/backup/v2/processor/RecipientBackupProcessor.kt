/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.processor

import org.signal.core.util.logging.Log
import org.stalker.securesms.backup.v2.BackupState
import org.stalker.securesms.backup.v2.ExportState
import org.stalker.securesms.backup.v2.database.BackupRecipient
import org.stalker.securesms.backup.v2.database.getAllForBackup
import org.stalker.securesms.backup.v2.database.getCallLinksForBackup
import org.stalker.securesms.backup.v2.database.getContactsForBackup
import org.stalker.securesms.backup.v2.database.getGroupsForBackup
import org.stalker.securesms.backup.v2.database.restoreContactFromBackup
import org.stalker.securesms.backup.v2.database.restoreFromBackup
import org.stalker.securesms.backup.v2.database.restoreGroupFromBackup
import org.stalker.securesms.backup.v2.database.restoreReleaseNotes
import org.stalker.securesms.backup.v2.proto.Frame
import org.stalker.securesms.backup.v2.proto.ReleaseNotes
import org.stalker.securesms.backup.v2.stream.BackupFrameEmitter
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.recipients.Recipient

typealias BackupRecipient = org.stalker.securesms.backup.v2.proto.Recipient

object RecipientBackupProcessor {

  val TAG = Log.tag(RecipientBackupProcessor::class.java)

  fun export(state: ExportState, emitter: BackupFrameEmitter) {
    val selfId = Recipient.self().id.toLong()
    val releaseChannelId = SignalStore.releaseChannelValues().releaseChannelRecipientId
    if (releaseChannelId != null) {
      emitter.emit(
        Frame(
          recipient = BackupRecipient(
            id = releaseChannelId.toLong(),
            releaseNotes = ReleaseNotes()
          )
        )
      )
    }

    SignalDatabase.recipients.getContactsForBackup(selfId).use { reader ->
      for (backupRecipient in reader) {
        if (backupRecipient != null) {
          state.recipientIds.add(backupRecipient.id)
          emitter.emit(Frame(recipient = backupRecipient))
        }
      }
    }

    SignalDatabase.recipients.getGroupsForBackup().use { reader ->
      for (backupRecipient in reader) {
        state.recipientIds.add(backupRecipient.id)
        emitter.emit(Frame(recipient = backupRecipient))
      }
    }

    SignalDatabase.distributionLists.getAllForBackup().forEach {
      state.recipientIds.add(it.id)
      emitter.emit(Frame(recipient = it))
    }

    SignalDatabase.callLinks.getCallLinksForBackup().forEach {
      state.recipientIds.add(it.id)
      emitter.emit(Frame(recipient = it))
    }
  }

  fun import(recipient: BackupRecipient, backupState: BackupState) {
    val newId = when {
      recipient.contact != null -> SignalDatabase.recipients.restoreContactFromBackup(recipient.contact)
      recipient.group != null -> SignalDatabase.recipients.restoreGroupFromBackup(recipient.group)
      recipient.distributionList != null -> SignalDatabase.distributionLists.restoreFromBackup(recipient.distributionList, backupState)
      recipient.self != null -> Recipient.self().id
      recipient.releaseNotes != null -> SignalDatabase.recipients.restoreReleaseNotes()
      recipient.callLink != null -> SignalDatabase.callLinks.restoreFromBackup(recipient.callLink)
      else -> {
        Log.w(TAG, "Unrecognized recipient type!")
        null
      }
    }
    if (newId != null) {
      backupState.backupToLocalRecipientId[recipient.id] = newId
    }
  }
}
