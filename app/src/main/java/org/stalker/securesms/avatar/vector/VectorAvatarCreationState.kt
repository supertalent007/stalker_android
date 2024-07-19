package org.stalker.securesms.avatar.vector

import org.stalker.securesms.avatar.Avatar
import org.stalker.securesms.avatar.AvatarColorItem
import org.stalker.securesms.avatar.Avatars

data class VectorAvatarCreationState(
  val currentAvatar: Avatar.Vector
) {
  fun colors(): List<AvatarColorItem> = Avatars.colors.map { AvatarColorItem(it, currentAvatar.color == it) }
}
