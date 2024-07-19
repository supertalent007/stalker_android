package org.stalker.securesms.components.reminder;

import android.content.Context;

import androidx.annotation.NonNull;

import org.stalker.securesms.R;
import org.stalker.securesms.util.TextSecurePreferences;

public class ServiceOutageReminder extends Reminder {

  public ServiceOutageReminder() {
    super(R.string.reminder_header_service_outage_text);
  }

  public static boolean isEligible(@NonNull Context context) {
    return TextSecurePreferences.getServiceOutage(context);
  }

  @Override
  public boolean isDismissable() {
    return false;
  }

  @NonNull
  @Override
  public Importance getImportance() {
    return Importance.ERROR;
  }
}
