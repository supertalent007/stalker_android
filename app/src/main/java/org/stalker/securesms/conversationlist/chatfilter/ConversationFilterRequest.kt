package org.stalker.securesms.conversationlist.chatfilter

import org.stalker.securesms.conversationlist.model.ConversationFilter

data class ConversationFilterRequest(
  val filter: ConversationFilter,
  val source: ConversationFilterSource
)
