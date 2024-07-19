package org.stalker.securesms.components.settings.conversation

import org.stalker.securesms.util.DynamicNoActionBarTheme
import org.stalker.securesms.util.DynamicTheme

class CallInfoActivity : ConversationSettingsActivity(), ConversationSettingsFragment.Callback {

  override val dynamicTheme: DynamicTheme = DynamicNoActionBarTheme()
}
