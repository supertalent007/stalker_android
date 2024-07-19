package org.stalker.securesms.wallpaper

import org.stalker.securesms.R
import org.stalker.securesms.components.AvatarImageView
import org.stalker.securesms.conversation.colors.AvatarColor
import org.stalker.securesms.recipients.Recipient

sealed class WallpaperPreviewPortrait {
  class ContactPhoto(private val recipient: Recipient) : WallpaperPreviewPortrait() {
    override fun applyToAvatarImageView(avatarImageView: AvatarImageView) {
      avatarImageView.setAvatar(recipient)
      avatarImageView.colorFilter = null
    }
  }

  class SolidColor(private val avatarColor: AvatarColor) : WallpaperPreviewPortrait() {
    override fun applyToAvatarImageView(avatarImageView: AvatarImageView) {
      avatarImageView.setImageResource(R.drawable.circle_tintable)
      avatarImageView.setColorFilter(avatarColor.colorInt())
    }
  }

  abstract fun applyToAvatarImageView(avatarImageView: AvatarImageView)
}
