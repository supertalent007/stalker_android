package org.stalker.securesms.components.settings.app.subscription.manage

import android.text.method.LinkMovementMethod
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.signal.core.util.money.FiatMoney
import org.stalker.securesms.R
import org.stalker.securesms.badges.BadgeImageView
import org.stalker.securesms.components.settings.PreferenceModel
import org.stalker.securesms.databinding.MySupportPreferenceBinding
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.payments.FiatMoneyUtil
import org.stalker.securesms.subscription.Subscription
import org.stalker.securesms.util.DateUtils
import org.stalker.securesms.util.SpanUtil
import org.stalker.securesms.util.adapter.mapping.BindingFactory
import org.stalker.securesms.util.adapter.mapping.BindingViewHolder
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.visible
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription
import java.util.Locale

/**
 * DSL renderable item that displays active subscription information on the user's
 * manage donations page.
 */
object ActiveSubscriptionPreference {

  class Model(
    val price: FiatMoney,
    val subscription: Subscription,
    val renewalTimestamp: Long = -1L,
    val redemptionState: ManageDonationsState.RedemptionState,
    val activeSubscription: ActiveSubscription.Subscription?,
    val onContactSupport: () -> Unit,
    val onPendingClick: (FiatMoney) -> Unit
  ) : PreferenceModel<Model>() {
    override fun areItemsTheSame(newItem: Model): Boolean {
      return subscription.id == newItem.subscription.id
    }

    override fun areContentsTheSame(newItem: Model): Boolean {
      return super.areContentsTheSame(newItem) &&
        subscription == newItem.subscription &&
        renewalTimestamp == newItem.renewalTimestamp &&
        redemptionState == newItem.redemptionState &&
        FiatMoney.equals(price, newItem.price) &&
        activeSubscription == newItem.activeSubscription
    }
  }

  class ViewHolder(binding: MySupportPreferenceBinding) : BindingViewHolder<Model, MySupportPreferenceBinding>(binding) {

    val badge: BadgeImageView = binding.mySupportBadge
    val title: TextView = binding.mySupportTitle
    val expiry: TextView = binding.mySupportExpiry
    val progress: ProgressBar = binding.mySupportProgress

    override fun bind(model: Model) {
      itemView.setOnClickListener(null)

      badge.setBadge(model.subscription.badge)

      title.text = context.getString(
        R.string.MySupportPreference__s_per_month,
        FiatMoneyUtil.format(
          context.resources,
          model.price,
          FiatMoneyUtil.formatOptions()
        )
      )

      expiry.movementMethod = LinkMovementMethod.getInstance()

      when (model.redemptionState) {
        ManageDonationsState.RedemptionState.NONE -> presentRenewalState(model)
        ManageDonationsState.RedemptionState.IS_PENDING_BANK_TRANSFER -> presentPendingBankTransferState(model)
        ManageDonationsState.RedemptionState.IN_PROGRESS -> presentInProgressState()
        ManageDonationsState.RedemptionState.FAILED -> presentFailureState(model)
      }
    }

    private fun presentRenewalState(model: Model) {
      expiry.text = context.getString(
        R.string.MySupportPreference__renews_s,
        DateUtils.formatDateWithYear(
          Locale.getDefault(),
          model.renewalTimestamp
        )
      )
      progress.visible = false
    }

    private fun presentPendingBankTransferState(model: Model) {
      expiry.text = context.getString(R.string.MySupportPreference__payment_pending)
      progress.visible = true
      itemView.setOnClickListener { model.onPendingClick(model.price) }
    }

    private fun presentInProgressState() {
      expiry.text = context.getString(R.string.MySupportPreference__processing_transaction)
      progress.visible = true
    }

    private fun presentFailureState(model: Model) {
      if (model.activeSubscription?.isFailedPayment == true || SignalStore.donationsValues().shouldCancelSubscriptionBeforeNextSubscribeAttempt) {
        presentPaymentFailureState(model)
      } else {
        presentRedemptionFailureState(model)
      }
    }

    private fun presentPaymentFailureState(model: Model) {
      val contactString = context.getString(R.string.MySupportPreference__please_contact_support)

      expiry.text = SpanUtil.clickSubstring(
        context.getString(R.string.DonationsErrors__error_processing_payment_s, contactString),
        contactString,
        {
          model.onContactSupport()
        },
        ContextCompat.getColor(context, R.color.signal_accent_primary)
      )
      progress.visible = false
    }

    private fun presentRedemptionFailureState(model: Model) {
      val contactString = context.getString(R.string.MySupportPreference__please_contact_support)

      expiry.text = SpanUtil.clickSubstring(
        context.getString(R.string.MySupportPreference__couldnt_add_badge_s, contactString),
        contactString,
        {
          model.onContactSupport()
        },
        ContextCompat.getColor(context, R.color.signal_accent_primary)
      )
      progress.visible = false
    }
  }

  fun register(adapter: MappingAdapter) {
    adapter.registerFactory(Model::class.java, BindingFactory(::ViewHolder, MySupportPreferenceBinding::inflate))
  }
}
