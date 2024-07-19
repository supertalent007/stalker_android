package org.stalker.securesms.stories.viewer.reply.reaction

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.view.doOnNextLayout
import org.stalker.securesms.R
import org.stalker.securesms.components.emoji.EmojiImageView

class OnReactionSentView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  layoutRes: Int = R.layout.on_reaction_sent_view
) : FrameLayout(context, attrs) {

  var callback: Callback? = null

  init {
    inflate(context, layoutRes, this)
  }

  private val motionLayout: MotionLayout = findViewById(R.id.motion_layout)

  init {
    motionLayout.addTransitionListener(object : TransitionAdapter() {
      override fun onTransitionCompleted(p0: MotionLayout?, p1: Int) {
        motionLayout.progress = 0f
        callback?.onFinished()
      }
    })
  }

  fun playForEmoji(emojis: List<CharSequence>) {
    motionLayout.progress = 0f

    listOf(
      R.id.emoji_1,
      R.id.emoji_2,
      R.id.emoji_3,
      R.id.emoji_4,
      R.id.emoji_5,
      R.id.emoji_6,
      R.id.emoji_7,
      R.id.emoji_8,
      R.id.emoji_9,
      R.id.emoji_10,
      R.id.emoji_11
    ).forEachIndexed { index, it ->
      val emojiIndex = index % emojis.size
      findViewById<EmojiImageView>(it).setImageEmoji(emojis[emojiIndex])
    }

    motionLayout.requestLayout()
    motionLayout.doOnNextLayout {
      motionLayout.transitionToEnd()
    }
  }

  interface Callback {
    fun onFinished()
  }
}
