package org.stalker.securesms.components.settings.app.internal.donor

import org.signal.donations.StripeDeclineCode
import org.stalker.securesms.badges.models.Badge
import org.stalker.securesms.components.settings.app.subscription.errors.UnexpectedSubscriptionCancellation

data class InternalDonorErrorConfigurationState(
  val badges: List<Badge> = emptyList(),
  val selectedBadge: Badge? = null,
  val selectedUnexpectedSubscriptionCancellation: UnexpectedSubscriptionCancellation? = null,
  val selectedStripeDeclineCode: StripeDeclineCode.Code? = null
)
