package org.stalker.securesms.messagedetails;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.signal.core.util.concurrent.SignalExecutors;
import org.stalker.securesms.conversation.ConversationMessage.ConversationMessageFactory;
import org.stalker.securesms.database.DatabaseObserver;
import org.stalker.securesms.database.GroupTable;
import org.stalker.securesms.database.GroupReceiptTable;
import org.stalker.securesms.database.NoSuchMessageException;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.documents.IdentityKeyMismatch;
import org.stalker.securesms.database.documents.NetworkFailure;
import org.stalker.securesms.database.model.MessageId;
import org.stalker.securesms.database.model.MessageRecord;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.recipients.RecipientId;
import org.whispersystems.signalservice.api.push.DistributionId;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class MessageDetailsRepository {

  private final Context context = ApplicationDependencies.getApplication();

  @NonNull LiveData<MessageRecord> getMessageRecord(Long messageId) {
    return new MessageRecordLiveData(new MessageId(messageId));
  }

  @NonNull LiveData<MessageDetails> getMessageDetails(@Nullable MessageRecord messageRecord) {
    final MutableLiveData<MessageDetails> liveData = new MutableLiveData<>();

    if (messageRecord != null) {
      SignalExecutors.BOUNDED.execute(() -> liveData.postValue(getRecipientDeliveryStatusesInternal(messageRecord)));
    } else {
      liveData.setValue(null);
    }

    return liveData;
  }

  public @NonNull Observable<MessageDetails> getMessageDetails(@NonNull MessageId messageId) {
    return Observable.<MessageDetails>create(emitter -> {
      DatabaseObserver.MessageObserver messageObserver = mId -> {
        try {
          MessageRecord  messageRecord  = SignalDatabase.messages().getMessageRecord(messageId.getId());
          MessageDetails messageDetails = getRecipientDeliveryStatusesInternal(messageRecord);

          emitter.onNext(messageDetails);
        } catch (NoSuchMessageException e) {
          emitter.onError(e);
        }
      };

      ApplicationDependencies.getDatabaseObserver().registerMessageUpdateObserver(messageObserver);
      emitter.setCancellable(() -> ApplicationDependencies.getDatabaseObserver().unregisterObserver(messageObserver));

      messageObserver.onMessageChanged(messageId);
    }).observeOn(Schedulers.io());
  }

  @WorkerThread
  private @NonNull MessageDetails getRecipientDeliveryStatusesInternal(@NonNull MessageRecord messageRecord) {
    List<RecipientDeliveryStatus> recipients = new LinkedList<>();

    if (!messageRecord.getToRecipient().isGroup() && !messageRecord.getToRecipient().isDistributionList()) {
      recipients.add(new RecipientDeliveryStatus(messageRecord,
                                                 messageRecord.isOutgoing() ? messageRecord.getToRecipient() : messageRecord.getFromRecipient(),
                                                 getStatusFor(messageRecord),
                                                 messageRecord.isUnidentified(),
                                                 messageRecord.getReceiptTimestamp(),
                                                 getNetworkFailure(messageRecord, messageRecord.getToRecipient()),
                                                 getKeyMismatchFailure(messageRecord, messageRecord.getToRecipient())));
    } else {
      List<GroupReceiptTable.GroupReceiptInfo> receiptInfoList = SignalDatabase.groupReceipts().getGroupReceiptInfo(messageRecord.getId());

      if (receiptInfoList.isEmpty() && messageRecord.getToRecipient().isGroup()) {
        List<Recipient> group = SignalDatabase.groups().getGroupMembers(messageRecord.getToRecipient().requireGroupId(), GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);

        for (Recipient recipient : group) {
          recipients.add(new RecipientDeliveryStatus(messageRecord,
                                                     recipient,
                                                     RecipientDeliveryStatus.Status.UNKNOWN,
                                                     false,
                                                     messageRecord.getReceiptTimestamp(),
                                                     getNetworkFailure(messageRecord, recipient),
                                                     getKeyMismatchFailure(messageRecord, recipient)));
        }
      } else if (receiptInfoList.isEmpty() && messageRecord.getToRecipient().isDistributionList()) {
        DistributionId   distributionId = SignalDatabase.distributionLists().getDistributionId(messageRecord.getToRecipient().requireDistributionListId());
        Set<RecipientId> recipientIds   = SignalDatabase.storySends().getRecipientsForDistributionId(messageRecord.getId(), Objects.requireNonNull(distributionId));
        List<Recipient>  resolved       = Recipient.resolvedList(recipientIds);

        for (Recipient recipient : resolved) {
          recipients.add(new RecipientDeliveryStatus(messageRecord,
                                                     recipient,
                                                     RecipientDeliveryStatus.Status.UNKNOWN,
                                                     false,
                                                     messageRecord.getReceiptTimestamp(),
                                                     getNetworkFailure(messageRecord, recipient),
                                                     getKeyMismatchFailure(messageRecord, recipient)));
        }
      } else {
        for (GroupReceiptTable.GroupReceiptInfo info : receiptInfoList) {
          Recipient           recipient        = Recipient.resolved(info.getRecipientId());
          NetworkFailure      failure          = getNetworkFailure(messageRecord, recipient);
          IdentityKeyMismatch mismatch         = getKeyMismatchFailure(messageRecord, recipient);
          boolean             recipientFailure = failure != null || mismatch != null;

          recipients.add(new RecipientDeliveryStatus(messageRecord,
                                                     recipient,
                                                     getStatusFor(info.getStatus(), messageRecord.isPending(), recipientFailure),
                                                     info.isUnidentified(),
                                                     info.getTimestamp(),
                                                     failure,
                                                     mismatch));
        }
      }
    }

    Recipient threadRecipient = Objects.requireNonNull(SignalDatabase.threads().getRecipientForThreadId(messageRecord.getThreadId()));
    return new MessageDetails(ConversationMessageFactory.createWithUnresolvedData(context, messageRecord, threadRecipient), recipients);
  }

  private @Nullable NetworkFailure getNetworkFailure(MessageRecord messageRecord, Recipient recipient) {
    if (messageRecord.hasNetworkFailures()) {
      for (final NetworkFailure failure : messageRecord.getNetworkFailures()) {
        if (failure.getRecipientId().equals(recipient.getId())) {
          return failure;
        }
      }
    }
    return null;
  }

  private @Nullable IdentityKeyMismatch getKeyMismatchFailure(MessageRecord messageRecord, Recipient recipient) {
    if (messageRecord.isIdentityMismatchFailure()) {
      for (final IdentityKeyMismatch mismatch : messageRecord.getIdentityKeyMismatches()) {
        if (mismatch.getRecipientId().equals(recipient.getId())) {
          return mismatch;
        }
      }
    }
    return null;
  }

  private @NonNull RecipientDeliveryStatus.Status getStatusFor(MessageRecord messageRecord) {
    if (messageRecord.isViewed())       return RecipientDeliveryStatus.Status.VIEWED;
    if (messageRecord.hasReadReceipt()) return RecipientDeliveryStatus.Status.READ;
    if (messageRecord.isDelivered())    return RecipientDeliveryStatus.Status.DELIVERED;
    if (messageRecord.isSent())         return RecipientDeliveryStatus.Status.SENT;
    if (messageRecord.isPending())      return RecipientDeliveryStatus.Status.PENDING;

    return RecipientDeliveryStatus.Status.UNKNOWN;
  }

  private @NonNull RecipientDeliveryStatus.Status getStatusFor(int groupStatus, boolean pending, boolean failed) {
    if      (groupStatus == GroupReceiptTable.STATUS_READ)                    return RecipientDeliveryStatus.Status.READ;
    else if (groupStatus == GroupReceiptTable.STATUS_DELIVERED)               return RecipientDeliveryStatus.Status.DELIVERED;
    else if (groupStatus == GroupReceiptTable.STATUS_UNDELIVERED && failed)   return RecipientDeliveryStatus.Status.UNKNOWN;
    else if (groupStatus == GroupReceiptTable.STATUS_UNDELIVERED && !pending) return RecipientDeliveryStatus.Status.SENT;
    else if (groupStatus == GroupReceiptTable.STATUS_UNDELIVERED)             return RecipientDeliveryStatus.Status.PENDING;
    else if (groupStatus == GroupReceiptTable.STATUS_UNKNOWN)                 return RecipientDeliveryStatus.Status.UNKNOWN;
    else if (groupStatus == GroupReceiptTable.STATUS_VIEWED)                  return RecipientDeliveryStatus.Status.VIEWED;
    else if (groupStatus == GroupReceiptTable.STATUS_SKIPPED)                 return RecipientDeliveryStatus.Status.SKIPPED;
    throw new AssertionError();
  }
}
