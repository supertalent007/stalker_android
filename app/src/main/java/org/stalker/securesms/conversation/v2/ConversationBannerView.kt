/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.conversation.v2

import android.content.Context
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.transition.addListener
import org.stalker.securesms.R
import org.stalker.securesms.components.identity.UnverifiedBannerView
import org.stalker.securesms.components.reminder.Reminder
import org.stalker.securesms.components.reminder.ReminderView
import org.stalker.securesms.components.voice.VoiceNotePlayerView
import org.stalker.securesms.database.identity.IdentityRecordList
import org.stalker.securesms.database.model.IdentityRecord
import org.stalker.securesms.groups.GroupId
import org.stalker.securesms.profiles.spoofing.ReviewBannerView
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.IdentityUtil
import org.stalker.securesms.util.ViewUtil
import org.stalker.securesms.util.views.Stub
import org.stalker.securesms.util.visible

/**
 * Responsible for showing the various "banner" views at the top of a conversation
 *
 * - Expired Build
 * - Unregistered
 * - Group join requests
 * - GroupV1 suggestions
 * - Disable Chat Bubbles setting
 * - Service outage
 */
class ConversationBannerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {
  private val unverifiedBannerStub: Stub<UnverifiedBannerView> by lazy { ViewUtil.findStubById(this, R.id.unverified_banner_stub) }
  private val reminderStub: Stub<ReminderView> by lazy { ViewUtil.findStubById(this, R.id.reminder_stub) }
  private val reviewBannerStub: Stub<ReviewBannerView> by lazy { ViewUtil.findStubById(this, R.id.review_banner_stub) }
  private val voiceNotePlayerStub: Stub<View> by lazy { ViewUtil.findStubById(this, R.id.voice_note_player_stub) }

  var listener: Listener? = null

  init {
    orientation = VERTICAL
  }

  fun showReminder(reminder: Reminder) {
    show(
      stub = reminderStub
    ) {
      showReminder(reminder)
      setOnActionClickListener {
        when (it) {
          R.id.reminder_action_update_now -> listener?.updateAppAction()
          R.id.reminder_action_re_register -> listener?.reRegisterAction()
          R.id.reminder_action_review_join_requests -> listener?.reviewJoinRequestsAction()
          R.id.reminder_action_gv1_suggestion_no_thanks -> listener?.gv1SuggestionsAction(it)
          R.id.reminder_action_bubble_not_now, R.id.reminder_action_bubble_turn_off -> {
            listener?.changeBubbleSettingAction(disableSetting = it == R.id.reminder_action_bubble_turn_off)
          }
        }
      }
      setOnHideListener {
        clearReminder()
        true
      }
    }
  }

  fun clearReminder() {
    hide(reminderStub)
  }

  fun showUnverifiedBanner(identityRecords: IdentityRecordList) {
    show(
      stub = unverifiedBannerStub
    ) {
      setOnHideListener {
        clearUnverifiedBanner()
        true
      }
      display(
        IdentityUtil.getUnverifiedBannerDescription(context, identityRecords.unverifiedRecipients)!!,
        identityRecords.unverifiedRecords,
        { listener?.onUnverifiedBannerClicked(identityRecords.unverifiedRecords) },
        { listener?.onUnverifiedBannerDismissed(identityRecords.unverifiedRecords) }
      )
    }
  }

  fun clearUnverifiedBanner() {
    hide(unverifiedBannerStub)
  }

  fun showReviewBanner(requestReviewState: RequestReviewState) {
    show(
      stub = reviewBannerStub
    ) {
      if (requestReviewState.individualReviewState != null) {
        setBannerMessage(context.getString(R.string.ConversationFragment__review_banner_body))
        setBannerRecipients(requestReviewState.individualReviewState.target, requestReviewState.individualReviewState.firstDuplicate)
        setOnClickListener { listener?.onRequestReviewIndividual(requestReviewState.individualReviewState.target.id) }
      } else if (requestReviewState.groupReviewState != null) {
        setBannerMessage(context.getString(R.string.ConversationFragment__d_group_members_have_the_same_name, requestReviewState.groupReviewState.count))
        setBannerRecipients(requestReviewState.groupReviewState.target, requestReviewState.groupReviewState.firstDuplicate)
        setOnClickListener { listener?.onReviewGroupMembers(requestReviewState.groupReviewState.groupId) }
      }

      setOnHideListener {
        clearRequestReview()
        listener?.onDismissReview()
        true
      }
    }
  }

  fun clearRequestReview() {
    hide(reviewBannerStub)
  }

  fun showVoiceNotePlayer(state: VoiceNotePlayerView.State, voiceNotePlayerViewListener: VoiceNotePlayerView.Listener) {
    show(
      stub = voiceNotePlayerStub
    ) {
      val playerView: VoiceNotePlayerView = findViewById(R.id.voice_note_player_view)
      playerView.listener = voiceNotePlayerViewListener
      playerView.setState(state)
    }
  }

  fun clearVoiceNotePlayer() {
    hide(voiceNotePlayerStub)
  }

  private fun <V : View> show(stub: Stub<V>, bind: V.() -> Unit = {}) {
    TransitionManager.beginDelayedTransition(this, Slide(Gravity.TOP))
    stub.get().bind()
    stub.get().visible = true
  }

  private fun hide(stub: Stub<*>) {
    if (!stub.isVisible) {
      return
    }

    val slideTransition = Slide(Gravity.TOP)
    val changeTransition = ChangeBounds().apply {
      if (reminderStub.isVisible) {
        addTarget(reminderStub.get())
      }

      if (unverifiedBannerStub.isVisible) {
        addTarget(unverifiedBannerStub.get())
      }

      if (reviewBannerStub.isVisible) {
        addTarget(reviewBannerStub.get())
      }
    }

    val transition = TransitionSet().apply {
      addTransition(slideTransition)
      addTransition(changeTransition)
      addListener(
        onEnd = {
          layoutParams = layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
        }
      )
    }

    layoutParams = layoutParams.apply { height = this@ConversationBannerView.height }
    TransitionManager.beginDelayedTransition(this, transition)
    stub.get().visible = false
  }

  interface Listener {
    fun updateAppAction()
    fun reRegisterAction()
    fun reviewJoinRequestsAction()
    fun gv1SuggestionsAction(actionId: Int)
    fun changeBubbleSettingAction(disableSetting: Boolean)
    fun onUnverifiedBannerClicked(unverifiedIdentities: List<IdentityRecord>)
    fun onUnverifiedBannerDismissed(unverifiedIdentities: List<IdentityRecord>)
    fun onRequestReviewIndividual(recipientId: RecipientId)
    fun onReviewGroupMembers(groupId: GroupId.V2)
    fun onDismissReview()
  }
}
