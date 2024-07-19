package org.stalker.securesms.components.settings.app.notifications.profiles.models

import android.view.View
import com.airbnb.lottie.SimpleColorFilter
import com.google.android.material.materialswitch.MaterialSwitch
import org.stalker.securesms.R
import org.stalker.securesms.components.settings.DSLSettingsIcon
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.PreferenceModel
import org.stalker.securesms.components.settings.PreferenceViewHolder
import org.stalker.securesms.conversation.colors.AvatarColor
import org.stalker.securesms.util.adapter.mapping.LayoutFactory
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.visible

/**
 * DSL custom preference for showing Notification Profile rows.
 */
object NotificationProfilePreference {

  fun register(adapter: MappingAdapter) {
    adapter.registerFactory(Model::class.java, LayoutFactory(::ViewHolder, R.layout.notification_profile_preference_item))
  }

  class Model(
    override val title: DSLSettingsText,
    override val summary: DSLSettingsText?,
    override val icon: DSLSettingsIcon?,
    val color: AvatarColor,
    val isOn: Boolean = false,
    val showSwitch: Boolean = false,
    val onClick: () -> Unit
  ) : PreferenceModel<Model>()

  private class ViewHolder(itemView: View) : PreferenceViewHolder<Model>(itemView) {

    private val switchWidget: MaterialSwitch = itemView.findViewById(R.id.switch_widget)

    override fun bind(model: Model) {
      super.bind(model)
      itemView.setOnClickListener { model.onClick() }
      switchWidget.setOnCheckedChangeListener(null)
      switchWidget.visible = model.showSwitch
      switchWidget.isEnabled = model.isEnabled
      switchWidget.isChecked = model.isOn
      iconView.background.colorFilter = SimpleColorFilter(model.color.colorInt())
      switchWidget.setOnCheckedChangeListener { _, _ -> model.onClick() }
    }
  }
}
