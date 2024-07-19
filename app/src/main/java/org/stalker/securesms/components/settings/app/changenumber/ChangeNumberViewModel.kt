package org.stalker.securesms.components.settings.app.changenumber

import android.app.Application
import androidx.annotation.WorkerThread
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.registration.RegistrationSessionProcessor
import org.stalker.securesms.registration.SmsRetrieverReceiver
import org.stalker.securesms.registration.VerifyAccountRepository
import org.stalker.securesms.registration.VerifyResponse
import org.stalker.securesms.registration.VerifyResponseProcessor
import org.stalker.securesms.registration.VerifyResponseWithRegistrationLockProcessor
import org.stalker.securesms.registration.VerifyResponseWithoutKbs
import org.stalker.securesms.registration.viewmodel.BaseRegistrationViewModel
import org.stalker.securesms.registration.viewmodel.NumberViewState
import org.stalker.securesms.registration.viewmodel.SvrAuthCredentialSet
import org.stalker.securesms.util.DefaultValueLiveData
import org.whispersystems.signalservice.api.push.ServiceId.PNI
import org.whispersystems.signalservice.api.push.exceptions.IncorrectCodeException
import org.whispersystems.signalservice.internal.ServiceResponse
import java.util.Objects

private val TAG: String = Log.tag(ChangeNumberViewModel::class.java)

