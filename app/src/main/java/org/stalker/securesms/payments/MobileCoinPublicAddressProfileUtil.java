package org.stalker.securesms.payments;

import androidx.annotation.NonNull;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.whispersystems.signalservice.internal.push.PaymentAddress;

import okio.ByteString;

public final class MobileCoinPublicAddressProfileUtil {

  private MobileCoinPublicAddressProfileUtil() {}

  /**
   * Signs the supplied address bytes with the {@link IdentityKeyPair}'s private key and returns a proto that includes it and it's signature.
   */
  public static @NonNull PaymentAddress signPaymentsAddress(@NonNull byte[] publicAddressBytes,
                                                            @NonNull IdentityKeyPair identityKeyPair)
  {
    byte[] signature = identityKeyPair.getPrivateKey().calculateSignature(publicAddressBytes);

    return new PaymentAddress.Builder()
                             .mobileCoinAddress(new PaymentAddress.MobileCoinAddress.Builder()
                                                                                    .address(ByteString.of(publicAddressBytes))
                                                                                    .signature(ByteString.of(signature))
                                                                                    .build())
                             .build();
  }

  /**
   * Verifies that the payments address is signed with the supplied {@link IdentityKey}.
   * <p>
   * Returns the validated bytes if so, otherwise throws.
   */
  public static @NonNull byte[] verifyPaymentsAddress(@NonNull PaymentAddress paymentAddress,
                                                      @NonNull IdentityKey identityKey)
      throws PaymentsAddressException
  {
    if (paymentAddress.mobileCoinAddress == null) {
      throw new PaymentsAddressException(PaymentsAddressException.Code.NO_ADDRESS);
    }

    if (paymentAddress.mobileCoinAddress.address == null || paymentAddress.mobileCoinAddress.signature == null) {
      throw new PaymentsAddressException(PaymentsAddressException.Code.INVALID_ADDRESS_SIGNATURE);
    }

    byte[] bytes     = paymentAddress.mobileCoinAddress.address.toByteArray();
    byte[] signature = paymentAddress.mobileCoinAddress.signature.toByteArray();

    if (signature.length != 64 || !identityKey.getPublicKey().verifySignature(bytes, signature)) {
      throw new PaymentsAddressException(PaymentsAddressException.Code.INVALID_ADDRESS_SIGNATURE);
    }

    return bytes;
  }
}
