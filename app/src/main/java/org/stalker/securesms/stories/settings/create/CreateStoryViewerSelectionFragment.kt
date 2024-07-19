package org.stalker.securesms.stories.settings.create

import androidx.navigation.fragment.findNavController
import org.stalker.securesms.R
import org.stalker.securesms.database.model.DistributionListId
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.stories.settings.select.BaseStoryRecipientSelectionFragment
import org.stalker.securesms.util.navigation.safeNavigate

/**
 * Allows user to select who will see the story they are creating
 */
class CreateStoryViewerSelectionFragment : BaseStoryRecipientSelectionFragment() {
  override val actionButtonLabel: Int = R.string.CreateStoryViewerSelectionFragment__next
  override val distributionListId: DistributionListId? = null

  override fun goToNextScreen(recipients: Set<RecipientId>) {
    findNavController().safeNavigate(CreateStoryViewerSelectionFragmentDirections.actionCreateStoryViewerSelectionToCreateStoryWithViewers(recipients.toTypedArray()))
  }
}
