package org.stalker.securesms.badges.gifts.thanks

import android.os.Bundle
import androidx.fragment.app.FragmentManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.signal.core.util.DimensionUnit
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.getParcelableCompat
import org.stalker.securesms.R
import org.stalker.securesms.badges.models.Badge
import org.stalker.securesms.badges.models.BadgePreview
import org.stalker.securesms.components.settings.DSLConfiguration
import org.stalker.securesms.components.settings.DSLSettingsAdapter
import org.stalker.securesms.components.settings.DSLSettingsBottomSheetFragment
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.configure
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.BottomSheetUtil

/**
 * Displays a "Thank you" message in a conversation when redirected
 * there after purchasing and sending a gift badge.
 */
class GiftThanksSheet : DSLSettingsBottomSheetFragment() {

  companion object {
    private const val ARGS_RECIPIENT_ID = "args.recipient.id"
    private const val ARGS_BADGE = "args.badge"

    @JvmStatic
    fun show(fragmentManager: FragmentManager, recipientId: RecipientId, badge: Badge) {
      GiftThanksSheet().apply {
        arguments = Bundle().apply {
          putParcelable(ARGS_RECIPIENT_ID, recipientId)
          putParcelable(ARGS_BADGE, badge)
        }
      }.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }

  private val lifecycleDisposable = LifecycleDisposable()

  private val recipientId: RecipientId
    get() = requireArguments().getParcelableCompat(ARGS_RECIPIENT_ID, RecipientId::class.java)!!

  private val badge: Badge
    get() = requireArguments().getParcelableCompat(ARGS_BADGE, Badge::class.java)!!

  override fun bindAdapter(adapter: DSLSettingsAdapter) {
    BadgePreview.register(adapter)

    lifecycleDisposable.bindTo(viewLifecycleOwner)
    lifecycleDisposable += Recipient.observable(recipientId).observeOn(AndroidSchedulers.mainThread()).subscribe {
      adapter.submitList(getConfiguration(it).toMappingModelList())
    }
  }

  private fun getConfiguration(recipient: Recipient): DSLConfiguration {
    return configure {
      textPref(
        title = DSLSettingsText.from(R.string.SubscribeThanksForYourSupportBottomSheetDialogFragment__thanks_for_your_support, DSLSettingsText.TitleLargeModifier, DSLSettingsText.CenterModifier)
      )

      noPadTextPref(
        title = DSLSettingsText.from(
          getString(R.string.GiftThanksSheet__youve_made_a_donation, recipient.getDisplayName(requireContext())),
          DSLSettingsText.CenterModifier
        )
      )

      space(DimensionUnit.DP.toPixels(37f).toInt())

      customPref(
        BadgePreview.BadgeModel.GiftedBadgeModel(
          badge = badge,
          recipient = recipient
        )
      )

      space(DimensionUnit.DP.toPixels(60f).toInt())
    }
  }
}
