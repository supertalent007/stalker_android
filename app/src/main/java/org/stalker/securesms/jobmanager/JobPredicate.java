package org.stalker.securesms.jobmanager;

import androidx.annotation.NonNull;

import org.stalker.securesms.jobmanager.persistence.JobSpec;

public interface JobPredicate {
  JobPredicate NONE = jobSpec -> true;

  boolean shouldRun(@NonNull JobSpec jobSpec);
}
