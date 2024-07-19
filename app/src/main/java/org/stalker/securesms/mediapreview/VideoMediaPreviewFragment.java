package org.stalker.securesms.mediapreview;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.LegacyPlayerControlView;

import org.signal.core.util.concurrent.LifecycleDisposable;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.R;
import org.stalker.securesms.components.voice.VoiceNoteMediaControllerOwner;
import org.stalker.securesms.mms.VideoSlide;
import org.stalker.securesms.util.MediaUtil;
import org.stalker.securesms.video.VideoPlayer;

import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public final class VideoMediaPreviewFragment extends MediaPreviewFragment {

  private static final String TAG = Log.tag(VideoMediaPreviewFragment.class);

  private static final Long MINIMUM_DURATION_FOR_SKIP_MS = TimeUnit.MILLISECONDS.convert(30, TimeUnit.SECONDS);

  private VideoPlayer             videoView;
  private boolean                 isVideoGif;
  private MediaPreviewV2ViewModel viewModel;
  private LifecycleDisposable     lifecycleDisposable;


  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View    itemView    = inflater.inflate(R.layout.media_preview_video_fragment, container, false);
    Bundle  arguments   = requireArguments();
    Uri     uri         = arguments.getParcelable(DATA_URI);
    if (uri == null) {
      Log.w(TAG, "Media URI was null.");
      Toast.makeText(requireContext(), R.string.MediaPreviewActivity_media_no_longer_available, Toast.LENGTH_LONG).show();
      requireActivity().finish();
      return itemView;
    }
    String  contentType = arguments.getString(DATA_CONTENT_TYPE);
    long    size        = arguments.getLong(DATA_SIZE);
    boolean autoPlay    = arguments.getBoolean(AUTO_PLAY);

    isVideoGif = arguments.getBoolean(VIDEO_GIF);

    if (!MediaUtil.isVideo(contentType)) {
      throw new AssertionError("This fragment can only display video");
    }

    videoView           = itemView.findViewById(R.id.video_player);
    viewModel           = new ViewModelProvider(requireActivity()).get(MediaPreviewV2ViewModel.class);
    lifecycleDisposable = new LifecycleDisposable();

    lifecycleDisposable.add(viewModel.getState().distinctUntilChanged().subscribe(state -> {
      Log.d(TAG, "ANIM" + state.isInSharedAnimation());
      itemView.setVisibility(state.isInSharedAnimation() ? View.INVISIBLE : View.VISIBLE);
    }));

    videoView.setWindow(requireActivity().getWindow());
    videoView.setVideoSource(new VideoSlide(getContext(), uri, size, false), autoPlay, TAG);
    videoView.setPlayerPositionDiscontinuityCallback((v, r) -> {
      if (events.getVideoControlsDelegate() != null) {
        events.getVideoControlsDelegate().onPlayerPositionDiscontinuity(r);
      }
    });
    videoView.setPlayerCallback(new VideoPlayer.PlayerCallback() {
      @Override
      public void onReady() {
        updateSkipButtonState();
        events.onMediaReady();
      }

      @Override
      public void onPlaying() {
        Activity activity = getActivity();
        if (!isVideoGif && activity instanceof VoiceNoteMediaControllerOwner) {
          ((VoiceNoteMediaControllerOwner) activity).getVoiceNoteMediaController().pausePlayback();
        }
        events.onPlaying();
      }

      @Override
      public void onStopped() {
        events.onStopped(getTag());
      }

      @Override
      public void onError() {
        events.unableToPlayMedia();
      }
    });

    if (isVideoGif) {
      videoView.loopForever();
    }

    videoView.setOnClickListener(v -> events.singleTapOnMedia());
    return itemView;
  }

  private void updateSkipButtonState() {
    final LegacyPlayerControlView playbackControls = videoView.getControlView();
    if (playbackControls != null) {
      boolean shouldShowSkipButtons = videoView.getDuration() > MINIMUM_DURATION_FOR_SKIP_MS;
      playbackControls.setShowFastForwardButton(shouldShowSkipButtons);
      playbackControls.setShowRewindButton(shouldShowSkipButtons);
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    cleanUp();
  }

  @Override
  public void onResume() {
    super.onResume();

    if (videoView != null && isVideoGif) {
      videoView.play();
    }

    if (events.getVideoControlsDelegate() != null) {
      events.getVideoControlsDelegate().attachPlayer(getUri(), videoView, isVideoGif);
    }
  }

  @Override
  public void autoPlayIfNeeded() {
    if (videoView != null && videoView.getPlaybackPosition() < videoView.getDuration()) {
      videoView.play();
    }
  }

  @Override
  public void cleanUp() {
    if (videoView != null) {
      videoView.cleanup();
    }
  }

  @Override
  public void pause() {
    if (videoView != null) {
      videoView.pause();
    }

    if (events.getVideoControlsDelegate() != null) {
      events.getVideoControlsDelegate().detachPlayer();
    }
  }

  @Override
  public void setBottomButtonControls(@NonNull MediaPreviewPlayerControlView playerControlView) {
    if (videoView != null) {
      videoView.setControlView(playerControlView);
    }
    updateSkipButtonState();
  }

  private @NonNull Uri getUri() {
    return requireArguments().getParcelable(DATA_URI);
  }
}
