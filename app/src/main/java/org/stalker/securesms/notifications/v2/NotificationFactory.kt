package org.stalker.securesms.notifications.v2

import android.annotation.TargetApi
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.TransactionTooLargeException
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.stalker.securesms.MainActivity
import org.stalker.securesms.R
import org.stalker.securesms.components.emoji.EmojiStrings
import org.stalker.securesms.contacts.avatars.GeneratedContactPhoto
import org.stalker.securesms.conversation.ConversationIntents
import org.stalker.securesms.conversation.colors.AvatarColor
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.InMemoryMessageRecord
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.notifications.NotificationChannels
import org.stalker.securesms.notifications.NotificationIds
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.stories.my.MyStoriesActivity
import org.stalker.securesms.util.BubbleUtil
import org.stalker.securesms.util.ConversationUtil
import org.stalker.securesms.util.ServiceUtil
import org.stalker.securesms.util.TextSecurePreferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Given a notification state consisting of conversations of messages, show appropriate system notifications.
 */
object NotificationFactory {

  val TAG: String = Log.tag(NotificationFactory::class.java)

  private val STILL_DECRYPTING_INDIVIDUAL_THROTTLE: Duration = 5.seconds
  private val GROUP_THROTTLE: Duration = 20.seconds

  fun notify(
    context: Context,
    state: NotificationState,
    visibleThread: ConversationId?,
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    notificationConfigurationChanged: Boolean,
    alertOverrides: Set<ConversationId>,
    previousState: NotificationState,
    lastThreadNotification: MutableMap<ConversationId, Long>
  ): Set<ConversationId> {
    if (state.isEmpty) {
      Log.d(TAG, "State is empty, bailing")
      return emptySet()
    }

    val nonVisibleThreadCount: Int = state.conversations.count { it.thread != visibleThread }
    return if (Build.VERSION.SDK_INT < 24) {
      notify19(
        context = context,
        state = state,
        visibleThread = visibleThread,
        targetThread = targetThread,
        defaultBubbleState = defaultBubbleState,
        lastAudibleNotification = lastAudibleNotification,
        alertOverrides = alertOverrides,
        nonVisibleThreadCount = nonVisibleThreadCount,
        lastThreadNotification = lastThreadNotification
      )
    } else {
      notify24(
        context = context,
        state = state,
        visibleThread = visibleThread,
        targetThread = targetThread,
        defaultBubbleState = defaultBubbleState,
        lastAudibleNotification = lastAudibleNotification,
        notificationConfigurationChanged = notificationConfigurationChanged,
        alertOverrides = alertOverrides,
        nonVisibleThreadCount = nonVisibleThreadCount,
        previousState = previousState,
        lastThreadNotification = lastThreadNotification
      )
    }
  }

  private fun notify19(
    context: Context,
    state: NotificationState,
    visibleThread: ConversationId?,
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    alertOverrides: Set<ConversationId>,
    nonVisibleThreadCount: Int,
    lastThreadNotification: MutableMap<ConversationId, Long>
  ): Set<ConversationId> {
    val threadsThatNewlyAlerted: MutableSet<ConversationId> = mutableSetOf()

    state.conversations.find { it.thread == visibleThread }?.let { conversation ->
      if (conversation.hasNewNotifications()) {
        Log.internal().i(TAG, "Thread is visible, notifying in thread. notificationId: ${conversation.notificationId}")
        notifyInThread(context, conversation.recipient, lastAudibleNotification)
      }
    }

    if (nonVisibleThreadCount == 1) {
      state.conversations.first { it.thread != visibleThread }.let { conversation ->
        val shouldAlert = shouldAlert(conversation, lastThreadNotification.getOrDefault(conversation.thread, 0), alertOverrides.contains(conversation.thread))
        if (shouldAlert) {
          lastThreadNotification[conversation.thread] = System.currentTimeMillis()
        }

        notifyForConversation(
          context = context,
          conversation = conversation,
          targetThread = targetThread,
          defaultBubbleState = defaultBubbleState,
          shouldAlert = shouldAlert
        )
        if (conversation.hasNewNotifications()) {
          threadsThatNewlyAlerted += conversation.thread
        }
      }
    } else if (nonVisibleThreadCount > 1) {
      val nonVisibleConversations: List<NotificationConversation> = state.getNonVisibleConversation(visibleThread)
      threadsThatNewlyAlerted += nonVisibleConversations.filter { it.hasNewNotifications() }.map { it.thread }
      notifySummary(context = context, state = state.copy(conversations = nonVisibleConversations))
    }

    return threadsThatNewlyAlerted
  }

