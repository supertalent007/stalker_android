package org.stalker.securesms.keyvalue;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

public final class RegistrationValues extends SignalStoreValues {

  private static final String REGISTRATION_COMPLETE = "registration.complete";
  private static final String PIN_REQUIRED          = "registration.pin_required";
  private static final String HAS_UPLOADED_PROFILE  = "registration.has_uploaded_profile";
  private static final String SESSION_E164          = "registration.session_e164";
  private static final String SESSION_ID            = "registration.session_id";

  RegistrationValues(@NonNull KeyValueStore store) {
    super(store);
  }

  public synchronized void onFirstEverAppLaunch() {
    getStore().beginWrite()
              .putBoolean(HAS_UPLOADED_PROFILE, false)
              .putBoolean(REGISTRATION_COMPLETE, false)
              .putBoolean(PIN_REQUIRED, true)
              .commit();
  }

  @Override
  @NonNull List<String> getKeysToIncludeInBackup() {
    return Collections.emptyList();
  }

  public synchronized void clearRegistrationComplete() {
    onFirstEverAppLaunch();
  }

  public synchronized void setRegistrationComplete() {
    getStore().beginWrite()
              .putBoolean(REGISTRATION_COMPLETE, true)
              .commit();
  }

  @CheckResult
  public synchronized boolean pinWasRequiredAtRegistration() {
    return getStore().getBoolean(PIN_REQUIRED, false);
  }

  @CheckResult
  public synchronized boolean isRegistrationComplete() {
    return getStore().getBoolean(REGISTRATION_COMPLETE, true);
  }

  public boolean hasUploadedProfile() {
    return getBoolean(HAS_UPLOADED_PROFILE, true);
  }

  public void markHasUploadedProfile() {
    putBoolean(HAS_UPLOADED_PROFILE, true);
  }

  public void clearHasUploadedProfile() {
    putBoolean(HAS_UPLOADED_PROFILE, false);
  }

  public void setSessionId(String sessionId) {
    putString(SESSION_ID, sessionId);
  }

  @Nullable
  public String getSessionId() {
    return getString(SESSION_ID, null);
  }

  public void setSessionE164(String sessionE164) {
    putString(SESSION_E164, sessionE164);
  }

  @Nullable
  public String getSessionE164() {
    return getString(SESSION_E164, null);
  }
}
