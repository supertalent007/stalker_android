package org.stalker.securesms.components.settings.conversation.preferences

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.stalker.securesms.R
import org.stalker.securesms.database.CallTable
import org.stalker.securesms.database.MessageTypes
import org.stalker.securesms.database.model.MessageRecord
import org.stalker.securesms.databinding.ConversationSettingsCallPreferenceItemBinding
import org.stalker.securesms.util.DateUtils
import org.stalker.securesms.util.adapter.mapping.BindingFactory
import org.stalker.securesms.util.adapter.mapping.BindingViewHolder
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.adapter.mapping.MappingModel

/**
 * Renders a single call preference row when displaying call info.
 */
object CallPreference {
  fun register(mappingAdapter: MappingAdapter) {
    mappingAdapter.registerFactory(Model::class.java, BindingFactory(::ViewHolder, ConversationSettingsCallPreferenceItemBinding::inflate))
  }

  class Model(
    val call: CallTable.Call,
    val record: MessageRecord
  ) : MappingModel<Model> {
    override fun areItemsTheSame(newItem: Model): Boolean = record.id == newItem.record.id

    override fun areContentsTheSame(newItem: Model): Boolean {
      return call == newItem.call &&
        record.type == newItem.record.type &&
        record.isOutgoing == newItem.record.isOutgoing &&
        record.timestamp == newItem.record.timestamp &&
        record.id == newItem.record.id
    }
  }

  private class ViewHolder(binding: ConversationSettingsCallPreferenceItemBinding) : BindingViewHolder<Model, ConversationSettingsCallPreferenceItemBinding>(binding) {
    override fun bind(model: Model) {
      binding.callIcon.setImageResource(getCallIcon(model.call))
      binding.callType.text = getCallType(model.call)
      binding.callTime.text = getCallTime(model.record)
    }

    @DrawableRes
    private fun getCallIcon(call: CallTable.Call): Int {
      return when (call.messageType) {
        MessageTypes.MISSED_VIDEO_CALL_TYPE, MessageTypes.MISSED_AUDIO_CALL_TYPE -> R.drawable.symbol_missed_incoming_24
        MessageTypes.INCOMING_AUDIO_CALL_TYPE, MessageTypes.INCOMING_VIDEO_CALL_TYPE -> if (call.isDisplayedAsMissedCallInUi) R.drawable.symbol_missed_incoming_24 else R.drawable.symbol_arrow_downleft_24
        MessageTypes.OUTGOING_AUDIO_CALL_TYPE, MessageTypes.OUTGOING_VIDEO_CALL_TYPE -> R.drawable.symbol_arrow_upright_24
        MessageTypes.GROUP_CALL_TYPE -> when {
          call.isDisplayedAsMissedCallInUi -> R.drawable.symbol_missed_incoming_24
          call.event == CallTable.Event.GENERIC_GROUP_CALL || call.event == CallTable.Event.JOINED -> R.drawable.symbol_group_24
          call.direction == CallTable.Direction.INCOMING -> R.drawable.symbol_arrow_downleft_24
          call.direction == CallTable.Direction.OUTGOING -> R.drawable.symbol_arrow_upright_24
          else -> throw AssertionError()
        }
        else -> error("Unexpected type ${call.type}")
      }
    }

    private fun getCallType(call: CallTable.Call): String {
      val id = when (call.messageType) {
        MessageTypes.MISSED_AUDIO_CALL_TYPE -> getMissedCallString(false, call.event)
        MessageTypes.MISSED_VIDEO_CALL_TYPE -> getMissedCallString(true, call.event)
        MessageTypes.INCOMING_AUDIO_CALL_TYPE -> if (call.isDisplayedAsMissedCallInUi) getMissedCallString(false, call.event) else R.string.MessageRecord_incoming_voice_call
        MessageTypes.INCOMING_VIDEO_CALL_TYPE -> if (call.isDisplayedAsMissedCallInUi) getMissedCallString(true, call.event) else R.string.MessageRecord_incoming_video_call
        MessageTypes.OUTGOING_AUDIO_CALL_TYPE -> R.string.MessageRecord_outgoing_voice_call
        MessageTypes.OUTGOING_VIDEO_CALL_TYPE -> R.string.MessageRecord_outgoing_video_call
        MessageTypes.GROUP_CALL_TYPE -> when {
          call.isDisplayedAsMissedCallInUi -> if (call.event == CallTable.Event.MISSED_NOTIFICATION_PROFILE) R.string.CallPreference__missed_group_call_notification_profile else R.string.CallPreference__missed_group_call
          call.event == CallTable.Event.GENERIC_GROUP_CALL || call.event == CallTable.Event.JOINED -> R.string.CallPreference__group_call
          call.direction == CallTable.Direction.INCOMING -> R.string.CallPreference__incoming_group_call
          call.direction == CallTable.Direction.OUTGOING -> R.string.CallPreference__outgoing_group_call
          else -> throw AssertionError()
        }
        else -> error("Unexpected type ${call.messageType}")
      }

      return context.getString(id)
    }

    @StringRes
    private fun getMissedCallString(isVideo: Boolean, callEvent: CallTable.Event): Int {
      return if (callEvent == CallTable.Event.MISSED_NOTIFICATION_PROFILE) {
        if (isVideo) {
          R.string.MessageRecord_missed_video_call_notification_profile
        } else {
          R.string.MessageRecord_missed_voice_call_notification_profile
        }
      } else {
        if (isVideo) {
          R.string.MessageRecord_missed_video_call
        } else {
          R.string.MessageRecord_missed_voice_call
        }
      }
    }

    private fun getCallTime(messageRecord: MessageRecord): String {
      return DateUtils.getOnlyTimeString(context, messageRecord.timestamp)
    }
  }
}
