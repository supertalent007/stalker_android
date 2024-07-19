/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.stalker.securesms.media

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import org.stalker.securesms.video.interfaces.MediaInput
import java.io.IOException

/**
 * A media input source defined by a [Uri] that the system can parse and access.
 */
class UriMediaInput(private val context: Context, private val uri: Uri) : MediaInput {
  @Throws(IOException::class)
  override fun createExtractor(): MediaExtractor {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)
    return extractor
  }

  override fun hasSameInput(other: MediaInput): Boolean {
    return other is UriMediaInput && other.uri == this.uri
  }

  override fun close() = Unit
}
