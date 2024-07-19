package org.stalker.securesms.components.settings.app.subscription.donate

import android.content.Context
import android.content.DialogInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.google.android.gms.wallet.PaymentData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.getParcelableCompat
import org.signal.core.util.logging.Log
import org.signal.core.util.money.FiatMoney
import org.signal.donations.GooglePayApi
import org.stalker.securesms.R
import org.stalker.securesms.components.settings.app.subscription.DonationPaymentComponent
import org.stalker.securesms.components.settings.app.subscription.InAppDonations
import org.stalker.securesms.components.settings.app.subscription.donate.card.CreditCardFragment
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewayRequest
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewayResponse
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewaySelectorBottomSheet
import org.stalker.securesms.components.settings.app.subscription.donate.paypal.PayPalPaymentInProgressFragment
import org.stalker.securesms.components.settings.app.subscription.donate.stripe.StripePaymentInProgressFragment
import org.stalker.securesms.components.settings.app.subscription.donate.stripe.StripePaymentInProgressViewModel
import org.stalker.securesms.components.settings.app.subscription.donate.transfer.BankTransferRequestKeys
import org.stalker.securesms.components.settings.app.subscription.errors.DonationError
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorDialogs
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorParams
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.payments.currency.CurrencyUtil
import org.stalker.securesms.util.fragments.requireListener
import java.math.BigDecimal
import java.util.Currency

/**
 * Abstracts out some common UI-level interactions between gift flow and normal donate flow.
 */
