package org.stalker.securesms.components.settings.app.subscription.manage

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.dp
import org.signal.core.util.money.FiatMoney
import org.stalker.securesms.R
import org.stalker.securesms.badges.gifts.ExpiredGiftSheet
import org.stalker.securesms.badges.gifts.flow.GiftFlowActivity
import org.stalker.securesms.badges.models.BadgePreview
import org.stalker.securesms.components.settings.DSLConfiguration
import org.stalker.securesms.components.settings.DSLSettingsFragment
import org.stalker.securesms.components.settings.DSLSettingsIcon
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.app.AppSettingsActivity
import org.stalker.securesms.components.settings.app.subscription.DonationSerializationHelper.toFiatMoney
import org.stalker.securesms.components.settings.app.subscription.MonthlyDonationRepository
import org.stalker.securesms.components.settings.app.subscription.completed.TerminalDonationDelegate
import org.stalker.securesms.components.settings.app.subscription.donate.DonateToSignalType
import org.stalker.securesms.components.settings.app.subscription.models.NetworkFailure
import org.stalker.securesms.components.settings.configure
import org.stalker.securesms.components.settings.models.IndeterminateLoadingCircle
import org.stalker.securesms.database.model.databaseprotos.DonationErrorValue
import org.stalker.securesms.database.model.databaseprotos.PendingOneTimeDonation
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.help.HelpFragment
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.payments.FiatMoneyUtil
import org.stalker.securesms.subscription.Subscription
import org.stalker.securesms.util.CommunicationActions
import org.stalker.securesms.util.Material3OnScrollHelper
import org.stalker.securesms.util.SpanUtil
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.navigation.safeNavigate
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription
import java.util.Currency
import java.util.concurrent.TimeUnit

/**
 * Fragment displayed when a user enters "Subscriptions" via app settings but is already
 * a subscriber. Used to manage their current subscription, view badges, and boost.
 */
