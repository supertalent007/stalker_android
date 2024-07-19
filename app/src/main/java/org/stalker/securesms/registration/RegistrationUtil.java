package org.stalker.securesms.registration;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobs.DirectoryRefreshJob;
import org.stalker.securesms.jobs.RefreshAttributesJob;
import org.stalker.securesms.jobs.StorageSyncJob;
import org.stalker.securesms.keyvalue.PhoneNumberPrivacyValues;
import org.stalker.securesms.keyvalue.PhoneNumberPrivacyValues.PhoneNumberDiscoverabilityMode;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.recipients.Recipient;

public final class RegistrationUtil {

  private static final String TAG = Log.tag(RegistrationUtil.class);

  private RegistrationUtil() {}

  /**
   * There's several events where a registration may or may not be considered complete based on what
   * path a user has taken. This will only truly mark registration as complete if all of the
   * requirements are met.
   */
  public static void maybeMarkRegistrationComplete() {
    if (!SignalStore.registrationValues().isRegistrationComplete() &&
        SignalStore.account().isRegistered()                       &&
        !Recipient.self().getProfileName().isEmpty()               &&
        (SignalStore.svr().hasPin() || SignalStore.svr().hasOptedOut()))
    {
      Log.i(TAG, "Marking registration completed.", new Throwable());
      SignalStore.registrationValues().setRegistrationComplete();

      if (SignalStore.phoneNumberPrivacy().getPhoneNumberDiscoverabilityMode() == PhoneNumberDiscoverabilityMode.UNDECIDED) {
        Log.w(TAG, "Phone number discoverability mode is still UNDECIDED. Setting to DISCOVERABLE.");
        SignalStore.phoneNumberPrivacy().setPhoneNumberDiscoverabilityMode(PhoneNumberDiscoverabilityMode.DISCOVERABLE);
      }

      ApplicationDependencies.getJobManager().startChain(new RefreshAttributesJob())
                                             .then(new StorageSyncJob())
                                             .then(new DirectoryRefreshJob(false))
                                             .enqueue();

    } else if (!SignalStore.registrationValues().isRegistrationComplete()) {
      Log.i(TAG, "Registration is not yet complete.", new Throwable());
    }
  }
}