class DonationCheckoutDelegate(
  private val fragment: Fragment,
  private val callback: Callback,
  private val uiSessionKey: Long,
  errorSource: DonationErrorSource,
  vararg additionalSources: DonationErrorSource
) : DefaultLifecycleObserver {

  companion object {
    private val TAG = Log.tag(DonationCheckoutDelegate::class.java)
  }

  private lateinit var donationPaymentComponent: DonationPaymentComponent
  private val disposables = LifecycleDisposable()
  private val viewModel: DonationCheckoutViewModel by fragment.viewModels()

  private val stripePaymentViewModel: StripePaymentInProgressViewModel by fragment.navGraphViewModels(
    R.id.donate_to_signal,
    factoryProducer = {
      donationPaymentComponent = fragment.requireListener()
      StripePaymentInProgressViewModel.Factory(donationPaymentComponent.stripeRepository)
    }
  )

  init {
    fragment.viewLifecycleOwner.lifecycle.addObserver(this)
    ErrorHandler().attach(fragment, callback, uiSessionKey, errorSource, *additionalSources)
  }

  override fun onCreate(owner: LifecycleOwner) {
    disposables.bindTo(fragment.viewLifecycleOwner)
    donationPaymentComponent = fragment.requireListener()
    registerGooglePayCallback()

    fragment.setFragmentResultListener(GatewaySelectorBottomSheet.REQUEST_KEY) { _, bundle ->
      if (bundle.containsKey(GatewaySelectorBottomSheet.FAILURE_KEY)) {
        callback.showSepaEuroMaximumDialog(FiatMoney(bundle.getSerializable(GatewaySelectorBottomSheet.SEPA_EURO_MAX) as BigDecimal, CurrencyUtil.EURO))
      } else {
        val response: GatewayResponse = bundle.getParcelableCompat(GatewaySelectorBottomSheet.REQUEST_KEY, GatewayResponse::class.java)!!
        handleGatewaySelectionResponse(response)
      }
    }

    fragment.setFragmentResultListener(StripePaymentInProgressFragment.REQUEST_KEY) { _, bundle ->
      val result: DonationProcessorActionResult = bundle.getParcelableCompat(StripePaymentInProgressFragment.REQUEST_KEY, DonationProcessorActionResult::class.java)!!
      handleDonationProcessorActionResult(result)
    }

    fragment.setFragmentResultListener(CreditCardFragment.REQUEST_KEY) { _, bundle ->
      val result: DonationProcessorActionResult = bundle.getParcelableCompat(StripePaymentInProgressFragment.REQUEST_KEY, DonationProcessorActionResult::class.java)!!
      handleDonationProcessorActionResult(result)
    }

    fragment.setFragmentResultListener(BankTransferRequestKeys.REQUEST_KEY) { _, bundle ->
      val result: DonationProcessorActionResult = bundle.getParcelableCompat(StripePaymentInProgressFragment.REQUEST_KEY, DonationProcessorActionResult::class.java)!!
      handleDonationProcessorActionResult(result)
    }

    fragment.setFragmentResultListener(BankTransferRequestKeys.PENDING_KEY) { _, bundle ->
      val request: GatewayRequest = bundle.getParcelableCompat(BankTransferRequestKeys.PENDING_KEY, GatewayRequest::class.java)!!
      callback.navigateToDonationPending(gatewayRequest = request)
    }

    fragment.setFragmentResultListener(PayPalPaymentInProgressFragment.REQUEST_KEY) { _, bundle ->
      val result: DonationProcessorActionResult = bundle.getParcelableCompat(PayPalPaymentInProgressFragment.REQUEST_KEY, DonationProcessorActionResult::class.java)!!
      handleDonationProcessorActionResult(result)
    }
  }

  private fun handleGatewaySelectionResponse(gatewayResponse: GatewayResponse) {
    if (InAppDonations.isPaymentSourceAvailable(gatewayResponse.gateway.toPaymentSourceType(), gatewayResponse.request.donateToSignalType)) {
      when (gatewayResponse.gateway) {
        GatewayResponse.Gateway.GOOGLE_PAY -> launchGooglePay(gatewayResponse)
        GatewayResponse.Gateway.PAYPAL -> launchPayPal(gatewayResponse)
        GatewayResponse.Gateway.CREDIT_CARD -> launchCreditCard(gatewayResponse)
        GatewayResponse.Gateway.SEPA_DEBIT -> launchBankTransfer(gatewayResponse)
        GatewayResponse.Gateway.IDEAL -> launchBankTransfer(gatewayResponse)
      }
    } else {
      error("Unsupported combination! ${gatewayResponse.gateway} ${gatewayResponse.request.donateToSignalType}")
    }
  }

  private fun handleDonationProcessorActionResult(result: DonationProcessorActionResult) {
    when (result.status) {
      DonationProcessorActionResult.Status.SUCCESS -> handleSuccessfulDonationProcessorActionResult(result)
      DonationProcessorActionResult.Status.FAILURE -> handleFailedDonationProcessorActionResult(result)
    }

    callback.onProcessorActionProcessed()
  }

  private fun handleSuccessfulDonationProcessorActionResult(result: DonationProcessorActionResult) {
    if (result.action == DonationProcessorAction.CANCEL_SUBSCRIPTION) {
      Snackbar.make(fragment.requireView(), R.string.SubscribeFragment__your_subscription_has_been_cancelled, Snackbar.LENGTH_LONG).show()
    } else {
      SignalStore.donationsValues().removeTerminalDonation(result.request.level)
      callback.onPaymentComplete(result.request)
    }
  }

  private fun handleFailedDonationProcessorActionResult(result: DonationProcessorActionResult) {
    if (result.action == DonationProcessorAction.CANCEL_SUBSCRIPTION) {
      MaterialAlertDialogBuilder(fragment.requireContext())
        .setTitle(R.string.DonationsErrors__failed_to_cancel_subscription)
        .setMessage(R.string.DonationsErrors__subscription_cancellation_requires_an_internet_connection)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          fragment.findNavController().popBackStack()
        }
        .show()
    } else {
      Log.w(TAG, "Processor action failed: ${result.action}")
    }
  }

  private fun launchPayPal(gatewayResponse: GatewayResponse) {
    callback.navigateToPayPalPaymentInProgress(gatewayResponse.request)
  }

  private fun launchGooglePay(gatewayResponse: GatewayResponse) {
    viewModel.provideGatewayRequestForGooglePay(gatewayResponse.request)
    donationPaymentComponent.stripeRepository.requestTokenFromGooglePay(
      price = FiatMoney(gatewayResponse.request.price, Currency.getInstance(gatewayResponse.request.currencyCode)),
      label = gatewayResponse.request.label,
      requestCode = gatewayResponse.request.donateToSignalType.requestCode.toInt()
    )
  }

  private fun launchCreditCard(gatewayResponse: GatewayResponse) {
    callback.navigateToCreditCardForm(gatewayResponse.request)
  }

  private fun launchBankTransfer(gatewayResponse: GatewayResponse) {
    if (gatewayResponse.request.donateToSignalType != DonateToSignalType.MONTHLY && gatewayResponse.gateway == GatewayResponse.Gateway.IDEAL) {
      callback.navigateToIdealDetailsFragment(gatewayResponse.request)
    } else {
      callback.navigateToBankTransferMandate(gatewayResponse)
    }
  }

  private fun registerGooglePayCallback() {
    disposables += donationPaymentComponent.googlePayResultPublisher.subscribeBy(
      onNext = { paymentResult ->
        viewModel.consumeGatewayRequestForGooglePay()?.let {
          donationPaymentComponent.stripeRepository.onActivityResult(
            paymentResult.requestCode,
            paymentResult.resultCode,
            paymentResult.data,
            paymentResult.requestCode,
            GooglePayRequestCallback(it)
          )
        }
      }
    )
  }

  inner class GooglePayRequestCallback(private val request: GatewayRequest) : GooglePayApi.PaymentRequestCallback {
    override fun onSuccess(paymentData: PaymentData) {
      Log.d(TAG, "Successfully retrieved payment data from Google Pay", true)
      stripePaymentViewModel.providePaymentData(paymentData)
      callback.navigateToStripePaymentInProgress(request)
    }

    override fun onError(googlePayException: GooglePayApi.GooglePayException) {
      Log.w(TAG, "Failed to retrieve payment data from Google Pay", googlePayException, true)

      val error = DonationError.getGooglePayRequestTokenError(
        source = when (request.donateToSignalType) {
          DonateToSignalType.MONTHLY -> DonationErrorSource.MONTHLY
          DonateToSignalType.ONE_TIME -> DonationErrorSource.ONE_TIME
          DonateToSignalType.GIFT -> DonationErrorSource.GIFT
        },
        throwable = googlePayException
      )

      DonationError.routeDonationError(fragment.requireContext(), error)
    }

    override fun onCancelled() {
      Log.d(TAG, "Cancelled Google Pay.", true)
    }
  }

  /**
   * Shared logic for handling checkout errors.
   */
  class ErrorHandler : DefaultLifecycleObserver {

    private var fragment: Fragment? = null
    private var errorDialog: DialogInterface? = null
    private var errorHandlerCallback: ErrorHandlerCallback? = null

    fun attach(fragment: Fragment, errorHandlerCallback: ErrorHandlerCallback?, uiSessionKey: Long, errorSource: DonationErrorSource, vararg additionalSources: DonationErrorSource) {
      this.fragment = fragment
      this.errorHandlerCallback = errorHandlerCallback

      val disposables = LifecycleDisposable()
      fragment.viewLifecycleOwner.lifecycle.addObserver(this)

      disposables.bindTo(fragment.viewLifecycleOwner)
      disposables += registerErrorSource(errorSource)
      additionalSources.forEach { source ->
        disposables += registerErrorSource(source)
      }

      disposables += registerUiSession(uiSessionKey)
    }

    override fun onDestroy(owner: LifecycleOwner) {
      errorDialog?.dismiss()
      fragment = null
      errorHandlerCallback = null
    }

    private fun registerErrorSource(errorSource: DonationErrorSource): Disposable {
      return DonationError.getErrorsForSource(errorSource)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { error ->
          showErrorDialog(error)
        }
    }

    private fun registerUiSession(uiSessionKey: Long): Disposable {
      return DonationError.getErrorsForUiSessionKey(uiSessionKey)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe {
          showErrorDialog(it)
        }
    }

    private fun showErrorDialog(throwable: Throwable) {
      if (errorDialog != null) {
        Log.d(TAG, "Already displaying an error dialog. Skipping.", throwable, true)
        return
      }

      if (throwable is DonationError.UserCancelledPaymentError) {
        Log.d(TAG, "User cancelled out of payment flow.", true)

        return
      }

      if (throwable is DonationError.UserLaunchedExternalApplication) {
        Log.d(TAG, "User launched an external application.", true)
        errorHandlerCallback?.onUserLaunchedAnExternalApplication()
        return
      }

      if (throwable is DonationError.BadgeRedemptionError.DonationPending) {
        Log.d(TAG, "Long-running donation is still pending.", true)
        errorHandlerCallback?.navigateToDonationPending(throwable.gatewayRequest)
        return
      }

      Log.d(TAG, "Displaying donation error dialog.", true)
      errorDialog = DonationErrorDialogs.show(
        fragment!!.requireContext(),
        throwable,
        object : DonationErrorDialogs.DialogCallback() {
          var tryAgain = false

          override fun onTryCreditCardAgain(context: Context): DonationErrorParams.ErrorAction<Unit> {
            return DonationErrorParams.ErrorAction(
              label = R.string.DeclineCode__try,
              action = {
                tryAgain = true
              }
            )
          }

          override fun onTryBankTransferAgain(context: Context): DonationErrorParams.ErrorAction<Unit> {
            return DonationErrorParams.ErrorAction(
              label = R.string.DeclineCode__try,
              action = {
                tryAgain = true
              }
            )
          }

          override fun onDialogDismissed() {
            errorDialog = null
            if (!tryAgain) {
              tryAgain = false
              fragment?.findNavController()?.popBackStack()
            }
          }
        }
      )
    }
  }

  interface ErrorHandlerCallback {
    fun onUserLaunchedAnExternalApplication()
    fun navigateToDonationPending(gatewayRequest: GatewayRequest)
  }

  interface Callback : ErrorHandlerCallback {
    fun navigateToStripePaymentInProgress(gatewayRequest: GatewayRequest)
    fun navigateToPayPalPaymentInProgress(gatewayRequest: GatewayRequest)
    fun navigateToCreditCardForm(gatewayRequest: GatewayRequest)
    fun navigateToIdealDetailsFragment(gatewayRequest: GatewayRequest)
    fun navigateToBankTransferMandate(gatewayResponse: GatewayResponse)
    fun onPaymentComplete(gatewayRequest: GatewayRequest)
    fun onProcessorActionProcessed()
    fun showSepaEuroMaximumDialog(sepaEuroMaximum: FiatMoney)
  }
}
