package org.stalker.securesms.video;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Size;

import androidx.annotation.NonNull;

import org.stalker.securesms.mms.MediaConstraints;
import org.stalker.securesms.video.videoconverter.utils.VideoConstants;

public final class VideoUtil {

  private VideoUtil() { }

  public static Size getVideoRecordingSize() {
    return isPortrait(screenSize())
           ? new Size(VideoConstants.VIDEO_SHORT_EDGE_HD, VideoConstants.VIDEO_LONG_EDGE_HD)
           : new Size(VideoConstants.VIDEO_LONG_EDGE_HD, VideoConstants.VIDEO_SHORT_EDGE_HD);
  }

  public static int getMaxVideoRecordDurationInSeconds(@NonNull Context context, @NonNull MediaConstraints mediaConstraints) {
    long allowedSize = mediaConstraints.getCompressedVideoMaxSize(context);
    int duration     = (int) Math.floor((float) allowedSize / VideoConstants.MAX_ALLOWED_BYTES_PER_SECOND);

    return Math.min(duration, VideoConstants.VIDEO_MAX_RECORD_LENGTH_S);
  }

  private static Size screenSize() {
    DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
    return new Size(metrics.widthPixels, metrics.heightPixels);
  }

  private static boolean isPortrait(Size size) {
    return size.getWidth() < size.getHeight();
  }
}
