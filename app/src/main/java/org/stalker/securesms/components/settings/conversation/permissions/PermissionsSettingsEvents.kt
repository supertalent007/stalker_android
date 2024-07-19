package org.stalker.securesms.components.settings.conversation.permissions

import org.stalker.securesms.groups.ui.GroupChangeFailureReason

sealed class PermissionsSettingsEvents {
  class GroupChangeError(val reason: GroupChangeFailureReason) : PermissionsSettingsEvents()
}
