package org.stalker.securesms.components.settings.app.subscription.donate.gateway

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.signal.donations.PaymentSourceType

@Parcelize
data class GatewayResponse(val gateway: Gateway, val request: GatewayRequest) : Parcelable {
  enum class Gateway {
    GOOGLE_PAY,
    PAYPAL,
    CREDIT_CARD,
    SEPA_DEBIT,
    IDEAL;

    fun toPaymentSourceType(): PaymentSourceType {
      return when (this) {
        GOOGLE_PAY -> PaymentSourceType.Stripe.GooglePay
        PAYPAL -> PaymentSourceType.PayPal
        CREDIT_CARD -> PaymentSourceType.Stripe.CreditCard
        SEPA_DEBIT -> PaymentSourceType.Stripe.SEPADebit
        IDEAL -> PaymentSourceType.Stripe.IDEAL
      }
    }
  }
}
