package org.stalker.securesms.components.settings.app.wrapped

import androidx.fragment.app.Fragment
import org.stalker.securesms.R
import org.stalker.securesms.help.HelpFragment

class WrappedHelpFragment : SettingsWrapperFragment() {
  override fun getFragment(): Fragment {
    toolbar.title = getString(R.string.preferences__help)

    val fragment = HelpFragment()
    fragment.arguments = arguments

    return fragment
  }
}
