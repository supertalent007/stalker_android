package org.stalker.securesms.components.settings.app.subscription.donate

import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.dp
import org.signal.core.util.logging.Log
import org.signal.core.util.money.FiatMoney
import org.stalker.securesms.R
import org.stalker.securesms.badges.models.BadgePreview
import org.stalker.securesms.components.KeyboardAwareLinearLayout
import org.stalker.securesms.components.ViewBinderDelegate
import org.stalker.securesms.components.WrapperDialogFragment
import org.stalker.securesms.components.settings.DSLConfiguration
import org.stalker.securesms.components.settings.DSLSettingsFragment
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.app.subscription.boost.Boost
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewayRequest
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewayResponse
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.stalker.securesms.components.settings.app.subscription.models.CurrencySelection
import org.stalker.securesms.components.settings.app.subscription.models.NetworkFailure
import org.stalker.securesms.components.settings.configure
import org.stalker.securesms.databinding.DonateToSignalFragmentBinding
import org.stalker.securesms.payments.FiatMoneyUtil
import org.stalker.securesms.subscription.Subscription
import org.stalker.securesms.util.Material3OnScrollHelper
import org.stalker.securesms.util.Projection
import org.stalker.securesms.util.SpanUtil
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.navigation.safeNavigate
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription
import java.util.Currency

/**
 * Unified donation fragment which allows users to choose between monthly or one-time donations.
 */
