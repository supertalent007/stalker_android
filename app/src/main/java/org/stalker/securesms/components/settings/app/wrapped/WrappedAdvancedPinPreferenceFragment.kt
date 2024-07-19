package org.stalker.securesms.components.settings.app.wrapped

import androidx.fragment.app.Fragment
import org.stalker.securesms.R
import org.stalker.securesms.preferences.AdvancedPinPreferenceFragment

class WrappedAdvancedPinPreferenceFragment : SettingsWrapperFragment() {
  override fun getFragment(): Fragment {
    toolbar.setTitle(R.string.preferences__advanced_pin_settings)
    return AdvancedPinPreferenceFragment()
  }
}
