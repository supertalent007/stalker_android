/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.permissions

import android.Manifest
import android.os.Build

/**
 * Compatibility object for requesting specific permissions that have become more
 * granular as the APIs have evolved.
 */
object PermissionCompat {
  @JvmStatic
  fun forImages(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
      arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
  }

  private fun forVideos(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
      arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
  }

  @JvmStatic
  fun forImagesAndVideos(): Array<String> {
    return setOf(*(forImages() + forVideos())).toTypedArray()
  }
}
