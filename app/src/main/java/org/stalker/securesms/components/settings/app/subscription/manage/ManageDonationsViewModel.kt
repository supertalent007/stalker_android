package org.stalker.securesms.components.settings.app.subscription.manage

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.signal.core.util.orNull
import org.stalker.securesms.components.settings.app.subscription.MonthlyDonationRepository
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.subscription.LevelUpdate
import org.stalker.securesms.util.InternetConnectionObserver
import org.stalker.securesms.util.livedata.Store
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription
import java.util.Optional

class ManageDonationsViewModel(
  private val subscriptionsRepository: MonthlyDonationRepository
) : ViewModel() {

  private val store = Store(ManageDonationsState())
  private val disposables = CompositeDisposable()
  private val networkDisposable: Disposable

  val state: LiveData<ManageDonationsState> = store.stateLiveData

  init {
    store.update(Recipient.self().live().liveDataResolved) { self, state ->
      state.copy(featuredBadge = self.featuredBadge)
    }

    networkDisposable = InternetConnectionObserver
      .observe()
      .distinctUntilChanged()
      .subscribe { isConnected ->
        if (isConnected) {
          retry()
        }
      }
  }

  override fun onCleared() {
    disposables.clear()
  }

  fun retry() {
    if (!disposables.isDisposed && store.state.subscriptionTransactionState == ManageDonationsState.TransactionState.NetworkFailure) {
      store.update { it.copy(subscriptionTransactionState = ManageDonationsState.TransactionState.Init) }
      refresh()
    }
  }

  fun refresh() {
    disposables.clear()

    val levelUpdateOperationEdges: Observable<Boolean> = LevelUpdate.isProcessing.distinctUntilChanged()
    val activeSubscription: Single<ActiveSubscription> = subscriptionsRepository.getActiveSubscription()

    disposables += Recipient.observable(Recipient.self().id).map { it.badges }.subscribeBy { badges ->
      store.update { state ->
        state.copy(
          hasOneTimeBadge = badges.any { it.isBoost() }
        )
      }
    }

    disposables += Single.fromCallable { SignalDatabase.donationReceipts.hasReceipts() }.subscribeOn(Schedulers.io()).subscribe { hasReceipts ->
      store.update { it.copy(hasReceipts = hasReceipts) }
    }

    disposables += DonationRedemptionJobWatcher.watchSubscriptionRedemption().subscribeBy { redemptionStatus ->
      store.update { manageDonationsState ->
        manageDonationsState.copy(
          nonVerifiedMonthlyDonation = if (redemptionStatus is DonationRedemptionJobStatus.PendingExternalVerification) redemptionStatus.nonVerifiedMonthlyDonation else null,
          subscriptionRedemptionState = mapStatusToRedemptionState(redemptionStatus)
        )
      }
    }

    disposables += Observable.combineLatest(
      SignalStore.donationsValues().observablePendingOneTimeDonation,
      DonationRedemptionJobWatcher.watchOneTimeRedemption()
    ) { pendingFromStore, pendingFromJob ->
      if (pendingFromStore.isPresent) {
        pendingFromStore
      } else if (pendingFromJob is DonationRedemptionJobStatus.PendingExternalVerification) {
        Optional.ofNullable(pendingFromJob.pendingOneTimeDonation)
      } else {
        Optional.empty()
      }
    }
      .distinctUntilChanged()
      .subscribeBy { pending ->
        store.update { it.copy(pendingOneTimeDonation = pending.orNull()) }
      }

    disposables += levelUpdateOperationEdges.switchMapSingle { isProcessing ->
      if (isProcessing) {
        Single.just(ManageDonationsState.TransactionState.InTransaction)
      } else {
        activeSubscription.map { ManageDonationsState.TransactionState.NotInTransaction(it) }
      }
    }.subscribeBy(
      onNext = { transactionState ->
        store.update {
          it.copy(subscriptionTransactionState = transactionState)
        }
      },
      onError = { throwable ->
        Log.w(TAG, "Error retrieving subscription transaction state", throwable)

        store.update {
          it.copy(subscriptionTransactionState = ManageDonationsState.TransactionState.NetworkFailure)
        }
      }
    )

    disposables += subscriptionsRepository.getSubscriptions().subscribeBy(
      onSuccess = { subs ->
        store.update { it.copy(availableSubscriptions = subs) }
      },
      onError = {
        Log.w(TAG, "Error retrieving subscriptions data", it)
      }
    )
  }

  private fun mapStatusToRedemptionState(status: DonationRedemptionJobStatus): ManageDonationsState.RedemptionState {
    return when (status) {
      DonationRedemptionJobStatus.FailedSubscription -> ManageDonationsState.RedemptionState.FAILED
      DonationRedemptionJobStatus.None -> ManageDonationsState.RedemptionState.NONE

      is DonationRedemptionJobStatus.PendingExternalVerification,
      DonationRedemptionJobStatus.PendingReceiptRedemption,
      DonationRedemptionJobStatus.PendingReceiptRequest -> ManageDonationsState.RedemptionState.IN_PROGRESS
    }
  }

  class Factory(
    private val subscriptionsRepository: MonthlyDonationRepository
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(ManageDonationsViewModel(subscriptionsRepository))!!
    }
  }

  companion object {
    private val TAG = Log.tag(ManageDonationsViewModel::class.java)
  }
}
