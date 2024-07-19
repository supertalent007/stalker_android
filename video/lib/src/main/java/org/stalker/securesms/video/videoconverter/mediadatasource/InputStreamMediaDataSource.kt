/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.video.videoconverter.mediadatasource

import android.media.MediaDataSource
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.InputStream

/**
 * Extend this class in order to be able to use the system media framework with any arbitrary [InputStream] of bytes.
 */
@RequiresApi(23)
abstract class InputStreamMediaDataSource : MediaDataSource() {
  @Throws(IOException::class)
  override fun readAt(position: Long, bytes: ByteArray?, offset: Int, length: Int): Int {
    if (position >= size) {
      return -1
    }

    createInputStream(position).use { inputStream ->
      var totalRead = 0
      while (totalRead < length) {
        val read: Int = inputStream.read(bytes, offset + totalRead, length - totalRead)
        if (read == -1) {
          return if (totalRead == 0) {
            -1
          } else {
            totalRead
          }
        }
        totalRead += read
      }
      return totalRead
    }
  }

  abstract override fun close()

  abstract override fun getSize(): Long

  @Throws(IOException::class)
  abstract fun createInputStream(position: Long): InputStream
}
