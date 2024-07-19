package org.stalker.securesms.components.webrtc.participantslist;

import org.stalker.securesms.R;
import org.stalker.securesms.util.adapter.mapping.LayoutFactory;
import org.stalker.securesms.util.adapter.mapping.MappingAdapter;

public class CallParticipantsListAdapter extends MappingAdapter {

  CallParticipantsListAdapter() {
    registerFactory(CallParticipantsListHeader.class, new LayoutFactory<>(CallParticipantsListHeaderViewHolder::new, R.layout.call_participants_list_header));
    registerFactory(CallParticipantViewState.class, new LayoutFactory<>(CallParticipantViewHolder::new, R.layout.call_participants_list_item));
  }

}
