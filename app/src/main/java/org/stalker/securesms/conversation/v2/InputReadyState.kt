/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.conversation.v2

import org.stalker.securesms.database.GroupTable
import org.stalker.securesms.database.model.GroupRecord
import org.stalker.securesms.messagerequests.MessageRequestState
import org.stalker.securesms.recipients.Recipient

/**
 * Information necessary for rendering compose input.
 */
class InputReadyState(
  val conversationRecipient: Recipient,
  val messageRequestState: MessageRequestState,
  val groupRecord: GroupRecord?,
  val isClientExpired: Boolean,
  val isUnauthorized: Boolean,
  val threadContainsSms: Boolean
) {
  private val selfMemberLevel: GroupTable.MemberLevel? = groupRecord?.memberLevel(Recipient.self())

  val isAnnouncementGroup: Boolean? = groupRecord?.isAnnouncementGroup
  val isActiveGroup: Boolean? = if (selfMemberLevel == null) null else selfMemberLevel != GroupTable.MemberLevel.NOT_A_MEMBER
  val isAdmin: Boolean? = selfMemberLevel?.equals(GroupTable.MemberLevel.ADMINISTRATOR)
  val isRequestingMember: Boolean? = selfMemberLevel?.equals(GroupTable.MemberLevel.REQUESTING_MEMBER)

  fun shouldShowInviteToSignal(): Boolean {
    return !conversationRecipient.isPushGroup &&
      !conversationRecipient.isRegistered &&
      !conversationRecipient.isReleaseNotes
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as InputReadyState

    if (!conversationRecipient.hasSameContent(other.conversationRecipient)) return false
    if (messageRequestState != other.messageRequestState) return false
    if (groupRecord != other.groupRecord) return false
    if (isClientExpired != other.isClientExpired) return false
    if (isUnauthorized != other.isUnauthorized) return false

    return true
  }

  override fun hashCode(): Int {
    var result = conversationRecipient.hashCode()
    result = 31 * result + messageRequestState.hashCode()
    result = 31 * result + (groupRecord?.hashCode() ?: 0)
    result = 31 * result + isClientExpired.hashCode()
    result = 31 * result + isUnauthorized.hashCode()
    return result
  }
}
