package org.stalker.securesms.sharing.interstitial;

import org.stalker.securesms.R;
import org.stalker.securesms.util.adapter.mapping.MappingAdapter;
import org.stalker.securesms.util.viewholders.RecipientViewHolder;

class ShareInterstitialSelectionAdapter extends MappingAdapter {
  ShareInterstitialSelectionAdapter() {
    registerFactory(ShareInterstitialMappingModel.class, RecipientViewHolder.createFactory(R.layout.share_contact_selection_item, null));
  }
}
