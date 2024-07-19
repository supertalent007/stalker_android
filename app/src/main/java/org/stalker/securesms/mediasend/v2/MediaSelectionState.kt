package org.stalker.securesms.mediasend.v2

import android.net.Uri
import org.stalker.securesms.conversation.MessageSendType
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.mediasend.Media
import org.stalker.securesms.mediasend.v2.videos.VideoTrimData
import org.stalker.securesms.mms.MediaConstraints
import org.stalker.securesms.mms.SentMediaQuality
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.stories.Stories
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.MediaUtil
import org.stalker.securesms.video.TranscodingPreset

data class MediaSelectionState(
  val sendType: MessageSendType,
  val selectedMedia: List<Media> = listOf(),
  val focusedMedia: Media? = null,
  val recipient: Recipient? = null,
  val quality: SentMediaQuality = SignalStore.settings().sentMediaQuality,
  val message: CharSequence? = null,
  val viewOnceToggleState: ViewOnceToggleState = ViewOnceToggleState.default,
  val isTouchEnabled: Boolean = true,
  val isSent: Boolean = false,
  val isPreUploadEnabled: Boolean = false,
  val isMeteredConnection: Boolean = false,
  val editorStateMap: Map<Uri, Any> = mapOf(),
  val cameraFirstCapture: Media? = null,
  val isStory: Boolean,
  val storySendRequirements: Stories.MediaTransform.SendRequirements = Stories.MediaTransform.SendRequirements.CAN_NOT_SEND,
  val suppressEmptyError: Boolean = true
) {

  val isVideoTrimmingVisible: Boolean = focusedMedia != null && MediaUtil.isVideoType(focusedMedia.mimeType) && MediaConstraints.isVideoTranscodeAvailable() && !focusedMedia.isVideoGif

  val transcodingPreset: TranscodingPreset = MediaConstraints.getPushMediaConstraints(SentMediaQuality.fromCode(quality.code)).videoTranscodingSettings

  val maxSelection = FeatureFlags.maxAttachmentCount()

  val canSend = !isSent && selectedMedia.isNotEmpty()

  fun getOrCreateVideoTrimData(uri: Uri): VideoTrimData {
    return editorStateMap[uri] as? VideoTrimData ?: VideoTrimData()
  }

  enum class ViewOnceToggleState(val code: Int) {
    INFINITE(0), ONCE(1);

    fun next(): ViewOnceToggleState {
      return when (this) {
        INFINITE -> ONCE
        ONCE -> INFINITE
      }
    }

    companion object {
      val default = INFINITE

      fun fromCode(code: Int): ViewOnceToggleState {
        return when (code) {
          1 -> ONCE
          else -> INFINITE
        }
      }
    }
  }
}
