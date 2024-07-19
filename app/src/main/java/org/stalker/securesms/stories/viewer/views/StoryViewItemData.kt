package org.stalker.securesms.stories.viewer.views

import org.stalker.securesms.recipients.Recipient

data class StoryViewItemData(
  val recipient: Recipient,
  val timeViewedInMillis: Long
)
