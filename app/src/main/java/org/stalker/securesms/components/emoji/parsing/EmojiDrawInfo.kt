package org.stalker.securesms.components.emoji.parsing

import org.stalker.securesms.emoji.EmojiPage

data class EmojiDrawInfo(val page: EmojiPage, val index: Int, private val emoji: String, val rawEmoji: String?, val jumboSheet: String?)
