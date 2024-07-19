package org.stalker.securesms.components.settings.app.subscription

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.signal.core.util.money.FiatMoney
import org.signal.donations.PaymentSourceType
import org.stalker.securesms.components.settings.app.subscription.donate.paypal.PayPalConfirmationResult
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.services.DonationsService
import org.whispersystems.signalservice.api.subscriptions.PayPalConfirmPaymentIntentResponse
import org.whispersystems.signalservice.api.subscriptions.PayPalCreatePaymentIntentResponse
import org.whispersystems.signalservice.api.subscriptions.PayPalCreatePaymentMethodResponse
import java.util.Locale

/**
 * Repository that deals directly with PayPal API calls. Since we don't interact with the PayPal APIs directly (yet)
 * we can do everything here in one place.
 */
class PayPalRepository(private val donationsService: DonationsService) {

  companion object {
    const val ONE_TIME_RETURN_URL = "https://signaldonations.org/return/onetime"
    const val MONTHLY_RETURN_URL = "https://signaldonations.org/return/monthly"
    const val CANCEL_URL = "https://signaldonations.org/cancel"

    private val TAG = Log.tag(PayPalRepository::class.java)
  }

  private val monthlyDonationRepository = MonthlyDonationRepository(donationsService)

  fun createOneTimePaymentIntent(
    amount: FiatMoney,
    badgeRecipient: RecipientId,
    badgeLevel: Long
  ): Single<PayPalCreatePaymentIntentResponse> {
    return Single.fromCallable {
      donationsService
        .createPayPalOneTimePaymentIntent(
          Locale.getDefault(),
          amount.currency.currencyCode,
          amount.minimumUnitPrecisionString,
          badgeLevel,
          ONE_TIME_RETURN_URL,
          CANCEL_URL
        )
    }
      .flatMap { it.flattenResult() }
      .onErrorResumeNext { OneTimeDonationRepository.handleCreatePaymentIntentError(it, badgeRecipient, PaymentSourceType.PayPal) }
      .subscribeOn(Schedulers.io())
  }

  fun confirmOneTimePaymentIntent(
    amount: FiatMoney,
    badgeLevel: Long,
    paypalConfirmationResult: PayPalConfirmationResult
  ): Single<PayPalConfirmPaymentIntentResponse> {
    return Single.fromCallable {
      Log.d(TAG, "Confirming one-time payment intent...", true)
      donationsService
        .confirmPayPalOneTimePaymentIntent(
          amount.currency.currencyCode,
          amount.minimumUnitPrecisionString,
          badgeLevel,
          paypalConfirmationResult.payerId,
          paypalConfirmationResult.paymentId,
          paypalConfirmationResult.paymentToken
        )
    }.flatMap { it.flattenResult() }.subscribeOn(Schedulers.io())
  }

  /**
   * Creates the PaymentMethod via the Signal Service. Note that if the operation fails with a 409,
   * it means that the PaymentMethod is already tied to a Stripe account. We can retry in this
   * situation by simply deleting the old subscriber id on the service and replacing it.
   */
  fun createPaymentMethod(retryOn409: Boolean = true): Single<PayPalCreatePaymentMethodResponse> {
    return Single.fromCallable {
      donationsService.createPayPalPaymentMethod(
        Locale.getDefault(),
        SignalStore.donationsValues().requireSubscriber().subscriberId,
        MONTHLY_RETURN_URL,
        CANCEL_URL
      )
    }.flatMap { serviceResponse ->
      if (retryOn409 && serviceResponse.status == 409) {
        monthlyDonationRepository.rotateSubscriberId().andThen(createPaymentMethod(retryOn409 = false))
      } else {
        serviceResponse.flattenResult()
      }
    }.subscribeOn(Schedulers.io())
  }

  fun setDefaultPaymentMethod(paymentMethodId: String): Completable {
    return Single.fromCallable {
      Log.d(TAG, "Setting default payment method...", true)
      donationsService.setDefaultPayPalPaymentMethod(
        SignalStore.donationsValues().requireSubscriber().subscriberId,
        paymentMethodId
      )
    }.flatMap { it.flattenResult() }.ignoreElement().doOnComplete {
      Log.d(TAG, "Set default payment method.", true)
      Log.d(TAG, "Storing the subscription payment source type locally.", true)
      SignalStore.donationsValues().setSubscriptionPaymentSourceType(PaymentSourceType.PayPal)
    }.subscribeOn(Schedulers.io())
  }
}
