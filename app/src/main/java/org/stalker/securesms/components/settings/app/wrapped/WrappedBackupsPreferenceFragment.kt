package org.stalker.securesms.components.settings.app.wrapped

import androidx.fragment.app.Fragment
import org.stalker.securesms.R
import org.stalker.securesms.preferences.BackupsPreferenceFragment

class WrappedBackupsPreferenceFragment : SettingsWrapperFragment() {
  override fun getFragment(): Fragment {
    toolbar.setTitle(R.string.BackupsPreferenceFragment__chat_backups)
    return BackupsPreferenceFragment()
  }
}
