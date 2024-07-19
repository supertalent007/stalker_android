package org.stalker.securesms.components.settings.app.changenumber

import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import okio.ByteString.Companion.toByteString
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.util.Medium
import org.stalker.securesms.crypto.IdentityKeyUtil
import org.stalker.securesms.crypto.PreKeyUtil
import org.stalker.securesms.database.IdentityTable
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.databaseprotos.PendingChangeNumberMetadata
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobs.RefreshAttributesJob
import org.stalker.securesms.keyvalue.CertificateType
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.pin.SvrRepository
import org.stalker.securesms.pin.SvrWrongPinException
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.registration.VerifyResponse
import org.stalker.securesms.registration.viewmodel.SvrAuthCredentialSet
import org.stalker.securesms.storage.StorageSyncHelper
import org.whispersystems.signalservice.api.SignalServiceAccountManager
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.SvrNoDataException
import org.whispersystems.signalservice.api.account.ChangePhoneNumberRequest
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.kbs.MasterKey
import org.whispersystems.signalservice.api.push.ServiceId.PNI
import org.whispersystems.signalservice.api.push.ServiceIdType
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity
import org.whispersystems.signalservice.internal.ServiceResponse
import org.whispersystems.signalservice.internal.push.KyberPreKeyEntity
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataResponse
import org.whispersystems.signalservice.internal.push.SyncMessage
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse
import org.whispersystems.signalservice.internal.push.WhoAmIResponse
import org.whispersystems.signalservice.internal.push.exceptions.MismatchedDevicesException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private val TAG: String = Log.tag(ChangeNumberRepository::class.java)

/**
 * Provides various change number operations. All operations must run on [Schedulers.single] to support
 * the global "I am changing the number" lock exclusivity.
 */
