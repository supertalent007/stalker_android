package org.stalker.securesms.migrations

import org.signal.core.util.logging.Log
import org.stalker.securesms.database.IdentityTable
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobs.AccountConsistencyWorkerJob
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.recipients.Recipient

/**
 * Migration to help cleanup some inconsistent state for ourself in the identity table.
 */
internal class IdentityTableCleanupMigrationJob(
  parameters: Parameters = Parameters.Builder().build()
) : MigrationJob(parameters) {

  companion object {
    const val KEY = "IdentityTableCleanupMigrationJob"

    val TAG = Log.tag(IdentityTableCleanupMigrationJob::class.java)
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    if (SignalStore.account().aci == null || SignalStore.account().pni == null) {
      Log.i(TAG, "ACI/PNI are unset, skipping.")
      return
    }

    if (!SignalStore.account().hasAciIdentityKey()) {
      Log.i(TAG, "No ACI identity set yet, skipping.")
      return
    }

    if (!SignalStore.account().hasPniIdentityKey()) {
      Log.i(TAG, "No PNI identity set yet, skipping.")
      return
    }

    ApplicationDependencies.getProtocolStore().aci().identities().saveIdentityWithoutSideEffects(
      Recipient.self().id,
      SignalStore.account().aci!!,
      SignalStore.account().aciIdentityKey.publicKey,
      IdentityTable.VerifiedStatus.VERIFIED,
      true,
      System.currentTimeMillis(),
      true
    )

    ApplicationDependencies.getProtocolStore().pni().identities().saveIdentityWithoutSideEffects(
      Recipient.self().id,
      SignalStore.account().pni!!,
      SignalStore.account().pniIdentityKey.publicKey,
      IdentityTable.VerifiedStatus.VERIFIED,
      true,
      System.currentTimeMillis(),
      true
    )

    ApplicationDependencies.getJobManager().add(AccountConsistencyWorkerJob())
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<IdentityTableCleanupMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): IdentityTableCleanupMigrationJob {
      return IdentityTableCleanupMigrationJob(parameters)
    }
  }
}
