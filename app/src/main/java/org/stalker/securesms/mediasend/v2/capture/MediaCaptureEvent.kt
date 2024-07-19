package org.stalker.securesms.mediasend.v2.capture

import org.stalker.securesms.mediasend.Media
import org.stalker.securesms.recipients.Recipient

sealed class MediaCaptureEvent {
  data class MediaCaptureRendered(val media: Media) : MediaCaptureEvent()
  data class UsernameScannedFromQrCode(val recipient: Recipient, val username: String) : MediaCaptureEvent()
  object DeviceLinkScannedFromQrCode : MediaCaptureEvent()
  object MediaCaptureRenderFailed : MediaCaptureEvent()
}
