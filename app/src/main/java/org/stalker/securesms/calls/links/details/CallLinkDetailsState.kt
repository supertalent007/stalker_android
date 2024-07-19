/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.calls.links.details

import androidx.compose.runtime.Immutable
import org.stalker.securesms.database.CallLinkTable

@Immutable
data class CallLinkDetailsState(
  val displayRevocationDialog: Boolean = false,
  val callLink: CallLinkTable.CallLink? = null
)
