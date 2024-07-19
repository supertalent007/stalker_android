package org.stalker.securesms.stories.viewer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.stalker.securesms.database.model.MmsMessageRecord
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.stories.Stories
import org.stalker.securesms.stories.StoryViewerArgs
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.rx.RxStore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class StoryViewerViewModel(
  private val storyViewerArgs: StoryViewerArgs,
  private val repository: StoryViewerRepository
) : ViewModel() {

  private val store = RxStore(
    StoryViewerState(
      crossfadeSource = when {
        storyViewerArgs.storyThumbTextModel != null -> StoryViewerState.CrossfadeSource.TextModel(storyViewerArgs.storyThumbTextModel)
        storyViewerArgs.storyThumbUri != null -> StoryViewerState.CrossfadeSource.ImageUri(storyViewerArgs.storyThumbUri, storyViewerArgs.storyThumbBlur)
        else -> StoryViewerState.CrossfadeSource.None
      },
      skipCrossfade = storyViewerArgs.isFromNotification || storyViewerArgs.isFromQuote
    )
  )

  private val loadStore = RxStore(StoryLoadState())

  private val disposables = CompositeDisposable()

  val stateSnapshot: StoryViewerState get() = store.state
  val state: Flowable<StoryViewerState> = store.stateFlowable
  val loadState: Flowable<StoryLoadState> = loadStore.stateFlowable.distinctUntilChanged().observeOn(AndroidSchedulers.mainThread())

  private val hidden = mutableSetOf<RecipientId>()

  private val scrollStatePublisher: MutableLiveData<Boolean> = MutableLiveData(false)
  val isScrolling: LiveData<Boolean> = scrollStatePublisher

  private val childScrollStatePublisher: BehaviorSubject<Boolean> = BehaviorSubject.createDefault(false)
  val allowParentScrolling: Observable<Boolean> = Observable.combineLatest(
    childScrollStatePublisher.distinctUntilChanged(),
    loadState.toObservable().map { it.isReady() }.distinctUntilChanged()
  ) { a, b -> !a && b }

  var hasConsumedInitialState = false
    private set

  private val firstTimeNavigationPublisher: BehaviorSubject<Boolean> = BehaviorSubject.createDefault(false)

  val isChildScrolling: Observable<Boolean> = childScrollStatePublisher.distinctUntilChanged()
  val isFirstTimeNavigationShowing: Observable<Boolean> = firstTimeNavigationPublisher.distinctUntilChanged()

  /**
   * Post an action *after* the story load state is ready.
   * A slight delay is applied here to ensure that animations settle
   * before the action takes place. Otherwise, some strange windowing
   * problems can occur.
   */
  fun postAfterLoadStateReady(
    delay: Duration = 100.milliseconds,
    action: () -> Unit
  ): Disposable {
    return loadState
      .filter { it.isReady() }
      .delay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      .firstOrError()
      .ignoreElement()
      .observeOn(AndroidSchedulers.mainThread())
      .subscribeBy { action() }
  }

  fun addHiddenAndRefresh(hidden: Set<RecipientId>) {
    this.hidden.addAll(hidden)
    refresh()
  }

  fun setIsDisplayingFirstTimeNavigation(isDisplayingFirstTimeNavigation: Boolean) {
    firstTimeNavigationPublisher.onNext(isDisplayingFirstTimeNavigation)
  }

  fun getHidden(): Set<RecipientId> = hidden

  fun setCrossfadeTarget(messageRecord: MmsMessageRecord) {
    store.update {
      it.copy(crossfadeTarget = StoryViewerState.CrossfadeTarget.Record(messageRecord))
    }
  }

  fun consumeInitialState() {
    hasConsumedInitialState = true
  }

  fun setContentIsReady() {
    loadStore.update {
      it.copy(isContentReady = true)
    }
  }

  fun setCrossfaderIsReady(isReady: Boolean) {
    loadStore.update {
      it.copy(isCrossfaderReady = isReady)
    }
  }

  fun setIsScrolling(isScrolling: Boolean) {
    scrollStatePublisher.value = isScrolling
  }

  private fun getStories(): Single<List<RecipientId>> {
    return if (storyViewerArgs.recipientIds.isNotEmpty()) {
      Single.just(storyViewerArgs.recipientIds - hidden)
    } else {
      repository.getStories(
        hiddenStories = storyViewerArgs.isInHiddenStoryMode,
        isOutgoingOnly = storyViewerArgs.isFromMyStories
      )
    }
  }

  fun refresh() {
    disposables.clear()
    disposables += repository.getFirstStory(storyViewerArgs.recipientId, storyViewerArgs.storyId).subscribe { record ->
      store.update {
        it.copy(
          crossfadeTarget = StoryViewerState.CrossfadeTarget.Record(record)
        )
      }
    }
    disposables += getStories().subscribe { recipientIds ->
      store.update {
        val page: Int = if (it.pages.isNotEmpty()) {
          val oldPage = it.page
          val oldRecipient = it.pages[oldPage]

          val newPage = recipientIds.indexOf(oldRecipient)
          if (newPage == -1) {
            it.page
          } else {
            newPage
          }
        } else {
          it.page
        }
        updatePages(it.copy(pages = recipientIds), page).copy(noPosts = recipientIds.isEmpty())
      }
    }
    disposables += state
      .map {
        if ((it.page + 1) in it.pages.indices) {
          it.pages[it.page + 1]
        } else {
          RecipientId.UNKNOWN
        }
      }
      .filter { it != RecipientId.UNKNOWN }
      .distinctUntilChanged()
      .subscribe {
        Stories.enqueueNextStoriesForDownload(it, true, FeatureFlags.storiesAutoDownloadMaximum())
      }
  }

  override fun onCleared() {
    disposables.clear()
    store.dispose()
    loadStore.dispose()
  }

  fun setSelectedPage(page: Int) {
    store.update {
      updatePages(it, page)
    }
  }

  fun onGoToNext(recipientId: RecipientId) {
    store.update {
      if (it.page in it.pages.indices && it.pages[it.page] == recipientId) {
        updatePages(it, it.page + 1)
      } else {
        it
      }
    }
  }

  fun onGoToPrevious(recipientId: RecipientId) {
    store.update {
      if (it.page in it.pages.indices && it.pages[it.page] == recipientId) {
        updatePages(it, max(0, it.page - 1))
      } else {
        it
      }
    }
  }

  private fun updatePages(state: StoryViewerState, page: Int): StoryViewerState {
    val newPage = resolvePage(page, state.pages)
    val prevPage = if (newPage == state.page) {
      state.previousPage
    } else {
      state.page
    }

    return state.copy(
      page = newPage,
      previousPage = prevPage
    )
  }

  private fun resolvePage(page: Int, recipientIds: List<RecipientId>): Int {
    return if (page > -1) {
      page
    } else {
      val indexOfStartRecipient = recipientIds.indexOf(storyViewerArgs.recipientId)
      if (indexOfStartRecipient == -1) {
        0
      } else {
        indexOfStartRecipient
      }
    }
  }

  fun setIsChildScrolling(isChildScrolling: Boolean) {
    childScrollStatePublisher.onNext(isChildScrolling)
  }

  class Factory(
    private val storyViewerArgs: StoryViewerArgs,
    private val repository: StoryViewerRepository
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(
        StoryViewerViewModel(
          storyViewerArgs,
          repository
        )
      ) as T
    }
  }
}
