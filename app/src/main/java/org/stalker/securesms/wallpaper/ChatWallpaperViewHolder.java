package org.stalker.securesms.wallpaper;

import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.recyclerview.widget.RecyclerView;


import org.stalker.securesms.R;
import org.stalker.securesms.util.DisplayMetricsUtil;
import org.stalker.securesms.util.adapter.mapping.Factory;
import org.stalker.securesms.util.adapter.mapping.LayoutFactory;
import org.stalker.securesms.util.adapter.mapping.MappingViewHolder;

@OptIn(markerClass = UnstableApi.class)
class ChatWallpaperViewHolder extends MappingViewHolder<ChatWallpaperSelectionMappingModel> {

  private final AspectRatioFrameLayout frame;
  private final ImageView              preview;
  private final EventListener          eventListener;

  public ChatWallpaperViewHolder(@NonNull View itemView, @Nullable EventListener eventListener, @Nullable DisplayMetrics windowDisplayMetrics) {
    super(itemView);
    this.frame         = itemView.findViewById(R.id.chat_wallpaper_preview_frame);
    this.preview       = itemView.findViewById(R.id.chat_wallpaper_preview);
    this.eventListener = eventListener;

    if (windowDisplayMetrics != null) {
      DisplayMetricsUtil.forceAspectRatioToScreenByAdjustingHeight(windowDisplayMetrics, itemView);
    } else if (frame != null) {
      frame.setAspectRatio(1.0f);
    }
  }

  @Override
  public void bind(@NonNull ChatWallpaperSelectionMappingModel model) {
    model.loadInto(preview);

    preview.setColorFilter(ChatWallpaperDimLevelUtil.getDimColorFilterForNightMode(context, model.getWallpaper()));

    if (eventListener != null) {
      preview.setOnClickListener(unused -> {
        if (getAdapterPosition() != RecyclerView.NO_POSITION) {
          eventListener.onModelClick(model);
        }
      });
    }
  }

  public static @NonNull Factory<ChatWallpaperSelectionMappingModel> createFactory(@LayoutRes int layout, @Nullable EventListener listener, @Nullable DisplayMetrics windowDisplayMetrics) {
    return new LayoutFactory<>(view -> new ChatWallpaperViewHolder(view, listener, windowDisplayMetrics), layout);
  }

  public interface EventListener {
    default void onModelClick(@NonNull ChatWallpaperSelectionMappingModel model) {
      onClick(model.getWallpaper());
    }

    void onClick(@NonNull ChatWallpaper chatWallpaper);
  }
}
