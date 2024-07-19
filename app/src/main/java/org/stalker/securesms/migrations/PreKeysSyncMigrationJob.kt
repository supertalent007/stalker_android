package org.stalker.securesms.migrations

import org.signal.core.util.logging.Log
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobs.PreKeysSyncJob
import org.stalker.securesms.keyvalue.SignalStore

/**
 * Schedules a prekey sync.
 */
internal class PreKeysSyncMigrationJob(
  parameters: Parameters = Parameters.Builder().build()
) : MigrationJob(parameters) {

  companion object {
    val TAG = Log.tag(PreKeysSyncMigrationJob::class.java)
    const val KEY = "PreKeysSyncIndexMigrationJob"
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    SignalStore.misc().lastFullPrekeyRefreshTime = 0
    PreKeysSyncJob.enqueue()
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<PreKeysSyncMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): PreKeysSyncMigrationJob {
      return PreKeysSyncMigrationJob(parameters)
    }
  }
}