  @TargetApi(24)
  private fun notify24(
    context: Context,
    state: NotificationState,
    visibleThread: ConversationId?,
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    notificationConfigurationChanged: Boolean,
    alertOverrides: Set<ConversationId>,
    nonVisibleThreadCount: Int,
    previousState: NotificationState,
    lastThreadNotification: MutableMap<ConversationId, Long>
  ): Set<ConversationId> {
    val threadsThatNewlyAlerted: MutableSet<ConversationId> = mutableSetOf()

    state.conversations.forEach { conversation ->
      if (conversation.thread == visibleThread && conversation.hasNewNotifications()) {
        Log.internal().i(TAG, "Thread is visible, notifying in thread. notificationId: ${conversation.notificationId}")
        notifyInThread(context, conversation.recipient, lastAudibleNotification)
      } else if (notificationConfigurationChanged || conversation.hasNewNotifications() || alertOverrides.contains(conversation.thread) || !conversation.hasSameContent(previousState.getConversation(conversation.thread))) {
        if (conversation.hasNewNotifications()) {
          threadsThatNewlyAlerted += conversation.thread
        }

        try {
          val shouldAlert = shouldAlert(
            conversation = conversation,
            lastNotificationTimestamp = lastThreadNotification.getOrDefault(conversation.thread, 0),
            alertOverride = alertOverrides.contains(conversation.thread)
          )

          if (shouldAlert) {
            lastThreadNotification[conversation.thread] = System.currentTimeMillis()
          }

          notifyForConversation(
            context = context,
            conversation = conversation,
            targetThread = targetThread,
            defaultBubbleState = defaultBubbleState,
            shouldAlert = shouldAlert
          )
        } catch (e: SecurityException) {
          Log.w(TAG, "Too many pending intents device quirk", e)
        }
      }
    }

    if (nonVisibleThreadCount > 1 || ServiceUtil.getNotificationManager(context).isDisplayingSummaryNotification()) {
      notifySummary(context = context, state = state.copy(conversations = state.getNonVisibleConversation(visibleThread)))
    }

    return threadsThatNewlyAlerted
  }

  private fun shouldAlert(conversation: NotificationConversation, lastNotificationTimestamp: Long, alertOverride: Boolean): Boolean {
    val throttle: Duration = when {
      conversation.recipient.isGroup && (conversation.mostRecentNotification as? MessageNotification)?.hasSelfMention == false -> GROUP_THROTTLE
      ApplicationDependencies.getIncomingMessageObserver().decryptionDrained -> STILL_DECRYPTING_INDIVIDUAL_THROTTLE
      else -> 0.seconds
    }
    val canAlertBasedOnTime: Boolean = lastNotificationTimestamp < System.currentTimeMillis() - throttle.inWholeMilliseconds || lastNotificationTimestamp > System.currentTimeMillis()

    return ((conversation.hasNewNotifications() && canAlertBasedOnTime) || alertOverride) && !conversation.mostRecentNotification.authorRecipient.isSelf
  }

  private fun notifyForConversation(
    context: Context,
    conversation: NotificationConversation,
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    shouldAlert: Boolean
  ) {
    if (conversation.notificationItems.isEmpty()) {
      return
    }

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.core_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      setChannelId(conversation.getChannelId())
      setContentTitle(conversation.getContentTitle(context))
      setLargeIcon(conversation.getContactLargeIcon(context).toLargeBitmap(context))
      addPerson(conversation.recipient)

      if (conversation.thread.groupStoryId == null) {
        setShortcutId(ConversationUtil.getShortcutId(conversation.recipient))
        setLocusId(ConversationUtil.getShortcutId(conversation.recipient))
      }

      setContentInfo(conversation.messageCount.toString())
      setNumber(conversation.messageCount)
      setContentText(conversation.getContentText(context))
      setContentIntent(conversation.getPendingIntent(context))
      setDeleteIntent(conversation.getDeleteIntent(context))
      setSortKey(conversation.sortKey.toString())
      setWhen(conversation)
      addReplyActions(conversation)
      setOnlyAlertOnce(!shouldAlert)
      addMessages(conversation)
      setPriority(TextSecurePreferences.getNotificationPriority(context))
      setLights()
      setAlarms(conversation.recipient)
      setTicker(conversation.mostRecentNotification.getStyledPrimaryText(context, true))
      setBubbleMetadata(conversation, if (targetThread == conversation.thread) defaultBubbleState else BubbleUtil.BubbleState.HIDDEN)
    }

    if (conversation.isOnlyContactJoinedEvent) {
      builder.addTurnOffJoinedNotificationsAction(conversation.getTurnOffJoinedNotificationsIntent(context))
    }

