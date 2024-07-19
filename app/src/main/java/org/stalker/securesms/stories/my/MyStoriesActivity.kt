package org.stalker.securesms.stories.my

import androidx.fragment.app.Fragment
import org.stalker.securesms.components.FragmentWrapperActivity

class MyStoriesActivity : FragmentWrapperActivity() {
  override fun getFragment(): Fragment {
    return MyStoriesFragment()
  }
}
