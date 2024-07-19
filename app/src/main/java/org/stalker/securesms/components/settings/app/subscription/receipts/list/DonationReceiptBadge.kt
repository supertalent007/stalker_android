package org.stalker.securesms.components.settings.app.subscription.receipts.list

import org.stalker.securesms.badges.models.Badge
import org.stalker.securesms.database.model.DonationReceiptRecord

data class DonationReceiptBadge(
  val type: DonationReceiptRecord.Type,
  val level: Int,
  val badge: Badge
)
