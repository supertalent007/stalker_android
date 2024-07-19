package org.stalker.securesms.video;

import android.content.Context;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.MimeTypes;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.mms.MediaStream;
import org.stalker.securesms.util.MemoryFileDescriptor;
import org.stalker.securesms.video.exceptions.VideoPostProcessingException;
import org.stalker.securesms.video.exceptions.VideoSizeException;
import org.stalker.securesms.video.exceptions.VideoSourceException;
import org.stalker.securesms.video.interfaces.TranscoderCancelationSignal;
import org.stalker.securesms.video.postprocessing.Mp4FaststartPostProcessor;
import org.stalker.securesms.video.videoconverter.MediaConverter;
import org.stalker.securesms.video.videoconverter.exceptions.EncodingException;
import org.stalker.securesms.video.videoconverter.mediadatasource.MediaDataSourceMediaInput;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@RequiresApi(26)
public final class InMemoryTranscoder implements Closeable {

  private static final String TAG = Log.tag(InMemoryTranscoder.class);

  private final           Context            context;
  private final           MediaDataSource    dataSource;
  private final           long               upperSizeLimit;
  private final           long               inSize;
  private final           long               duration;
  private final           int                inputBitRate;
  private final           TranscodingQuality targetQuality;
  private final           long               memoryFileEstimate;
  private final           boolean            transcodeRequired;
  private final           long               fileSizeEstimate;
  private final @Nullable TranscoderOptions  options;

  private @Nullable MemoryFileDescriptor memoryFile;

  /**
   * @param upperSizeLimit A upper size to transcode to. The actual output size can be up to 10% smaller.
   */
  public InMemoryTranscoder(@NonNull Context context, @NonNull MediaDataSource dataSource, @Nullable TranscoderOptions options, @NonNull TranscodingPreset preset, long upperSizeLimit) throws IOException, VideoSourceException {
    this.context    = context;
    this.dataSource = dataSource;
    this.options    = options;

    final MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
    try {
      mediaMetadataRetriever.setDataSource(dataSource);
    } catch (RuntimeException e) {
      Log.w(TAG, "Unable to read datasource", e);
      throw new VideoSourceException("Unable to read datasource", e);
    }

    if (options != null && options.endTimeUs != 0) {
      this.duration = TimeUnit.MICROSECONDS.toMillis(options.endTimeUs - options.startTimeUs);
    } else {
      this.duration = getDuration(mediaMetadataRetriever);
    }

    this.inSize         = dataSource.getSize();
    this.inputBitRate   = TranscodingQuality.bitRate(inSize, duration);
    this.targetQuality  = TranscodingQuality.createFromPreset(preset, duration);
    this.upperSizeLimit = upperSizeLimit;

    this.transcodeRequired = inputBitRate >= targetQuality.getTargetTotalBitRate() * 1.2 || inSize > upperSizeLimit || containsLocation(mediaMetadataRetriever) || options != null;
    if (!transcodeRequired) {
      Log.i(TAG, "Video is within 20% of target bitrate, below the size limit, contained no location metadata or custom options.");
    }

    this.fileSizeEstimate   = targetQuality.getByteCountEstimate();
    this.memoryFileEstimate = (long) (fileSizeEstimate * 1.1);
  }

