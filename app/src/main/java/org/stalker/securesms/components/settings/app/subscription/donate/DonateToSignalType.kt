package org.stalker.securesms.components.settings.app.subscription.donate

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorSource

@Parcelize
enum class DonateToSignalType(val requestCode: Short) : Parcelable {
  ONE_TIME(16141),
  MONTHLY(16142),
  GIFT(16143);

  fun toErrorSource(): DonationErrorSource {
    return when (this) {
      ONE_TIME -> DonationErrorSource.ONE_TIME
      MONTHLY -> DonationErrorSource.MONTHLY
      GIFT -> DonationErrorSource.GIFT
    }
  }
}
