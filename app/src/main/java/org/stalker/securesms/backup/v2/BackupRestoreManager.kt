/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2

import org.signal.core.util.concurrent.SignalExecutors
import org.stalker.securesms.attachments.AttachmentId
import org.stalker.securesms.attachments.DatabaseAttachment
import org.stalker.securesms.database.AttachmentTable
import org.stalker.securesms.database.model.MessageRecord
import org.stalker.securesms.database.model.MmsMessageRecord
import org.stalker.securesms.jobs.RestoreAttachmentJob

/**
 * Responsible for managing logic around restore prioritization
 */
object BackupRestoreManager {

  private val reprioritizedAttachments: HashSet<AttachmentId> = HashSet()

  /**
   * Raise priority of all attachments for the included message records.
   *
   * This is so we can make certain attachments get downloaded more quickly
   */
  fun prioritizeAttachmentsIfNeeded(messageRecords: List<MessageRecord>) {
    SignalExecutors.BOUNDED.execute {
      synchronized(this) {
        val restoringAttachments: List<AttachmentId> = messageRecords
          .mapNotNull { (it as? MmsMessageRecord?)?.slideDeck?.slides }
          .flatten()
          .mapNotNull { it.asAttachment() as? DatabaseAttachment }
          .filter { it.transferState == AttachmentTable.TRANSFER_RESTORE_IN_PROGRESS && !reprioritizedAttachments.contains(it.attachmentId) }
          .map { it.attachmentId }

        reprioritizedAttachments += restoringAttachments

        if (restoringAttachments.isNotEmpty()) {
          RestoreAttachmentJob.modifyPriorities(restoringAttachments.toSet(), 1)
        }
      }
    }
  }
}
