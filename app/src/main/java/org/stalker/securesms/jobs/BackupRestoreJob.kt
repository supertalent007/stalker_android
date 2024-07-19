/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.jobs

import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.stalker.securesms.R
import org.stalker.securesms.backup.RestoreState
import org.stalker.securesms.backup.v2.BackupRepository
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.net.NotPushRegisteredException
import org.stalker.securesms.providers.BlobProvider
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.service.BackupProgressService
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment.ProgressListener
import java.io.IOException

/**
 * Job that is responsible for restoring a backup from the server
 */
class BackupRestoreJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    private val TAG = Log.tag(BackupRestoreJob::class.java)

    const val KEY = "BackupRestoreJob"
  }

  constructor() : this(
    Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .setMaxAttempts(Parameters.UNLIMITED)
      .setMaxInstancesForFactory(1)
      .build()
  )

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  override fun onFailure() = Unit

  override fun onAdded() {
    SignalStore.backup().restoreState = RestoreState.PENDING
  }

  override fun onRun() {
    if (!SignalStore.account().isRegistered) {
      Log.e(TAG, "Not registered, cannot restore!")
      throw NotPushRegisteredException()
    }

    BackupProgressService.start(context, context.getString(R.string.BackupProgressService_title)).use {
      restore(it)
    }
  }

  private fun restore(controller: BackupProgressService.Controller) {
    SignalStore.backup().restoreState = RestoreState.RESTORING_DB

    val progressListener = object : ProgressListener {
      override fun onAttachmentProgress(total: Long, progress: Long) {
        controller.update(
          title = context.getString(R.string.BackupProgressService_title_downloading),
          progress = progress.toFloat() / total.toFloat(),
          indeterminate = false
        )
      }

      override fun shouldCancel() = isCanceled
    }

    val tempBackupFile = BlobProvider.getInstance().forNonAutoEncryptingSingleSessionOnDisk(ApplicationDependencies.getApplication())
    if (!BackupRepository.downloadBackupFile(tempBackupFile, progressListener)) {
      Log.e(TAG, "Failed to download backup file")
      throw IOException()
    }

    controller.update(
      title = context.getString(R.string.BackupProgressService_title),
      progress = 0f,
      indeterminate = true
    )

    val self = Recipient.self()
    val selfData = BackupRepository.SelfData(self.aci.get(), self.pni.get(), self.e164.get(), ProfileKey(self.profileKey))
    BackupRepository.import(length = tempBackupFile.length(), inputStreamFactory = tempBackupFile::inputStream, selfData = selfData, plaintext = false)

    SignalStore.backup().restoreState = RestoreState.RESTORING_MEDIA
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<BackupRestoreJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): BackupRestoreJob {
      return BackupRestoreJob(parameters)
    }
  }
}
