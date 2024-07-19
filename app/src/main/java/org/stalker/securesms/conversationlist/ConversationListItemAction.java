package org.stalker.securesms.conversationlist;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import com.bumptech.glide.RequestManager;

import org.stalker.securesms.BindableConversationListItem;
import org.stalker.securesms.R;
import org.stalker.securesms.conversationlist.model.ConversationSet;
import org.stalker.securesms.database.model.ThreadRecord;

import java.util.Locale;
import java.util.Set;

public class ConversationListItemAction extends FrameLayout implements BindableConversationListItem {

  private TextView description;

  public ConversationListItemAction(Context context) {
    super(context);
  }

  public ConversationListItemAction(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public ConversationListItemAction(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();
    this.description = findViewById(R.id.description);
  }

  @Override
  public void bind(@NonNull LifecycleOwner lifecycleOwner,
                   @NonNull ThreadRecord thread,
                   @NonNull RequestManager requestManager,
                   @NonNull Locale locale,
                   @NonNull Set<Long> typingThreads,
                   @NonNull ConversationSet selectedConversations)
  {
    this.description.setText(getContext().getString(R.string.ConversationListItemAction_archived_conversations_d, thread.getUnreadCount()));
  }

  @Override
  public void unbind() {

  }

  @Override
  public void setSelectedConversations(@NonNull ConversationSet conversations) {

  }

  @Override
  public void updateTypingIndicator(@NonNull Set<Long> typingThreads) {

  }

  @Override
  public void updateTimestamp() {
    // Intentionally left blank.
  }
}
