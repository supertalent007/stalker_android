/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.internal

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.money.FiatMoney
import org.stalker.securesms.badges.Badges
import org.stalker.securesms.components.settings.app.subscription.DonationSerializationHelper.toFiatValue
import org.stalker.securesms.database.model.databaseprotos.PendingOneTimeDonation
import org.stalker.securesms.dependencies.ApplicationDependencies
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

/**
 * Fetches a badge for our pending donation, which requires downloading the donation config.
 */
class InternalPendingOneTimeDonationConfigurationViewModel : ViewModel() {

  val state: MutableState<PendingOneTimeDonation> = mutableStateOf(
    PendingOneTimeDonation(
      timestamp = System.currentTimeMillis(),
      amount = FiatMoney(BigDecimal.valueOf(20), Currency.getInstance("EUR")).toFiatValue()
    )
  )

  val disposable: Disposable = Single
    .fromCallable {
      ApplicationDependencies.getDonationsService()
        .getDonationsConfiguration(Locale.getDefault())
    }
    .flatMap { it.flattenResult() }
    .subscribeOn(Schedulers.io())
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe { config ->
      val badge = Badges.fromServiceBadge(config.levels.values.first().badge)
      state.value = state.value.copy(badge = Badges.toDatabaseBadge(badge))
    }

  override fun onCleared() {
    super.onCleared()
  }
}
