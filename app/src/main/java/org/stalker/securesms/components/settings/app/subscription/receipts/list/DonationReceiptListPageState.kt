package org.stalker.securesms.components.settings.app.subscription.receipts.list

import org.stalker.securesms.database.model.DonationReceiptRecord

data class DonationReceiptListPageState(
  val records: List<DonationReceiptRecord> = emptyList(),
  val isLoaded: Boolean = false
)
