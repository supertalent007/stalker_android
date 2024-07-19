package org.stalker.securesms.blocked;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.database.RecipientTable;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.groups.GroupChangeBusyException;
import org.stalker.securesms.groups.GroupChangeFailedException;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.recipients.RecipientId;
import org.stalker.securesms.recipients.RecipientUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class BlockedUsersRepository {

  private static final String TAG = Log.tag(BlockedUsersRepository.class);

  private final Context context;

  BlockedUsersRepository(@NonNull Context context) {
    this.context = context;
  }

  void getBlocked(@NonNull Consumer<List<Recipient>> blockedUsers) {
    SignalExecutors.BOUNDED.execute(() -> {
      RecipientTable db = SignalDatabase.recipients();
      try (RecipientTable.RecipientReader reader = db.readerForBlocked(db.getBlocked())) {
        int count = reader.getCount();
        if (count == 0) {
          blockedUsers.accept(Collections.emptyList());
        } else {
          List<Recipient> recipients = new ArrayList<>();
          while (reader.getNext() != null) {
            recipients.add(reader.getCurrent());
          }
          blockedUsers.accept(recipients);
        }
      }
    });
  }

  void block(@NonNull RecipientId recipientId, @NonNull Runnable success, @NonNull Runnable failure) {
    SignalExecutors.BOUNDED.execute(() -> {
      try {
        RecipientUtil.block(context, Recipient.resolved(recipientId));
        success.run();
      } catch (IOException | GroupChangeFailedException | GroupChangeBusyException e) {
        Log.w(TAG, "block: failed to block recipient: ", e);
        failure.run();
      }
    });
  }

  void createAndBlock(@NonNull String number, @NonNull Runnable success) {
    SignalExecutors.BOUNDED.execute(() -> {
      RecipientUtil.blockNonGroup(context, Recipient.external(context, number));
      success.run();
    });
  }

  void unblock(@NonNull RecipientId recipientId, @NonNull Runnable success) {
    SignalExecutors.BOUNDED.execute(() -> {
      RecipientUtil.unblock(Recipient.resolved(recipientId));
      success.run();
    });
  }
}
