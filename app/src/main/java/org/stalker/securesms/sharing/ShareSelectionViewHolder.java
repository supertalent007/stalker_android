package org.stalker.securesms.sharing;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import org.stalker.securesms.R;
import org.stalker.securesms.util.adapter.mapping.Factory;
import org.stalker.securesms.util.adapter.mapping.LayoutFactory;
import org.stalker.securesms.util.adapter.mapping.MappingViewHolder;

public class ShareSelectionViewHolder extends MappingViewHolder<ShareSelectionMappingModel> {

  protected final @NonNull TextView name;

  public ShareSelectionViewHolder(@NonNull View itemView) {
    super(itemView);

    name = findViewById(R.id.recipient_view_name);
  }

  @Override
  public void bind(@NonNull ShareSelectionMappingModel model) {
    name.setText(model.getName(context));
  }

  public static @NonNull Factory<ShareSelectionMappingModel> createFactory(@LayoutRes int layout) {
    return new LayoutFactory<>(ShareSelectionViewHolder::new, layout);
  }
}
