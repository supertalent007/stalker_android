package org.stalker.securesms.jobs;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.stalker.securesms.crypto.ProfileKeyUtil;
import org.stalker.securesms.crypto.UnidentifiedAccessUtil;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobmanager.Job;
import org.stalker.securesms.jobmanager.impl.NetworkConstraint;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.net.NotPushRegisteredException;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.recipients.RecipientUtil;
import org.stalker.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;
import org.whispersystems.signalservice.api.messages.multidevice.ContactsMessage;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContact;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsOutputStream;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MultiDeviceProfileKeyUpdateJob extends BaseJob {

  public static String KEY = "MultiDeviceProfileKeyUpdateJob";

  private static final String TAG = Log.tag(MultiDeviceProfileKeyUpdateJob.class);

  public MultiDeviceProfileKeyUpdateJob() {
    this(new Job.Parameters.Builder()
                           .addConstraint(NetworkConstraint.KEY)
                           .setQueue("MultiDeviceProfileKeyUpdateJob")
                           .setLifespan(TimeUnit.DAYS.toMillis(1))
                           .setMaxAttempts(Parameters.UNLIMITED)
                           .build());
  }

  private MultiDeviceProfileKeyUpdateJob(@NonNull Job.Parameters parameters) {
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
      Log.i(TAG, "Not multi device...");
      return;
    }

    Optional<ProfileKey>  profileKey = Optional.of(ProfileKeyUtil.getSelfProfileKey());
    ByteArrayOutputStream baos       = new ByteArrayOutputStream();
    DeviceContactsOutputStream out        = new DeviceContactsOutputStream(baos);

    out.write(new DeviceContact(Optional.ofNullable(SignalStore.account().getAci()),
                                Optional.ofNullable(SignalStore.account().getE164()),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                profileKey,
                                Optional.empty(),
                                Optional.empty(),
                                false));

    out.close();

    SignalServiceMessageSender    messageSender    = ApplicationDependencies.getSignalServiceMessageSender();
    SignalServiceAttachmentStream attachmentStream = SignalServiceAttachment.newStreamBuilder()
                                                                            .withStream(new ByteArrayInputStream(baos.toByteArray()))
                                                                            .withContentType("application/octet-stream")
                                                                            .withLength(baos.toByteArray().length)
                                                                            .build();

    SignalServiceSyncMessage      syncMessage      = SignalServiceSyncMessage.forContacts(new ContactsMessage(attachmentStream, false));

    messageSender.sendSyncMessage(syncMessage, UnidentifiedAccessUtil.getAccessForSync(context));
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    if (exception instanceof ServerRejectedException) return false;
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Profile key sync failed!");
  }

  public static final class Factory implements Job.Factory<MultiDeviceProfileKeyUpdateJob> {
    @Override
    public @NonNull MultiDeviceProfileKeyUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new MultiDeviceProfileKeyUpdateJob(parameters);
    }
  }
}
