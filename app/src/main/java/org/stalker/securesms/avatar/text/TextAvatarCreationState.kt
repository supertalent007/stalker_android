package org.stalker.securesms.avatar.text

import org.stalker.securesms.avatar.Avatar
import org.stalker.securesms.avatar.AvatarColorItem
import org.stalker.securesms.avatar.Avatars

data class TextAvatarCreationState(
  val currentAvatar: Avatar.Text
) {
  fun colors(): List<AvatarColorItem> = Avatars.colors.map { AvatarColorItem(it, currentAvatar.color == it) }
}
