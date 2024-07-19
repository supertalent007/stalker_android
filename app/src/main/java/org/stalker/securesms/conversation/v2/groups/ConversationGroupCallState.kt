/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.conversation.v2.groups

import org.stalker.securesms.recipients.RecipientId

/** State of a group call used solely within rendering UX/UI in the conversation */
data class ConversationGroupCallState(
  val recipientId: RecipientId? = null,
  val activeV2Group: Boolean = false,
  val ongoingCall: Boolean = false,
  val hasCapacity: Boolean = false
)
