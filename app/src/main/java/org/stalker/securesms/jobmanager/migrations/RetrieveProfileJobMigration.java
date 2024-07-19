package org.stalker.securesms.jobmanager.migrations;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.jobmanager.JsonJobData;
import org.stalker.securesms.jobmanager.JobMigration;

public class RetrieveProfileJobMigration extends JobMigration {

  private static final String TAG = Log.tag(RetrieveProfileJobMigration.class);

  public RetrieveProfileJobMigration() {
    super(7);
  }

  @Override
  public @NonNull JobData migrate(@NonNull JobData jobData) {
    Log.i(TAG, "Running.");

    if ("RetrieveProfileJob".equals(jobData.getFactoryKey())) {
      return migrateRetrieveProfileJob(jobData);
    }
    return jobData;
  }

  private static @NonNull JobData migrateRetrieveProfileJob(@NonNull JobData jobData) {
    JsonJobData data = JsonJobData.deserialize(jobData.getData());

    if (data.hasString("recipient")) {
      Log.i(TAG, "Migrating job.");

      String recipient = data.getString("recipient");
      return jobData.withData(new JsonJobData.Builder()
                                      .putStringArray("recipients", new String[] { recipient })
                                      .serialize());
    } else {
      return jobData;
    }
  }

}
