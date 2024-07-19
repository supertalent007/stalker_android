package org.stalker.securesms.keyboard.emoji

import org.stalker.securesms.components.emoji.EmojiPageModel
import org.stalker.securesms.components.emoji.EmojiPageViewGridAdapter
import org.stalker.securesms.components.emoji.RecentEmojiPageModel
import org.stalker.securesms.components.emoji.parsing.EmojiTree
import org.stalker.securesms.emoji.EmojiCategory
import org.stalker.securesms.emoji.EmojiSource
import org.stalker.securesms.util.adapter.mapping.MappingModel

fun EmojiPageModel.toMappingModels(): List<MappingModel<*>> {
  val emojiTree: EmojiTree = EmojiSource.latest.emojiTree

  return displayEmoji.map {
    val isTextEmoji = EmojiCategory.EMOTICONS.key == key || (RecentEmojiPageModel.KEY == key && emojiTree.getEmoji(it.value, 0, it.value.length) == null)

    if (isTextEmoji) {
      EmojiPageViewGridAdapter.EmojiTextModel(key, it)
    } else {
      EmojiPageViewGridAdapter.EmojiModel(key, it)
    }
  }
}