class ChangeNumberViewModel(
  private val localNumber: String,
  private val changeNumberRepository: ChangeNumberRepository,
  savedState: SavedStateHandle,
  password: String,
  verifyAccountRepository: VerifyAccountRepository,
  private val smsRetrieverReceiver: SmsRetrieverReceiver = SmsRetrieverReceiver(ApplicationDependencies.getApplication())
) : BaseRegistrationViewModel(savedState, verifyAccountRepository, password) {

  var oldNumberState: NumberViewState = NumberViewState.Builder().build()
    private set

  private val liveOldNumberState = DefaultValueLiveData(oldNumberState)
  private val liveNewNumberState = DefaultValueLiveData(number)

  init {
    try {
      val countryCode: Int = PhoneNumberUtil.getInstance()
        .parse(localNumber, null)
        .countryCode

      setOldCountry(countryCode)
      setNewCountry(countryCode)
    } catch (e: NumberParseException) {
      Log.i(TAG, "Unable to parse number for default country code")
    }

    smsRetrieverReceiver.registerReceiver()
  }

  override fun onCleared() {
    super.onCleared()
    smsRetrieverReceiver.unregisterReceiver()
  }

  fun getLiveOldNumber(): LiveData<NumberViewState> {
    return liveOldNumberState
  }

  fun getLiveNewNumber(): LiveData<NumberViewState> {
    return liveNewNumberState
  }

  fun setOldNationalNumber(number: String) {
    oldNumberState = oldNumberState.toBuilder()
      .nationalNumber(number)
      .build()

    liveOldNumberState.value = oldNumberState
  }

  fun setOldCountry(countryCode: Int, country: String? = null) {
    oldNumberState = oldNumberState.toBuilder()
      .selectedCountryDisplayName(country)
      .countryCode(countryCode)
      .build()

    liveOldNumberState.value = oldNumberState
  }

  fun setNewNationalNumber(number: String) {
    setNationalNumber(number)

    liveNewNumberState.value = this.number
  }

  fun setNewCountry(countryCode: Int, country: String? = null) {
    onCountrySelected(country, countryCode)

    liveNewNumberState.value = this.number
  }

  fun canContinue(): ContinueStatus {
    return if (oldNumberState.e164Number == localNumber) {
      if (number.isValid) {
        ContinueStatus.CAN_CONTINUE
      } else {
        ContinueStatus.INVALID_NUMBER
      }
    } else {
      ContinueStatus.OLD_NUMBER_DOESNT_MATCH
    }
  }

  fun ensureDecryptionsDrained(): Completable {
    return changeNumberRepository.ensureDecryptionsDrained()
  }

  override fun verifyCodeWithoutRegistrationLock(code: String): Single<VerifyResponseProcessor> {
    return super.verifyCodeWithoutRegistrationLock(code)
      .compose(ChangeNumberRepository::acquireReleaseChangeNumberLock)
      .flatMap(this::attemptToUnlockChangeNumber)
  }

  override fun verifyCodeAndRegisterAccountWithRegistrationLock(pin: String): Single<VerifyResponseWithRegistrationLockProcessor> {
    return super.verifyCodeAndRegisterAccountWithRegistrationLock(pin)
      .compose(ChangeNumberRepository::acquireReleaseChangeNumberLock)
      .flatMap(this::attemptToUnlockChangeNumber)
  }

  private fun <T : VerifyResponseProcessor> attemptToUnlockChangeNumber(processor: T): Single<T> {
    return if (processor.hasResult() || processor.isServerSentError()) {
      SignalStore.misc().unlockChangeNumber()
      SignalStore.misc().clearPendingChangeNumberMetadata()
      Single.just(processor)
    } else {
      changeNumberRepository.whoAmI()
        .map { whoAmI ->
          if (Objects.equals(whoAmI.number, localNumber)) {
            Log.i(TAG, "Local and remote numbers match, we can unlock.")
            SignalStore.misc().unlockChangeNumber()
            SignalStore.misc().clearPendingChangeNumberMetadata()
          }
          processor
        }
        .onErrorReturn { processor }
    }
  }

  override fun verifyAccountWithoutRegistrationLock(): Single<ServiceResponse<VerifyResponse>> {
    val sessionId = sessionId ?: throw IllegalStateException("No valid registration session")

    return changeNumberRepository.verifyAccount(sessionId, textCodeEntered)
      .map { RegistrationSessionProcessor.RegistrationSessionProcessorForVerification(it) }
      .observeOn(AndroidSchedulers.mainThread())
      .doOnSuccess {
        if (it.hasResult()) {
          setCanSmsAtTime(it.getNextCodeViaSmsAttempt())
          setCanCallAtTime(it.getNextCodeViaCallAttempt())
        }
      }
      .observeOn(Schedulers.io())
      .flatMap { processor ->
        if (processor.isAlreadyVerified() || processor.hasResult() && processor.isVerified()) {
          changeNumberRepository.changeNumber(sessionId = sessionId, newE164 = number.e164Number)
        } else if (processor.error == null) {
          Single.just<ServiceResponse<VerifyResponse>>(ServiceResponse.forApplicationError(IncorrectCodeException(), 403, null))
        } else {
          Single.just<ServiceResponse<VerifyResponse>>(ServiceResponse.coerceError(processor.response))
        }
      }
  }

  override fun verifyAccountWithRegistrationLock(pin: String, svrAuthCredentials: SvrAuthCredentialSet): Single<ServiceResponse<VerifyResponse>> {
    val sessionId = sessionId ?: throw IllegalStateException("No valid registration session")
    return changeNumberRepository.changeNumber(sessionId, number.e164Number, pin, svrAuthCredentials)
  }

  @WorkerThread
  override fun onVerifySuccess(processor: VerifyResponseProcessor): Single<VerifyResponseProcessor> {
    return changeNumberRepository.changeLocalNumber(number.e164Number, PNI.parseOrThrow(processor.result.verifyAccountResponse.pni))
      .map { processor }
      .onErrorReturn { t ->
        Log.w(TAG, "Error attempting to change local number", t)
        VerifyResponseWithoutKbs(ServiceResponse.forUnknownError(t))
      }
  }

  override fun onVerifySuccessWithRegistrationLock(processor: VerifyResponseWithRegistrationLockProcessor, pin: String): Single<VerifyResponseWithRegistrationLockProcessor> {
    return changeNumberRepository.changeLocalNumber(number.e164Number, PNI.parseOrThrow(processor.result.verifyAccountResponse.pni))
      .map { processor }
      .onErrorReturn { t ->
        Log.w(TAG, "Error attempting to change local number", t)
        VerifyResponseWithRegistrationLockProcessor(ServiceResponse.forUnknownError(t), processor.svrAuthCredentials)
      }
  }

  fun changeNumberWithRecoveryPassword(): Single<Boolean> {
    val recoveryPassword = SignalStore.svr().recoveryPassword

    return if (SignalStore.svr().hasPin() && recoveryPassword != null) {
      changeNumberRepository.changeNumber(recoveryPassword = recoveryPassword, newE164 = number.e164Number)
        .map { r -> VerifyResponseWithoutKbs(r) }
        .flatMap { p ->
          if (p.hasResult()) {
            onVerifySuccess(p).map { true }
          } else {
            Single.just(false)
          }
        }
    } else {
      Single.just(false)
    }
  }

  class Factory(owner: SavedStateRegistryOwner) : AbstractSavedStateViewModelFactory(owner, null) {

    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
      val context: Application = ApplicationDependencies.getApplication()
      val localNumber: String = SignalStore.account().e164!!
      val password: String = SignalStore.account().servicePassword!!

      val viewModel = ChangeNumberViewModel(
        localNumber = localNumber,
        changeNumberRepository = ChangeNumberRepository(),
        savedState = handle,
        password = password,
        verifyAccountRepository = VerifyAccountRepository(context)
      )

      return requireNotNull(modelClass.cast(viewModel))
    }
  }

  enum class ContinueStatus {
    CAN_CONTINUE,
    INVALID_NUMBER,
    OLD_NUMBER_DOESNT_MATCH
  }
}
