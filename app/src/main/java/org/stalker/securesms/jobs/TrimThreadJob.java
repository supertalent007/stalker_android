/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.stalker.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.ThreadTable;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobmanager.JsonJobData;
import org.stalker.securesms.jobmanager.Job;
import org.stalker.securesms.keyvalue.KeepMessagesDuration;
import org.stalker.securesms.keyvalue.SignalStore;

public class TrimThreadJob extends BaseJob {

  public static final String KEY = "TrimThreadJob";

  private static final String TAG           = Log.tag(TrimThreadJob.class);
  private static final String QUEUE_PREFIX  = "TrimThreadJob_";
  private static final String KEY_THREAD_ID = "thread_id";

  private final long threadId;

  public static void enqueueAsync(long threadId) {
    if (SignalStore.settings().getKeepMessagesDuration() != KeepMessagesDuration.FOREVER || SignalStore.settings().isTrimByLengthEnabled()) {
      SignalExecutors.BOUNDED.execute(() -> ApplicationDependencies.getJobManager().add(new TrimThreadJob(threadId)));
    }
  }

  private TrimThreadJob(long threadId) {
    this(new Job.Parameters.Builder().setQueue(QUEUE_PREFIX + threadId)
                                     .setMaxInstancesForQueue(2)
                                     .build(),
         threadId);
  }

  private TrimThreadJob(@NonNull Job.Parameters parameters, long threadId) {
    super(parameters);
    this.threadId = threadId;
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
  public void onRun() {
    KeepMessagesDuration keepMessagesDuration = SignalStore.settings().getKeepMessagesDuration();

    int trimLength = SignalStore.settings().isTrimByLengthEnabled() ? SignalStore.settings().getThreadTrimLength()
                                                                    : ThreadTable.NO_TRIM_MESSAGE_COUNT_SET;

    long trimBeforeDate = keepMessagesDuration != KeepMessagesDuration.FOREVER ? System.currentTimeMillis() - keepMessagesDuration.getDuration()
                                                                               : ThreadTable.NO_TRIM_BEFORE_DATE_SET;

    SignalDatabase.threads().trimThread(threadId, trimLength, trimBeforeDate);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Canceling trim attempt: " + threadId);
  }

  public static final class Factory implements Job.Factory<TrimThreadJob> {
    @Override
    public @NonNull TrimThreadJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new TrimThreadJob(parameters, data.getLong(KEY_THREAD_ID));
    }
  }
}
