package org.stalker.securesms.database.model

import org.stalker.securesms.recipients.RecipientId

/** A model for [org.stalker.securesms.database.PendingRetryReceiptTable] */
data class PendingRetryReceiptModel(
  val id: Long,
  val author: RecipientId,
  val authorDevice: Int,
  val sentTimestamp: Long,
  val receivedTimestamp: Long,
  val threadId: Long
)
