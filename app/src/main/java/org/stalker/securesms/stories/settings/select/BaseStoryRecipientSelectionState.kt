package org.stalker.securesms.stories.settings.select

import org.stalker.securesms.database.model.DistributionListId
import org.stalker.securesms.database.model.DistributionListRecord
import org.stalker.securesms.recipients.RecipientId

data class BaseStoryRecipientSelectionState(
  val distributionListId: DistributionListId?,
  val privateStory: DistributionListRecord? = null,
  val selection: Set<RecipientId> = emptySet()
)