class ManageDonationsFragment :
  DSLSettingsFragment(
    layoutId = R.layout.manage_donations_fragment
  ),
  ExpiredGiftSheet.Callback {

  companion object {
    private val alertedIdealDonations = mutableSetOf<Long>()
    const val DONATE_TROUBLESHOOTING_URL = "https://support.signal.org/hc/articles/360031949872#fix"
  }

  private val supportTechSummary: CharSequence by lazy {
    SpannableStringBuilder(SpanUtil.color(ContextCompat.getColor(requireContext(), R.color.signal_colorOnSurfaceVariant), requireContext().getString(R.string.DonateToSignalFragment__private_messaging)))
      .append(" ")
      .append(
        SpanUtil.readMore(requireContext(), ContextCompat.getColor(requireContext(), R.color.signal_colorPrimary)) {
          findNavController().safeNavigate(ManageDonationsFragmentDirections.actionManageDonationsFragmentToSubscribeLearnMoreBottomSheetDialog())
        }
      )
  }

  private val viewModel: ManageDonationsViewModel by viewModels(
    factoryProducer = {
      ManageDonationsViewModel.Factory(MonthlyDonationRepository(ApplicationDependencies.getDonationsService()))
    }
  )

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    viewLifecycleOwner.lifecycle.addObserver(TerminalDonationDelegate(childFragmentManager, viewLifecycleOwner))
    super.onViewCreated(view, savedInstanceState)
  }

  override fun onResume() {
    super.onResume()
    viewModel.refresh()
  }

  override fun bindAdapter(adapter: MappingAdapter) {
    ActiveSubscriptionPreference.register(adapter)
    OneTimeDonationPreference.register(adapter)
    IndeterminateLoadingCircle.register(adapter)
    BadgePreview.register(adapter)
    NetworkFailure.register(adapter)

    val expiredGiftBadge = SignalStore.donationsValues().getExpiredGiftBadge()
    if (expiredGiftBadge != null) {
      SignalStore.donationsValues().setExpiredGiftBadge(null)
      ExpiredGiftSheet.show(childFragmentManager, expiredGiftBadge)
    }

    viewModel.state.observe(viewLifecycleOwner) { state ->
      adapter.submitList(getConfiguration(state).toMappingModelList())

      if (state.nonVerifiedMonthlyDonation?.checkedVerification == true &&
        !alertedIdealDonations.contains(state.nonVerifiedMonthlyDonation.timestamp)
      ) {
        alertedIdealDonations += state.nonVerifiedMonthlyDonation.timestamp

        val amount = FiatMoneyUtil.format(resources, state.nonVerifiedMonthlyDonation.price)

        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.ManageDonationsFragment__couldnt_confirm_donation)
          .setMessage(getString(R.string.ManageDonationsFragment__your_monthly_s_donation_couldnt_be_confirmed, amount))
          .setPositiveButton(android.R.string.ok, null)
          .show()
      } else if (state.pendingOneTimeDonation?.pendingVerification == true &&
        state.pendingOneTimeDonation.checkedVerification &&
        !alertedIdealDonations.contains(state.pendingOneTimeDonation.timestamp)
      ) {
        alertedIdealDonations += state.pendingOneTimeDonation.timestamp

        val amount = FiatMoneyUtil.format(resources, state.pendingOneTimeDonation.amount!!.toFiatMoney(), FiatMoneyUtil.formatOptions().trimZerosAfterDecimal())

        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.ManageDonationsFragment__couldnt_confirm_donation)
          .setMessage(getString(R.string.ManageDonationsFragment__your_one_time_s_donation_couldnt_be_confirmed, amount))
          .setPositiveButton(android.R.string.ok, null)
          .show()
      }
    }
  }

  override fun getMaterial3OnScrollHelper(toolbar: Toolbar?): Material3OnScrollHelper {
    return object : Material3OnScrollHelper(requireActivity(), toolbar!!, viewLifecycleOwner) {
      override val activeColorSet: ColorSet = ColorSet(R.color.transparent, R.color.signal_colorBackground)
      override val inactiveColorSet: ColorSet = ColorSet(R.color.transparent, R.color.signal_colorBackground)
    }
  }

  private fun getConfiguration(state: ManageDonationsState): DSLConfiguration {
    return configure {
      space(36.dp)

      customPref(
        BadgePreview.BadgeModel.SubscriptionModel(
          badge = state.featuredBadge
        )
      )

      space(12.dp)

      noPadTextPref(
        title = DSLSettingsText.from(
          R.string.DonateToSignalFragment__privacy_over_profit,
          DSLSettingsText.CenterModifier,
          DSLSettingsText.TitleLargeModifier
        )
      )

      space(8.dp)

      noPadTextPref(
        title = DSLSettingsText.from(supportTechSummary, DSLSettingsText.CenterModifier)
      )

      space(24.dp)

      primaryWrappedButton(
        text = DSLSettingsText.from(R.string.ManageDonationsFragment__donate_to_signal),
        onClick = {
          findNavController().safeNavigate(ManageDonationsFragmentDirections.actionManageDonationsFragmentToDonateToSignalFragment(DonateToSignalType.ONE_TIME))
        }
      )

      space(16.dp)

      if (state.subscriptionTransactionState is ManageDonationsState.TransactionState.NotInTransaction) {
        val activeSubscription = state.subscriptionTransactionState.activeSubscription.activeSubscription

        if (activeSubscription != null) {
          val subscription: Subscription? = state.availableSubscriptions.firstOrNull { it.level == activeSubscription.level }
          if (subscription != null) {
            presentSubscriptionSettings(activeSubscription, subscription, state)
          } else {
            customPref(IndeterminateLoadingCircle)
          }
        } else if (state.nonVerifiedMonthlyDonation != null) {
          val subscription: Subscription? = state.availableSubscriptions.firstOrNull { it.level == state.nonVerifiedMonthlyDonation.level }
          if (subscription != null) {
            presentNonVerifiedSubscriptionSettings(state.nonVerifiedMonthlyDonation, subscription, state)
          } else {
            customPref(IndeterminateLoadingCircle)
          }
        } else if (state.hasOneTimeBadge || state.pendingOneTimeDonation != null) {
          presentActiveOneTimeDonorSettings(state)
        } else {
          presentNotADonorSettings(state.hasReceipts)
        }
      } else if (state.subscriptionTransactionState == ManageDonationsState.TransactionState.NetworkFailure) {
        presentNetworkFailureSettings(state, state.hasReceipts)
      } else {
        customPref(IndeterminateLoadingCircle)
      }
    }
  }

  private fun DSLConfiguration.presentActiveOneTimeDonorSettings(state: ManageDonationsState) {
    dividerPref()

    sectionHeaderPref(R.string.ManageDonationsFragment__my_support)

    presentPendingOrProcessingOneTimeDonationState(state)

    presentBadges()

    presentOtherWaysToGive()

    presentMore()
  }

  private fun DSLConfiguration.presentPendingOrProcessingOneTimeDonationState(state: ManageDonationsState) {
    val pendingOneTimeDonation = state.pendingOneTimeDonation
    if (pendingOneTimeDonation != null) {
      customPref(
        OneTimeDonationPreference.Model(
          pendingOneTimeDonation = pendingOneTimeDonation,
          onPendingClick = {
            displayPendingDialog(it)
          },
          onErrorClick = {
            displayPendingOneTimeDonationErrorDialog(it, pendingOneTimeDonation.paymentMethodType == PendingOneTimeDonation.PaymentMethodType.IDEAL)
          }
        )
      )
    }
  }

  private fun DSLConfiguration.presentNetworkFailureSettings(state: ManageDonationsState, hasReceipts: Boolean) {
    if (SignalStore.donationsValues().isLikelyASustainer()) {
      presentSubscriptionSettingsWithNetworkError(state)
    } else {
      presentNotADonorSettings(hasReceipts)
    }
  }

  private fun DSLConfiguration.presentSubscriptionSettingsWithNetworkError(state: ManageDonationsState) {
    presentSubscriptionSettingsWithState(state) {
      customPref(
        NetworkFailure.Model(
          onRetryClick = {
            viewModel.retry()
          }
        )
      )
    }
  }

  private fun DSLConfiguration.presentSubscriptionSettings(
    activeSubscription: ActiveSubscription.Subscription,
    subscription: Subscription,
    state: ManageDonationsState
  ) {
    presentSubscriptionSettingsWithState(state) {
      val activeCurrency = Currency.getInstance(activeSubscription.currency)
      val activeAmount = activeSubscription.amount.movePointLeft(activeCurrency.defaultFractionDigits)

      customPref(
        ActiveSubscriptionPreference.Model(
          price = FiatMoney(activeAmount, activeCurrency),
          subscription = subscription,
          renewalTimestamp = TimeUnit.SECONDS.toMillis(activeSubscription.endOfCurrentPeriod),
          redemptionState = state.getMonthlyDonorRedemptionState(),
          onContactSupport = {
            requireActivity().finish()
            requireActivity().startActivity(AppSettingsActivity.help(requireContext(), HelpFragment.DONATION_INDEX))
          },
          activeSubscription = activeSubscription,
          onPendingClick = {
            displayPendingDialog(it)
          }
        )
      )
    }
  }

  private fun DSLConfiguration.presentNonVerifiedSubscriptionSettings(
    nonVerifiedMonthlyDonation: NonVerifiedMonthlyDonation,
    subscription: Subscription,
    state: ManageDonationsState
  ) {
    presentSubscriptionSettingsWithState(state) {
      customPref(
        ActiveSubscriptionPreference.Model(
          price = nonVerifiedMonthlyDonation.price,
          subscription = subscription,
          redemptionState = ManageDonationsState.RedemptionState.IN_PROGRESS,
          onContactSupport = {},
          activeSubscription = null,
          onPendingClick = {}
        )
      )
    }
  }

  private fun DSLConfiguration.presentSubscriptionSettingsWithState(
    state: ManageDonationsState,
    subscriptionBlock: DSLConfiguration.() -> Unit
  ) {
    dividerPref()

    sectionHeaderPref(R.string.ManageDonationsFragment__my_support)

    subscriptionBlock()

    presentPendingOrProcessingOneTimeDonationState(state)

    clickPref(
      title = DSLSettingsText.from(R.string.ManageDonationsFragment__manage_subscription),
      icon = DSLSettingsIcon.from(R.drawable.symbol_person_24),
      isEnabled = state.getMonthlyDonorRedemptionState() != ManageDonationsState.RedemptionState.IN_PROGRESS,
      onClick = {
        findNavController().safeNavigate(ManageDonationsFragmentDirections.actionManageDonationsFragmentToDonateToSignalFragment(DonateToSignalType.MONTHLY))
      }
    )

    presentBadges()

    presentOtherWaysToGive()

    presentMore()
  }

  private fun DSLConfiguration.presentNotADonorSettings(hasReceipts: Boolean) {
    presentOtherWaysToGive()

    if (hasReceipts) {
      presentMore()
    }
  }

  private fun DSLConfiguration.presentOtherWaysToGive() {
    dividerPref()

    sectionHeaderPref(R.string.ManageDonationsFragment__other_ways_to_give)

    clickPref(
      title = DSLSettingsText.from(R.string.ManageDonationsFragment__donate_for_a_friend),
      icon = DSLSettingsIcon.from(R.drawable.symbol_gift_24),
      onClick = {
        startActivity(Intent(requireContext(), GiftFlowActivity::class.java))
      }
    )
  }

  private fun DSLConfiguration.presentBadges() {
    clickPref(
      title = DSLSettingsText.from(R.string.ManageDonationsFragment__badges),
      icon = DSLSettingsIcon.from(R.drawable.symbol_badge_multi_24),
      onClick = {
        findNavController().safeNavigate(ManageDonationsFragmentDirections.actionManageDonationsFragmentToManageBadges())
      }
    )
  }

  private fun DSLConfiguration.presentReceipts() {
    clickPref(
      title = DSLSettingsText.from(R.string.ManageDonationsFragment__donation_receipts),
      icon = DSLSettingsIcon.from(R.drawable.symbol_receipt_24),
      onClick = {
        findNavController().safeNavigate(ManageDonationsFragmentDirections.actionManageDonationsFragmentToDonationReceiptListFragment())
      }
    )
  }

  private fun DSLConfiguration.presentMore() {
    dividerPref()

    sectionHeaderPref(R.string.ManageDonationsFragment__more)

    presentReceipts()

    externalLinkPref(
      title = DSLSettingsText.from(R.string.ManageDonationsFragment__subscription_faq),
      icon = DSLSettingsIcon.from(R.drawable.symbol_help_24),
      linkId = R.string.donate_faq_url
    )
  }

  private fun displayPendingDialog(fiatMoney: FiatMoney) {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.MySupportPreference__payment_pending)
      .setMessage(
        getString(
          R.string.MySupportPreference__your_bank_transfer_of_s,
          FiatMoneyUtil.format(resources, fiatMoney, FiatMoneyUtil.formatOptions().trimZerosAfterDecimal())
        )
      )
      .setPositiveButton(android.R.string.ok) { _, _ -> }
      .setNegativeButton(R.string.MySupportPreference__learn_more) { _, _ ->
        CommunicationActions.openBrowserLink(
          requireContext(),
          getString(R.string.pending_transfer_url)
        )
      }
      .show()
  }

  private fun displayPendingOneTimeDonationErrorDialog(error: DonationErrorValue, isIdeal: Boolean) {
    when (error.type) {
      DonationErrorValue.Type.REDEMPTION -> {
        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.DonationsErrors__couldnt_add_badge)
          .setMessage(R.string.DonationsErrors__your_badge_could_not)
          .setNegativeButton(R.string.DonationsErrors__learn_more) { _, _ ->
            CommunicationActions.openBrowserLink(requireContext(), DONATE_TROUBLESHOOTING_URL)
          }
          .setPositiveButton(R.string.Subscription__contact_support) { _, _ ->
            requireActivity().finish()
            startActivity(AppSettingsActivity.help(requireContext(), HelpFragment.DONATION_INDEX))
          }
          .setOnDismissListener {
            SignalStore.donationsValues().setPendingOneTimeDonation(null)
          }
          .show()
      }
      else -> {
        val message = if (isIdeal) {
          R.string.DonationsErrors__your_ideal_couldnt_be_processed
        } else {
          R.string.DonationsErrors__try_another_payment_method
        }

        MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.DonationsErrors__error_processing_payment)
          .setMessage(message)
          .setNegativeButton(R.string.DonationsErrors__learn_more) { _, _ ->
            CommunicationActions.openBrowserLink(requireContext(), DONATE_TROUBLESHOOTING_URL)
          }
          .setPositiveButton(android.R.string.ok, null)
          .setOnDismissListener {
            SignalStore.donationsValues().setPendingOneTimeDonation(null)
          }
          .show()
      }
    }
  }

  override fun onMakeAMonthlyDonation() {
    findNavController().safeNavigate(ManageDonationsFragmentDirections.actionManageDonationsFragmentToDonateToSignalFragment(DonateToSignalType.MONTHLY))
  }
}
