/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.restore

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.stalker.securesms.AppInitialization
import org.stalker.securesms.backup.BackupPassphrase
import org.stalker.securesms.backup.FullBackupImporter
import org.stalker.securesms.crypto.AttachmentSecretProvider
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.jobmanager.impl.DataRestoreConstraint
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.notifications.NotificationChannels
import org.stalker.securesms.service.LocalBackupListener
import org.stalker.securesms.util.BackupUtil
import java.io.IOException

/**
 * Repository to handle restoring a backup of a user's message history.
 */
object RestoreRepository {
  private val TAG = Log.tag(RestoreRepository.javaClass)

  suspend fun getLocalBackupFromUri(context: Context, uri: Uri): BackupUtil.BackupInfo? = withContext(Dispatchers.IO) {
    BackupUtil.getBackupInfoFromSingleUri(context, uri)
  }

  suspend fun restoreBackupAsynchronously(context: Context, backupFileUri: Uri, passphrase: String): BackupImportResult = withContext(Dispatchers.IO) {
    // TODO [regv2]: migrate this to a service
    try {
      Log.i(TAG, "Starting backup restore.")
      DataRestoreConstraint.isRestoringData = true

      val database = SignalDatabase.backupDatabase

      BackupPassphrase.set(context, passphrase)

      if (!FullBackupImporter.validatePassphrase(context, backupFileUri, passphrase)) {
        // TODO [regv2]: implement a specific, user-visible error for wrong passphrase.
        return@withContext BackupImportResult.FAILURE_UNKNOWN
      }

      FullBackupImporter.importFile(
        context,
        AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
        database,
        backupFileUri,
        passphrase
      )

      SignalDatabase.runPostBackupRestoreTasks(database)
      NotificationChannels.getInstance().restoreContactNotificationChannels()

      if (BackupUtil.canUserAccessBackupDirectory(context)) {
        LocalBackupListener.setNextBackupTimeToIntervalFromNow(context)
        SignalStore.settings().isBackupEnabled = true
        LocalBackupListener.schedule(context)
      }

      AppInitialization.onPostBackupRestore(context)

      Log.i(TAG, "Backup restore complete.")
      return@withContext BackupImportResult.SUCCESS
    } catch (e: FullBackupImporter.DatabaseDowngradeException) {
      Log.w(TAG, "Failed due to the backup being from a newer version of Signal.", e)
      return@withContext BackupImportResult.FAILURE_VERSION_DOWNGRADE
    } catch (e: FullBackupImporter.ForeignKeyViolationException) {
      Log.w(TAG, "Failed due to foreign key constraint violations.", e)
      return@withContext BackupImportResult.FAILURE_FOREIGN_KEY
    } catch (e: IOException) {
      Log.w(TAG, e)
      return@withContext BackupImportResult.FAILURE_UNKNOWN
    } finally {
      DataRestoreConstraint.isRestoringData = false
    }
  }

  enum class BackupImportResult {
    SUCCESS,
    FAILURE_VERSION_DOWNGRADE,
    FAILURE_FOREIGN_KEY,
    FAILURE_UNKNOWN
  }
}
