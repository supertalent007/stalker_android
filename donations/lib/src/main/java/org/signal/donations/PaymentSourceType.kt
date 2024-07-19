package org.signal.donations

sealed class PaymentSourceType {
  abstract val code: String
  open val isBankTransfer: Boolean = false

  object Unknown : PaymentSourceType() {
    override val code: String = Codes.UNKNOWN.code
  }

  object PayPal : PaymentSourceType() {
    override val code: String = Codes.PAY_PAL.code
  }

  sealed class Stripe(
    override val code: String,
    val paymentMethod: String,
    override val isBankTransfer: Boolean
  ) : PaymentSourceType() {
    /**
     * Credit card should happen instantaneously but can take up to 1 day to process.
     */
    object CreditCard : Stripe(Codes.CREDIT_CARD.code, "CARD", false)

    /**
     * Google Pay should happen instantaneously but can take up to 1 day to process.
     */
    object GooglePay : Stripe(Codes.GOOGLE_PAY.code, "CARD", false)

    /**
     * SEPA Debits can take up to 14 bank days to process.
     */
    object SEPADebit : Stripe(Codes.SEPA_DEBIT.code, "SEPA_DEBIT", true)

    /**
     * iDEAL Bank transfers happen instantaneously for 1:1 transactions, but do not do so for subscriptions, as Stripe
     * will utilize SEPA under the hood.
     */
    object IDEAL : Stripe(Codes.IDEAL.code, "IDEAL", true)

    fun hasDeclineCodeSupport(): Boolean = !this.isBankTransfer
    fun hasFailureCodeSupport(): Boolean = this.isBankTransfer
  }

  private enum class Codes(val code: String) {
    UNKNOWN("unknown"),
    PAY_PAL("paypal"),
    CREDIT_CARD("credit_card"),
    GOOGLE_PAY("google_pay"),
    SEPA_DEBIT("sepa_debit"),
    IDEAL("ideal")
  }

  companion object {
    fun fromCode(code: String?): PaymentSourceType {
      return when (Codes.values().firstOrNull { it.code == code } ?: Codes.UNKNOWN) {
        Codes.UNKNOWN -> Unknown
        Codes.PAY_PAL -> PayPal
        Codes.CREDIT_CARD -> Stripe.CreditCard
        Codes.GOOGLE_PAY -> Stripe.GooglePay
        Codes.SEPA_DEBIT -> Stripe.SEPADebit
        Codes.IDEAL -> Stripe.IDEAL
      }
    }
  }
}
