package org.stalker.securesms.payments.backup;

import androidx.annotation.NonNull;

import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.payments.Mnemonic;

public final class PaymentsRecoveryRepository {
  public @NonNull Mnemonic getMnemonic() {
    return SignalStore.paymentsValues().getPaymentsMnemonic();
  }
}
