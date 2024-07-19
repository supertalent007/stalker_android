package org.stalker.securesms.stories.viewer.post

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.stalker.securesms.R
import org.stalker.securesms.components.ViewBinderDelegate
import org.stalker.securesms.components.voice.VoiceNoteMediaControllerOwner
import org.stalker.securesms.databinding.StoriesPostFragmentBinding
import org.stalker.securesms.mediapreview.VideoControlsDelegate
import org.stalker.securesms.stories.viewer.page.StoryDisplay
import org.stalker.securesms.stories.viewer.page.StoryViewerPageViewModel
import org.stalker.securesms.util.fragments.requireListener
import org.stalker.securesms.util.visible
import org.stalker.securesms.video.VideoPlayer.PlayerCallback

/**
 * Renders a given StoryPost object as a viewable story.
 */
class StoryPostFragment : Fragment(R.layout.stories_post_fragment) {

  companion object {
    private val TAG = Log.tag(StoryPostFragment::class.java)
  }

  private val postViewModel: StoryPostViewModel by viewModels(factoryProducer = {
    StoryPostViewModel.Factory(StoryTextPostRepository())
  })

  private val pageViewModel: StoryViewerPageViewModel by viewModels(ownerProducer = {
    requireParentFragment()
  })

  private val binding by ViewBinderDelegate(StoriesPostFragmentBinding::bind) {
    it.video.cleanup()
    presentNone()
  }

  private val disposables = LifecycleDisposable()

  private var storyImageLoader: StoryImageLoader? = null
  private var storyTextLoader: StoryTextLoader? = null
  private var storyVideoLoader: StoryVideoLoader? = null

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    initializeVideoPlayer()

    disposables.bindTo(viewLifecycleOwner)

    disposables += pageViewModel.postContent
      .filter { it.isPresent }
      .map { it.get() }
      .distinctUntilChanged()
      .subscribe {
        postViewModel.onPostContentChanged(it)
      }

    disposables += postViewModel.state.distinctUntilChanged().subscribe { state ->
      if (context == null) {
        Log.w(TAG, "Attempted state change while not attached to a context. Dropping.")
        return@subscribe
      }

      when (state) {
        is StoryPostState.None -> presentNone()
        is StoryPostState.TextPost -> presentTextPost(state)
        is StoryPostState.VideoPost -> presentVideoPost(state)
        is StoryPostState.ImagePost -> presentImagePost(state)
      }
    }
  }

  private fun initializeVideoPlayer() {
    binding.video.setWindow(requireActivity().window)
    binding.video.setPlayerPositionDiscontinuityCallback { _, r: Int ->
      requireCallback().getVideoControlsDelegate()?.onPlayerPositionDiscontinuity(r)
    }

    binding.video.setPlayerCallback(object : PlayerCallback {
      override fun onReady() {
        requireCallback().onContentReady()
      }

      override fun onPlaying() {
        val activity: Activity? = activity
        if (activity is VoiceNoteMediaControllerOwner) {
          (activity as VoiceNoteMediaControllerOwner).voiceNoteMediaController.pausePlayback()
        }
      }

      override fun onStopped() {}
      override fun onError() {
        requireCallback().onContentNotAvailable()
      }
    })
  }

  private fun presentNone() {
    storyImageLoader?.clear()
    storyImageLoader = null

    storyVideoLoader?.clear()
    storyVideoLoader = null

    storyTextLoader = null

    binding.text.visible = false
    binding.blur.visible = false
    binding.image.visible = false
    binding.video.visible = false
  }

  private fun presentVideoPost(state: StoryPostState.VideoPost) {
    presentNone()

    binding.video.visible = true
    binding.blur.visible = true

    val storyBlurLoader = StoryBlurLoader(
      viewLifecycleOwner.lifecycle,
      state.blurHash,
      state.videoUri,
      pageViewModel.storyCache,
      StoryDisplay.getStorySize(resources),
      binding.blur
    )

    storyVideoLoader = StoryVideoLoader(
      this,
      state,
      binding.video,
      requireCallback(),
      storyBlurLoader
    )

    storyVideoLoader?.load()
  }

  private fun presentImagePost(state: StoryPostState.ImagePost) {
    presentNone()

    binding.image.visible = true
    binding.blur.visible = true

    storyImageLoader = StoryImageLoader(
      this,
      state,
      pageViewModel.storyCache,
      StoryDisplay.getStorySize(resources),
      binding.image,
      binding.blur,
      requireCallback()
    )

    storyImageLoader?.load()
  }

  private fun presentTextPost(state: StoryPostState.TextPost) {
    presentNone()

    if (state.loadState == StoryPostState.LoadState.FAILED) {
      requireCallback().onContentNotAvailable()
      return
    }

    if (state.loadState == StoryPostState.LoadState.INIT) {
      return
    }

    binding.text.visible = true

    storyTextLoader = StoryTextLoader(
      this,
      binding.text,
      state,
      requireCallback()
    )

    storyTextLoader?.load()
  }

  fun requireCallback(): Callback {
    return requireListener()
  }

  interface Callback {
    fun onContentReady()
    fun onContentNotAvailable()
    fun setIsDisplayingLinkPreviewTooltip(isDisplayingLinkPreviewTooltip: Boolean)
    fun getVideoControlsDelegate(): VideoControlsDelegate?
  }
}
