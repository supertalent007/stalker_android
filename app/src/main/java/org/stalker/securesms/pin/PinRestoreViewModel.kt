package org.stalker.securesms.pin

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
import org.stalker.securesms.lock.v2.PinKeyboardType
import org.stalker.securesms.lock.v2.SvrConstants
import org.stalker.securesms.util.DefaultValueLiveData
import org.stalker.securesms.util.SingleLiveEvent
import org.whispersystems.signalservice.api.svr.SecureValueRecovery

class PinRestoreViewModel : ViewModel() {
  private val repo: SvrRepository = SvrRepository

  @JvmField
  val triesRemaining: DefaultValueLiveData<TriesRemaining> = DefaultValueLiveData(TriesRemaining(10, false))

  private val event: SingleLiveEvent<Event> = SingleLiveEvent()

  private val disposables = CompositeDisposable()

  fun onPinSubmitted(pin: String, pinKeyboardType: PinKeyboardType) {
    val trimmedLength = pin.trim().length
    if (trimmedLength == 0) {
      event.postValue(Event.EMPTY_PIN)
      return
    }
    if (trimmedLength < SvrConstants.MINIMUM_PIN_LENGTH) {
      event.postValue(Event.PIN_TOO_SHORT)
      return
    }

    disposables += Single
      .fromCallable { repo.restoreMasterKeyPostRegistration(pin, pinKeyboardType) }
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { result ->
        when (result) {
          is SecureValueRecovery.RestoreResponse.Success -> {
            event.postValue(Event.SUCCESS)
          }
          is SecureValueRecovery.RestoreResponse.PinMismatch -> {
            event.postValue(Event.PIN_INCORRECT)
            triesRemaining.postValue(TriesRemaining(result.triesRemaining, true))
          }
          SecureValueRecovery.RestoreResponse.Missing -> {
            event.postValue(Event.PIN_LOCKED)
          }
          is SecureValueRecovery.RestoreResponse.NetworkError -> {
            event.postValue(Event.NETWORK_ERROR)
          }
          is SecureValueRecovery.RestoreResponse.ApplicationError -> {
            event.postValue(Event.NETWORK_ERROR)
          }
        }
      }
  }

  fun getEvent(): LiveData<Event> {
    return event
  }

  enum class Event {
    SUCCESS,
    EMPTY_PIN,
    PIN_TOO_SHORT,
    PIN_INCORRECT,
    PIN_LOCKED,
    NETWORK_ERROR
  }

  class TriesRemaining(val count: Int, private val hasIncorrectGuess: Boolean) {
    fun hasIncorrectGuess(): Boolean {
      return hasIncorrectGuess
    }
  }
}
