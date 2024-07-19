/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.subscription.manage

import org.stalker.securesms.database.model.databaseprotos.PendingOneTimeDonation

/**
 * Represent the status of a donation as represented in the job system.
 */
sealed class DonationRedemptionJobStatus {
  /**
   * No pending/running jobs for a donation type.
   */
  object None : DonationRedemptionJobStatus()

  /**
   * Donation is pending external user verification (e.g., iDEAL).
   *
   * For one-time, pending donation data is provided via the job data as it is not in the store yet.
   */
  class PendingExternalVerification(
    val pendingOneTimeDonation: PendingOneTimeDonation? = null,
    val nonVerifiedMonthlyDonation: NonVerifiedMonthlyDonation? = null
  ) : DonationRedemptionJobStatus()

  /**
   * Donation is at the receipt request status.
   *
   * For one-time donations, pending donation data available via the store.
   */
  object PendingReceiptRequest : DonationRedemptionJobStatus()

  /**
   * Donation is at the receipt redemption status.
   *
   * For one-time donations, pending donation data available via the store.
   */
  object PendingReceiptRedemption : DonationRedemptionJobStatus()

  /**
   * Representation of a failed subscription job chain derived from no pending/running jobs and
   * a failure state in the store.
   */
  object FailedSubscription : DonationRedemptionJobStatus()

  fun isInProgress(): Boolean {
    return when (this) {
      is PendingExternalVerification,
      PendingReceiptRedemption,
      PendingReceiptRequest -> true

      FailedSubscription,
      None -> false
    }
  }
}
