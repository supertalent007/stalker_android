package org.stalker.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.jobmanager.Job;
import org.stalker.securesms.jobmanager.JsonJobData;
import org.stalker.securesms.jobmanager.impl.DecryptionsDrainedConstraint;
import org.stalker.securesms.jobmanager.impl.NetworkConstraint;
import org.stalker.securesms.messages.GroupSendUtil;
import org.stalker.securesms.net.NotPushRegisteredException;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.recipients.RecipientId;
import org.stalker.securesms.recipients.RecipientUtil;
import org.stalker.securesms.transport.RetryLaterException;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ProfileKeySendJob extends BaseJob {

  private static final String TAG            = Log.tag(ProfileKeySendJob.class);
  private static final String KEY_RECIPIENTS = "recipients";
  private static final String KEY_THREAD     = "thread";

  public static final String KEY = "ProfileKeySendJob";

  private final long              threadId;
  private final List<RecipientId> recipients;

  public static ProfileKeySendJob createForCallLinks(List<RecipientId> recipientIds) {
    return new ProfileKeySendJob(
        new Parameters.Builder()
            .setQueue("ProfileKeySendJob__call_links")
            .setMaxInstancesForQueue(Parameters.UNLIMITED)
            .addConstraint(NetworkConstraint.KEY)
            .addConstraint(DecryptionsDrainedConstraint.KEY)
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(Parameters.UNLIMITED)
            .build(),
        -1L,
        recipientIds
    );
  }

  /**
   * Suitable for a 1:1 conversation or a GV1 group only.
   *
   * @param queueLimits True if you only want one of these to be run per person after decryptions
   *                    are drained, otherwise false.
   *
   * @return The job that is created, or null if the threadId provided was invalid.
   */
  @WorkerThread
  public static @Nullable ProfileKeySendJob create(long threadId, boolean queueLimits) {
    Recipient conversationRecipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

    if (conversationRecipient == null) {
      Log.w(TAG, "Thread no longer valid! Aborting.");
      return null;
    }

    if (conversationRecipient.isPushV2Group()) {
      throw new AssertionError("Do not send profile keys directly for GV2");
    }

    List<RecipientId> recipients = conversationRecipient.isGroup() ? Stream.of(RecipientUtil.getEligibleForSending(Recipient.resolvedList(conversationRecipient.getParticipantIds())))
                                                                           .map(Recipient::getId)
                                                                           .toList()
                                                                   : Stream.of(conversationRecipient.getId())
                                                                           .toList();

    recipients.remove(Recipient.self().getId());

    if (queueLimits) {
      return new ProfileKeySendJob(new Parameters.Builder()
                                                 .setQueue("ProfileKeySendJob_" + conversationRecipient.getId().toQueueKey())
                                                 .setMaxInstancesForQueue(1)
                                                 .addConstraint(NetworkConstraint.KEY)
                                                 .addConstraint(DecryptionsDrainedConstraint.KEY)
                                                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                                                 .setMaxAttempts(Parameters.UNLIMITED)
                                                 .build(), threadId, recipients);
    } else {
      return new ProfileKeySendJob(new Parameters.Builder()
                                                 .setQueue(conversationRecipient.getId().toQueueKey())
                                                 .addConstraint(NetworkConstraint.KEY)
                                                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                                                 .setMaxAttempts(Parameters.UNLIMITED)
                                                 .build(), threadId, recipients);
    }
  }

  private ProfileKeySendJob(@NonNull Parameters parameters, long threadId, @NonNull List<RecipientId> recipients) {
    super(parameters);
    this.threadId   = threadId;
    this.recipients = recipients;
  }

  @Override
  protected void onRun() throws Exception {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (threadId > 0) {
      Recipient conversationRecipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

      if (conversationRecipient == null) {
        Log.w(TAG, "Thread no longer present");
        return;
      }
    }

    List<Recipient> destinations = Stream.of(recipients).map(Recipient::resolved).toList();
    List<Recipient> completions  = deliver(destinations);

    for (Recipient completion : completions) {
      recipients.remove(completion.getId());
    }

    Log.i(TAG, "Completed now: " + completions.size() + ", Remaining: " + recipients.size());

    if (!recipients.isEmpty()) {
      Log.w(TAG, "Still need to send to " + recipients.size() + " recipients. Retrying.");
      throw new RetryLaterException();
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    if (e instanceof NotPushRegisteredException) return false;
    return e instanceof IOException ||
           e instanceof RetryLaterException;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder()
                   .putLong(KEY_THREAD, threadId)
                   .putString(KEY_RECIPIENTS, RecipientId.toSerializedList(recipients))
                   .serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onFailure() {

  }

  private List<Recipient> deliver(@NonNull List<Recipient> destinations) throws IOException, UntrustedIdentityException {
    SignalServiceDataMessage.Builder dataMessage = SignalServiceDataMessage.newBuilder()
                                                                           .asProfileKeyUpdate(true)
                                                                           .withTimestamp(System.currentTimeMillis())
                                                                           .withProfileKey(Recipient.self().resolve().getProfileKey());

    List<SendMessageResult> results = GroupSendUtil.sendUnresendableDataMessage(context, null, destinations, false, ContentHint.IMPLICIT, dataMessage.build(), false);

    GroupSendJobHelper.SendResult groupResult = GroupSendJobHelper.getCompletedSends(destinations, results);

    for (RecipientId unregistered : groupResult.unregistered) {
      SignalDatabase.recipients().markUnregistered(unregistered);
    }

    return groupResult.completed;
  }

  public static class Factory implements Job.Factory<ProfileKeySendJob> {

    @Override
    public @NonNull ProfileKeySendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);

      long              threadId   = data.getLong(KEY_THREAD);
      List<RecipientId> recipients = RecipientId.fromSerializedList(data.getString(KEY_RECIPIENTS));

      return new ProfileKeySendJob(parameters, threadId, recipients);
    }
  }
}
