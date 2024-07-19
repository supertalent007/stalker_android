package org.stalker.securesms.video.interfaces

fun interface TranscoderCancelationSignal {
  fun isCanceled(): Boolean
}
