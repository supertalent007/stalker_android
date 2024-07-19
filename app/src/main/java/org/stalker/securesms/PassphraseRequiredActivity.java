package org.stalker.securesms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.greenrobot.eventbus.EventBus;
import org.signal.core.util.logging.Log;
import org.signal.core.util.tracing.Tracer;
import org.signal.devicetransfer.TransferStatus;
import org.stalker.securesms.components.settings.app.changenumber.ChangeNumberLockActivity;
import org.stalker.securesms.crypto.MasterSecretUtil;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.devicetransfer.olddevice.OldDeviceTransferActivity;
import org.stalker.securesms.keyvalue.InternalValues;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.lock.v2.CreateSvrPinActivity;
import org.stalker.securesms.migrations.ApplicationMigrationActivity;
import org.stalker.securesms.migrations.ApplicationMigrations;
import org.stalker.securesms.pin.PinRestoreActivity;
import org.stalker.securesms.profiles.edit.CreateProfileActivity;
import org.stalker.securesms.push.SignalServiceNetworkAccess;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.registration.RegistrationNavigationActivity;
import org.stalker.securesms.registration.v2.ui.RegistrationV2Activity;
import org.stalker.securesms.restore.RestoreActivity;
import org.stalker.securesms.service.KeyCachingService;
import org.stalker.securesms.util.AppStartup;
import org.stalker.securesms.util.FeatureFlags;
import org.stalker.securesms.util.TextSecurePreferences;

import java.util.Locale;

public abstract class PassphraseRequiredActivity extends BaseActivity implements MasterSecretListener {
  private static final String TAG = Log.tag(PassphraseRequiredActivity.class);

  public static final String LOCALE_EXTRA      = "locale_extra";
  public static final String NEXT_INTENT_EXTRA = "next_intent";

  private static final int STATE_NORMAL              = 0;
  private static final int STATE_CREATE_PASSPHRASE   = 1;
  private static final int STATE_PROMPT_PASSPHRASE   = 2;
  private static final int STATE_UI_BLOCKING_UPGRADE = 3;
  private static final int STATE_WELCOME_PUSH_SCREEN = 4;
  private static final int STATE_ENTER_SIGNAL_PIN    = 5;
  private static final int STATE_CREATE_PROFILE_NAME = 6;
  private static final int STATE_CREATE_SIGNAL_PIN   = 7;
  private static final int STATE_TRANSFER_ONGOING    = 8;
  private static final int STATE_TRANSFER_LOCKED     = 9;
  private static final int STATE_CHANGE_NUMBER_LOCK  = 10;
  private static final int STATE_RESTORE_BACKUP      = 11;

  private SignalServiceNetworkAccess networkAccess;
  private BroadcastReceiver          clearKeyReceiver;

  @Override
  protected final void onCreate(Bundle savedInstanceState) {
    Tracer.getInstance().start(Log.tag(getClass()) + "#onCreate()");
    AppStartup.getInstance().onCriticalRenderEventStart();
    this.networkAccess = ApplicationDependencies.getSignalServiceNetworkAccess();
    onPreCreate();

    final boolean locked = KeyCachingService.isLocked(this);
    routeApplicationState(locked);

    super.onCreate(savedInstanceState);

    if (!isFinishing()) {
      initializeClearKeyReceiver();
      onCreate(savedInstanceState, true);
    }

    AppStartup.getInstance().onCriticalRenderEventEnd();
    Tracer.getInstance().end(Log.tag(getClass()) + "#onCreate()");
  }

  protected void onPreCreate() {}
  protected void onCreate(Bundle savedInstanceState, boolean ready) {}

  @Override
  protected void onDestroy() {
    super.onDestroy();
    removeClearKeyReceiver(this);
  }

  @Override
  public void onMasterSecretCleared() {
    Log.d(TAG, "onMasterSecretCleared()");
    if (ApplicationDependencies.getAppForegroundObserver().isForegrounded()) routeApplicationState(true);
    else                                                                     finish();
  }

  protected <T extends Fragment> T initFragment(@IdRes int target,
                                                @NonNull T fragment)
  {
    return initFragment(target, fragment, null);
  }

  protected <T extends Fragment> T initFragment(@IdRes int target,
                                                @NonNull T fragment,
                                                @Nullable Locale locale)
  {
    return initFragment(target, fragment, locale, null);
  }

