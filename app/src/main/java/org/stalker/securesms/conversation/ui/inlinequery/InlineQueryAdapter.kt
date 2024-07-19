package org.stalker.securesms.conversation.ui.inlinequery

import org.stalker.securesms.R
import org.stalker.securesms.util.adapter.mapping.AnyMappingModel
import org.stalker.securesms.util.adapter.mapping.MappingAdapter

class InlineQueryAdapter(listener: (AnyMappingModel) -> Unit) : MappingAdapter() {
  init {
    registerFactory(InlineQueryEmojiResult.Model::class.java, { InlineQueryEmojiResult.ViewHolder(it, listener) }, R.layout.inline_query_emoji_result)
  }
}