  public @NonNull MediaStream transcode(@NonNull Progress progress,
                                        @Nullable TranscoderCancelationSignal cancelationSignal)
      throws IOException, EncodingException
  {
    if (memoryFile != null) throw new AssertionError("Not expecting to reuse transcoder");

    float durationSec = duration / 1000f;

    NumberFormat numberFormat = NumberFormat.getInstance(Locale.US);

    Log.i(TAG, String.format(Locale.US,
                             "Transcoding:\n" +
                             "Target bitrate : %s + %s = %s\n" +
                             "Target format  : %dp\n" +
                             "Video duration : %.1fs\n" +
                             "Size limit     : %s kB\n" +
                             "Estimate       : %s kB\n" +
                             "Input size     : %s kB\n" +
                             "Input bitrate  : %s bps",
                             numberFormat.format(targetQuality.getTargetVideoBitRate()),
                             numberFormat.format(targetQuality.getTargetAudioBitRate()),
                             numberFormat.format(targetQuality.getTargetTotalBitRate()),
                             targetQuality.getOutputResolution(),
                             durationSec,
                             numberFormat.format(upperSizeLimit / 1024),
                             numberFormat.format(fileSizeEstimate / 1024),
                             numberFormat.format(inSize / 1024),
                             numberFormat.format(inputBitRate)));

    if (fileSizeEstimate > upperSizeLimit) {
      throw new VideoSizeException("Size constraints could not be met!");
    }

    memoryFile = MemoryFileDescriptor.newMemoryFileDescriptor(context,
                                                              "TRANSCODE",
                                                              memoryFileEstimate);
    final long startTime = System.currentTimeMillis();

    final FileDescriptor memoryFileFileDescriptor = memoryFile.getFileDescriptor();

    final MediaConverter converter = new MediaConverter();

    converter.setInput(new MediaDataSourceMediaInput(dataSource));
    converter.setOutput(memoryFileFileDescriptor);
    converter.setVideoResolution(targetQuality.getOutputResolution());
    converter.setVideoBitrate(targetQuality.getTargetVideoBitRate());
    converter.setAudioBitrate(targetQuality.getTargetAudioBitRate());

    if (options != null) {
      if (options.endTimeUs > 0) {
        long timeFrom = options.startTimeUs / 1000;
        long timeTo   = options.endTimeUs   / 1000;
        converter.setTimeRange(timeFrom, timeTo);
        Log.i(TAG, String.format(Locale.US, "Trimming:\nTotal duration: %d\nKeeping: %d..%d\nFinal duration:(%d)", duration, timeFrom, timeTo, timeTo - timeFrom));
      }
    }

    converter.setListener(percent -> {
      progress.onProgress(percent);
      return cancelationSignal != null && cancelationSignal.isCanceled();
    });

    converter.convert();

    memoryFile.seek(0);

    // output details of the transcoding
    long  outSize           = memoryFile.size();
    float encodeDurationSec = (System.currentTimeMillis() - startTime) / 1000f;

    Log.i(TAG, String.format(Locale.US,
                             "Transcoding complete:\n" +
                             "Transcode time : %.1fs (%.1fx)\n" +
                             "Output size    : %s kB\n" +
                             "  of Original  : %.1f%%\n" +
                             "  of Estimate  : %.1f%%\n" +
                             "  of Memory    : %.1f%%\n" +
                             "Output bitrate : %s bps",
                             encodeDurationSec,
                             durationSec / encodeDurationSec,
                             numberFormat.format(outSize / 1024),
                             (outSize * 100d) / inSize,
                             (outSize * 100d) / fileSizeEstimate,
                             (outSize * 100d) / memoryFileEstimate,
                             numberFormat.format(TranscodingQuality.bitRate(outSize, duration))));

    if (outSize > upperSizeLimit) {
      throw new VideoSizeException("Size constraints could not be met!");
    }

    try {
      final Mp4FaststartPostProcessor postProcessor = new Mp4FaststartPostProcessor(() -> {
        try {
          memoryFile.seek(0);
          return new FileInputStream(memoryFileFileDescriptor);
        } catch (IOException e) {
          Log.w(TAG, "IOException thrown while creating FileInputStream.", e);
          throw new VideoPostProcessingException("Exception while opening InputStream!", e);
        }
      });

      return new MediaStream(postProcessor.process(outSize), MimeTypes.VIDEO_MP4, 0, 0, true);
    } catch (VideoPostProcessingException e) {
      Log.w(TAG, "Exception thrown during post processing.", e);
      final Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      } else if (cause instanceof EncodingException) {
        throw (EncodingException) cause;
      }
    }

    memoryFile.seek(0);
    return new MediaStream(new FileInputStream(memoryFileFileDescriptor), MimeTypes.VIDEO_MP4, 0, 0);
  }

  public boolean isTranscodeRequired() {
    return transcodeRequired;
  }

  @Override
  public void close() throws IOException {
    if (memoryFile != null) {
      memoryFile.close();
    }
  }

  private static long getDuration(MediaMetadataRetriever mediaMetadataRetriever) throws VideoSourceException {
    String durationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
    if (durationString == null) {
      throw new VideoSourceException("Cannot determine duration of video, null meta data");
    }
    try {
      long duration = Long.parseLong(durationString);
      if (duration <= 0) {
        throw new VideoSourceException("Cannot determine duration of video, meta data: " + durationString);
      }
      return duration;
    } catch (NumberFormatException e) {
      throw new VideoSourceException("Cannot determine duration of video, meta data: " + durationString, e);
    }
  }

  private static boolean containsLocation(MediaMetadataRetriever mediaMetadataRetriever) {
    String locationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
    return locationString != null;
  }

  public interface Progress {
    void onProgress(int percent);
  }
}
