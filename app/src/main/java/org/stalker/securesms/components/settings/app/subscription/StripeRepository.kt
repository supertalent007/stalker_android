package org.stalker.securesms.components.settings.app.subscription

import android.app.Activity
import android.content.Intent
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.core.util.money.FiatMoney
import org.signal.donations.GooglePayApi
import org.signal.donations.PaymentSourceType
import org.signal.donations.StripeApi
import org.signal.donations.StripeIntentAccessor
import org.signal.donations.json.StripeIntentStatus
import org.stalker.securesms.components.settings.app.subscription.errors.DonationError
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.storage.StorageSyncHelper
import org.stalker.securesms.util.Environment
import org.whispersystems.signalservice.api.subscriptions.StripeClientSecret
import org.whispersystems.signalservice.internal.EmptyResponse
import org.whispersystems.signalservice.internal.ServiceResponse

/**
 * Manages bindings with payment APIs
 *
 * Steps for setting up payments for a subscription:
 * 1. Ask GooglePay for a payment token. This will pop up the Google Pay Sheet, which allows the user to select a payment method.
 * 1. Generate and send a SubscriberId, which is a 32 byte ID representing this user, to Signal Service, which creates a Stripe Customer
 * 1. Create a SetupIntent via the Stripe API
 * 1. Create a PaymentMethod vai the Stripe API, utilizing the token from Google Pay
 * 1. Confirm the SetupIntent via the Stripe API
 * 1. Set the default PaymentMethod for the customer, using the PaymentMethod id, via the Signal service
 *
 * For Boosts and Gifts:
 * 1. Ask GooglePay for a payment token. This will pop up the Google Pay Sheet, which allows the user to select a payment method.
 * 1. Create a PaymentIntent via the Stripe API
 * 1. Create a PaymentMethod vai the Stripe API, utilizing the token from Google Pay
 * 1. Confirm the PaymentIntent via the Stripe API
 */
class StripeRepository(activity: Activity) : StripeApi.PaymentIntentFetcher, StripeApi.SetupIntentHelper {

  private val googlePayApi = GooglePayApi(activity, StripeApi.Gateway(Environment.Donations.STRIPE_CONFIGURATION), Environment.Donations.GOOGLE_PAY_CONFIGURATION)
  private val stripeApi = StripeApi(Environment.Donations.STRIPE_CONFIGURATION, this, this, ApplicationDependencies.getOkHttpClient())
  private val monthlyDonationRepository = MonthlyDonationRepository(ApplicationDependencies.getDonationsService())

  fun isGooglePayAvailable(): Completable {
    return googlePayApi.queryIsReadyToPay()
  }

  fun scheduleSyncForAccountRecordChange() {
    SignalExecutors.BOUNDED.execute {
      scheduleSyncForAccountRecordChangeSync()
    }
  }

  private fun scheduleSyncForAccountRecordChangeSync() {
    SignalDatabase.recipients.markNeedsSync(Recipient.self().id)
    StorageSyncHelper.scheduleSyncForDataChange()
  }

  fun requestTokenFromGooglePay(price: FiatMoney, label: String, requestCode: Int) {
    Log.d(TAG, "Requesting a token from google pay...")
    googlePayApi.requestPayment(price, label, requestCode)
  }

  fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
    expectedRequestCode: Int,
    paymentsRequestCallback: GooglePayApi.PaymentRequestCallback
  ) {
    Log.d(TAG, "Processing possible google pay result...")
    googlePayApi.onActivityResult(requestCode, resultCode, data, expectedRequestCode, paymentsRequestCallback)
  }

  /**
   * @param price             The amount to charce the local user
   * @param badgeRecipient    Who will be getting the badge
   */
  fun continuePayment(
    price: FiatMoney,
    badgeRecipient: RecipientId,
    badgeLevel: Long,
    paymentSourceType: PaymentSourceType
  ): Single<StripeIntentAccessor> {
    check(paymentSourceType is PaymentSourceType.Stripe)

    Log.d(TAG, "Creating payment intent for $price...", true)

    return stripeApi.createPaymentIntent(price, badgeLevel, paymentSourceType)
      .onErrorResumeNext {
        OneTimeDonationRepository.handleCreatePaymentIntentError(it, badgeRecipient, paymentSourceType)
      }
      .flatMap { result ->
        val recipient = Recipient.resolved(badgeRecipient)
        val errorSource = if (recipient.isSelf) DonationErrorSource.ONE_TIME else DonationErrorSource.GIFT

        Log.d(TAG, "Created payment intent for $price.", true)
        when (result) {
          is StripeApi.CreatePaymentIntentResult.AmountIsTooSmall -> Single.error(DonationError.oneTimeDonationAmountTooSmall(errorSource))
          is StripeApi.CreatePaymentIntentResult.AmountIsTooLarge -> Single.error(DonationError.oneTimeDonationAmountTooLarge(errorSource))
          is StripeApi.CreatePaymentIntentResult.CurrencyIsNotSupported -> Single.error(DonationError.invalidCurrencyForOneTimeDonation(errorSource))
          is StripeApi.CreatePaymentIntentResult.Success -> Single.just(result.paymentIntent)
        }
      }.subscribeOn(Schedulers.io())
  }

  fun createAndConfirmSetupIntent(
    paymentSource: StripeApi.PaymentSource,
    paymentSourceType: PaymentSourceType.Stripe
  ): Single<StripeApi.Secure3DSAction> {
    Log.d(TAG, "Continuing subscription setup...", true)
    return stripeApi.createSetupIntent(paymentSourceType)
      .flatMap { result ->
        Log.d(TAG, "Retrieved SetupIntent, confirming...", true)
        stripeApi.confirmSetupIntent(paymentSource, result.setupIntent)
      }
  }

  fun confirmPayment(
    paymentSource: StripeApi.PaymentSource,
    paymentIntent: StripeIntentAccessor,
    badgeRecipient: RecipientId
  ): Single<StripeApi.Secure3DSAction> {
    val isBoost = badgeRecipient == Recipient.self().id
    val donationErrorSource: DonationErrorSource = if (isBoost) DonationErrorSource.ONE_TIME else DonationErrorSource.GIFT

    Log.d(TAG, "Confirming payment intent...", true)
    return stripeApi.confirmPaymentIntent(paymentSource, paymentIntent)
      .onErrorResumeNext {
        Single.error(DonationError.getPaymentSetupError(donationErrorSource, it, paymentSource.type))
      }
  }

  override fun fetchPaymentIntent(price: FiatMoney, level: Long, sourceType: PaymentSourceType.Stripe): Single<StripeIntentAccessor> {
    Log.d(TAG, "Fetching payment intent from Signal service for $price... (Locale.US minimum precision: ${price.minimumUnitPrecisionString})")
    return Single
      .fromCallable {
        ApplicationDependencies
          .getDonationsService()
          .createDonationIntentWithAmount(price.minimumUnitPrecisionString, price.currency.currencyCode, level, sourceType.paymentMethod)
      }
      .flatMap(ServiceResponse<StripeClientSecret>::flattenResult)
      .map {
        StripeIntentAccessor(
          objectType = StripeIntentAccessor.ObjectType.PAYMENT_INTENT,
          intentId = it.id,
          intentClientSecret = it.clientSecret
        )
      }.doOnSuccess {
        Log.d(TAG, "Got payment intent from Signal service!")
      }
  }

  /**
   * Creates the PaymentMethod via the Signal Service. Note that if the operation fails with a 409,
   * it means that the PaymentMethod is already tied to a PayPal account. We can retry in this
   * situation by simply deleting the old subscriber id on the service and replacing it.
   */
  private fun createPaymentMethod(paymentSourceType: PaymentSourceType.Stripe, retryOn409: Boolean = true): Single<StripeClientSecret> {
    return Single.fromCallable { SignalStore.donationsValues().requireSubscriber() }
      .flatMap {
        Single.fromCallable {
          ApplicationDependencies
            .getDonationsService()
            .createStripeSubscriptionPaymentMethod(it.subscriberId, paymentSourceType.paymentMethod)
        }
      }
      .flatMap { serviceResponse ->
        if (retryOn409 && serviceResponse.status == 409) {
          monthlyDonationRepository.rotateSubscriberId().andThen(createPaymentMethod(paymentSourceType, retryOn409 = false))
        } else {
          serviceResponse.flattenResult()
        }
      }
  }

  override fun fetchSetupIntent(sourceType: PaymentSourceType.Stripe): Single<StripeIntentAccessor> {
    Log.d(TAG, "Fetching setup intent from Signal service...")
    return createPaymentMethod(sourceType)
      .map {
        StripeIntentAccessor(
          objectType = StripeIntentAccessor.ObjectType.SETUP_INTENT,
          intentId = it.id,
          intentClientSecret = it.clientSecret
        )
      }
      .doOnSuccess {
        Log.d(TAG, "Got setup intent from Signal service!")
      }
  }

  /**
   * Note: There seem to be times when PaymentIntent does not return a status. In these cases, we assume
   *       that we are successful and proceed as normal. If the payment didn't actually succeed, then we
   *       expect an error later in the chain to inform us of this.
   */
  fun getStatusAndPaymentMethodId(
    stripeIntentAccessor: StripeIntentAccessor,
    paymentMethodId: String?
  ): Single<StatusAndPaymentMethodId> {
    return Single.fromCallable {
      when (stripeIntentAccessor.objectType) {
        StripeIntentAccessor.ObjectType.NONE -> StatusAndPaymentMethodId(stripeIntentAccessor.intentId, StripeIntentStatus.SUCCEEDED, paymentMethodId)
        StripeIntentAccessor.ObjectType.PAYMENT_INTENT -> stripeApi.getPaymentIntent(stripeIntentAccessor).let {
          if (it.status == null) {
            Log.d(TAG, "Returned payment intent had a null status.", true)
          }
          StatusAndPaymentMethodId(stripeIntentAccessor.intentId, it.status ?: StripeIntentStatus.SUCCEEDED, it.paymentMethod)
        }

        StripeIntentAccessor.ObjectType.SETUP_INTENT -> stripeApi.getSetupIntent(stripeIntentAccessor).let {
          StatusAndPaymentMethodId(stripeIntentAccessor.intentId, it.status, it.paymentMethod)
        }
      }
    }
  }

  fun setDefaultPaymentMethod(
    paymentMethodId: String,
    setupIntentId: String,
    paymentSourceType: PaymentSourceType
  ): Completable {
    return Single.fromCallable {
      Log.d(TAG, "Getting the subscriber...")
      SignalStore.donationsValues().requireSubscriber()
    }.flatMap {
      Log.d(TAG, "Setting default payment method via Signal service...")
      Single.fromCallable {
        if (paymentSourceType == PaymentSourceType.Stripe.IDEAL) {
          ApplicationDependencies
            .getDonationsService()
            .setDefaultIdealPaymentMethod(it.subscriberId, setupIntentId)
        } else {
          ApplicationDependencies
            .getDonationsService()
            .setDefaultStripePaymentMethod(it.subscriberId, paymentMethodId)
        }
      }
    }.flatMap(ServiceResponse<EmptyResponse>::flattenResult).ignoreElement().doOnComplete {
      Log.d(TAG, "Set default payment method via Signal service!")
      Log.d(TAG, "Storing the subscription payment source type locally.")
      SignalStore.donationsValues().setSubscriptionPaymentSourceType(paymentSourceType)
    }
  }

  fun createCreditCardPaymentSource(donationErrorSource: DonationErrorSource, cardData: StripeApi.CardData): Single<StripeApi.PaymentSource> {
    Log.d(TAG, "Creating credit card payment source via Stripe api...")
    return stripeApi.createPaymentSourceFromCardData(cardData).map {
      when (it) {
        is StripeApi.CreatePaymentSourceFromCardDataResult.Failure -> throw DonationError.getPaymentSetupError(donationErrorSource, it.reason, PaymentSourceType.Stripe.CreditCard)
        is StripeApi.CreatePaymentSourceFromCardDataResult.Success -> it.paymentSource
      }
    }
  }

  fun createSEPADebitPaymentSource(sepaDebitData: StripeApi.SEPADebitData): Single<StripeApi.PaymentSource> {
    Log.d(TAG, "Creating SEPA Debit payment source via Stripe api...")
    return stripeApi.createPaymentSourceFromSEPADebitData(sepaDebitData)
  }

  fun createIdealPaymentSource(idealData: StripeApi.IDEALData): Single<StripeApi.PaymentSource> {
    Log.d(TAG, "Creating iDEAL payment source via Stripe api...")
    return stripeApi.createPaymentSourceFromIDEALData(idealData)
  }

  data class StatusAndPaymentMethodId(
    val intentId: String,
    val status: StripeIntentStatus,
    val paymentMethod: String?
  )

  companion object {
    private val TAG = Log.tag(StripeRepository::class.java)
  }
}
