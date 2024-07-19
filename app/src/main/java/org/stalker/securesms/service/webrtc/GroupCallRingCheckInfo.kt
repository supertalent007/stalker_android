package org.stalker.securesms.service.webrtc

import org.signal.ringrtc.CallManager
import org.stalker.securesms.groups.GroupId
import org.stalker.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ServiceId.ACI

data class GroupCallRingCheckInfo(
  val recipientId: RecipientId,
  val groupId: GroupId.V2,
  val ringId: Long,
  val ringerAci: ACI,
  val ringUpdate: CallManager.RingUpdate
)
