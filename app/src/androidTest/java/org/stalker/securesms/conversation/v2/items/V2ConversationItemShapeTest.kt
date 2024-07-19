/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.conversation.v2.items

import android.net.Uri
import android.view.View
import androidx.lifecycle.Observer
import com.bumptech.glide.RequestManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.signal.ringrtc.CallLinkRootKey
import org.stalker.securesms.components.voice.VoiceNotePlaybackState
import org.stalker.securesms.contactshare.Contact
import org.stalker.securesms.conversation.ConversationAdapter
import org.stalker.securesms.conversation.ConversationItem
import org.stalker.securesms.conversation.ConversationItemDisplayMode
import org.stalker.securesms.conversation.ConversationMessage
import org.stalker.securesms.conversation.colors.Colorizer
import org.stalker.securesms.conversation.mutiselect.MultiselectPart
import org.stalker.securesms.database.FakeMessageRecords
import org.stalker.securesms.database.model.InMemoryMessageRecord
import org.stalker.securesms.database.model.MessageRecord
import org.stalker.securesms.database.model.MmsMessageRecord
import org.stalker.securesms.groups.GroupId
import org.stalker.securesms.groups.GroupMigrationMembershipChange
import org.stalker.securesms.linkpreview.LinkPreview
import org.stalker.securesms.mediapreview.MediaIntentFactory
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.stickers.StickerLocator
import org.stalker.securesms.testing.SignalActivityRule
import kotlin.time.Duration.Companion.minutes

