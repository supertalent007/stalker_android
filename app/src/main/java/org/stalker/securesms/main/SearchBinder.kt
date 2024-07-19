package org.stalker.securesms.main

import android.widget.ImageView
import org.stalker.securesms.components.Material3SearchToolbar
import org.stalker.securesms.util.views.Stub

interface SearchBinder {
  fun getSearchAction(): ImageView

  fun getSearchToolbar(): Stub<Material3SearchToolbar>

  fun onSearchOpened()

  fun onSearchClosed()
}
