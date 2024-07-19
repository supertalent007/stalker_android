package org.stalker.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.crypto.UnidentifiedAccessUtil;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobmanager.Job;
import org.stalker.securesms.jobmanager.impl.NetworkConstraint;
import org.stalker.securesms.net.NotPushRegisteredException;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;

public class MultiDeviceProfileContentUpdateJob extends BaseJob {

  public static final String KEY = "MultiDeviceProfileContentUpdateJob";

  private static final String TAG = Log.tag(MultiDeviceProfileContentUpdateJob.class);

  public MultiDeviceProfileContentUpdateJob() {
    this(new Parameters.Builder()
                       .setQueue("MultiDeviceProfileUpdateJob")
                       .setMaxInstancesForFactory(2)
                       .addConstraint(NetworkConstraint.KEY)
                       .setMaxAttempts(10)
                       .build());
  }

  private MultiDeviceProfileContentUpdateJob(@NonNull Parameters parameters) {
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
  protected void onRun() throws Exception {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.i(TAG, "Not multi device, aborting...");
      return;
    }

    SignalServiceMessageSender messageSender = ApplicationDependencies.getSignalServiceMessageSender();

    messageSender.sendSyncMessage(SignalServiceSyncMessage.forFetchLatest(SignalServiceSyncMessage.FetchType.LOCAL_PROFILE),
                                  UnidentifiedAccessUtil.getAccessForSync(context));
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Did not succeed!");
  }

  public static final class Factory implements Job.Factory<MultiDeviceProfileContentUpdateJob> {
    @Override
    public @NonNull MultiDeviceProfileContentUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new MultiDeviceProfileContentUpdateJob(parameters);
    }
  }
}
