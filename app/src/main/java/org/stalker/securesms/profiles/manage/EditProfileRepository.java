package org.stalker.securesms.profiles.manage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobs.MultiDeviceProfileContentUpdateJob;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.profiles.AvatarHelper;
import org.stalker.securesms.profiles.ProfileName;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.util.ProfileUtil;
import org.whispersystems.signalservice.api.util.StreamDetails;

import java.io.ByteArrayInputStream;
import java.io.IOException;

final class EditProfileRepository {

  private static final String TAG = Log.tag(EditProfileRepository.class);

  public void setName(@NonNull Context context, @NonNull ProfileName profileName, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        ProfileUtil.uploadProfileWithName(context, profileName);
        SignalDatabase.recipients().setProfileName(Recipient.self().getId(), profileName);
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (IOException e) {
        Log.w(TAG, "Failed to upload profile during name change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  public void setAbout(@NonNull Context context, @NonNull String about, @NonNull String emoji, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        ProfileUtil.uploadProfileWithAbout(context, about, emoji);
        SignalDatabase.recipients().setAbout(Recipient.self().getId(), about, emoji);
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (IOException e) {
        Log.w(TAG, "Failed to upload profile during about change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  public void setAvatar(@NonNull Context context, @NonNull byte[] data, @NonNull String contentType, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        ProfileUtil.uploadProfileWithAvatar(new StreamDetails(new ByteArrayInputStream(data), contentType, data.length));
        AvatarHelper.setAvatar(context, Recipient.self().getId(), new ByteArrayInputStream(data));
        SignalStore.misc().setHasEverHadAnAvatar(true);
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (IOException e) {
        Log.w(TAG, "Failed to upload profile during avatar change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  public void clearAvatar(@NonNull Context context, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        ProfileUtil.uploadProfileWithAvatar(null);
        AvatarHelper.delete(context, Recipient.self().getId());
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (IOException e) {
        Log.w(TAG, "Failed to upload profile during name change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  enum Result {
    SUCCESS, FAILURE_NETWORK
  }
}
