package org.stalker.securesms.recipients.ui.bottomsheet;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.contacts.sync.ContactDiscovery;
import org.stalker.securesms.database.GroupTable;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.model.GroupRecord;
import org.stalker.securesms.database.model.IdentityRecord;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.groups.GroupChangeException;
import org.stalker.securesms.groups.GroupId;
import org.stalker.securesms.groups.GroupManager;
import org.stalker.securesms.groups.ui.GroupChangeErrorCallback;
import org.stalker.securesms.groups.ui.GroupChangeFailureReason;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.recipients.RecipientId;
import org.signal.core.util.concurrent.SimpleTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class RecipientDialogRepository {

  private static final String TAG = Log.tag(RecipientDialogRepository.class);

  @NonNull  private final Context     context;
  @NonNull  private final RecipientId recipientId;
  @Nullable private final GroupId     groupId;

  RecipientDialogRepository(@NonNull Context context,
                            @NonNull RecipientId recipientId,
                            @Nullable GroupId groupId)
  {
    this.context     = context;
    this.recipientId = recipientId;
    this.groupId     = groupId;
  }

  @NonNull RecipientId getRecipientId() {
    return recipientId;
  }

  @Nullable GroupId getGroupId() {
    return groupId;
  }

  void getIdentity(@NonNull Consumer<IdentityRecord> callback) {
    SignalExecutors.BOUNDED.execute(
      () -> callback.accept(ApplicationDependencies.getProtocolStore().aci().identities().getIdentityRecord(recipientId).orElse(null)));
  }

  void getRecipient(@NonNull RecipientCallback recipientCallback) {
    SimpleTask.run(SignalExecutors.BOUNDED,
                   () -> Recipient.resolved(recipientId),
                   recipientCallback::onRecipient);
  }

  void refreshRecipient() {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        ContactDiscovery.refresh(context, Recipient.resolved(recipientId), false);
      } catch (IOException e) {
        Log.w(TAG, "Failed to refresh user after adding to contacts.");
      }
    });
  }

  void removeMember(@NonNull Consumer<Boolean> onComplete, @NonNull GroupChangeErrorCallback error) {
    SimpleTask.run(SignalExecutors.UNBOUNDED,
                   () -> {
                     try {
                       GroupManager.ejectAndBanFromGroup(context, Objects.requireNonNull(groupId).requireV2(), Recipient.resolved(recipientId));
                       return true;
                     } catch (GroupChangeException | IOException e) {
                       Log.w(TAG, e);
                       error.onError(GroupChangeFailureReason.fromException(e));
                     }
                     return false;
                   },
                   onComplete::accept);
  }

  void setMemberAdmin(boolean admin, @NonNull Consumer<Boolean> onComplete, @NonNull GroupChangeErrorCallback error) {
    SimpleTask.run(SignalExecutors.UNBOUNDED,
                   () -> {
                     try {
                       GroupManager.setMemberAdmin(context, Objects.requireNonNull(groupId).requireV2(), recipientId, admin);
                       return true;
                     } catch (GroupChangeException | IOException e) {
                       Log.w(TAG, e);
                       error.onError(GroupChangeFailureReason.fromException(e));
                     }
                     return false;
                   },
                   onComplete::accept);
  }

  void getGroupMembership(@NonNull Consumer<List<RecipientId>> onComplete) {
    SimpleTask.run(SignalExecutors.UNBOUNDED,
                   () -> {
                     GroupTable             groupDatabase   = SignalDatabase.groups();
                     List<GroupRecord>      groupRecords    = groupDatabase.getPushGroupsContainingMember(recipientId);
                     ArrayList<RecipientId> groupRecipients = new ArrayList<>(groupRecords.size());

                     for (GroupRecord groupRecord : groupRecords) {
                       groupRecipients.add(groupRecord.getRecipientId());
                     }

                     return groupRecipients;
                   },
                   onComplete::accept);
  }

  public void getActiveGroupCount(@NonNull Consumer<Integer> onComplete) {
    SignalExecutors.BOUNDED.execute(() -> onComplete.accept(SignalDatabase.groups().getActiveGroupCount()));
  }

  interface RecipientCallback {
    void onRecipient(@NonNull Recipient recipient);
  }
}
