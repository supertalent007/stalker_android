package org.stalker.securesms.safety

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.stalker.securesms.contacts.paged.ContactSearchKey
import org.stalker.securesms.database.model.MessageId
import org.stalker.securesms.recipients.RecipientId

/**
 * Fragment argument for `SafetyNumberBottomSheetFragment`
 */
@Parcelize
data class SafetyNumberBottomSheetArgs(
  val untrustedRecipients: List<RecipientId>,
  val destinations: List<ContactSearchKey.RecipientSearchKey>,
  val messageId: MessageId? = null
) : Parcelable
