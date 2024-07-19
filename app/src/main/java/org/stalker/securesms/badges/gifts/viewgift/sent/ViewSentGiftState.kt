package org.stalker.securesms.badges.gifts.viewgift.sent

import org.stalker.securesms.badges.models.Badge
import org.stalker.securesms.recipients.Recipient

data class ViewSentGiftState(
  val recipient: Recipient? = null,
  val badge: Badge? = null
)
