package org.stalker.securesms.components.reminder;

import android.content.Context;

import androidx.annotation.NonNull;

import org.stalker.securesms.R;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.registration.RegistrationNavigationActivity;
import org.stalker.securesms.util.TextSecurePreferences;

public class UnauthorizedReminder extends Reminder {

  public UnauthorizedReminder() {
    super(R.string.UnauthorizedReminder_this_is_likely_because_you_registered_your_phone_number_with_Signal_on_a_different_device);
    addAction(new Action(R.string.UnauthorizedReminder_reregister_action, R.id.reminder_action_re_register));
  }

  @Override
  public boolean isDismissable() {
    return false;
  }

  @Override
  public @NonNull Importance getImportance() {
    return Importance.ERROR;
  }

  public static boolean isEligible(Context context) {
    return TextSecurePreferences.isUnauthorizedReceived(context) || !SignalStore.account().isRegistered();
  }
}
