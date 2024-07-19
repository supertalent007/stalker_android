package org.stalker.securesms.components.settings.conversation.sounds

import androidx.fragment.app.viewModels
import androidx.navigation.Navigation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.stalker.securesms.MuteDialog
import org.stalker.securesms.R
import org.stalker.securesms.components.settings.DSLConfiguration
import org.stalker.securesms.components.settings.DSLSettingsFragment
import org.stalker.securesms.components.settings.DSLSettingsIcon
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.configure
import org.stalker.securesms.components.settings.conversation.preferences.Utils.formatMutedUntil
import org.stalker.securesms.database.RecipientTable
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.navigation.safeNavigate

class SoundsAndNotificationsSettingsFragment : DSLSettingsFragment(
  titleId = R.string.ConversationSettingsFragment__sounds_and_notifications
) {

  private val mentionLabels: Array<String> by lazy {
    resources.getStringArray(R.array.SoundsAndNotificationsSettingsFragment__mention_labels)
  }

  private val viewModel: SoundsAndNotificationsSettingsViewModel by viewModels(
    factoryProducer = {
      val recipientId = SoundsAndNotificationsSettingsFragmentArgs.fromBundle(requireArguments()).recipientId
      val repository = SoundsAndNotificationsSettingsRepository(requireContext())

      SoundsAndNotificationsSettingsViewModel.Factory(recipientId, repository)
    }
  )

  override fun onResume() {
    super.onResume()
    viewModel.channelConsistencyCheck()
  }

  override fun bindAdapter(adapter: MappingAdapter) {
    viewModel.state.observe(viewLifecycleOwner) { state ->
      if (state.channelConsistencyCheckComplete && state.recipientId != Recipient.UNKNOWN.id) {
        adapter.submitList(getConfiguration(state).toMappingModelList())
      }
    }
  }

  private fun getConfiguration(state: SoundsAndNotificationsSettingsState): DSLConfiguration {
    return configure {
      val muteSummary = if (state.muteUntil > 0) {
        state.muteUntil.formatMutedUntil(requireContext())
      } else {
        getString(R.string.SoundsAndNotificationsSettingsFragment__not_muted)
      }

      val muteIcon = if (state.muteUntil > 0) {
        R.drawable.ic_bell_disabled_24
      } else {
        R.drawable.ic_bell_24
      }

      clickPref(
        title = DSLSettingsText.from(R.string.SoundsAndNotificationsSettingsFragment__mute_notifications),
        icon = DSLSettingsIcon.from(muteIcon),
        summary = DSLSettingsText.from(muteSummary),
        onClick = {
          if (state.muteUntil <= 0) {
            MuteDialog.show(requireContext(), viewModel::setMuteUntil)
          } else {
            MaterialAlertDialogBuilder(requireContext())
              .setMessage(muteSummary)
              .setPositiveButton(R.string.ConversationSettingsFragment__unmute) { dialog, _ ->
                viewModel.unmute()
                dialog.dismiss()
              }
              .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
              .show()
          }
        }
      )

      if (state.hasMentionsSupport) {
        val mentionSelection = if (state.mentionSetting == RecipientTable.MentionSetting.ALWAYS_NOTIFY) {
          0
        } else {
          1
        }

        radioListPref(
          title = DSLSettingsText.from(R.string.SoundsAndNotificationsSettingsFragment__mentions),
          icon = DSLSettingsIcon.from(R.drawable.ic_at_24),
          selected = mentionSelection,
          listItems = mentionLabels,
          onSelected = {
            viewModel.setMentionSetting(
              if (it == 0) {
                RecipientTable.MentionSetting.ALWAYS_NOTIFY
              } else {
                RecipientTable.MentionSetting.DO_NOT_NOTIFY
              }
            )
          }
        )
      }

      val customSoundSummary = if (state.hasCustomNotificationSettings) {
        R.string.preferences_on
      } else {
        R.string.preferences_off
      }

      clickPref(
        title = DSLSettingsText.from(R.string.SoundsAndNotificationsSettingsFragment__custom_notifications),
        icon = DSLSettingsIcon.from(R.drawable.ic_speaker_24),
        summary = DSLSettingsText.from(customSoundSummary),
        onClick = {
          val action = SoundsAndNotificationsSettingsFragmentDirections.actionSoundsAndNotificationsSettingsFragmentToCustomNotificationsSettingsFragment(state.recipientId)
          Navigation.findNavController(requireView()).safeNavigate(action)
        }
      )
    }
  }
}
