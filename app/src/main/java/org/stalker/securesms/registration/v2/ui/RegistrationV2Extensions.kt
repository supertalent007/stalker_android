/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.registration.v2.ui

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber

fun PhoneNumber.toE164(): String {
  return PhoneNumberUtil.getInstance().format(this, PhoneNumberUtil.PhoneNumberFormat.E164)
}
