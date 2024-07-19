package org.stalker.securesms.jobs

import org.signal.core.util.logging.Log
import org.stalker.securesms.attachments.AttachmentId
import org.stalker.securesms.backup.v2.BackupRepository
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.jobs.protos.ArchiveAttachmentJobData
import org.stalker.securesms.keyvalue.SignalStore
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Copies and re-encrypts attachments from the attachment cdn to the archive cdn.
 *
 * Job will fail if the attachment isn't available on the attachment cdn, use [AttachmentUploadJob] to upload first if necessary.
 */
class ArchiveAttachmentJob private constructor(private val attachmentId: AttachmentId, parameters: Parameters) : BaseJob(parameters) {

  companion object {
    private val TAG = Log.tag(ArchiveAttachmentJob::class.java)

    const val KEY = "ArchiveAttachmentJob"

    fun enqueueIfPossible(attachmentId: AttachmentId) {
      if (!SignalStore.backup().backsUpMedia) {
        return
      }

      ApplicationDependencies.getJobManager().add(ArchiveAttachmentJob(attachmentId))
    }
  }

  constructor(attachmentId: AttachmentId) : this(
    attachmentId = attachmentId,
    parameters = Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .build()
  )

  override fun serialize(): ByteArray = ArchiveAttachmentJobData(attachmentId.id).encode()

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    if (!SignalStore.backup().backsUpMedia) {
      Log.w(TAG, "Do not have permission to read/write to archive cdn")
      return
    }

    val attachment = SignalDatabase.attachments.getAttachment(attachmentId)

    if (attachment == null) {
      Log.w(TAG, "Unable to find attachment to archive: $attachmentId")
      return
    }

    BackupRepository.archiveMedia(attachment).successOrThrow()
    ArchiveThumbnailUploadJob.enqueueIfNecessary(attachmentId)

    SignalStore.backup().usedBackupMediaSpace += attachment.size
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return e is IOException && e !is NonSuccessfulResponseCodeException
  }

  override fun onFailure() = Unit

  class Factory : Job.Factory<ArchiveAttachmentJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): ArchiveAttachmentJob {
      val jobData = ArchiveAttachmentJobData.ADAPTER.decode(serializedData!!)
      return ArchiveAttachmentJob(AttachmentId(jobData.attachmentId), parameters)
    }
  }
}
