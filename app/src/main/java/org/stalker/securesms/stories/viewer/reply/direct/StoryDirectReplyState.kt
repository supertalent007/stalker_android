package org.stalker.securesms.stories.viewer.reply.direct

import org.stalker.securesms.database.model.MessageRecord
import org.stalker.securesms.recipients.Recipient

data class StoryDirectReplyState(
  val groupDirectReplyRecipient: Recipient? = null,
  val storyRecord: MessageRecord? = null
)
