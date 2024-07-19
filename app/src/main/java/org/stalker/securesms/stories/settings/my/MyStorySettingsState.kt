package org.stalker.securesms.stories.settings.my

data class MyStorySettingsState(
  val myStoryPrivacyState: MyStoryPrivacyState = MyStoryPrivacyState(),
  val areRepliesAndReactionsEnabled: Boolean = false,
  val allSignalConnectionsCount: Int = 0,
  val hasUserPerformedManualSelection: Boolean
)
