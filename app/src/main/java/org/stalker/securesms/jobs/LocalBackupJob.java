package org.stalker.securesms.jobs;


import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.Stopwatch;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.R;
import org.stalker.securesms.backup.BackupEvent;
import org.stalker.securesms.backup.BackupFileIOError;
import org.stalker.securesms.backup.BackupPassphrase;
import org.stalker.securesms.backup.BackupVerifier;
import org.stalker.securesms.backup.FullBackupExporter;
import org.stalker.securesms.crypto.AttachmentSecretProvider;
import org.stalker.securesms.database.NoExternalStorageException;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobmanager.Job;
import org.stalker.securesms.jobmanager.JobManager;
import org.stalker.securesms.notifications.NotificationChannels;
import org.stalker.securesms.permissions.Permissions;
import org.stalker.securesms.service.GenericForegroundService;
import org.stalker.securesms.service.NotificationController;
import org.stalker.securesms.util.BackupUtil;
import org.stalker.securesms.util.StorageUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LocalBackupJob extends BaseJob {

  public static final String KEY = "LocalBackupJob";

  private static final String TAG = Log.tag(LocalBackupJob.class);

  public static final String QUEUE = "__LOCAL_BACKUP__";

  public static final String TEMP_BACKUP_FILE_PREFIX = ".backup";
  public static final String TEMP_BACKUP_FILE_SUFFIX = ".tmp";

  public static void enqueue(boolean force) {
    JobManager         jobManager = ApplicationDependencies.getJobManager();
    Parameters.Builder parameters = new Parameters.Builder()
                                                  .setQueue(QUEUE)
                                                  .setMaxInstancesForFactory(1)
                                                  .setMaxAttempts(3);
    if (force) {
      jobManager.cancelAllInQueue(QUEUE);
    }

    if (BackupUtil.isUserSelectionRequired(ApplicationDependencies.getApplication())) {
      jobManager.add(new LocalBackupJobApi29(parameters.build()));
    } else {
      jobManager.add(new LocalBackupJob(parameters.build()));
    }
  }

  private LocalBackupJob(@NonNull Job.Parameters parameters) {
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
  public void onRun() throws NoExternalStorageException, IOException {
    Log.i(TAG, "Executing backup job...");

    BackupFileIOError.clearNotification(context);

    if (!Permissions.hasAll(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
      throw new IOException("No external storage permission!");
    }

    ProgressUpdater updater = new ProgressUpdater(context.getString(R.string.LocalBackupJob_verifying_signal_backup));
    try (NotificationController notification = GenericForegroundService.startForegroundTask(context,
                                                                     context.getString(R.string.LocalBackupJob_creating_signal_backup),
                                                                     NotificationChannels.getInstance().BACKUPS,
                                                                     R.drawable.ic_signal_backup))
    {
      updater.setNotification(notification);
      EventBus.getDefault().register(updater);
      notification.setIndeterminateProgress();

      String backupPassword  = BackupPassphrase.get(context);
      File   backupDirectory = StorageUtil.getOrCreateBackupDirectory();
      String timestamp       = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
      String fileName        = String.format("signal-%s.backup", timestamp);
      File   backupFile      = new File(backupDirectory, fileName);

      deleteOldTemporaryBackups(backupDirectory);

      if (backupFile.exists()) {
        throw new IOException("Backup file already exists?");
      }

      if (backupPassword == null) {
        throw new IOException("Backup password is null");
      }

      File tempFile = File.createTempFile(TEMP_BACKUP_FILE_PREFIX, TEMP_BACKUP_FILE_SUFFIX, backupDirectory);

      try {
        Stopwatch   stopwatch     = new Stopwatch("backup-export");
        BackupEvent finishedEvent = FullBackupExporter.export(context,
                                                              AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                                                              SignalDatabase.getBackupDatabase(),
                                                              tempFile,
                                                              backupPassword,
                                                              this::isCanceled);
        stopwatch.split("backup-create");

        boolean valid = BackupVerifier.verifyFile(new FileInputStream(tempFile), backupPassword, finishedEvent.getCount(), this::isCanceled);
        stopwatch.split("backup-verify");
        stopwatch.stop(TAG);

        EventBus.getDefault().post(finishedEvent);

        if (valid) {
          if (!tempFile.renameTo(backupFile)) {
            Log.w(TAG, "Failed to rename temp file");
            throw new IOException("Renaming temporary backup file failed!");
          }
        } else {
          BackupFileIOError.VERIFICATION_FAILED.postNotification(context);
        }
      } catch (FullBackupExporter.BackupCanceledException e) {
        EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, 0, 0));
        Log.w(TAG, "Backup cancelled");
        throw e;
      } catch (IOException e) {
        Log.w(TAG, "Error during backup!", e);
        EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, 0, 0));
        BackupFileIOError.postNotificationForException(context, e);
        throw e;
      } finally {
        if (tempFile.exists()) {
          if (tempFile.delete()) {
            Log.w(TAG, "Backup failed. Deleted temp file");
          } else {
            Log.w(TAG, "Backup failed. Failed to delete temp file " + tempFile);
          }
        }
      }

      BackupUtil.deleteOldBackups();
    } catch (UnableToStartException e) {
      Log.w(TAG, "This should not happen on API < 31");
      throw new AssertionError(e);
    } finally {
      EventBus.getDefault().unregister(updater);
      updater.setNotification(null);
    }
  }

  private static void deleteOldTemporaryBackups(@NonNull File backupDirectory) {
    for (File file : backupDirectory.listFiles()) {
      if (file.isFile()) {
        String name = file.getName();
        if (name.startsWith(TEMP_BACKUP_FILE_PREFIX) && name.endsWith(TEMP_BACKUP_FILE_SUFFIX)) {
          if (file.delete()) {
            Log.w(TAG, "Deleted old temporary backup file");
          } else {
            Log.w(TAG, "Could not delete old temporary backup file");
          }
        }
      }
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return false;
  }

  @Override
  public void onFailure() {
  }

  private static class ProgressUpdater {
    private final String                 verifyProgressTitle;
    private       NotificationController notification;
    private       boolean                verifying = false;

    public ProgressUpdater(String verifyProgressTitle) {
      this.verifyProgressTitle = verifyProgressTitle;
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onEvent(BackupEvent event) {
      if (notification == null) {
        return;
      }

      if (event.getType() == BackupEvent.Type.PROGRESS || event.getType() == BackupEvent.Type.PROGRESS_VERIFYING) {
        if (event.getEstimatedTotalCount() == 0) {
          notification.setIndeterminateProgress();
        } else {
          notification.setProgress(100, (int) event.getCompletionPercentage());
          if (event.getType() == BackupEvent.Type.PROGRESS_VERIFYING && !verifying) {
            notification.replaceTitle(verifyProgressTitle);
            verifying = true;
          }
        }
      }
    }

    public void setNotification(NotificationController notification) {
      this.notification = notification;
    }
  }

  public static class Factory implements Job.Factory<LocalBackupJob> {
    @Override
    public @NonNull LocalBackupJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new LocalBackupJob(parameters);
    }
  }
}
