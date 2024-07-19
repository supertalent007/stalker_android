/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.pin

import android.app.backup.BackupManager
import androidx.annotation.WorkerThread
import org.signal.core.util.Stopwatch
import org.signal.core.util.logging.Log
import org.stalker.securesms.BuildConfig
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.JobTracker
import org.stalker.securesms.jobs.ReclaimUsernameAndLinkJob
import org.stalker.securesms.jobs.RefreshAttributesJob
import org.stalker.securesms.jobs.ResetSvrGuessCountJob
import org.stalker.securesms.jobs.StorageAccountRestoreJob
import org.stalker.securesms.jobs.StorageForcePushJob
import org.stalker.securesms.jobs.StorageSyncJob
import org.stalker.securesms.jobs.Svr2MirrorJob
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.lock.v2.PinKeyboardType
import org.stalker.securesms.megaphone.Megaphones
import org.stalker.securesms.registration.viewmodel.SvrAuthCredentialSet
import org.stalker.securesms.util.FeatureFlags
import org.whispersystems.signalservice.api.SvrNoDataException
import org.whispersystems.signalservice.api.kbs.MasterKey
import org.whispersystems.signalservice.api.svr.SecureValueRecovery
import org.whispersystems.signalservice.api.svr.SecureValueRecovery.BackupResponse
import org.whispersystems.signalservice.api.svr.SecureValueRecovery.RestoreResponse
import org.whispersystems.signalservice.internal.push.AuthCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object SvrRepository {

  val TAG = Log.tag(SvrRepository::class.java)

  private val svr2: SecureValueRecovery = ApplicationDependencies.getSignalServiceAccountManager().getSecureValueRecoveryV2(BuildConfig.SVR2_MRENCLAVE)

  /** An ordered list of SVR implementations to read from. They should be in priority order, with the most important one listed first. */
  private val readImplementations: List<SecureValueRecovery> = listOf(svr2)

  /** An ordered list of SVR implementations to write to. They should be in priority order, with the most important one listed first. */
  private val writeImplementations: List<SecureValueRecovery> = listOf(svr2)

  /**
   * A lock that ensures that only one thread at a time is altering the various pieces of SVR state.
   *
   * External usage of this should be limited to one-time migrations. Any routine operation that needs the lock should go in
   * this repository instead.
   */
  val operationLock = ReentrantLock()

  /**
   * Restores the master key from the first available SVR implementation available.
   *
   * This is intended to be called before registration has been completed, requiring
   * that you pass in the credentials provided during registration to access SVR.
   *
   * You could be hitting this because the user has reglock (and therefore need to
   * restore the master key before you can register), or you may be doing the
   * sms-skip flow.
   */
  @JvmStatic
  @WorkerThread
  @Throws(IOException::class, SvrWrongPinException::class, SvrNoDataException::class)
  fun restoreMasterKeyPreRegistration(credentials: SvrAuthCredentialSet, userPin: String): MasterKey {
    operationLock.withLock {
      Log.i(TAG, "restoreMasterKeyPreRegistration()", true)

      val operations: List<Pair<SecureValueRecovery, () -> RestoreResponse>> = listOf(
        svr2 to { restoreMasterKeyPreRegistration(svr2, credentials.svr2, userPin) }
      )

      for ((implementation, operation) in operations) {
        when (val response: RestoreResponse = operation()) {
          is RestoreResponse.Success -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Successfully restored master key. $implementation", true)
            if (implementation == svr2) {
              SignalStore.svr().appendAuthTokenToList(response.authorization.asBasic())
            }

            return response.masterKey
          }

          is RestoreResponse.PinMismatch -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Incorrect PIN. $implementation", true)
            throw SvrWrongPinException(response.triesRemaining)
          }

          is RestoreResponse.NetworkError -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Network error. $implementation", response.exception, true)
            throw response.exception
          }

          is RestoreResponse.ApplicationError -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Application error. $implementation", response.exception, true)
            throw IOException(response.exception)
          }

          RestoreResponse.Missing -> {
            Log.w(TAG, "[restoreMasterKeyPreRegistration] No data found for $implementation | Continuing to next implementation.", true)
          }
        }
      }

      Log.w(TAG, "[restoreMasterKeyPreRegistration] No data found for any implementation!", true)

      throw SvrNoDataException()
    }
  }

  /**
   * Restores the master key from the first available SVR implementation available.
   *
   * This is intended to be called after the user has registered, allowing the function
   * to fetch credentials on its own.
   */
  @WorkerThread
  fun restoreMasterKeyPostRegistration(userPin: String, pinKeyboardType: PinKeyboardType): RestoreResponse {
    val stopwatch = Stopwatch("pin-submission")

    operationLock.withLock {
      for (implementation in readImplementations) {
        when (val response: RestoreResponse = implementation.restoreDataPostRegistration(userPin)) {
          is RestoreResponse.Success -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Successfully restored master key. $implementation", true)
            stopwatch.split("restore")

            SignalStore.svr().setMasterKey(response.masterKey, userPin)
            SignalStore.svr().isRegistrationLockEnabled = false
            SignalStore.pinValues().resetPinReminders()
            SignalStore.svr().isPinForgottenOrSkipped = false
            SignalStore.storageService().setNeedsAccountRestore(false)
            SignalStore.pinValues().keyboardType = pinKeyboardType
            SignalStore.storageService().setNeedsAccountRestore(false)
            if (implementation == svr2) {
              SignalStore.svr().appendAuthTokenToList(response.authorization.asBasic())
            }
            ApplicationDependencies.getJobManager().add(ResetSvrGuessCountJob())
            stopwatch.split("metadata")

            ApplicationDependencies.getJobManager().runSynchronously(StorageAccountRestoreJob(), StorageAccountRestoreJob.LIFESPAN)
            stopwatch.split("account-restore")

            ApplicationDependencies
              .getJobManager()
              .startChain(StorageSyncJob())
              .then(ReclaimUsernameAndLinkJob())
              .enqueueAndBlockUntilCompletion(TimeUnit.SECONDS.toMillis(10))
            stopwatch.split("contact-restore")

            if (implementation != svr2) {
              ApplicationDependencies.getJobManager().add(Svr2MirrorJob())
            }

            stopwatch.stop(TAG)

            return response
          }

          is RestoreResponse.PinMismatch -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Incorrect PIN. $implementation", true)
            return response
          }

          is RestoreResponse.NetworkError -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Network error. $implementation", response.exception, true)
            return response
          }

          is RestoreResponse.ApplicationError -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Application error. $implementation", response.exception, true)
            return response
          }

          RestoreResponse.Missing -> {
            Log.w(TAG, "[restoreMasterKeyPostRegistration] No data found for: $implementation | Continuing to next implementation.", true)
          }
        }
      }

      Log.w(TAG, "[restoreMasterKeyPostRegistration] No data found for any implementation!", true)
      return RestoreResponse.Missing
    }
  }

  /**
   * Sets the user's PIN to the one specified, updating local stores as necessary.
   * The resulting Single will not throw an error in any expected case, only if there's a runtime exception.
   */
  @WorkerThread
  @JvmStatic
  fun setPin(userPin: String, keyboardType: PinKeyboardType): BackupResponse {
    return operationLock.withLock {
      val masterKey: MasterKey = SignalStore.svr().getOrCreateMasterKey()

      val responses: List<BackupResponse> = writeImplementations
        .filter { it != svr2 || FeatureFlags.svr2() }
        .map { it.setPin(userPin, masterKey) }
        .map { it.execute() }

      Log.i(TAG, "[setPin] Responses: $responses", true)

      val error: BackupResponse? = responses.map {
        when (it) {
          is BackupResponse.ApplicationError -> it
          BackupResponse.ExposeFailure -> it
          is BackupResponse.NetworkError -> it
          BackupResponse.ServerRejected -> it
          BackupResponse.EnclaveNotFound -> null
          is BackupResponse.Success -> null
        }
      }.firstOrNull()

      val overallResponse = error
        ?: responses.firstOrNull { it is BackupResponse.Success }
        ?: responses[0]

      if (overallResponse is BackupResponse.Success) {
        Log.i(TAG, "[setPin] Success!", true)

        SignalStore.svr().setMasterKey(masterKey, userPin)
        SignalStore.svr().isPinForgottenOrSkipped = false
        SignalStore.svr().appendAuthTokenToList(overallResponse.authorization.asBasic())

        SignalStore.pinValues().keyboardType = keyboardType
        SignalStore.pinValues().resetPinReminders()

        ApplicationDependencies.getMegaphoneRepository().markFinished(Megaphones.Event.PINS_FOR_ALL)

        ApplicationDependencies.getJobManager().add(RefreshAttributesJob())
      } else {
        Log.w(TAG, "[setPin] Failed to set PIN! $overallResponse", true)

        if (hasNoRegistrationLock) {
          SignalStore.svr().onPinCreateFailure()
        }
      }

      overallResponse
    }
  }

  /**
   * Invoked after a user has successfully registered. Ensures all the necessary state is updated.
   */
  @WorkerThread
  @JvmStatic
  fun onRegistrationComplete(
    masterKey: MasterKey?,
    userPin: String?,
    hasPinToRestore: Boolean,
    setRegistrationLockEnabled: Boolean
  ) {
    Log.i(TAG, "[onRegistrationComplete] Starting", true)
    operationLock.withLock {
      if (masterKey == null && userPin != null) {
        error("If masterKey is present, pin must also be present!")
      }

      if (masterKey != null && userPin != null) {
        if (setRegistrationLockEnabled) {
          Log.i(TAG, "[onRegistrationComplete] Registration Lock", true)
          SignalStore.svr().isRegistrationLockEnabled = true
        } else {
          Log.i(TAG, "[onRegistrationComplete] ReRegistration Skip SMS", true)
        }

        SignalStore.svr().setMasterKey(masterKey, userPin)
        SignalStore.pinValues().resetPinReminders()

        ApplicationDependencies.getJobManager().add(ResetSvrGuessCountJob())
      } else if (hasPinToRestore) {
        Log.i(TAG, "[onRegistrationComplete] Has a PIN to restore.", true)
        SignalStore.svr().clearRegistrationLockAndPin()
        SignalStore.storageService().setNeedsAccountRestore(true)
      } else {
        Log.i(TAG, "[onRegistrationComplete] No registration lock or PIN at all.", true)
        SignalStore.svr().clearRegistrationLockAndPin()
      }
    }

    ApplicationDependencies.getJobManager().add(RefreshAttributesJob())
  }

  /**
   * Invoked when the user skips out on PIN restoration or otherwise fails to remember their PIN.
   */
  @JvmStatic
  fun onPinRestoreForgottenOrSkipped() {
    operationLock.withLock {
      SignalStore.svr().clearRegistrationLockAndPin()
      SignalStore.storageService().setNeedsAccountRestore(false)
      SignalStore.svr().isPinForgottenOrSkipped = true
    }
  }

  @JvmStatic
  @WorkerThread
  fun optOutOfPin() {
    operationLock.withLock {
      SignalStore.svr().optOut()

      ApplicationDependencies.getMegaphoneRepository().markFinished(Megaphones.Event.PINS_FOR_ALL)

      bestEffortRefreshAttributes()
      bestEffortForcePushStorage()
    }
  }

  @JvmStatic
  @WorkerThread
  @Throws(IOException::class)
  fun enableRegistrationLockForUserWithPin() {
    operationLock.withLock {
      check(SignalStore.svr().hasPin() && !SignalStore.svr().hasOptedOut()) { "Must have a PIN to set a registration lock!" }

      Log.i(TAG, "[enableRegistrationLockForUserWithPin] Enabling registration lock.", true)
      ApplicationDependencies.getSignalServiceAccountManager().enableRegistrationLock(SignalStore.svr().getOrCreateMasterKey())
      SignalStore.svr().isRegistrationLockEnabled = true
      Log.i(TAG, "[enableRegistrationLockForUserWithPin] Registration lock successfully enabled.", true)
    }
  }

  @JvmStatic
  @WorkerThread
  @Throws(IOException::class)
  fun disableRegistrationLockForUserWithPin() {
    operationLock.withLock {
      check(SignalStore.svr().hasPin() && !SignalStore.svr().hasOptedOut()) { "Must have a PIN to disable registration lock!" }

      Log.i(TAG, "[disableRegistrationLockForUserWithPin] Disabling registration lock.", true)
      ApplicationDependencies.getSignalServiceAccountManager().disableRegistrationLock()
      SignalStore.svr().isRegistrationLockEnabled = false
      Log.i(TAG, "[disableRegistrationLockForUserWithPin] Registration lock successfully disabled.", true)
    }
  }

  /**
   * Fetches new SVR credentials and persists them in the backup store to be used during re-registration.
   */
  @WorkerThread
  @Throws(IOException::class)
  fun refreshAndStoreAuthorization() {
    try {
      val credentials: AuthCredentials = svr2.authorization()
      backupSvrCredentials(credentials)
    } catch (e: Throwable) {
      if (e is IOException) {
        throw e
      } else {
        throw IOException(e)
      }
    }
  }

  @WorkerThread
  private fun restoreMasterKeyPreRegistration(svr: SecureValueRecovery, credentials: AuthCredentials?, userPin: String): RestoreResponse {
    return if (credentials == null) {
      RestoreResponse.Missing
    } else {
      svr.restoreDataPreRegistration(credentials, userPin)
    }
  }

  @WorkerThread
  private fun bestEffortRefreshAttributes() {
    val result = ApplicationDependencies.getJobManager().runSynchronously(RefreshAttributesJob(), TimeUnit.SECONDS.toMillis(10))
    if (result.isPresent && result.get() == JobTracker.JobState.SUCCESS) {
      Log.i(TAG, "Attributes were refreshed successfully.", true)
    } else if (result.isPresent) {
      Log.w(TAG, "Attribute refresh finished, but was not successful. Enqueuing one for later. (Result: " + result.get() + ")", true)
      ApplicationDependencies.getJobManager().add(RefreshAttributesJob())
    } else {
      Log.w(TAG, "Job did not finish in the allotted time. It'll finish later.", true)
    }
  }

  @WorkerThread
  private fun bestEffortForcePushStorage() {
    val result = ApplicationDependencies.getJobManager().runSynchronously(StorageForcePushJob(), TimeUnit.SECONDS.toMillis(10))
    if (result.isPresent && result.get() == JobTracker.JobState.SUCCESS) {
      Log.i(TAG, "Storage was force-pushed successfully.", true)
    } else if (result.isPresent) {
      Log.w(TAG, "Storage force-pushed finished, but was not successful. Enqueuing one for later. (Result: " + result.get() + ")", true)
      ApplicationDependencies.getJobManager().add(RefreshAttributesJob())
    } else {
      Log.w(TAG, "Storage fore push did not finish in the allotted time. It'll finish later.", true)
    }
  }

  private fun backupSvrCredentials(credentials: AuthCredentials) {
    val tokenIsNew = SignalStore.svr().appendAuthTokenToList(credentials.asBasic())

    if (tokenIsNew && SignalStore.svr().hasPin()) {
      BackupManager(ApplicationDependencies.getApplication()).dataChanged()
    }
  }

  private val hasNoRegistrationLock: Boolean
    get() {
      return !SignalStore.svr().isRegistrationLockEnabled &&
        !SignalStore.svr().hasPin() &&
        !SignalStore.svr().hasOptedOut()
    }
}
