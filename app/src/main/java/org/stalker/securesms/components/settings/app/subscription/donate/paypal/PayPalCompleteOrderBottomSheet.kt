package org.stalker.securesms.components.settings.app.subscription.donate.paypal

import android.content.DialogInterface
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import org.signal.core.util.dp
import org.stalker.securesms.R
import org.stalker.securesms.badges.models.BadgeDisplay112
import org.stalker.securesms.components.settings.DSLConfiguration
import org.stalker.securesms.components.settings.DSLSettingsAdapter
import org.stalker.securesms.components.settings.DSLSettingsBottomSheetFragment
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewaySelectorBottomSheet.Companion.presentTitleAndSubtitle
import org.stalker.securesms.components.settings.configure

/**
 * Bottom sheet for final order confirmation from PayPal
 */
class PayPalCompleteOrderBottomSheet : DSLSettingsBottomSheetFragment() {

  companion object {
    const val REQUEST_KEY = "complete_order"
  }

  private var didConfirmOrder = false
  private val args: PayPalCompleteOrderBottomSheetArgs by navArgs()

  override fun bindAdapter(adapter: DSLSettingsAdapter) {
    BadgeDisplay112.register(adapter)
    PayPalCompleteOrderPaymentItem.register(adapter)

    adapter.submitList(getConfiguration().toMappingModelList())
  }

  override fun onDismiss(dialog: DialogInterface) {
    super.onDismiss(dialog)
    setFragmentResult(REQUEST_KEY, bundleOf(REQUEST_KEY to didConfirmOrder))
  }

  private fun getConfiguration(): DSLConfiguration {
    return configure {
      customPref(
        BadgeDisplay112.Model(
          badge = args.request.badge,
          withDisplayText = false
        )
      )

      space(12.dp)

      presentTitleAndSubtitle(requireContext(), args.request)

      space(24.dp)

      customPref(PayPalCompleteOrderPaymentItem.Model())

      space(82.dp)

      primaryButton(
        text = DSLSettingsText.from(R.string.PaypalCompleteOrderBottomSheet__donate),
        onClick = {
          didConfirmOrder = true
          findNavController().popBackStack()
        }
      )

      secondaryButtonNoOutline(
        text = DSLSettingsText.from(android.R.string.cancel),
        onClick = {
          findNavController().popBackStack()
        }
      )

      space(16.dp)
    }
  }
}