class V2ConversationItemShapeTest {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 10)

  @Test
  fun givenNextAndPreviousMessageDoNotExist_whenISetMessageShape_thenIExpectSingle() {
    val testSubject = V2ConversationItemShape(FakeConversationContext())

    val expected = V2ConversationItemShape.MessageShape.SINGLE
    val actual = testSubject.setMessageShape(
      currentMessage = getMessageRecord(),
      isGroupThread = false,
      adapterPosition = 5
    )

    assertEquals(expected, actual)
  }

  @Test
  fun givenPreviousWithinTimeoutAndNoNext_whenISetMessageShape_thenIExpectEnd() {
    val now = System.currentTimeMillis()
    val prev = now - 2.minutes.inWholeMilliseconds

    val testSubject = V2ConversationItemShape(
      FakeConversationContext(
        previousMessage = getMessageRecord(prev)
      )
    )

    val expected = V2ConversationItemShape.MessageShape.END
    val actual = testSubject.setMessageShape(
      currentMessage = getMessageRecord(now),
      isGroupThread = false,
      adapterPosition = 5
    )

    assertEquals(expected, actual)
  }

  @Test
  fun givenNextWithinTimeoutAndNoPrevious_whenISetMessageShape_thenIExpectStart() {
    val now = System.currentTimeMillis()
    val prev = now - 2.minutes.inWholeMilliseconds

    val testSubject = V2ConversationItemShape(
      FakeConversationContext(
        nextMessage = getMessageRecord(now)
      )
    )

    val expected = V2ConversationItemShape.MessageShape.START
    val actual = testSubject.setMessageShape(
      currentMessage = getMessageRecord(prev),
      isGroupThread = false,
      adapterPosition = 5
    )

    assertEquals(expected, actual)
  }

  @Test
  fun givenPreviousAndNextWithinTimeout_whenISetMessageShape_thenIExpectMiddle() {
    val now = System.currentTimeMillis()
    val prev = now - 2.minutes.inWholeMilliseconds
    val next = now + 2.minutes.inWholeMilliseconds

    val testSubject = V2ConversationItemShape(
      FakeConversationContext(
        previousMessage = getMessageRecord(prev),
        nextMessage = getMessageRecord(next)
      )
    )

    val expected = V2ConversationItemShape.MessageShape.MIDDLE
    val actual = testSubject.setMessageShape(
      currentMessage = getMessageRecord(now),
      isGroupThread = false,
      adapterPosition = 5
    )

    assertEquals(expected, actual)
  }

  @Test
  fun givenPreviousOutsideTimeoutAndNoNext_whenISetMessageShape_thenIExpectSingle() {
    val now = System.currentTimeMillis()
    val prev = now - 4.minutes.inWholeMilliseconds

    val testSubject = V2ConversationItemShape(
      FakeConversationContext(
        previousMessage = getMessageRecord(prev)
      )
    )

    val expected = V2ConversationItemShape.MessageShape.SINGLE
    val actual = testSubject.setMessageShape(
      currentMessage = getMessageRecord(now),
      isGroupThread = false,
      adapterPosition = 5
    )

    assertEquals(expected, actual)
  }

  @Test
  fun givenNextOutsideTimeoutAndNoPrevious_whenISetMessageShape_thenIExpectSingle() {
    val now = System.currentTimeMillis()
    val prev = now - 4.minutes.inWholeMilliseconds

    val testSubject = V2ConversationItemShape(
      FakeConversationContext(
        nextMessage = getMessageRecord(now)
      )
    )

    val expected = V2ConversationItemShape.MessageShape.SINGLE
    val actual = testSubject.setMessageShape(
      currentMessage = getMessageRecord(prev),
      isGroupThread = false,
      adapterPosition = 5
    )

    assertEquals(expected, actual)
  }

  @Test
  fun givenPreviousAndNextOutsideTimeout_whenISetMessageShape_thenIExpectSingle() {
    val now = System.currentTimeMillis()
    val prev = now - 4.minutes.inWholeMilliseconds
    val next = now + 4.minutes.inWholeMilliseconds

    val testSubject = V2ConversationItemShape(
      FakeConversationContext(
        previousMessage = getMessageRecord(prev),
        nextMessage = getMessageRecord(next)
      )
    )

    val expected = V2ConversationItemShape.MessageShape.SINGLE
    val actual = testSubject.setMessageShape(
      currentMessage = getMessageRecord(now),
      isGroupThread = false,
      adapterPosition = 5
    )

    assertEquals(expected, actual)
  }

  private fun getMessageRecord(
    timestamp: Long = System.currentTimeMillis()
  ): MessageRecord {
    return FakeMessageRecords.buildMediaMmsMessageRecord(
      dateReceived = timestamp,
      dateSent = timestamp,
      dateServer = timestamp
    )
  }

  private class FakeConversationContext(
    private val hasWallpaper: Boolean = false,
    private val previousMessage: MessageRecord? = null,
    private val nextMessage: MessageRecord? = null
  ) : V2ConversationContext {

    private val colorizer = Colorizer()

    override val displayMode: ConversationItemDisplayMode = ConversationItemDisplayMode.Standard

    override val clickListener: ConversationAdapter.ItemClickListener = FakeConversationItemClickListener
    override val selectedItems: Set<MultiselectPart> = emptySet()
    override val isMessageRequestAccepted: Boolean = true
    override val searchQuery: String? = null
    override val requestManager: RequestManager = mockk()
    override val isParentInScroll: Boolean = false
    override fun getChatColorsData(): ChatColorsDrawable.ChatColorsData = ChatColorsDrawable.ChatColorsData(null, null)

    override fun onStartExpirationTimeout(messageRecord: MessageRecord) = Unit

    override fun hasWallpaper(): Boolean = hasWallpaper

    override fun getColorizer(): Colorizer = colorizer

    override fun getNextMessage(adapterPosition: Int): MessageRecord? = nextMessage

    override fun getPreviousMessage(adapterPosition: Int): MessageRecord? = previousMessage
  }

  private object FakeConversationItemClickListener : ConversationAdapter.ItemClickListener {
    override fun onQuoteClicked(messageRecord: MmsMessageRecord?) = Unit

    override fun onLinkPreviewClicked(linkPreview: LinkPreview) = Unit

    override fun onQuotedIndicatorClicked(messageRecord: MessageRecord) = Unit

    override fun onMoreTextClicked(conversationRecipientId: RecipientId, messageId: Long, isMms: Boolean) = Unit

    override fun onStickerClicked(stickerLocator: StickerLocator) = Unit

    override fun onViewOnceMessageClicked(messageRecord: MmsMessageRecord) = Unit

    override fun onSharedContactDetailsClicked(contact: Contact, avatarTransitionView: View) = Unit

    override fun onAddToContactsClicked(contact: Contact) = Unit

    override fun onMessageSharedContactClicked(choices: MutableList<Recipient>) = Unit

    override fun onInviteSharedContactClicked(choices: MutableList<Recipient>) = Unit

    override fun onReactionClicked(multiselectPart: MultiselectPart, messageId: Long, isMms: Boolean) = Unit

    override fun onGroupMemberClicked(recipientId: RecipientId, groupId: GroupId) = Unit

    override fun onMessageWithErrorClicked(messageRecord: MessageRecord) = Unit

    override fun onMessageWithRecaptchaNeededClicked(messageRecord: MessageRecord) = Unit

    override fun onIncomingIdentityMismatchClicked(recipientId: RecipientId) = Unit

    override fun onRegisterVoiceNoteCallbacks(onPlaybackStartObserver: Observer<VoiceNotePlaybackState>) = Unit

    override fun onUnregisterVoiceNoteCallbacks(onPlaybackStartObserver: Observer<VoiceNotePlaybackState>) = Unit

    override fun onVoiceNotePause(uri: Uri) = Unit

    override fun onVoiceNotePlay(uri: Uri, messageId: Long, position: Double) = Unit

    override fun onVoiceNoteSeekTo(uri: Uri, position: Double) = Unit

    override fun onVoiceNotePlaybackSpeedChanged(uri: Uri, speed: Float) = Unit

    override fun onGroupMigrationLearnMoreClicked(membershipChange: GroupMigrationMembershipChange) = Unit

    override fun onChatSessionRefreshLearnMoreClicked() = Unit

    override fun onBadDecryptLearnMoreClicked(author: RecipientId) = Unit

    override fun onSafetyNumberLearnMoreClicked(recipient: Recipient) = Unit

    override fun onJoinGroupCallClicked() = Unit

    override fun onInviteFriendsToGroupClicked(groupId: GroupId.V2) = Unit

    override fun onEnableCallNotificationsClicked() = Unit

    override fun onPlayInlineContent(conversationMessage: ConversationMessage?) = Unit

    override fun onInMemoryMessageClicked(messageRecord: InMemoryMessageRecord) = Unit

    override fun onViewGroupDescriptionChange(groupId: GroupId?, description: String, isMessageRequestAccepted: Boolean) = Unit

    override fun onChangeNumberUpdateContact(recipient: Recipient) = Unit

    override fun onCallToAction(action: String) = Unit

    override fun onDonateClicked() = Unit

    override fun onBlockJoinRequest(recipient: Recipient) = Unit

    override fun onRecipientNameClicked(target: RecipientId) = Unit

    override fun onInviteToSignalClicked() = Unit

    override fun onActivatePaymentsClicked() = Unit

    override fun onSendPaymentClicked(recipientId: RecipientId) = Unit

    override fun onScheduledIndicatorClicked(view: View, conversationMessage: ConversationMessage) = Unit

    override fun onUrlClicked(url: String): Boolean = false

    override fun onViewGiftBadgeClicked(messageRecord: MessageRecord) = Unit

    override fun onGiftBadgeRevealed(messageRecord: MessageRecord) = Unit

    override fun goToMediaPreview(parent: ConversationItem?, sharedElement: View?, args: MediaIntentFactory.MediaPreviewArgs?) = Unit

    override fun onEditedIndicatorClicked(messageRecord: MessageRecord) = Unit

    override fun onShowGroupDescriptionClicked(groupName: String, description: String, shouldLinkifyWebLinks: Boolean) = Unit

    override fun onJoinCallLink(callLinkRootKey: CallLinkRootKey) = Unit

    override fun onItemClick(item: MultiselectPart?) = Unit

    override fun onItemLongClick(itemView: View?, item: MultiselectPart?) = Unit

    override fun onShowSafetyTips(forGroup: Boolean) = Unit

    override fun onReportSpamLearnMoreClicked() = Unit

    override fun onMessageRequestAcceptOptionsClicked() = Unit

    override fun onItemDoubleClick(item: MultiselectPart) = Unit
  }
}
