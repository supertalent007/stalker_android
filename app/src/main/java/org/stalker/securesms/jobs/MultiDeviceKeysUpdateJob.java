package org.stalker.securesms.jobs;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.crypto.UnidentifiedAccessUtil;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobmanager.Job;
import org.stalker.securesms.jobmanager.impl.NetworkConstraint;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.net.NotPushRegisteredException;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.multidevice.KeysMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.api.storage.StorageKey;

import java.io.IOException;
import java.util.Optional;

public class MultiDeviceKeysUpdateJob extends BaseJob {

  public static final String KEY = "MultiDeviceKeysUpdateJob";

  private static final String TAG = Log.tag(MultiDeviceKeysUpdateJob.class);

  public MultiDeviceKeysUpdateJob() {
    this(new Parameters.Builder()
                           .setQueue("MultiDeviceKeysUpdateJob")
                           .setMaxInstancesForFactory(2)
                           .addConstraint(NetworkConstraint.KEY)
                           .setMaxAttempts(10)
                           .build());

  }

  private MultiDeviceKeysUpdateJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public @Nullable byte[] serialize() {
    return null;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, UntrustedIdentityException {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.i(TAG, "Not multi device, aborting...");
      return;
    }

    if (SignalStore.account().isLinkedDevice()) {
      Log.i(TAG, "Not primary device, aborting...");
      return;
    }

    SignalServiceMessageSender messageSender     = ApplicationDependencies.getSignalServiceMessageSender();
    StorageKey                 storageServiceKey = SignalStore.storageService().getOrCreateStorageKey();

    messageSender.sendSyncMessage(SignalServiceSyncMessage.forKeys(new KeysMessage(Optional.ofNullable(storageServiceKey), Optional.of(SignalStore.svr().getOrCreateMasterKey()))),
                                  UnidentifiedAccessUtil.getAccessForSync(context));
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<MultiDeviceKeysUpdateJob> {
    @Override
    public @NonNull MultiDeviceKeysUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new MultiDeviceKeysUpdateJob(parameters);
    }
  }
}
