/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.ui.subscription

import org.stalker.securesms.backup.v2.MessageBackupTier
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewayResponse
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.lock.v2.PinKeyboardType

data class MessageBackupsFlowState(
  val selectedMessageBackupTier: MessageBackupTier? = null,
  val currentMessageBackupTier: MessageBackupTier? = null,
  val availableBackupTiers: List<MessageBackupTier> = emptyList(),
  val selectedPaymentGateway: GatewayResponse.Gateway? = null,
  val availablePaymentGateways: List<GatewayResponse.Gateway> = emptyList(),
  val pin: String = "",
  val pinKeyboardType: PinKeyboardType = SignalStore.pinValues().keyboardType
)
