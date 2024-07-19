package org.stalker.securesms.keyboard.emoji

import org.stalker.securesms.components.emoji.EmojiEventListener
import org.stalker.securesms.keyboard.emoji.search.EmojiSearchFragment

interface EmojiKeyboardCallback :
  EmojiEventListener,
  EmojiKeyboardPageFragment.Callback,
  EmojiSearchFragment.Callback
