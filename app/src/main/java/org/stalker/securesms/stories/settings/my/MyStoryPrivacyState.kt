package org.stalker.securesms.stories.settings.my

import org.stalker.securesms.database.model.DistributionListPrivacyMode

data class MyStoryPrivacyState(val privacyMode: DistributionListPrivacyMode? = null, val connectionCount: Int = 0)