  protected <T extends Fragment> T initFragment(@IdRes int target,
                                                @NonNull T fragment,
                                                @Nullable Locale locale,
                                                @Nullable Bundle extras)
  {
    Bundle args = new Bundle();
    args.putSerializable(LOCALE_EXTRA, locale);

    if (extras != null) {
      args.putAll(extras);
    }

    fragment.setArguments(args);
    getSupportFragmentManager().beginTransaction()
                               .replace(target, fragment)
                               .commitAllowingStateLoss();
    return fragment;
  }

  private void routeApplicationState(boolean locked) {
    final int applicationState = getApplicationState(locked);
    Intent    intent           = getIntentForState(applicationState);
    if (intent != null) {
      Log.d(TAG, "routeApplicationState(), intent: " + intent.getComponent());
      startActivity(intent);
      finish();
    }
  }

  private Intent getIntentForState(int state) {
    Log.d(TAG, "routeApplicationState(), state: " + state);

    switch (state) {
      case STATE_CREATE_PASSPHRASE:   return getCreatePassphraseIntent();
      case STATE_PROMPT_PASSPHRASE:   return getPromptPassphraseIntent();
      case STATE_UI_BLOCKING_UPGRADE: return getUiBlockingUpgradeIntent();
      case STATE_WELCOME_PUSH_SCREEN: return getPushRegistrationIntent();
      case STATE_ENTER_SIGNAL_PIN:    return getEnterSignalPinIntent();
      case STATE_CREATE_SIGNAL_PIN:   return getCreateSignalPinIntent();
      case STATE_CREATE_PROFILE_NAME: return getCreateProfileNameIntent();
      case STATE_TRANSFER_ONGOING:    return getOldDeviceTransferIntent();
      case STATE_TRANSFER_LOCKED:     return getOldDeviceTransferLockedIntent();
      case STATE_CHANGE_NUMBER_LOCK:  return getChangeNumberLockIntent();
      case STATE_RESTORE_BACKUP:      return getRestoreIntent();
      default:                        return null;
    }
  }

  private int getApplicationState(boolean locked) {
    if (!MasterSecretUtil.isPassphraseInitialized(this)) {
      return STATE_CREATE_PASSPHRASE;
    } else if (locked) {
      return STATE_PROMPT_PASSPHRASE;
    } else if (ApplicationMigrations.isUpdate(this) && ApplicationMigrations.isUiBlockingMigrationRunning()) {
      return STATE_UI_BLOCKING_UPGRADE;
    } else if (!TextSecurePreferences.hasPromptedPushRegistration(this)) {
      return STATE_WELCOME_PUSH_SCREEN;
    } else if (SignalStore.internalValues().enterRestoreV2Flow()) {
      return STATE_RESTORE_BACKUP;
    } else if (SignalStore.storageService().needsAccountRestore()) {
      return STATE_ENTER_SIGNAL_PIN;
    } else if (userHasSkippedOrForgottenPin()) {
      return STATE_CREATE_SIGNAL_PIN;
    } else if (userMustSetProfileName()) {
      return STATE_CREATE_PROFILE_NAME;
    } else if (userMustCreateSignalPin()) {
      return STATE_CREATE_SIGNAL_PIN;
    } else if (EventBus.getDefault().getStickyEvent(TransferStatus.class) != null && getClass() != OldDeviceTransferActivity.class) {
      return STATE_TRANSFER_ONGOING;
    } else if (SignalStore.misc().isOldDeviceTransferLocked()) {
      return STATE_TRANSFER_LOCKED;
    } else if (SignalStore.misc().isChangeNumberLocked() && getClass() != ChangeNumberLockActivity.class) {
      return STATE_CHANGE_NUMBER_LOCK;
    } else {
      return STATE_NORMAL;
    }
  }

  private boolean userMustCreateSignalPin() {
    return !SignalStore.registrationValues().isRegistrationComplete() && !SignalStore.svr().hasPin() && !SignalStore.svr().lastPinCreateFailed() && !SignalStore.svr().hasOptedOut();
  }

  private boolean userHasSkippedOrForgottenPin() {
    return !SignalStore.registrationValues().isRegistrationComplete() && !SignalStore.svr().hasPin() && !SignalStore.svr().hasOptedOut() && SignalStore.svr().isPinForgottenOrSkipped();
  }

