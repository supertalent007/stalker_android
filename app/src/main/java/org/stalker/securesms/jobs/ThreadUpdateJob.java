package org.stalker.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.ThreadUtil;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobmanager.JsonJobData;
import org.stalker.securesms.jobmanager.Job;

/**
 * A job that effectively debounces thread updates through a combination of having a max instance count
 * and sleeping at the end of the job to make sure it takes a minimum amount of time.
 */
public final class ThreadUpdateJob extends BaseJob {

  public static final String KEY = "ThreadUpdateJob";

  private static final String KEY_THREAD_ID = "thread_id";

  private static final long DEBOUNCE_INTERVAL = 500;
  private static final long DEBOUNCE_INTERVAL_WITH_BACKLOG = 3000;

  private final long threadId;

  private ThreadUpdateJob(long threadId) {
    this(new Parameters.Builder()
                       .setQueue("ThreadUpdateJob_" + threadId)
                       .setMaxInstancesForQueue(2)
                       .build(),
         threadId);
  }

  private ThreadUpdateJob(@NonNull Parameters  parameters, long threadId) {
    super(parameters);
    this.threadId = threadId;
  }

  public static void enqueue(long threadId) {
    SignalDatabase.runPostSuccessfulTransaction(KEY + threadId, () -> {
      ApplicationDependencies.getJobManager().add(new ThreadUpdateJob(threadId));
    });
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId).serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    SignalDatabase.threads().update(threadId, true);
    if (!ApplicationDependencies.getIncomingMessageObserver().getDecryptionDrained()) {
      ThreadUtil.sleep(DEBOUNCE_INTERVAL_WITH_BACKLOG);
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return false;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<ThreadUpdateJob> {
    @Override
    public @NonNull ThreadUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new ThreadUpdateJob(parameters, data.getLong(KEY_THREAD_ID));
    }
  }
}
