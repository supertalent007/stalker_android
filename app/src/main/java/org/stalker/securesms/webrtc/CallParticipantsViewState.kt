package org.stalker.securesms.webrtc

import org.stalker.securesms.components.webrtc.CallParticipantsState
import org.stalker.securesms.service.webrtc.state.WebRtcEphemeralState

class CallParticipantsViewState(
  callParticipantsState: CallParticipantsState,
  ephemeralState: WebRtcEphemeralState,
  val isPortrait: Boolean,
  val isLandscapeEnabled: Boolean,
  val isStartedFromCallLink: Boolean
) {

  val callParticipantsState = CallParticipantsState.update(callParticipantsState, ephemeralState)
}
