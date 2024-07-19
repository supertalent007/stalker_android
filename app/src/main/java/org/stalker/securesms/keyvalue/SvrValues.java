package org.stalker.securesms.keyvalue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.StringStringSerializer;
import org.stalker.securesms.util.JsonUtils;
import org.whispersystems.signalservice.api.kbs.MasterKey;
import org.whispersystems.signalservice.api.kbs.PinHashUtil;
import org.whispersystems.signalservice.internal.contacts.entities.TokenResponse;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SvrValues extends SignalStoreValues {

  public static final  String REGISTRATION_LOCK_ENABLED       = "kbs.v2_lock_enabled";
  private static final String MASTER_KEY                      = "kbs.registration_lock_master_key";
  private static final String TOKEN_RESPONSE                  = "kbs.token_response";
  private static final String PIN                             = "kbs.pin";
  private static final String LOCK_LOCAL_PIN_HASH             = "kbs.registration_lock_local_pin_hash";
  private static final String LAST_CREATE_FAILED_TIMESTAMP    = "kbs.last_create_failed_timestamp";
  public static final  String OPTED_OUT                       = "kbs.opted_out";
  private static final String PIN_FORGOTTEN_OR_SKIPPED        = "kbs.pin.forgotten.or.skipped";
  private static final String SVR_AUTH_TOKENS                 = "kbs.kbs_auth_tokens";
  private static final String SVR_LAST_AUTH_REFRESH_TIMESTAMP = "kbs.kbs_auth_tokens.last_refresh_timestamp";

  SvrValues(KeyValueStore store) {
    super(store);
  }

  @Override
  void onFirstEverAppLaunch() {
  }

  @Override
  @NonNull
  List<String> getKeysToIncludeInBackup() {
    return List.of(SVR_AUTH_TOKENS);
  }

  /**
   * Deliberately does not clear the {@link #MASTER_KEY}.
   */
  public void clearRegistrationLockAndPin() {
    getStore().beginWrite()
              .remove(REGISTRATION_LOCK_ENABLED)
              .remove(TOKEN_RESPONSE)
              .remove(LOCK_LOCAL_PIN_HASH)
              .remove(PIN)
              .remove(LAST_CREATE_FAILED_TIMESTAMP)
              .remove(OPTED_OUT)
              .remove(SVR_AUTH_TOKENS)
              .remove(SVR_LAST_AUTH_REFRESH_TIMESTAMP)
              .commit();
  }

  public synchronized void setMasterKey(@NonNull MasterKey masterKey, @NonNull String pin) {
    getStore().beginWrite()
              .putBlob(MASTER_KEY, masterKey.serialize())
              .putString(LOCK_LOCAL_PIN_HASH, PinHashUtil.localPinHash(pin))
              .putString(PIN, pin)
              .putLong(LAST_CREATE_FAILED_TIMESTAMP, -1)
              .putBoolean(OPTED_OUT, false)
              .commit();
  }

  synchronized void setPinIfNotPresent(@NonNull String pin) {
    if (getStore().getString(PIN, null) == null) {
      getStore().beginWrite().putString(PIN, pin).commit();
    }
  }

  public synchronized void setRegistrationLockEnabled(boolean enabled) {
    putBoolean(REGISTRATION_LOCK_ENABLED, enabled);
  }

  /**
   * Whether or not registration lock V2 is enabled.
   */
  public synchronized boolean isRegistrationLockEnabled() {
    return getBoolean(REGISTRATION_LOCK_ENABLED, false);
  }

  public synchronized void onPinCreateFailure() {
    putLong(LAST_CREATE_FAILED_TIMESTAMP, System.currentTimeMillis());
  }

  /**
   * Whether or not the last time the user attempted to create a PIN, it failed.
   */
  public synchronized boolean lastPinCreateFailed() {
    return getLong(LAST_CREATE_FAILED_TIMESTAMP, -1) > 0;
  }

  /**
   * Finds or creates the master key. Therefore this will always return a master key whether backed
   * up or not.
   * <p>
   * If you only want a key when it's backed up, use {@link #getPinBackedMasterKey()}.
   */
  public synchronized @NonNull MasterKey getOrCreateMasterKey() {
    byte[] blob = getStore().getBlob(MASTER_KEY, null);

    if (blob == null) {
      getStore().beginWrite()
                .putBlob(MASTER_KEY, MasterKey.createNew(new SecureRandom()).serialize())
                .commit();
      blob = getBlob(MASTER_KEY, null);
    }

    return new MasterKey(blob);
  }

  /**
   * Returns null if master key is not backed up by a pin.
   */
  public synchronized @Nullable MasterKey getPinBackedMasterKey() {
    if (!isRegistrationLockEnabled()) return null;
    return getMasterKey();
  }

  private synchronized @Nullable MasterKey getMasterKey() {
    byte[] blob = getBlob(MASTER_KEY, null);
    return blob != null ? new MasterKey(blob) : null;
  }

  public @Nullable String getRegistrationLockToken() {
    MasterKey masterKey = getPinBackedMasterKey();
    if (masterKey == null) {
      return null;
    } else {
      return masterKey.deriveRegistrationLock();
    }
  }

  public synchronized @Nullable String getRecoveryPassword() {
    MasterKey masterKey = getMasterKey();
    if (masterKey != null && hasPin()) {
      return masterKey.deriveRegistrationRecoveryPassword();
    } else {
      return null;
    }
  }

  public synchronized @Nullable String getPin() {
    return getString(PIN, null);
  }

  public synchronized @Nullable String getLocalPinHash() {
    return getString(LOCK_LOCAL_PIN_HASH, null);
  }

  public synchronized boolean hasPin() {
    return getLocalPinHash() != null;
  }

  public synchronized boolean isPinForgottenOrSkipped() {
    return getBoolean(PIN_FORGOTTEN_OR_SKIPPED, false);
  }

  public synchronized void setPinForgottenOrSkipped(boolean value) {
    putBoolean(PIN_FORGOTTEN_OR_SKIPPED, value);
  }

  public synchronized void putAuthTokenList(List<String> tokens) {
    putList(SVR_AUTH_TOKENS, tokens, StringStringSerializer.INSTANCE);
    setLastRefreshAuthTimestamp(System.currentTimeMillis());
  }

  public synchronized List<String> getAuthTokenList() {
    return getList(SVR_AUTH_TOKENS, StringStringSerializer.INSTANCE);
  }

  /**
   * Keeps the 10 most recent KBS auth tokens.
   * @param token
   * @return whether the token was added (new) or ignored (already existed)
   */
  public synchronized boolean appendAuthTokenToList(String token) {
    List<String> tokens = getAuthTokenList();
    if (tokens.contains(token)) {
      return false;
    } else {
      final List<String> result = Stream.concat(Stream.of(token), tokens.stream()).limit(10).collect(Collectors.toList());
      putAuthTokenList(result);
      return true;
    }
  }

  public boolean removeAuthTokens(@NonNull List<String> invalid) {
    List<String> tokens = new ArrayList<>(getAuthTokenList());
    if (tokens.removeAll(invalid)) {
      putAuthTokenList(tokens);
      return true;
    }

    return false;
  }

  public synchronized void optOut() {
    getStore().beginWrite()
              .putBoolean(OPTED_OUT, true)
              .remove(TOKEN_RESPONSE)
              .putBlob(MASTER_KEY, MasterKey.createNew(new SecureRandom()).serialize())
              .remove(LOCK_LOCAL_PIN_HASH)
              .remove(PIN)
              .putLong(LAST_CREATE_FAILED_TIMESTAMP, -1)
              .commit();
  }

  public synchronized boolean hasOptedOut() {
    return getBoolean(OPTED_OUT, false);
  }

  public synchronized @Nullable TokenResponse getRegistrationLockTokenResponse() {
    String token = getStore().getString(TOKEN_RESPONSE, null);

    if (token == null) return null;

    try {
      return JsonUtils.fromJson(token, TokenResponse.class);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private void setLastRefreshAuthTimestamp(long timestamp) {
    putLong(SVR_LAST_AUTH_REFRESH_TIMESTAMP, timestamp);
  }

  public long getLastRefreshAuthTimestamp() {
    return getLong(SVR_LAST_AUTH_REFRESH_TIMESTAMP, 0L);
  }
}
