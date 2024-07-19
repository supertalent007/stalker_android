/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.conversation.v2

import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import org.stalker.securesms.database.model.StickerRecord
import org.stalker.securesms.stickers.StickerSearchRepository

class StickerSuggestionsViewModel(
  private val stickerSearchRepository: StickerSearchRepository = StickerSearchRepository()
) : ViewModel() {

  private val stickerSearchProcessor = BehaviorProcessor.createDefault("")

  val stickers: Flowable<List<StickerRecord>> = stickerSearchProcessor
    .switchMapSingle { stickerSearchRepository.searchByEmoji(it) }

  fun onInputTextUpdated(text: String) {
    stickerSearchProcessor.onNext(text)
  }
}
