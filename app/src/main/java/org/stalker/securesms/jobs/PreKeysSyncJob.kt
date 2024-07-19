package org.stalker.securesms.jobs

import androidx.annotation.VisibleForTesting
import org.signal.core.util.logging.Log
import org.signal.core.util.roundedString
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.stalker.securesms.crypto.PreKeyUtil
import org.stalker.securesms.crypto.storage.PreKeyMetadataStore
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.jobs.protos.PreKeysSyncJobData
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.util.FeatureFlags
import org.whispersystems.signalservice.api.NetworkResult
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.ServiceIdType
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import org.whispersystems.signalservice.internal.push.OneTimePreKeyCounts
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.jvm.Throws
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Regardless of the current state of affairs with respect to prekeys for either ACI or PNI identities, will
 * attempt to make the state valid.
 *
 * It will rotate/create signed prekeys for both ACI and PNI identities, as well as ensure that the user
 * has a sufficient number of one-time EC prekeys available on the service.
 *
 * It will also rotate/create last-resort kyber prekeys for both ACI and PNI identities, as well as ensure
 * that the user has a sufficient number of one-time kyber prekeys available on the service.
 */
class PreKeysSyncJob private constructor(
  parameters: Parameters,
  private val forceRotationRequested: Boolean
) : BaseJob(parameters) {

  companion object {
    const val KEY = "PreKeysSyncJob"

    private val TAG = Log.tag(PreKeysSyncJob::class.java)

    /** The minimum number of one-time prekeys we want to the service to have. If we have less than this, refill. Applies to both EC and kyber prekeys. */
    private const val ONE_TIME_PREKEY_MINIMUM = 10

    /** How often we want to rotate signed prekeys and last-resort kyber prekeys. */
    @JvmField
    val REFRESH_INTERVAL = 2.days.inWholeMilliseconds

    /** If signed prekeys or last-resort kyber keys are older than this, we will require rotation before sending messages. */
    @JvmField
    val MAXIMUM_ALLOWED_SIGNED_PREKEY_AGE = 14.days.inWholeMilliseconds

    /**
     * @param forceRotationRequested If true, this will force the rotation of all keys, provided we haven't already done a forced refresh recently.
     */
    @JvmOverloads
    @JvmStatic
    fun create(forceRotationRequested: Boolean = false): PreKeysSyncJob {
      return PreKeysSyncJob(forceRotationRequested)
    }

    @JvmStatic
    fun enqueue() {
      ApplicationDependencies.getJobManager().add(create())
    }

    @JvmStatic
    fun enqueueIfNeeded() {
      if (!SignalStore.account().aciPreKeys.isSignedPreKeyRegistered || !SignalStore.account().pniPreKeys.isSignedPreKeyRegistered) {
        Log.i(TAG, "Some signed/last-resort prekeys aren't registered yet. Enqueuing a job. ACI: ${SignalStore.account().aciPreKeys.isSignedPreKeyRegistered} PNI: ${SignalStore.account().pniPreKeys.isSignedPreKeyRegistered}")
        ApplicationDependencies.getJobManager().add(PreKeysSyncJob())
      } else if (SignalStore.account().aciPreKeys.activeSignedPreKeyId < 0 || SignalStore.account().pniPreKeys.activeSignedPreKeyId < 0) {
        Log.i(TAG, "Some signed prekeys aren't active yet. Enqueuing a job. ACI: ${SignalStore.account().aciPreKeys.activeSignedPreKeyId >= 0} PNI: ${SignalStore.account().pniPreKeys.activeSignedPreKeyId >= 0}")
        ApplicationDependencies.getJobManager().add(PreKeysSyncJob())
      } else if (SignalStore.account().aciPreKeys.lastResortKyberPreKeyId < 0 || SignalStore.account().pniPreKeys.lastResortKyberPreKeyId < 0) {
        Log.i(TAG, "Some last-resort kyber prekeys aren't active yet. Enqueuing a job. ACI: ${SignalStore.account().aciPreKeys.lastResortKyberPreKeyId >= 0} PNI: ${SignalStore.account().pniPreKeys.lastResortKyberPreKeyId >= 0}")
        ApplicationDependencies.getJobManager().add(PreKeysSyncJob())
      } else {
        val timeSinceLastFullRefresh = System.currentTimeMillis() - SignalStore.misc().lastFullPrekeyRefreshTime

        if (timeSinceLastFullRefresh >= REFRESH_INTERVAL || timeSinceLastFullRefresh < 0) {
          Log.i(TAG, "Scheduling a prekey refresh. Time since last full refresh: $timeSinceLastFullRefresh ms")
          ApplicationDependencies.getJobManager().add(PreKeysSyncJob())
        } else {
          Log.d(TAG, "No prekey job needed. Time since last full refresh: $timeSinceLastFullRefresh ms")
        }
      }
    }
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  constructor(forceRotation: Boolean = false) : this(
    Parameters.Builder()
      .setQueue("PreKeysSyncJob")
      .addConstraint(NetworkConstraint.KEY)
      .setMaxInstancesForFactory(1)
      .setMaxAttempts(Parameters.UNLIMITED)
      .setLifespan(TimeUnit.DAYS.toMillis(30))
      .build(),
    forceRotation
  )

  override fun getFactoryKey(): String = KEY

  override fun serialize(): ByteArray {
    return PreKeysSyncJobData(forceRotationRequested).encode()
  }

  override fun onRun() {
    if (!SignalStore.account().isRegistered || SignalStore.account().aci == null || SignalStore.account().pni == null) {
      warn(TAG, "Not yet registered")
      return
    }

    val forceRotation = if (forceRotationRequested) {
      warn(TAG, "Forced rotation was requested.")
      warn(TAG, ServiceIdType.ACI, "Active Signed EC: ${SignalStore.account().aciPreKeys.activeSignedPreKeyId}, Last Resort Kyber: ${SignalStore.account().aciPreKeys.lastResortKyberPreKeyId}")
      warn(TAG, ServiceIdType.PNI, "Active Signed EC: ${SignalStore.account().pniPreKeys.activeSignedPreKeyId}, Last Resort Kyber: ${SignalStore.account().pniPreKeys.lastResortKyberPreKeyId}")

      if (!checkPreKeyConsistency(ServiceIdType.ACI, ApplicationDependencies.getProtocolStore().aci(), SignalStore.account().aciPreKeys)) {
        warn(TAG, ServiceIdType.ACI, "Prekey consistency check failed! Must rotate keys!")
        true
      } else if (!checkPreKeyConsistency(ServiceIdType.PNI, ApplicationDependencies.getProtocolStore().pni(), SignalStore.account().pniPreKeys)) {
        warn(TAG, ServiceIdType.PNI, "Prekey consistency check failed! Must rotate keys! (ACI consistency check must have passed)")
        true
      } else {
        warn(TAG, "Forced rotation was requested, but the consistency checks passed!")
        val timeSinceLastForcedRotation = System.currentTimeMillis() - SignalStore.misc().lastForcedPreKeyRefresh
        // We check < 0 in case someone changed their clock and had a bad value set
        timeSinceLastForcedRotation > FeatureFlags.preKeyForceRefreshInterval() || timeSinceLastForcedRotation < 0
      }
    } else {
      false
    }

    if (forceRotation) {
      warn(TAG, "Forcing prekey rotation.")
    } else if (forceRotationRequested) {
      warn(TAG, "Forced prekey rotation was requested, but we already did a forced refresh ${System.currentTimeMillis() - SignalStore.misc().lastForcedPreKeyRefresh} ms ago. Ignoring.")
    }

    syncPreKeys(ServiceIdType.ACI, SignalStore.account().aci, ApplicationDependencies.getProtocolStore().aci(), SignalStore.account().aciPreKeys, forceRotation)
    syncPreKeys(ServiceIdType.PNI, SignalStore.account().pni, ApplicationDependencies.getProtocolStore().pni(), SignalStore.account().pniPreKeys, forceRotation)
    SignalStore.misc().lastFullPrekeyRefreshTime = System.currentTimeMillis()

    if (forceRotation) {
      SignalStore.misc().lastForcedPreKeyRefresh = System.currentTimeMillis()
    }
  }

  private fun syncPreKeys(serviceIdType: ServiceIdType, serviceId: ServiceId?, protocolStore: SignalServiceAccountDataStore, metadataStore: PreKeyMetadataStore, forceRotation: Boolean) {
    if (serviceId == null) {
      warn(TAG, serviceIdType, "AccountId not set!")
      return
    }

    val accountManager = ApplicationDependencies.getSignalServiceAccountManager()
    val availablePreKeyCounts: OneTimePreKeyCounts = accountManager.getPreKeyCounts(serviceIdType)

    val signedPreKeyToUpload: SignedPreKeyRecord? = signedPreKeyUploadIfNeeded(serviceIdType, protocolStore, metadataStore, forceRotation)

    val oneTimeEcPreKeysToUpload: List<PreKeyRecord>? = if (forceRotation || availablePreKeyCounts.ecCount < ONE_TIME_PREKEY_MINIMUM) {
      log(serviceIdType, "There are ${availablePreKeyCounts.ecCount} one-time EC prekeys available, which is less than our threshold. Need more. (Forced: $forceRotation)")
      PreKeyUtil.generateAndStoreOneTimeEcPreKeys(protocolStore, metadataStore)
    } else {
      log(serviceIdType, "There are ${availablePreKeyCounts.ecCount} one-time EC prekeys available, which is enough.")
      null
    }

    val lastResortKyberPreKeyToUpload: KyberPreKeyRecord? = lastResortKyberPreKeyUploadIfNeeded(serviceIdType, protocolStore, metadataStore, forceRotation)

    val oneTimeKyberPreKeysToUpload: List<KyberPreKeyRecord>? = if (forceRotation || availablePreKeyCounts.kyberCount < ONE_TIME_PREKEY_MINIMUM) {
      log(serviceIdType, "There are ${availablePreKeyCounts.kyberCount} one-time kyber prekeys available, which is less than our threshold. Need more. (Forced: $forceRotation)")
      PreKeyUtil.generateAndStoreOneTimeKyberPreKeys(protocolStore, metadataStore)
    } else {
      log(serviceIdType, "There are ${availablePreKeyCounts.kyberCount} one-time kyber prekeys available, which is enough.")
      null
    }

    if (signedPreKeyToUpload != null || oneTimeEcPreKeysToUpload != null || lastResortKyberPreKeyToUpload != null || oneTimeKyberPreKeysToUpload != null) {
      log(serviceIdType, "Something to upload. SignedPreKey: ${signedPreKeyToUpload != null}, OneTimeEcPreKeys: ${oneTimeEcPreKeysToUpload != null}, LastResortKyberPreKey: ${lastResortKyberPreKeyToUpload != null}, OneTimeKyberPreKeys: ${oneTimeKyberPreKeysToUpload != null}")
      accountManager.setPreKeys(
        PreKeyUpload(
          serviceIdType = serviceIdType,
          signedPreKey = signedPreKeyToUpload,
          oneTimeEcPreKeys = oneTimeEcPreKeysToUpload,
          lastResortKyberPreKey = lastResortKyberPreKeyToUpload,
          oneTimeKyberPreKeys = oneTimeKyberPreKeysToUpload
        )
      )

      if (signedPreKeyToUpload != null) {
        log(serviceIdType, "Successfully uploaded signed prekey.")
        metadataStore.activeSignedPreKeyId = signedPreKeyToUpload.id
        metadataStore.isSignedPreKeyRegistered = true
        metadataStore.lastSignedPreKeyRotationTime = System.currentTimeMillis()
      }

      if (oneTimeEcPreKeysToUpload != null) {
        log(serviceIdType, "Successfully uploaded one-time EC prekeys.")
      }

      if (lastResortKyberPreKeyToUpload != null) {
        log(serviceIdType, "Successfully uploaded last-resort kyber prekey.")
        metadataStore.lastResortKyberPreKeyId = lastResortKyberPreKeyToUpload.id
        metadataStore.lastResortKyberPreKeyRotationTime = System.currentTimeMillis()
      }

      if (oneTimeKyberPreKeysToUpload != null) {
        log(serviceIdType, "Successfully uploaded one-time kyber prekeys.")
      }
    } else {
      log(serviceIdType, "No prekeys to upload.")
    }

    log(serviceIdType, "Cleaning prekeys...")
    PreKeyUtil.cleanSignedPreKeys(protocolStore, metadataStore)
    PreKeyUtil.cleanLastResortKyberPreKeys(protocolStore, metadataStore)
    PreKeyUtil.cleanOneTimePreKeys(protocolStore)
  }

  private fun signedPreKeyUploadIfNeeded(serviceIdType: ServiceIdType, protocolStore: SignalProtocolStore, metadataStore: PreKeyMetadataStore, forceRotation: Boolean): SignedPreKeyRecord? {
    val signedPreKeyRegistered = metadataStore.isSignedPreKeyRegistered && metadataStore.activeSignedPreKeyId >= 0
    val timeSinceLastSignedPreKeyRotation = System.currentTimeMillis() - metadataStore.lastSignedPreKeyRotationTime

    return if (forceRotation || !signedPreKeyRegistered || timeSinceLastSignedPreKeyRotation >= REFRESH_INTERVAL || timeSinceLastSignedPreKeyRotation < 0) {
      log(serviceIdType, "Rotating signed prekey. ForceRotation: $forceRotation, SignedPreKeyRegistered: $signedPreKeyRegistered, TimeSinceLastRotation: $timeSinceLastSignedPreKeyRotation ms (${timeSinceLastSignedPreKeyRotation.milliseconds.toDouble(DurationUnit.DAYS).roundedString(2)} days)")
      PreKeyUtil.generateAndStoreSignedPreKey(protocolStore, metadataStore)
    } else {
      log(serviceIdType, "No need to rotate signed prekey. TimeSinceLastRotation: $timeSinceLastSignedPreKeyRotation ms (${timeSinceLastSignedPreKeyRotation.milliseconds.toDouble(DurationUnit.DAYS).roundedString(2)} days)")
      null
    }
  }

  private fun lastResortKyberPreKeyUploadIfNeeded(serviceIdType: ServiceIdType, protocolStore: SignalServiceAccountDataStore, metadataStore: PreKeyMetadataStore, forceRotation: Boolean): KyberPreKeyRecord? {
    val lastResortRegistered = metadataStore.lastResortKyberPreKeyId >= 0
    val timeSinceLastSignedPreKeyRotation = System.currentTimeMillis() - metadataStore.lastResortKyberPreKeyRotationTime

    return if (forceRotation || !lastResortRegistered || timeSinceLastSignedPreKeyRotation >= REFRESH_INTERVAL || timeSinceLastSignedPreKeyRotation < 0) {
      log(serviceIdType, "Rotating last-resort kyber prekey. ForceRotation: $forceRotation, TimeSinceLastRotation: $timeSinceLastSignedPreKeyRotation ms (${timeSinceLastSignedPreKeyRotation.milliseconds.toDouble(DurationUnit.DAYS).roundedString(2)} days)")
      PreKeyUtil.generateAndStoreLastResortKyberPreKey(protocolStore, metadataStore)
    } else {
      log(serviceIdType, "No need to rotate last-resort kyber prekey. TimeSinceLastRotation: $timeSinceLastSignedPreKeyRotation ms (${timeSinceLastSignedPreKeyRotation.milliseconds.toDouble(DurationUnit.DAYS).roundedString(2)} days)")
      null
    }
  }

  @Throws(IOException::class)
  private fun checkPreKeyConsistency(serviceIdType: ServiceIdType, protocolStore: SignalServiceAccountDataStore, metadataStore: PreKeyMetadataStore): Boolean {
    val result: NetworkResult<Unit> = ApplicationDependencies.getSignalServiceAccountManager().keysApi.checkRepeatedUseKeys(
      serviceIdType = serviceIdType,
      identityKey = protocolStore.identityKeyPair.publicKey,
      signedPreKeyId = metadataStore.activeSignedPreKeyId,
      signedPreKey = protocolStore.loadSignedPreKey(metadataStore.activeSignedPreKeyId).keyPair.publicKey,
      lastResortKyberKeyId = metadataStore.lastResortKyberPreKeyId,
      lastResortKyberKey = protocolStore.loadKyberPreKey(metadataStore.lastResortKyberPreKeyId).keyPair.publicKey
    )

    return when (result) {
      is NetworkResult.Success -> true
      is NetworkResult.NetworkError -> throw result.exception ?: PushNetworkException("Network error")
      is NetworkResult.ApplicationError -> throw result.throwable
      is NetworkResult.StatusCodeError -> if (result.code == 409) {
        false
      } else {
        throw NonSuccessfulResponseCodeException(result.code)
      }
    }
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return when (e) {
      is NonSuccessfulResponseCodeException -> false
      is PushNetworkException -> true
      else -> false
    }
  }

  override fun onFailure() {
    Log.w(TAG, "Failed to sync prekeys. Enqueuing an account consistency check.")
    ApplicationDependencies.getJobManager().add(AccountConsistencyWorkerJob())
  }

  private fun log(serviceIdType: ServiceIdType, message: String) {
    Log.i(TAG, "[$serviceIdType] $message")
  }

  class Factory : Job.Factory<PreKeysSyncJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): PreKeysSyncJob {
      return try {
        serializedData?.let {
          val data = PreKeysSyncJobData.ADAPTER.decode(serializedData)
          PreKeysSyncJob(parameters, data.forceRefreshRequested)
        } ?: PreKeysSyncJob(parameters, forceRotationRequested = false)
      } catch (e: IOException) {
        Log.w(TAG, "Error deserializing PreKeysSyncJob", e)
        PreKeysSyncJob(parameters, forceRotationRequested = false)
      }
    }
  }
}
