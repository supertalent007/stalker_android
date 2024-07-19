package org.stalker.securesms.stories.settings.group

import org.stalker.securesms.recipients.Recipient

data class GroupStorySettingsState(
  val name: String = "",
  val members: List<Recipient> = emptyList(),
  val removed: Boolean = false
)
