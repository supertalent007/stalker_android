/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.internal.svr

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.collections.immutable.persistentListOf
import org.stalker.securesms.BuildConfig
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.keyvalue.SignalStore
import org.whispersystems.signalservice.api.svr.SecureValueRecovery
import org.whispersystems.signalservice.api.svr.SecureValueRecoveryV3

class InternalSvrPlaygroundViewModel : ViewModel() {

  private val _state: MutableState<InternalSvrPlaygroundState> = mutableStateOf(
    InternalSvrPlaygroundState(
      options = persistentListOf(SvrImplementation.SVR2, SvrImplementation.SVR3)
    )
  )
  val state: State<InternalSvrPlaygroundState> = _state

  private val disposables: CompositeDisposable = CompositeDisposable()

  fun onTabSelected(svr: SvrImplementation) {
    _state.value = _state.value.copy(
      selected = svr,
      lastResult = null
    )
  }

  fun onPinChanged(pin: String) {
    _state.value = _state.value.copy(
      userPin = pin
    )
  }

  fun onCreateClicked() {
    _state.value = _state.value.copy(
      loading = true
    )

    disposables += Single
      .fromCallable {
        _state.value.selected.toImplementation()
          .setPin(_state.value.userPin, SignalStore.svr().getOrCreateMasterKey())
          .execute()
      }
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { response ->
        _state.value = _state.value.copy(
          loading = false,
          lastResult = "${response.javaClass.simpleName}\n\n$response"
        )
      }
  }

  fun onRestoreClicked() {
    _state.value = _state.value.copy(
      loading = true
    )

    disposables += Single.fromCallable { _state.value.selected.toImplementation().restoreDataPostRegistration(_state.value.userPin) }
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { response ->
        _state.value = _state.value.copy(
          loading = false,
          lastResult = "${response.javaClass.simpleName}\n\n$response"
        )
      }
  }

  fun onDeleteClicked() {
    _state.value = _state.value.copy(
      loading = true
    )

    disposables += Single.fromCallable { _state.value.selected.toImplementation().deleteData() }
      .subscribeOn(Schedulers.io())
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { response ->
        _state.value = _state.value.copy(
          loading = false,
          lastResult = "${response.javaClass.simpleName}\n\n$response"
        )
      }
  }

  override fun onCleared() {
    disposables.clear()
  }

  private fun SvrImplementation.toImplementation(): SecureValueRecovery {
    return when (this) {
      SvrImplementation.SVR2 -> ApplicationDependencies.getSignalServiceAccountManager().getSecureValueRecoveryV2(BuildConfig.SVR2_MRENCLAVE)
      SvrImplementation.SVR3 -> ApplicationDependencies.getSignalServiceAccountManager().getSecureValueRecoveryV3(ApplicationDependencies.getLibsignalNetwork().network, TestShareSetStorage())
    }
  }

  /**
   * Temporary implementation of share set storage. Only useful for testing.
   */
  private class TestShareSetStorage : SecureValueRecoveryV3.ShareSetStorage {
    private var shareSet: ByteArray? = null

    override fun write(data: ByteArray) {
      shareSet = data
    }

    override fun read(): ByteArray? {
      return shareSet
    }
  }
}
