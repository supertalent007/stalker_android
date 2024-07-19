package org.stalker.securesms.components.settings.app.wrapped

import androidx.fragment.app.Fragment
import org.stalker.securesms.R
import org.stalker.securesms.delete.DeleteAccountFragment

class WrappedDeleteAccountFragment : SettingsWrapperFragment() {
  override fun getFragment(): Fragment {
    toolbar.setTitle(R.string.preferences__delete_account)
    return DeleteAccountFragment()
  }
}