  private boolean userMustSetProfileName() {
    return !SignalStore.registrationValues().isRegistrationComplete() && Recipient.self().getProfileName().isEmpty();
  }

  private Intent getCreatePassphraseIntent() {
    return getRoutedIntent(PassphraseCreateActivity.class, getIntent());
  }

  private Intent getPromptPassphraseIntent() {
    Intent intent = getRoutedIntent(PassphrasePromptActivity.class, getIntent());
    intent.putExtra(PassphrasePromptActivity.FROM_FOREGROUND, ApplicationDependencies.getAppForegroundObserver().isForegrounded());
    return intent;
  }

  private Intent getUiBlockingUpgradeIntent() {
    return getRoutedIntent(ApplicationMigrationActivity.class,
                           TextSecurePreferences.hasPromptedPushRegistration(this)
                               ? getConversationListIntent()
                               : getPushRegistrationIntent());
  }

  private Intent getPushRegistrationIntent() {
    if (FeatureFlags.registrationV2()) {
      return RegistrationV2Activity.newIntentForNewRegistration(this, getIntent());
    } else {
      return RegistrationNavigationActivity.newIntentForNewRegistration(this, getIntent());
    }
  }

  private Intent getEnterSignalPinIntent() {
    return getRoutedIntent(PinRestoreActivity.class, getIntent());
  }

  private Intent getCreateSignalPinIntent() {

    final Intent intent;
    if (userMustSetProfileName()) {
      intent = getCreateProfileNameIntent();
    } else {
      intent = getIntent();
    }

    return getRoutedIntent(CreateSvrPinActivity.class, intent);
  }

  private Intent getRestoreIntent() {
    Intent intent = RestoreActivity.getIntentForRestore(this);
    return getRoutedIntent(intent, getIntent());
  }

  private Intent getCreateProfileNameIntent() {
    Intent intent = CreateProfileActivity.getIntentForUserProfile(this);
    return getRoutedIntent(intent, getIntent());
  }

  private Intent getOldDeviceTransferIntent() {
    Intent intent = new Intent(this, OldDeviceTransferActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return intent;
  }

  private @Nullable Intent getOldDeviceTransferLockedIntent() {
    if (getClass() == MainActivity.class) {
      return null;
    }
    return MainActivity.clearTop(this);
  }

  private Intent getChangeNumberLockIntent() {
    return ChangeNumberLockActivity.createIntent(this);
  }

  private Intent getRoutedIntent(Intent destination, @Nullable Intent nextIntent) {
    if (nextIntent != null)   destination.putExtra("next_intent", nextIntent);
    return destination;
  }

  private Intent getRoutedIntent(Class<?> destination, @Nullable Intent nextIntent) {
    final Intent intent = new Intent(this, destination);
    if (nextIntent != null)   intent.putExtra("next_intent", nextIntent);
    return intent;
  }

  private Intent getConversationListIntent() {
    // TODO [greyson] Navigation
    return MainActivity.clearTop(this);
  }

  private void initializeClearKeyReceiver() {
    this.clearKeyReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "onReceive() for clear key event. PasswordDisabled: " + TextSecurePreferences.isPasswordDisabled(context) + ", ScreenLock: " + TextSecurePreferences.isScreenLockEnabled(context));
        if (TextSecurePreferences.isScreenLockEnabled(context) || !TextSecurePreferences.isPasswordDisabled(context)) {
          onMasterSecretCleared();
        }
      }
    };

    IntentFilter filter = new IntentFilter(KeyCachingService.CLEAR_KEY_EVENT);
    registerReceiver(clearKeyReceiver, filter, KeyCachingService.KEY_PERMISSION, null);
  }

  private void removeClearKeyReceiver(Context context) {
    if (clearKeyReceiver != null) {
      context.unregisterReceiver(clearKeyReceiver);
      clearKeyReceiver = null;
    }
  }

  /**
   * Puts an extra in {@code intent} so that {@code nextIntent} will be shown after it.
   */
  public static @NonNull Intent chainIntent(@NonNull Intent intent, @NonNull Intent nextIntent) {
    intent.putExtra(NEXT_INTENT_EXTRA, nextIntent);
    return intent;
  }
}
