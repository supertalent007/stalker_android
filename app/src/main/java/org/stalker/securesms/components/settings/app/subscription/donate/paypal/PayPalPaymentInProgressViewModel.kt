package org.stalker.securesms.components.settings.app.subscription.donate.paypal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.signal.donations.PaymentSourceType
import org.stalker.securesms.components.settings.app.subscription.MonthlyDonationRepository
import org.stalker.securesms.components.settings.app.subscription.OneTimeDonationRepository
import org.stalker.securesms.components.settings.app.subscription.PayPalRepository
import org.stalker.securesms.components.settings.app.subscription.donate.DonateToSignalType
import org.stalker.securesms.components.settings.app.subscription.donate.DonationProcessorStage
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewayRequest
import org.stalker.securesms.components.settings.app.subscription.errors.DonationError
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.stalker.securesms.components.settings.app.subscription.errors.toDonationError
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobs.MultiDeviceSubscriptionSyncRequestJob
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.util.rx.RxStore
import org.whispersystems.signalservice.api.subscriptions.PayPalCreatePaymentIntentResponse
import org.whispersystems.signalservice.api.subscriptions.PayPalCreatePaymentMethodResponse
import org.whispersystems.signalservice.api.util.Preconditions
import org.whispersystems.signalservice.internal.push.DonationProcessor
import org.whispersystems.signalservice.internal.push.exceptions.DonationProcessorError

