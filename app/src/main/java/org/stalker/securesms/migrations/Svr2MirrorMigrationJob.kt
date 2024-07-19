package org.stalker.securesms.migrations

import org.signal.core.util.logging.Log
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobs.Svr2MirrorJob

/**
 * Mirrors the user's SVR1 data to SVR2.
 */
internal class Svr2MirrorMigrationJob(
  parameters: Parameters = Parameters.Builder().build()
) : MigrationJob(parameters) {

  companion object {
    val TAG = Log.tag(Svr2MirrorMigrationJob::class.java)
    const val KEY = "Svr2MirrorMigrationJob"
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    ApplicationDependencies.getJobManager().add(Svr2MirrorJob())
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<Svr2MirrorMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): Svr2MirrorMigrationJob {
      return Svr2MirrorMigrationJob(parameters)
    }
  }
}
