package org.stalker.securesms.payments.preferences.viewholder;

import android.view.View;

import androidx.annotation.NonNull;

import org.stalker.securesms.R;
import org.stalker.securesms.payments.preferences.PaymentsHomeAdapter;
import org.stalker.securesms.payments.preferences.model.SeeAll;
import org.stalker.securesms.util.adapter.mapping.MappingViewHolder;

public class SeeAllViewHolder extends MappingViewHolder<SeeAll> {

  private final PaymentsHomeAdapter.Callbacks callbacks;
  private final View                          seeAllButton;

  public SeeAllViewHolder(@NonNull View itemView, PaymentsHomeAdapter.Callbacks callbacks) {
    super(itemView);
    this.callbacks = callbacks;
    this.seeAllButton = itemView.findViewById(R.id.payments_home_see_all_item_button);
  }

  @Override
  public void bind(@NonNull SeeAll model) {
    seeAllButton.setOnClickListener(v -> callbacks.onSeeAll(model.getPaymentType()));
  }
}