class PayPalPaymentInProgressViewModel(
  private val payPalRepository: PayPalRepository,
  private val monthlyDonationRepository: MonthlyDonationRepository,
  private val oneTimeDonationRepository: OneTimeDonationRepository
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(PayPalPaymentInProgressViewModel::class.java)
  }

  private val store = RxStore(DonationProcessorStage.INIT)
  val state: Flowable<DonationProcessorStage> = store.stateFlowable.observeOn(AndroidSchedulers.mainThread())

  private val disposables = CompositeDisposable()
  override fun onCleared() {
    store.dispose()
    disposables.clear()
  }

  fun onBeginNewAction() {
    Preconditions.checkState(!store.state.isInProgress)

    Log.d(TAG, "Beginning a new action. Ensuring cleared state.", true)
    disposables.clear()
  }

  fun onEndAction() {
    Preconditions.checkState(store.state.isTerminal)

    Log.d(TAG, "Ending current state. Clearing state and setting stage to INIT", true)
    store.update { DonationProcessorStage.INIT }
    disposables.clear()
  }

  fun processNewDonation(
    request: GatewayRequest,
    routeToOneTimeConfirmation: (PayPalCreatePaymentIntentResponse) -> Single<PayPalConfirmationResult>,
    routeToMonthlyConfirmation: (PayPalCreatePaymentMethodResponse) -> Single<PayPalPaymentMethodId>
  ) {
    Log.d(TAG, "Proceeding with donation...", true)

    return when (request.donateToSignalType) {
      DonateToSignalType.ONE_TIME -> proceedOneTime(request, routeToOneTimeConfirmation)
      DonateToSignalType.MONTHLY -> proceedMonthly(request, routeToMonthlyConfirmation)
      DonateToSignalType.GIFT -> proceedOneTime(request, routeToOneTimeConfirmation)
    }
  }

  fun updateSubscription(request: GatewayRequest) {
    Log.d(TAG, "Beginning subscription update...", true)

    store.update { DonationProcessorStage.PAYMENT_PIPELINE }
    disposables += monthlyDonationRepository.cancelActiveSubscriptionIfNecessary().andThen(monthlyDonationRepository.setSubscriptionLevel(request, false))
      .subscribeBy(
        onComplete = {
          Log.w(TAG, "Completed subscription update", true)
          store.update { DonationProcessorStage.COMPLETE }
        },
        onError = { throwable ->
          Log.w(TAG, "Failed to update subscription", throwable, true)
          val donationError: DonationError = when (throwable) {
            is DonationError -> throwable
            is DonationProcessorError -> throwable.toDonationError(DonationErrorSource.MONTHLY, PaymentSourceType.PayPal)
            else -> DonationError.genericBadgeRedemptionFailure(DonationErrorSource.MONTHLY)
          }
          DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)

          store.update { DonationProcessorStage.FAILED }
        }
      )
  }

  fun cancelSubscription() {
    Log.d(TAG, "Beginning cancellation...", true)

    store.update { DonationProcessorStage.CANCELLING }
    disposables += monthlyDonationRepository.cancelActiveSubscription().subscribeBy(
      onComplete = {
        Log.d(TAG, "Cancellation succeeded", true)
        SignalStore.donationsValues().updateLocalStateForManualCancellation()
        MultiDeviceSubscriptionSyncRequestJob.enqueue()
        monthlyDonationRepository.syncAccountRecord().subscribe()
        store.update { DonationProcessorStage.COMPLETE }
      },
      onError = { throwable ->
        Log.w(TAG, "Cancellation failed", throwable, true)
        store.update { DonationProcessorStage.FAILED }
      }
    )
  }

  private fun proceedOneTime(
    request: GatewayRequest,
    routeToPaypalConfirmation: (PayPalCreatePaymentIntentResponse) -> Single<PayPalConfirmationResult>
  ) {
    Log.d(TAG, "Proceeding with one-time payment pipeline...", true)
    store.update { DonationProcessorStage.PAYMENT_PIPELINE }
    val verifyUser = if (request.donateToSignalType == DonateToSignalType.GIFT) {
      OneTimeDonationRepository.verifyRecipientIsAllowedToReceiveAGift(request.recipientId)
    } else {
      Completable.complete()
    }

    disposables += verifyUser
      .andThen(
        payPalRepository
          .createOneTimePaymentIntent(
            amount = request.fiat,
            badgeRecipient = request.recipientId,
            badgeLevel = request.level
          )
      )
      .flatMap(routeToPaypalConfirmation)
      .flatMap { result ->
        payPalRepository.confirmOneTimePaymentIntent(
          amount = request.fiat,
          badgeLevel = request.level,
          paypalConfirmationResult = result
        )
      }
      .flatMapCompletable { response ->
        oneTimeDonationRepository.waitForOneTimeRedemption(
          gatewayRequest = request,
          paymentIntentId = response.paymentId,
          donationProcessor = DonationProcessor.PAYPAL,
          paymentSourceType = PaymentSourceType.PayPal
        )
      }
      .subscribeOn(Schedulers.io())
      .subscribeBy(
        onError = { throwable ->
          Log.w(TAG, "Failure in one-time payment pipeline...", throwable, true)
          store.update { DonationProcessorStage.FAILED }

          val donationError: DonationError = when (throwable) {
            is DonationError -> throwable
            is DonationProcessorError -> throwable.toDonationError(request.donateToSignalType.toErrorSource(), PaymentSourceType.PayPal)
            else -> DonationError.genericBadgeRedemptionFailure(request.donateToSignalType.toErrorSource())
          }
          DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)
        },
        onComplete = {
          Log.d(TAG, "Finished one-time payment pipeline...", true)
          store.update { DonationProcessorStage.COMPLETE }
        }
      )
  }

  private fun proceedMonthly(request: GatewayRequest, routeToPaypalConfirmation: (PayPalCreatePaymentMethodResponse) -> Single<PayPalPaymentMethodId>) {
    Log.d(TAG, "Proceeding with monthly payment pipeline...")

    val setup = monthlyDonationRepository.ensureSubscriberId()
      .andThen(monthlyDonationRepository.cancelActiveSubscriptionIfNecessary())
      .andThen(payPalRepository.createPaymentMethod())
      .flatMap(routeToPaypalConfirmation)
      .flatMapCompletable { payPalRepository.setDefaultPaymentMethod(it.paymentId) }
      .onErrorResumeNext { Completable.error(DonationError.getPaymentSetupError(DonationErrorSource.MONTHLY, it, PaymentSourceType.PayPal)) }

    disposables += setup.andThen(monthlyDonationRepository.setSubscriptionLevel(request, false))
      .subscribeBy(
        onError = { throwable ->
          Log.w(TAG, "Failure in monthly payment pipeline...", throwable, true)
          store.update { DonationProcessorStage.FAILED }

          val donationError: DonationError = when (throwable) {
            is DonationError -> throwable
            is DonationProcessorError -> throwable.toDonationError(DonationErrorSource.MONTHLY, PaymentSourceType.PayPal)
            else -> DonationError.genericBadgeRedemptionFailure(DonationErrorSource.MONTHLY)
          }
          DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)
        },
        onComplete = {
          Log.d(TAG, "Finished subscription payment pipeline...", true)
          store.update { DonationProcessorStage.COMPLETE }
        }
      )
  }

  class Factory(
    private val payPalRepository: PayPalRepository = PayPalRepository(ApplicationDependencies.getDonationsService()),
    private val monthlyDonationRepository: MonthlyDonationRepository = MonthlyDonationRepository(ApplicationDependencies.getDonationsService()),
    private val oneTimeDonationRepository: OneTimeDonationRepository = OneTimeDonationRepository(ApplicationDependencies.getDonationsService())
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(PayPalPaymentInProgressViewModel(payPalRepository, monthlyDonationRepository, oneTimeDonationRepository)) as T
    }
  }
}
