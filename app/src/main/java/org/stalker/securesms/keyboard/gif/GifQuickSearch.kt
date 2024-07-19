package org.stalker.securesms.keyboard.gif

import org.stalker.securesms.util.adapter.mapping.MappingModel

data class GifQuickSearch(val gifQuickSearchOption: GifQuickSearchOption, val selected: Boolean) : MappingModel<GifQuickSearch> {
  override fun areItemsTheSame(newItem: GifQuickSearch): Boolean {
    return gifQuickSearchOption == newItem.gifQuickSearchOption
  }

  override fun areContentsTheSame(newItem: GifQuickSearch): Boolean {
    return selected == newItem.selected
  }
}
