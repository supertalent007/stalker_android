package org.stalker.securesms.migrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.database.RecipientTable;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.model.RecipientRecord;
import org.stalker.securesms.jobmanager.Job;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.storage.StorageSyncHelper;
import org.whispersystems.signalservice.api.storage.SignalAccountRecord;
import org.whispersystems.signalservice.api.storage.StorageId;
import org.whispersystems.signalservice.internal.storage.protos.AccountRecord;

import java.io.IOException;

/**
 * Check for unknown fields stored on self and attempt to apply them.
 */
public class ApplyUnknownFieldsToSelfMigrationJob extends MigrationJob {

  private static final String TAG = Log.tag(ApplyUnknownFieldsToSelfMigrationJob.class);

  public static final String KEY = "ApplyUnknownFieldsToSelfMigrationJob";

  ApplyUnknownFieldsToSelfMigrationJob() {
    this(new Parameters.Builder().build());
  }

  private ApplyUnknownFieldsToSelfMigrationJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public boolean isUiBlocking() {
    return false;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void performMigration() {
    if (!SignalStore.account().isRegistered() || SignalStore.account().getAci() == null) {
      Log.w(TAG, "Not registered!");
      return;
    }

    Recipient       self;
    RecipientRecord settings;

    try {
      self     = Recipient.self();
      settings = SignalDatabase.recipients().getRecordForSync(self.getId());
    } catch (RecipientTable.MissingRecipientException e) {
      Log.w(TAG, "Unable to find self");
      return;
    }

    if (settings == null || settings.getSyncExtras().getStorageProto() == null) {
      Log.d(TAG, "No unknowns to apply");
      return;
    }

    try {
      StorageId           storageId           = StorageId.forAccount(self.getStorageId());
      AccountRecord       accountRecord       = AccountRecord.ADAPTER.decode(settings.getSyncExtras().getStorageProto());
      SignalAccountRecord signalAccountRecord = new SignalAccountRecord(storageId, accountRecord);

      Log.d(TAG, "Applying potentially now known unknowns");
      StorageSyncHelper.applyAccountStorageSyncUpdates(context, self, signalAccountRecord, false);
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  @Override
  boolean shouldRetry(@NonNull Exception e) {
    return false;
  }

  public static class Factory implements Job.Factory<ApplyUnknownFieldsToSelfMigrationJob> {
    @Override
    public @NonNull ApplyUnknownFieldsToSelfMigrationJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new ApplyUnknownFieldsToSelfMigrationJob(parameters);
    }
  }
}
