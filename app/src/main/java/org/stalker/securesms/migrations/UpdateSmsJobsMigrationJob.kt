package org.stalker.securesms.migrations

import org.signal.core.util.logging.Log
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.JsonJobData
import org.stalker.securesms.jobmanager.persistence.JobSpec
import org.stalker.securesms.keyvalue.SignalStore

/**
 * Updates the data in queued jobs to reflect the new ids SMS messages get assigned during the table merge migration.
 * Normally we'd do this in a JobManager migration, but unfortunately this migration requires that a database migration
 * happened already, but we don't want the database to be accessed until the [DatabaseMigrationJob] is run, otherwise
 * we won't show the progress update.
 *
 * This ends up being more straightforward regardless because by the time this application migration is being run, it must be the
 * case that the database migration is finished (since it's enqueued after the [DatabaseMigrationJob]), so we don't have to
 * do any weird wait-notify stuff to guarantee the offset is set.
 */
internal class UpdateSmsJobsMigrationJob(
  parameters: Parameters = Parameters.Builder().build()
) : MigrationJob(parameters) {

  companion object {
    val TAG = Log.tag(UpdateSmsJobsMigrationJob::class.java)
    const val KEY = "UpdateSmsJobsMigrationJob"
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    val idOffset = SignalStore.plaintext().smsMigrationIdOffset
    check(idOffset >= 0) { "Invalid ID offset of $idOffset -- this shouldn't be possible!" }

    ApplicationDependencies.getJobManager().update { jobSpec ->
      when (jobSpec.factoryKey) {
        "PushTextSendJob" -> jobSpec.updateAndSerialize("message_id", null, idOffset)
        "ReactionSendJob" -> jobSpec.updateAndSerialize("message_id", "is_mms", idOffset)
        "RemoteDeleteSendJob" -> jobSpec.updateAndSerialize("message_id", "is_mms", idOffset)
        "SmsSendJob" -> jobSpec.updateAndSerialize("message_id", null, idOffset)
        "SmsSentJob" -> jobSpec.updateAndSerialize("message_id", null, idOffset)
        else -> jobSpec
      }
    }
  }

  private fun JobSpec.updateAndSerialize(idKey: String, isMmsKey: String?, offset: Long): JobSpec {
    val data = JsonJobData.deserialize(this.serializedData)

    if (isMmsKey != null && data.getBooleanOrDefault(isMmsKey, false)) {
      return this
    }

    return if (data.hasLong(idKey)) {
      val currentValue: Long = data.getLong(idKey)
      val updatedValue: Long = currentValue + offset
      val updatedData: JsonJobData = data.buildUpon().putLong(idKey, updatedValue).build()

      Log.d(TAG, "Updating job with factory ${this.factoryKey} from $currentValue to $updatedValue")
      this.withData(updatedData.serialize())
    } else {
      this
    }
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<UpdateSmsJobsMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): UpdateSmsJobsMigrationJob {
      return UpdateSmsJobsMigrationJob(parameters)
    }
  }
}
