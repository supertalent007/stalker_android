package org.stalker.securesms.components.settings.app.subscription.receipts.detail

import org.stalker.securesms.database.model.DonationReceiptRecord

data class DonationReceiptDetailState(
  val donationReceiptRecord: DonationReceiptRecord? = null,
  val subscriptionName: String? = null
)