    val notificationId: Int = if (Build.VERSION.SDK_INT < 24) NotificationIds.MESSAGE_SUMMARY else conversation.notificationId

    NotificationManagerCompat.from(context).safelyNotify(conversation.recipient, notificationId, builder.build())
  }

  private fun notifySummary(context: Context, state: NotificationState) {
    if (state.messageCount == 0) {
      return
    }

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.core_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      setChannelId(NotificationChannels.getInstance().messagesChannel)
      setContentTitle(context.getString(R.string.app_name))
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, MainActivity.clearTop(context), PendingIntentFlags.mutable()))
      setGroupSummary(true)
      setSubText(context.buildSummaryString(state.messageCount, state.threadCount))
      setContentInfo(state.messageCount.toString())
      setNumber(state.messageCount)
      setSummaryContentText(state.mostRecentSender)
      setDeleteIntent(state.getDeleteIntent(context))
      setWhen(state.mostRecentNotification)
      addMarkAsReadAction(state)
      addMessages(state)
      setOnlyAlertOnce(!state.notificationItems.any { it.isNewNotification })
      setPriority(TextSecurePreferences.getNotificationPriority(context))
      setLights()
      setAlarms(state.mostRecentSender)
      setTicker(state.mostRecentNotification?.getStyledPrimaryText(context, true))
    }

    Log.d(TAG, "showing summary notification")
    NotificationManagerCompat.from(context).safelyNotify(null, NotificationIds.MESSAGE_SUMMARY, builder.build())
  }

  private fun Context.buildSummaryString(messageCount: Int, threadCount: Int): String {
    val messageString = resources.getQuantityString(R.plurals.MessageNotifier_d_messages, messageCount, messageCount)
    val threadString = resources.getQuantityString(R.plurals.MessageNotifier_d_chats, threadCount, threadCount)
    return getString(R.string.MessageNotifier_s_in_s, messageString, threadString)
  }

  private fun notifyInThread(context: Context, recipient: Recipient, lastAudibleNotification: Long) {
    if (!SignalStore.settings().isMessageNotificationsInChatSoundsEnabled ||
      ServiceUtil.getAudioManager(context).ringerMode != AudioManager.RINGER_MODE_NORMAL ||
      (System.currentTimeMillis() - lastAudibleNotification) < DefaultMessageNotifier.MIN_AUDIBLE_PERIOD_MILLIS
    ) {
      return
    }

    val uri: Uri = if (NotificationChannels.supported()) {
      NotificationChannels.getInstance().getMessageRingtone(recipient) ?: NotificationChannels.getInstance().messageRingtone
    } else {
      recipient.messageRingtone ?: SignalStore.settings().messageNotificationSound
    }

    if (uri == Uri.EMPTY || uri.toString().isEmpty()) {
      Log.d(TAG, "ringtone uri is empty")
      return
    }

    val ringtone = RingtoneManager.getRingtone(context, uri)

    if (ringtone == null) {
      Log.w(TAG, "ringtone is null")
      return
    }

    ringtone.audioAttributes = AudioAttributes.Builder()
      .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
      .setUsage(AudioAttributes.USAGE_NOTIFICATION)
      .build()

    ringtone.play()
  }

  fun notifyMessageDeliveryFailed(context: Context, recipient: Recipient, thread: ConversationId, visibleThread: ConversationId?) {
    if (thread == visibleThread) {
      notifyInThread(context, recipient, 0)
      return
    }

    val intent: Intent = if (recipient.isDistributionList || thread.groupStoryId != null) {
      Intent(context, MyStoriesActivity::class.java)
    } else {
      ConversationIntents.createBuilderSync(context, recipient.id, thread.threadId)
        .build()
    }.makeUniqueToPreventMerging()

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.symbol_error_triangle_fill_32))
      setContentTitle(context.getString(R.string.MessageNotifier_message_delivery_failed))
      setContentText(context.getString(R.string.MessageNotifier_failed_to_deliver_message))
      setTicker(context.getString(R.string.MessageNotifier_error_delivering_message))
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, intent, PendingIntentFlags.mutable()))
      setAutoCancel(true)
      setAlarms(recipient)
      setChannelId(NotificationChannels.getInstance().FAILURES)
    }

    NotificationManagerCompat.from(context).safelyNotify(recipient, NotificationIds.getNotificationIdForMessageDeliveryFailed(thread), builder.build())
  }

  fun notifyStoryDeliveryFailed(context: Context, recipient: Recipient, thread: ConversationId) {
    val intent = Intent(context, MyStoriesActivity::class.java).makeUniqueToPreventMerging()

    val contentTitle = if (SignalStore.settings().messageNotificationsPrivacy.isDisplayContact) {
      if (recipient.isGroup) {
        context.getString(R.string.MessageNotifier_group_story_title, recipient.getDisplayName(context))
      } else {
        recipient.getDisplayName(context)
      }
    } else {
      context.getString(R.string.SingleRecipientNotificationBuilder_signal)
    }

    val largeIcon = if (SignalStore.settings().messageNotificationsPrivacy.isDisplayContact) {
      if (recipient.isMyStory) {
        Recipient.self().getContactDrawable(context)
      } else {
        recipient.getContactDrawable(context)
      }
    } else {
      GeneratedContactPhoto("Unknown", R.drawable.ic_profile_outline_40).asDrawable(context, AvatarColor.UNKNOWN)
    }.toLargeBitmap(context)

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setLargeIcon(largeIcon)
      setContentTitle(contentTitle)
      setContentText(String.format("%s %s", EmojiStrings.FAILED_STORY, context.getString(R.string.MessageNotifier_story_delivery_failed)))
      setTicker(context.getString(R.string.MessageNotifier_story_delivery_failed))
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, intent, PendingIntentFlags.mutable()))
      setAutoCancel(true)
      setAlarms(recipient)
      setChannelId(NotificationChannels.getInstance().FAILURES)
    }

    NotificationManagerCompat.from(context).safelyNotify(recipient, NotificationIds.getNotificationIdForMessageDeliveryFailed(thread), builder.build())
  }

  fun notifyProofRequired(context: Context, recipient: Recipient, thread: ConversationId, visibleThread: ConversationId?) {
    if (thread == visibleThread) {
      notifyInThread(context, recipient, 0)
      return
    }

    val intent: Intent = if (recipient.isDistributionList || thread.groupStoryId != null) {
      Intent(context, MyStoriesActivity::class.java)
    } else {
      ConversationIntents.createBuilderSync(context, recipient.id, thread.threadId)
        .build()
    }.makeUniqueToPreventMerging()

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.symbol_info_24))
      setContentTitle(context.getString(R.string.MessageNotifier_message_delivery_paused))
      setContentText(context.getString(R.string.MessageNotifier_verify_to_continue_messaging_on_signal))
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, intent, PendingIntentFlags.mutable()))
      setOnlyAlertOnce(true)
      setAutoCancel(true)
      setAlarms(recipient)
      setChannelId(NotificationChannels.getInstance().FAILURES)
    }

    NotificationManagerCompat.from(context).safelyNotify(recipient, NotificationIds.getNotificationIdForMessageDeliveryFailed(thread), builder.build())
  }

  @JvmStatic
  fun notifyToBubbleConversation(context: Context, recipient: Recipient, threadId: Long) {
    val builder: NotificationBuilder = NotificationBuilder.create(context)

    val conversation = NotificationConversation(
      recipient = recipient,
      thread = ConversationId.forConversation(threadId),
      notificationItems = listOf(
        MessageNotification(
          threadRecipient = recipient,
          record = InMemoryMessageRecord.ForceConversationBubble(recipient, threadId)
        )
      )
    )

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.core_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setChannelId(conversation.getChannelId())
      setContentTitle(conversation.getContentTitle(context))
      setLargeIcon(conversation.getContactLargeIcon(context).toLargeBitmap(context))
      addPerson(conversation.recipient)
      setShortcutId(ConversationUtil.getShortcutId(conversation.recipient))
      setLocusId(ConversationUtil.getShortcutId(conversation.recipient))
      addMessages(conversation)
      setBubbleMetadata(conversation, BubbleUtil.BubbleState.SHOWN)
    }

    Log.d(TAG, "Posting Notification for requested bubble")
    NotificationManagerCompat.from(context).safelyNotify(recipient, conversation.notificationId, builder.build())
  }

  private fun NotificationManagerCompat.safelyNotify(threadRecipient: Recipient?, notificationId: Int, notification: Notification) {
    try {
      notify(notificationId, notification)
      Log.internal().i(TAG, "Posted notification: $notification")
    } catch (e: SecurityException) {
      Log.i(TAG, "Security exception when posting notification, clearing ringtone")
      if (threadRecipient != null) {
        SignalExecutors.BOUNDED.execute {
          SignalDatabase.recipients.setMessageRingtone(threadRecipient.id, null)
          NotificationChannels.getInstance().updateMessageRingtone(threadRecipient, null)
        }
      }
    } catch (runtimeException: RuntimeException) {
      if (runtimeException.cause is TransactionTooLargeException) {
        Log.e(TAG, "Transaction too large", runtimeException)
      } else {
        throw runtimeException
      }
    }
  }
}
