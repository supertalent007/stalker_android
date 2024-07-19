package org.stalker.securesms.components.settings.models

import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import org.stalker.securesms.R
import org.stalker.securesms.components.settings.DSLSettingsText
import org.stalker.securesms.components.settings.PreferenceModel
import org.stalker.securesms.util.adapter.mapping.LayoutFactory
import org.stalker.securesms.util.adapter.mapping.MappingAdapter
import org.stalker.securesms.util.adapter.mapping.MappingViewHolder

/**
 * A Text without any padding, allowing for exact padding to be handed in at runtime.
 */
data class Text(
  val text: DSLSettingsText
) {

  companion object {
    fun register(adapter: MappingAdapter) {
      adapter.registerFactory(Model::class.java, LayoutFactory({ ViewHolder(it) }, R.layout.dsl_text_preference))
    }
  }

  class Model(val paddableText: Text) : PreferenceModel<Model>() {
    override fun areItemsTheSame(newItem: Model): Boolean {
      return true
    }

    override fun areContentsTheSame(newItem: Model): Boolean {
      return super.areContentsTheSame(newItem) && newItem.paddableText == paddableText
    }
  }

  class ViewHolder(itemView: View) : MappingViewHolder<Model>(itemView) {

    private val text: TextView = itemView.findViewById(R.id.title)

    override fun bind(model: Model) {
      text.text = model.paddableText.text.resolve(context)

      val clickableSpans = (text.text as? Spanned)?.getSpans(0, text.text.length, ClickableSpan::class.java)
      if (clickableSpans?.isEmpty() == false) {
        text.movementMethod = LinkMovementMethod.getInstance()
      } else {
        text.movementMethod = null
      }
    }
  }
}
