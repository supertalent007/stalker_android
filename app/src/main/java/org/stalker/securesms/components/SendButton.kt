package org.stalker.securesms.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageButton
import org.stalker.securesms.conversation.MessageSendType
import org.stalker.securesms.util.ViewUtil

/**
 * The send button you see in a conversation.
 * Also encapsulates the long-press menu that allows users to switch [MessageSendType]s.
 */
class SendButton(context: Context, attributeSet: AttributeSet?) : AppCompatImageButton(context, attributeSet), OnLongClickListener {

  private var scheduledSendListener: ScheduledSendListener? = null

  private var popupContainer: ViewGroup? = null

  init {
    setOnLongClickListener(this)
    ViewUtil.mirrorIfRtl(this, getContext())
    setImageResource(MessageSendType.SignalMessageSendType.buttonDrawableRes)
    contentDescription = context.getString(MessageSendType.SignalMessageSendType.titleRes)
  }

  fun setScheduledSendListener(listener: ScheduledSendListener?) {
    this.scheduledSendListener = listener
  }

  /**
   * Must be called with a view that is acceptable for determining the bounds of the popup selector.
   */
  fun setPopupContainer(container: ViewGroup) {
    popupContainer = container
  }

  override fun onLongClick(v: View): Boolean {
    if (!isEnabled) {
      return false
    }

    val scheduleListener = scheduledSendListener

    return if (scheduleListener?.canSchedule() == true) {
      scheduleListener.onSendScheduled()
      true
    } else {
      false
    }
  }

  interface ScheduledSendListener {
    fun onSendScheduled()
    fun canSchedule(): Boolean
  }
}