class ChangeNumberRepository(
  private val accountManager: SignalServiceAccountManager = ApplicationDependencies.getSignalServiceAccountManager(),
  private val messageSender: SignalServiceMessageSender = ApplicationDependencies.getSignalServiceMessageSender()
) {

  companion object {
    /**
     * This lock should be held by anyone who is performing a change number operation, so that two different parties cannot change the user's number
     * at the same time.
     */
    val CHANGE_NUMBER_LOCK = ReentrantLock()

    /**
     * Adds Rx operators to chain to acquire and release the [CHANGE_NUMBER_LOCK] on subscribe and on finish.
     */
    fun <T : Any> acquireReleaseChangeNumberLock(upstream: Single<T>): Single<T> {
      return upstream.doOnSubscribe {
        CHANGE_NUMBER_LOCK.lock()
        SignalStore.misc().lockChangeNumber()
      }
        .subscribeOn(Schedulers.single())
        .observeOn(Schedulers.single())
        .doFinally {
          if (CHANGE_NUMBER_LOCK.isHeldByCurrentThread) {
            CHANGE_NUMBER_LOCK.unlock()
          }
        }
    }
  }

  fun ensureDecryptionsDrained(): Completable {
    return Completable.create { emitter ->
      val drainedListener = object : Runnable {
        override fun run() {
          emitter.onComplete()
          ApplicationDependencies
            .getIncomingMessageObserver()
            .removeDecryptionDrainedListener(this)
        }
      }

      emitter.setCancellable {
        ApplicationDependencies
          .getIncomingMessageObserver()
          .removeDecryptionDrainedListener(drainedListener)
      }

      ApplicationDependencies
        .getIncomingMessageObserver()
        .addDecryptionDrainedListener(drainedListener)
    }.subscribeOn(Schedulers.single())
      .timeout(15, TimeUnit.SECONDS)
  }

  fun changeNumber(sessionId: String? = null, recoveryPassword: String? = null, newE164: String): Single<ServiceResponse<VerifyResponse>> {
    check((sessionId != null && recoveryPassword == null) || (sessionId == null && recoveryPassword != null))

    return Single.fromCallable {
      var completed = false
      var attempts = 0
      lateinit var changeNumberResponse: ServiceResponse<VerifyAccountResponse>

      while (!completed && attempts < 5) {
        val (request: ChangePhoneNumberRequest, metadata: PendingChangeNumberMetadata) = createChangeNumberRequest(
          sessionId = sessionId,
          recoveryPassword = recoveryPassword,
          newE164 = newE164
        )

        SignalStore.misc().setPendingChangeNumberMetadata(metadata)

        changeNumberResponse = accountManager.changeNumber(request)

        val possibleError: Throwable? = changeNumberResponse.applicationError.orElse(null)
        if (possibleError is MismatchedDevicesException) {
          messageSender.handleChangeNumberMismatchDevices(possibleError.mismatchedDevices)
          attempts++
        } else {
          completed = true
        }
      }

      VerifyResponse.from(
        response = changeNumberResponse,
        masterKey = null,
        pin = null,
        aciPreKeyCollection = null,
        pniPreKeyCollection = null
      )
    }.subscribeOn(Schedulers.single())
      .onErrorReturn { t -> ServiceResponse.forExecutionError(t) }
  }

  fun changeNumber(
    sessionId: String,
    newE164: String,
    pin: String,
    svrAuthCredentials: SvrAuthCredentialSet
  ): Single<ServiceResponse<VerifyResponse>> {
    return Single.fromCallable {
      val masterKey: MasterKey
      val registrationLock: String

      try {
        masterKey = SvrRepository.restoreMasterKeyPreRegistration(svrAuthCredentials, pin)
        registrationLock = masterKey.deriveRegistrationLock()
      } catch (e: SvrWrongPinException) {
        return@fromCallable ServiceResponse.forExecutionError(e)
      } catch (e: SvrNoDataException) {
        return@fromCallable ServiceResponse.forExecutionError(e)
      } catch (e: IOException) {
        return@fromCallable ServiceResponse.forExecutionError(e)
      }

      var completed = false
      var attempts = 0
      lateinit var changeNumberResponse: ServiceResponse<VerifyAccountResponse>

      while (!completed && attempts < 5) {
        val (request: ChangePhoneNumberRequest, metadata: PendingChangeNumberMetadata) = createChangeNumberRequest(
          sessionId = sessionId,
          newE164 = newE164,
          registrationLock = registrationLock
        )

        SignalStore.misc().setPendingChangeNumberMetadata(metadata)

        changeNumberResponse = accountManager.changeNumber(request)

        val possibleError: Throwable? = changeNumberResponse.applicationError.orElse(null)
        if (possibleError is MismatchedDevicesException) {
          messageSender.handleChangeNumberMismatchDevices(possibleError.mismatchedDevices)
          attempts++
        } else {
          completed = true
        }
      }

      VerifyResponse.from(
        response = changeNumberResponse,
        masterKey = masterKey,
        pin = pin,
        aciPreKeyCollection = null,
        pniPreKeyCollection = null
      )
    }.subscribeOn(Schedulers.single())
      .onErrorReturn { t -> ServiceResponse.forExecutionError(t) }
  }

  @Suppress("UsePropertyAccessSyntax")
  fun whoAmI(): Single<WhoAmIResponse> {
    return Single.fromCallable { ApplicationDependencies.getSignalServiceAccountManager().getWhoAmI() }
      .subscribeOn(Schedulers.single())
  }

  @WorkerThread
  fun changeLocalNumber(e164: String, pni: PNI): Single<Unit> {
    val oldStorageId: ByteArray? = Recipient.self().storageId
    SignalDatabase.recipients.updateSelfE164(e164, pni)
    val newStorageId: ByteArray? = Recipient.self().storageId

    if (e164 != SignalStore.account().requireE164() && MessageDigest.isEqual(oldStorageId, newStorageId)) {
      Log.w(TAG, "Self storage id was not rotated, attempting to rotate again")
      SignalDatabase.recipients.rotateStorageId(Recipient.self().id)
      StorageSyncHelper.scheduleSyncForDataChange()
      val secondAttemptStorageId: ByteArray? = Recipient.self().storageId
      if (MessageDigest.isEqual(oldStorageId, secondAttemptStorageId)) {
        Log.w(TAG, "Second attempt also failed to rotate storage id")
      }
    }

    ApplicationDependencies.getRecipientCache().clear()

    SignalStore.account().setE164(e164)
    SignalStore.account().setPni(pni)
    ApplicationDependencies.resetProtocolStores()

    ApplicationDependencies.getGroupsV2Authorization().clear()

    val metadata: PendingChangeNumberMetadata? = SignalStore.misc().pendingChangeNumberMetadata
    if (metadata == null) {
      Log.w(TAG, "No change number metadata, this shouldn't happen")
      throw AssertionError("No change number metadata")
    }

    val originalPni = PNI.parseOrThrow(metadata.previousPni)

    if (originalPni == pni) {
      Log.i(TAG, "No change has occurred, PNI is unchanged: $pni")
    } else {
      val pniIdentityKeyPair = IdentityKeyPair(metadata.pniIdentityKeyPair.toByteArray())
      val pniRegistrationId = metadata.pniRegistrationId
      val pniSignedPreyKeyId = metadata.pniSignedPreKeyId
      val pniLastResortKyberPreKeyId = metadata.pniLastResortKyberPreKeyId

      val pniProtocolStore = ApplicationDependencies.getProtocolStore().pni()
      val pniMetadataStore = SignalStore.account().pniPreKeys

      SignalStore.account().pniRegistrationId = pniRegistrationId
      SignalStore.account().setPniIdentityKeyAfterChangeNumber(pniIdentityKeyPair)

      val signedPreKey = pniProtocolStore.loadSignedPreKey(pniSignedPreyKeyId)
      val oneTimeEcPreKeys = PreKeyUtil.generateAndStoreOneTimeEcPreKeys(pniProtocolStore, pniMetadataStore)
      val lastResortKyberPreKey = pniProtocolStore.loadLastResortKyberPreKeys().firstOrNull { it.id == pniLastResortKyberPreKeyId }
      val oneTimeKyberPreKeys = PreKeyUtil.generateAndStoreOneTimeKyberPreKeys(pniProtocolStore, pniMetadataStore)

      if (lastResortKyberPreKey == null) {
        Log.w(TAG, "Last-resort kyber prekey is missing!")
      }

      pniMetadataStore.activeSignedPreKeyId = signedPreKey.id
      accountManager.setPreKeys(
        PreKeyUpload(
          serviceIdType = ServiceIdType.PNI,
          signedPreKey = signedPreKey,
          oneTimeEcPreKeys = oneTimeEcPreKeys,
          lastResortKyberPreKey = lastResortKyberPreKey,
          oneTimeKyberPreKeys = oneTimeKyberPreKeys
        )
      )
      pniMetadataStore.isSignedPreKeyRegistered = true
      pniMetadataStore.lastResortKyberPreKeyId = pniLastResortKyberPreKeyId

      pniProtocolStore.identities().saveIdentityWithoutSideEffects(
        Recipient.self().id,
        pni,
        pniProtocolStore.identityKeyPair.publicKey,
        IdentityTable.VerifiedStatus.VERIFIED,
        true,
        System.currentTimeMillis(),
        true
      )

      SignalStore.misc().hasPniInitializedDevices = true
      ApplicationDependencies.getGroupsV2Authorization().clear()
    }

    Recipient.self().live().refresh()
    StorageSyncHelper.scheduleSyncForDataChange()

    ApplicationDependencies.closeConnections()
    ApplicationDependencies.getIncomingMessageObserver()

    ApplicationDependencies.getJobManager().add(RefreshAttributesJob())

    return rotateCertificates()
  }

  @Suppress("UsePropertyAccessSyntax")
  private fun rotateCertificates(): Single<Unit> {
    val certificateTypes = SignalStore.phoneNumberPrivacy().allCertificateTypes

    Log.i(TAG, "Rotating these certificates $certificateTypes")

    return Single.fromCallable {
      for (certificateType in certificateTypes) {
        val certificate: ByteArray? = when (certificateType) {
          CertificateType.ACI_AND_E164 -> accountManager.getSenderCertificate()
          CertificateType.ACI_ONLY -> accountManager.getSenderCertificateForPhoneNumberPrivacy()
          else -> throw AssertionError()
        }

        Log.i(TAG, "Successfully got $certificateType certificate")

        SignalStore.certificateValues().setUnidentifiedAccessCertificate(certificateType, certificate)
      }
    }.subscribeOn(Schedulers.single())
  }

  @WorkerThread
  private fun createChangeNumberRequest(
    sessionId: String? = null,
    recoveryPassword: String? = null,
    newE164: String,
    registrationLock: String? = null
  ): ChangeNumberRequestData {
    val selfIdentifier: String = SignalStore.account().requireAci().toString()
    val aciProtocolStore: SignalProtocolStore = ApplicationDependencies.getProtocolStore().aci()

    val pniIdentity: IdentityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()
    val deviceMessages = mutableListOf<OutgoingPushMessage>()
    val devicePniSignedPreKeys = mutableMapOf<Int, SignedPreKeyEntity>()
    val devicePniLastResortKyberPreKeys = mutableMapOf<Int, KyberPreKeyEntity>()
    val pniRegistrationIds = mutableMapOf<Int, Int>()
    val primaryDeviceId: Int = SignalServiceAddress.DEFAULT_DEVICE_ID

    val devices: List<Int> = listOf(primaryDeviceId) + aciProtocolStore.getSubDeviceSessions(selfIdentifier)

    devices
      .filter { it == primaryDeviceId || aciProtocolStore.containsSession(SignalProtocolAddress(selfIdentifier, it)) }
      .forEach { deviceId ->
        // Signed Prekeys
        val signedPreKeyRecord: SignedPreKeyRecord = if (deviceId == primaryDeviceId) {
          PreKeyUtil.generateAndStoreSignedPreKey(ApplicationDependencies.getProtocolStore().pni(), SignalStore.account().pniPreKeys, pniIdentity.privateKey)
        } else {
          PreKeyUtil.generateSignedPreKey(SecureRandom().nextInt(Medium.MAX_VALUE), pniIdentity.privateKey)
        }
        devicePniSignedPreKeys[deviceId] = SignedPreKeyEntity(signedPreKeyRecord.id, signedPreKeyRecord.keyPair.publicKey, signedPreKeyRecord.signature)

        // Last-resort kyber prekeys
        val lastResortKyberPreKeyRecord: KyberPreKeyRecord = if (deviceId == primaryDeviceId) {
          PreKeyUtil.generateAndStoreLastResortKyberPreKey(ApplicationDependencies.getProtocolStore().pni(), SignalStore.account().pniPreKeys, pniIdentity.privateKey)
        } else {
          PreKeyUtil.generateLastResortKyberPreKey(SecureRandom().nextInt(Medium.MAX_VALUE), pniIdentity.privateKey)
        }
        devicePniLastResortKyberPreKeys[deviceId] = KyberPreKeyEntity(lastResortKyberPreKeyRecord.id, lastResortKyberPreKeyRecord.keyPair.publicKey, lastResortKyberPreKeyRecord.signature)

        // Registration Ids
        var pniRegistrationId = -1

        while (pniRegistrationId < 0 || pniRegistrationIds.values.contains(pniRegistrationId)) {
          pniRegistrationId = KeyHelper.generateRegistrationId(false)
        }
        pniRegistrationIds[deviceId] = pniRegistrationId

        // Device Messages
        if (deviceId != primaryDeviceId) {
          val pniChangeNumber = SyncMessage.PniChangeNumber(
            identityKeyPair = pniIdentity.serialize().toByteString(),
            signedPreKey = signedPreKeyRecord.serialize().toByteString(),
            lastResortKyberPreKey = lastResortKyberPreKeyRecord.serialize().toByteString(),
            registrationId = pniRegistrationId,
            newE164 = newE164
          )

          deviceMessages += messageSender.getEncryptedSyncPniInitializeDeviceMessage(deviceId, pniChangeNumber)
        }
      }

    val request = ChangePhoneNumberRequest(
      sessionId,
      recoveryPassword,
      newE164,
      registrationLock,
      pniIdentity.publicKey,
      deviceMessages,
      devicePniSignedPreKeys.mapKeys { it.key.toString() },
      devicePniLastResortKyberPreKeys.mapKeys { it.key.toString() },
      pniRegistrationIds.mapKeys { it.key.toString() }
    )

    val metadata = PendingChangeNumberMetadata(
      previousPni = SignalStore.account().pni!!.toByteString(),
      pniIdentityKeyPair = pniIdentity.serialize().toByteString(),
      pniRegistrationId = pniRegistrationIds[primaryDeviceId]!!,
      pniSignedPreKeyId = devicePniSignedPreKeys[primaryDeviceId]!!.keyId,
      pniLastResortKyberPreKeyId = devicePniLastResortKyberPreKeys[primaryDeviceId]!!.keyId
    )

    return ChangeNumberRequestData(request, metadata)
  }

  fun verifyAccount(sessionId: String, code: String): Single<ServiceResponse<RegistrationSessionMetadataResponse>> {
    return Single.fromCallable {
      accountManager.verifyAccount(code, sessionId)
    }.subscribeOn(Schedulers.io())
  }

  data class ChangeNumberRequestData(val changeNumberRequest: ChangePhoneNumberRequest, val pendingChangeNumberMetadata: PendingChangeNumberMetadata)
}