class DonateToSignalFragment :
  DSLSettingsFragment(
    layoutId = R.layout.donate_to_signal_fragment
  ),
  DonationCheckoutDelegate.Callback {

  companion object {
    private val TAG = Log.tag(DonateToSignalFragment::class.java)
  }

  class Dialog : WrapperDialogFragment() {

    override fun getWrappedFragment(): Fragment {
      return NavHostFragment.create(
        R.navigation.donate_to_signal,
        arguments
      )
    }

    companion object {
      @JvmStatic
      fun create(donateToSignalType: DonateToSignalType): DialogFragment {
        return Dialog().apply {
          arguments = DonateToSignalFragmentArgs.Builder(donateToSignalType).build().toBundle()
        }
      }
    }
  }

  private val args: DonateToSignalFragmentArgs by navArgs()
  private val viewModel: DonateToSignalViewModel by viewModels(factoryProducer = {
    DonateToSignalViewModel.Factory(args.startType)
  })

  private val disposables = LifecycleDisposable()
  private val binding by ViewBinderDelegate(DonateToSignalFragmentBinding::bind)

  private var donationCheckoutDelegate: DonationCheckoutDelegate? = null

  private val supportTechSummary: CharSequence by lazy {
    SpannableStringBuilder(SpanUtil.color(ContextCompat.getColor(requireContext(), R.color.signal_colorOnSurfaceVariant), requireContext().getString(R.string.DonateToSignalFragment__private_messaging)))
      .append(" ")
      .append(
        SpanUtil.readMore(requireContext(), ContextCompat.getColor(requireContext(), R.color.signal_colorPrimary)) {
          findNavController().safeNavigate(DonateToSignalFragmentDirections.actionDonateToSignalFragmentToSubscribeLearnMoreBottomSheetDialog())
        }
      )
  }

  override fun onToolbarNavigationClicked() {
    requireActivity().onBackPressedDispatcher.onBackPressed()
  }

  override fun getMaterial3OnScrollHelper(toolbar: Toolbar?): Material3OnScrollHelper {
    return object : Material3OnScrollHelper(requireActivity(), toolbar!!, viewLifecycleOwner) {
      override val activeColorSet: ColorSet = ColorSet(R.color.transparent, R.color.signal_colorBackground)
      override val inactiveColorSet: ColorSet = ColorSet(R.color.transparent, R.color.signal_colorBackground)
    }
  }

  override fun bindAdapter(adapter: MappingAdapter) {
    donationCheckoutDelegate = DonationCheckoutDelegate(this, this, viewModel.uiSessionKey, DonationErrorSource.ONE_TIME, DonationErrorSource.MONTHLY)

    val recyclerView = this.recyclerView!!
    recyclerView.overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS

    KeyboardAwareLinearLayout(requireContext()).apply {
      addOnKeyboardHiddenListener {
        recyclerView.post { recyclerView.requestLayout() }
      }

      addOnKeyboardShownListener {
        recyclerView.post { recyclerView.scrollToPosition(adapter.itemCount - 1) }
      }

      (view as ViewGroup).addView(this)
    }

    Boost.register(adapter)
    Subscription.register(adapter)
    NetworkFailure.register(adapter)
    BadgePreview.register(adapter)
    CurrencySelection.register(adapter)
    DonationPillToggle.register(adapter)

    disposables.bindTo(viewLifecycleOwner)
    disposables += viewModel.actions.subscribe { action ->
      when (action) {
        is DonateToSignalAction.DisplayCurrencySelectionDialog -> {
          val navAction = DonateToSignalFragmentDirections.actionDonateToSignalFragmentToSetDonationCurrencyFragment(
            action.donateToSignalType == DonateToSignalType.ONE_TIME,
            action.supportedCurrencies.toTypedArray()
          )

          findNavController().safeNavigate(navAction)
        }

        is DonateToSignalAction.DisplayGatewaySelectorDialog -> {
          Log.d(TAG, "Presenting gateway selector for ${action.gatewayRequest}")
          val navAction = DonateToSignalFragmentDirections.actionDonateToSignalFragmentToGatewaySelectorBottomSheetDialog(action.gatewayRequest)

          findNavController().safeNavigate(navAction)
        }

        is DonateToSignalAction.CancelSubscription -> {
          findNavController().safeNavigate(
            DonateToSignalFragmentDirections.actionDonateToSignalFragmentToStripePaymentInProgressFragment(
              DonationProcessorAction.CANCEL_SUBSCRIPTION,
              action.gatewayRequest
            )
          )
        }

        is DonateToSignalAction.UpdateSubscription -> {
          findNavController().safeNavigate(
            DonateToSignalFragmentDirections.actionDonateToSignalFragmentToStripePaymentInProgressFragment(
              DonationProcessorAction.UPDATE_SUBSCRIPTION,
              action.gatewayRequest
            )
          )
        }
      }
    }

    disposables += viewModel.state.subscribe { state ->
      adapter.submitList(getConfiguration(state).toMappingModelList())
    }
  }

  override fun onStop() {
    super.onStop()

    listOf(
      binding.boost1Animation,
      binding.boost2Animation,
      binding.boost3Animation,
      binding.boost4Animation,
      binding.boost5Animation,
      binding.boost6Animation
    ).forEach {
      it.cancelAnimation()
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    donationCheckoutDelegate = null
  }

  private fun getConfiguration(state: DonateToSignalState): DSLConfiguration {
    return configure {
      space(36.dp)

      customPref(BadgePreview.BadgeModel.SubscriptionModel(state.badge))

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

      customPref(
        CurrencySelection.Model(
          selectedCurrency = state.selectedCurrency,
          isEnabled = state.canSetCurrency,
          onClick = {
            viewModel.requestChangeCurrency()
          }
        )
      )

      space(16.dp)

      customPref(
        DonationPillToggle.Model(
          selected = state.donateToSignalType,
          onClick = {
            viewModel.toggleDonationType()
          }
        )
      )

      space(10.dp)

      when (state.donateToSignalType) {
        DonateToSignalType.ONE_TIME -> displayOneTimeSelection(state.areFieldsEnabled, state.oneTimeDonationState)
        DonateToSignalType.MONTHLY -> displayMonthlySelection(state.areFieldsEnabled, state.monthlyDonationState)
        DonateToSignalType.GIFT -> error("This fragment does not support gifts.")
      }

      space(20.dp)

      if (state.donateToSignalType == DonateToSignalType.MONTHLY && state.monthlyDonationState.isSubscriptionActive) {
        primaryButton(
          text = DSLSettingsText.from(R.string.SubscribeFragment__update_subscription),
          isEnabled = state.canUpdate,
          onClick = {
            if (state.monthlyDonationState.transactionState.isTransactionJobPending) {
              showDonationPendingDialog(state)
            } else {
              MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.SubscribeFragment__update_subscription_question)
                .setMessage(
                  getString(
                    R.string.SubscribeFragment__you_will_be_charged_the_full_amount_s_of,
                    FiatMoneyUtil.format(
                      requireContext().resources,
                      viewModel.getSelectedSubscriptionCost(),
                      FiatMoneyUtil.formatOptions().trimZerosAfterDecimal()
                    )
                  )
                )
                .setPositiveButton(R.string.SubscribeFragment__update) { _, _ ->
                  viewModel.updateSubscription()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            }
          }
        )

        space(4.dp)

        secondaryButtonNoOutline(
          text = DSLSettingsText.from(R.string.SubscribeFragment__cancel_subscription),
          isEnabled = state.areFieldsEnabled,
          onClick = {
            if (state.monthlyDonationState.transactionState.isTransactionJobPending) {
              showDonationPendingDialog(state)
            } else {
              MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.SubscribeFragment__confirm_cancellation)
                .setMessage(R.string.SubscribeFragment__you_wont_be_charged_again)
                .setPositiveButton(R.string.SubscribeFragment__confirm) { _, _ ->
                  viewModel.cancelSubscription()
                }
                .setNegativeButton(R.string.SubscribeFragment__not_now) { _, _ -> }
                .show()
            }
          }
        )
      } else {
        primaryButton(
          text = DSLSettingsText.from(R.string.DonateToSignalFragment__continue),
          isEnabled = state.continueEnabled,
          onClick = {
            if (state.canContinue) {
              viewModel.requestSelectGateway()
            } else {
              showDonationPendingDialog(state)
            }
          }
        )
      }
    }
  }

  private fun showDonationPendingDialog(state: DonateToSignalState) {
    val message = if (state.donateToSignalType == DonateToSignalType.ONE_TIME) {
      if (state.oneTimeDonationState.isOneTimeDonationLongRunning) {
        R.string.DonateToSignalFragment__bank_transfers_usually_take_1_business_day_to_process_onetime
      } else if (state.oneTimeDonationState.isNonVerifiedIdeal) {
        R.string.DonateToSignalFragment__your_ideal_payment_is_still_processing
      } else {
        R.string.DonateToSignalFragment__your_payment_is_still_being_processed_onetime
      }
    } else {
      if (state.monthlyDonationState.activeSubscription?.paymentMethod == ActiveSubscription.PAYMENT_METHOD_SEPA_DEBIT) {
        R.string.DonateToSignalFragment__bank_transfers_usually_take_1_business_day_to_process_monthly
      } else if (state.monthlyDonationState.nonVerifiedMonthlyDonation != null) {
        R.string.DonateToSignalFragment__your_ideal_payment_is_still_processing
      } else {
        R.string.DonateToSignalFragment__your_payment_is_still_being_processed_monthly
      }
    }

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.DonateToSignalFragment__you_have_a_donation_pending)
      .setMessage(message)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun DSLConfiguration.displayOneTimeSelection(areFieldsEnabled: Boolean, state: DonateToSignalState.OneTimeDonationState) {
    when (state.donationStage) {
      DonateToSignalState.DonationStage.INIT -> customPref(Boost.LoadingModel())
      DonateToSignalState.DonationStage.FAILURE -> customPref(NetworkFailure.Model { viewModel.retryOneTimeDonationState() })
      DonateToSignalState.DonationStage.READY -> {
        customPref(
          Boost.SelectionModel(
            boosts = state.boosts,
            selectedBoost = state.selectedBoost,
            currency = state.customAmount.currency,
            isCustomAmountFocused = state.isCustomAmountFocused,
            isCustomAmountTooSmall = state.shouldDisplayCustomAmountTooSmallError,
            minimumAmount = state.minimumDonationAmountOfSelectedCurrency,
            isEnabled = areFieldsEnabled,
            onBoostClick = { view, boost ->
              startAnimationAboveSelectedBoost(view)
              viewModel.setSelectedBoost(boost)
            },
            onCustomAmountChanged = {
              viewModel.setCustomAmount(it)
            },
            onCustomAmountFocusChanged = {
              if (it) {
                viewModel.setCustomAmountFocused()
              }
            }
          )
        )
      }
    }
  }

  private fun DSLConfiguration.displayMonthlySelection(areFieldsEnabled: Boolean, state: DonateToSignalState.MonthlyDonationState) {
    when (state.donationStage) {
      DonateToSignalState.DonationStage.INIT -> customPref(Subscription.LoaderModel())
      DonateToSignalState.DonationStage.FAILURE -> customPref(NetworkFailure.Model { viewModel.retryMonthlyDonationState() })
      else -> {
        state.subscriptions.forEach { subscription ->

          val isActive = state.activeLevel == subscription.level && state.isSubscriptionActive

          val activePrice = state.activeSubscription?.let { sub ->
            val activeCurrency = Currency.getInstance(sub.currency)
            val activeAmount = sub.amount.movePointLeft(activeCurrency.defaultFractionDigits)

            FiatMoney(activeAmount, activeCurrency)
          }

          customPref(
            Subscription.Model(
              activePrice = if (isActive) activePrice else null,
              subscription = subscription,
              isSelected = state.selectedSubscription == subscription,
              isEnabled = areFieldsEnabled,
              isActive = isActive,
              willRenew = isActive && !state.isActiveSubscriptionEnding,
              onClick = { viewModel.setSelectedSubscription(it) },
              renewalTimestamp = state.renewalTimestamp,
              selectedCurrency = state.selectedCurrency
            )
          )
        }
      }
    }
  }

  private fun startAnimationAboveSelectedBoost(view: View) {
    val animationView = getAnimationContainer(view)
    val viewProjection = Projection.relativeToViewRoot(view, null)
    val animationProjection = Projection.relativeToViewRoot(animationView, null)
    val viewHorizontalCenter = viewProjection.x + viewProjection.width / 2f
    val animationHorizontalCenter = animationProjection.x + animationProjection.width / 2f
    val animationBottom = animationProjection.y + animationProjection.height

    animationView.translationY = -(animationBottom - viewProjection.y) + (viewProjection.height / 2f)
    animationView.translationX = viewHorizontalCenter - animationHorizontalCenter

    animationView.playAnimation()

    viewProjection.release()
    animationProjection.release()
  }

  private fun getAnimationContainer(view: View): LottieAnimationView {
    return when (view.id) {
      R.id.boost_1 -> binding.boost1Animation
      R.id.boost_2 -> binding.boost2Animation
      R.id.boost_3 -> binding.boost3Animation
      R.id.boost_4 -> binding.boost4Animation
      R.id.boost_5 -> binding.boost5Animation
      R.id.boost_6 -> binding.boost6Animation
      else -> throw AssertionError()
    }
  }

  override fun navigateToStripePaymentInProgress(gatewayRequest: GatewayRequest) {
    findNavController().safeNavigate(DonateToSignalFragmentDirections.actionDonateToSignalFragmentToStripePaymentInProgressFragment(DonationProcessorAction.PROCESS_NEW_DONATION, gatewayRequest))
  }

  override fun navigateToPayPalPaymentInProgress(gatewayRequest: GatewayRequest) {
    findNavController().safeNavigate(
      DonateToSignalFragmentDirections.actionDonateToSignalFragmentToPaypalPaymentInProgressFragment(
        DonationProcessorAction.PROCESS_NEW_DONATION,
        gatewayRequest
      )
    )
  }

  override fun navigateToCreditCardForm(gatewayRequest: GatewayRequest) {
    findNavController().safeNavigate(DonateToSignalFragmentDirections.actionDonateToSignalFragmentToCreditCardFragment(gatewayRequest))
  }

  override fun navigateToIdealDetailsFragment(gatewayRequest: GatewayRequest) {
    findNavController().safeNavigate(DonateToSignalFragmentDirections.actionDonateToSignalFragmentToIdealTransferDetailsFragment(gatewayRequest))
  }

  override fun navigateToBankTransferMandate(gatewayResponse: GatewayResponse) {
    findNavController().safeNavigate(DonateToSignalFragmentDirections.actionDonateToSignalFragmentToBankTransferMandateFragment(gatewayResponse))
  }

  override fun onPaymentComplete(gatewayRequest: GatewayRequest) {
    findNavController().safeNavigate(DonateToSignalFragmentDirections.actionDonateToSignalFragmentToThanksForYourSupportBottomSheetDialog(gatewayRequest.badge))
  }

  override fun onProcessorActionProcessed() {
    viewModel.refreshActiveSubscription()
  }

  override fun showSepaEuroMaximumDialog(sepaEuroMaximum: FiatMoney) {
    val max = FiatMoneyUtil.format(resources, sepaEuroMaximum, FiatMoneyUtil.formatOptions().trimZerosAfterDecimal())
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.DonateToSignal__donation_amount_too_high)
      .setMessage(getString(R.string.DonateToSignalFragment__you_can_send_up_to_s_via_bank_transfer, max))
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  override fun onUserLaunchedAnExternalApplication() = Unit

  override fun navigateToDonationPending(gatewayRequest: GatewayRequest) {
    findNavController().safeNavigate(DonateToSignalFragmentDirections.actionDonateToSignalFragmentToDonationPendingBottomSheet(gatewayRequest))
  }
}
