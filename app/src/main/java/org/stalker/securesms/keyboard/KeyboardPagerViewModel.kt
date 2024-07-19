package org.stalker.securesms.keyboard

import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import org.signal.core.util.ThreadUtil
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.stickers.StickerSearchRepository
import org.stalker.securesms.util.DefaultValueLiveData
import org.stalker.securesms.util.FeatureFlags

class KeyboardPagerViewModel : ViewModel() {

  private val page: DefaultValueLiveData<KeyboardPage>
  private val pages: DefaultValueLiveData<Set<KeyboardPage>>

  init {
    val startingPages: MutableSet<KeyboardPage> = KeyboardPage.values().toMutableSet()
    if (SignalStore.settings().isPreferSystemEmoji) {
      startingPages.remove(KeyboardPage.EMOJI)
    }

    if (!FeatureFlags.gifSearchAvailable()) {
      startingPages.remove(KeyboardPage.GIF)
    }

    pages = DefaultValueLiveData(startingPages)
    page = DefaultValueLiveData(startingPages.first())

    StickerSearchRepository().getStickerFeatureAvailability { available ->
      if (!available) {
        val updatedPages = pages.value.toMutableSet().apply { remove(KeyboardPage.STICKER) }
        pages.postValue(updatedPages)
        if (page.value == KeyboardPage.STICKER) {
          switchToPage(KeyboardPage.GIF)
          switchToPage(KeyboardPage.EMOJI)
        }
      }
    }
  }

  fun page(): LiveData<KeyboardPage> = page
  fun pages(): LiveData<Set<KeyboardPage>> = pages

  fun setPages(pageOverride: Set<KeyboardPage>) {
    pages.value = pageOverride
  }

  @MainThread
  fun setOnlyPage(page: KeyboardPage) {
    pages.value = setOf(page)
    switchToPage(page)
  }

  fun switchToPage(page: KeyboardPage) {
    if (this.pages.value.contains(page) && this.page.value != page) {
      if (ThreadUtil.isMainThread()) {
        this.page.value = page
      } else {
        this.page.postValue(page)
      }
    }
  }
}
