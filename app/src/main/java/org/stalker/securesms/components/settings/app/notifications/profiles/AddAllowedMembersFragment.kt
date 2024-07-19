package org.stalker.securesms.components.settings.app.notifications.profiles

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.stalker.securesms.R
import org.stalker.securesms.components.settings.DSLConfiguration
import org.stalker.securesms.components.settings.DSLSettingsFragment
import org.stalker.securesms.components.settings.DSLSettingsIcon
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.app.notifications.profiles.models.NotificationProfileAddMembers
import org.stalker.securesms.components.settings.app.notifications.profiles.models.NotificationProfileRecipient
import org.stalker.securesms.components.settings.configure
import org.stalker.securesms.components.settings.conversation.preferences.RecipientPreference
import org.stalker.securesms.notifications.profiles.NotificationProfile
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.navigation.safeNavigate
import org.stalker.securesms.util.views.CircularProgressMaterialButton

/**
 * Show and allow addition of recipients to a profile during the create flow.
 */
class AddAllowedMembersFragment : DSLSettingsFragment(layoutId = R.layout.fragment_add_allowed_members) {

  private val viewModel: AddAllowedMembersViewModel by viewModels(factoryProducer = { AddAllowedMembersViewModel.Factory(profileId) })
  private val lifecycleDisposable = LifecycleDisposable()
  private val profileId: Long by lazy { AddAllowedMembersFragmentArgs.fromBundle(requireArguments()).profileId }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    lifecycleDisposable.bindTo(viewLifecycleOwner.lifecycle)

    view.findViewById<CircularProgressMaterialButton>(R.id.add_allowed_members_profile_next).apply {
      setOnClickListener {
        findNavController().safeNavigate(AddAllowedMembersFragmentDirections.actionAddAllowedMembersFragmentToEditNotificationProfileScheduleFragment(profileId, true))
      }
    }
  }

  override fun bindAdapter(adapter: MappingAdapter) {
    NotificationProfileAddMembers.register(adapter)
    NotificationProfileRecipient.register(adapter)

    lifecycleDisposable += viewModel.getProfile()
      .subscribeBy(
        onNext = { (profile, recipients) ->
          adapter.submitList(getConfiguration(profile, recipients).toMappingModelList())
        }
      )
  }

  private fun getConfiguration(profile: NotificationProfile, recipients: List<Recipient>): DSLConfiguration {
    return configure {
      sectionHeaderPref(R.string.AddAllowedMembers__allowed_notifications)

      customPref(
        NotificationProfileAddMembers.Model(
          onClick = { id, currentSelection ->
            findNavController().safeNavigate(
              AddAllowedMembersFragmentDirections.actionAddAllowedMembersFragmentToSelectRecipientsFragment(id)
                .setCurrentSelection(currentSelection.toTypedArray())
            )
          },
          profileId = profile.id,
          currentSelection = profile.allowedMembers
        )
      )

      for (member in recipients) {
        customPref(
          NotificationProfileRecipient.Model(
            recipientModel = RecipientPreference.Model(
              recipient = member,
              onClick = {}
            ),
            onRemoveClick = { id ->
              lifecycleDisposable += viewModel.removeMember(id)
                .subscribeBy(
                  onSuccess = { removed ->
                    view?.let { view ->
                      Snackbar.make(view, getString(R.string.NotificationProfileDetails__s_removed, removed.getDisplayName(requireContext())), Snackbar.LENGTH_LONG)
                        .setAction(R.string.NotificationProfileDetails__undo) { undoRemove(id) }
                        .show()
                    }
                  }
                )
            }
          )
        )
      }

      sectionHeaderPref(R.string.AddAllowedMembers__exceptions)

      switchPref(
        title = DSLSettingsText.from(R.string.AddAllowedMembers__allow_all_calls),
        icon = DSLSettingsIcon.from(R.drawable.symbol_phone_24),
        isChecked = profile.allowAllCalls,
        onClick = {
          lifecycleDisposable += viewModel.toggleAllowAllCalls()
            .subscribeBy(
              onError = { Log.w(TAG, "Error updating profile", it) }
            )
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.AddAllowedMembers__notify_for_all_mentions),
        icon = DSLSettingsIcon.from(R.drawable.symbol_at_24),
        isChecked = profile.allowAllMentions,
        onClick = {
          lifecycleDisposable += viewModel.toggleAllowAllMentions()
            .subscribeBy(
              onError = { Log.w(TAG, "Error updating profile", it) }
            )
        }
      )
    }
  }

  private fun undoRemove(id: RecipientId) {
    lifecycleDisposable += viewModel.addMember(id)
      .subscribe()
  }

  companion object {
    private val TAG = Log.tag(AddAllowedMembersFragment::class.java)
  }
}
