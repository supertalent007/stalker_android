package org.stalker.securesms.recipients;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.contacts.sync.ContactDiscovery;
import org.stalker.securesms.database.GroupTable;
import org.stalker.securesms.database.RecipientTable.RegisteredState;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.ThreadTable;
import org.stalker.securesms.database.model.GroupRecord;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.groups.GroupChangeBusyException;
import org.stalker.securesms.groups.GroupChangeException;
import org.stalker.securesms.groups.GroupChangeFailedException;
import org.stalker.securesms.groups.GroupManager;
import org.stalker.securesms.jobs.MultiDeviceBlockedUpdateJob;
import org.stalker.securesms.jobs.RefreshOwnProfileJob;
import org.stalker.securesms.jobs.RotateProfileKeyJob;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.mms.OutgoingMessage;
import org.stalker.securesms.sms.MessageSender;
import org.stalker.securesms.storage.StorageSyncHelper;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class RecipientUtil {

  private static final String TAG = Log.tag(RecipientUtil.class);

  /**
   * This method will do it's best to get a {@link ServiceId} for the provided recipient. This includes performing
   * a possible network request if no ServiceId is available. If the request to get a ServiceId fails or the user is
   * not registered, an IOException is thrown.
   */
  @WorkerThread
  public static @NonNull ServiceId getOrFetchServiceId(@NonNull Context context, @NonNull Recipient recipient) throws IOException {
    return toSignalServiceAddress(context, recipient).getServiceId();
  }

  /**
   * This method will do it's best to craft a fully-populated {@link SignalServiceAddress} based on
   * the provided recipient. This includes performing a possible network request if no UUID is
   * available. If the request to get a UUID fails or the user is not registered, an IOException is thrown.
   */
  @WorkerThread
  public static @NonNull SignalServiceAddress toSignalServiceAddress(@NonNull Context context, @NonNull Recipient recipient)
      throws IOException
  {
    recipient = recipient.resolve();

    if (!recipient.getServiceId().isPresent() && !recipient.getE164().isPresent()) {
      throw new AssertionError(recipient.getId() + " - No UUID or phone number!");
    }

    if (!recipient.getServiceId().isPresent()) {
      Log.i(TAG, recipient.getId() + " is missing a UUID...");
      RegisteredState state = ContactDiscovery.refresh(context, recipient, false);

      recipient = Recipient.resolved(recipient.getId());
      Log.i(TAG, "Successfully performed a UUID fetch for " + recipient.getId() + ". Registered: " + state);
    }

    if (recipient.getHasServiceId()) {
      return new SignalServiceAddress(recipient.requireServiceId(), Optional.ofNullable(recipient.resolve().getE164().orElse(null)));
    } else {
      throw new NotFoundException(recipient.getId() + " is not registered!");
    }
  }

  public static @NonNull List<SignalServiceAddress> toSignalServiceAddresses(@NonNull Context context, @NonNull List<RecipientId> recipients)
      throws IOException
  {
    return toSignalServiceAddressesFromResolved(context, Recipient.resolvedList(recipients));
  }

  public static @NonNull List<SignalServiceAddress> toSignalServiceAddressesFromResolved(@NonNull Context context, @NonNull List<Recipient> recipients)
      throws IOException
  {
    ensureUuidsAreAvailable(context, recipients);

    return Stream.of(recipients)
                 .map(Recipient::resolve)
                 .map(r -> new SignalServiceAddress(r.requireServiceId(), r.getE164().orElse(null)))
                 .toList();
  }

  /**
   * Ensures that UUIDs are available. If a UUID cannot be retrieved or a user is found to be unregistered, an exception is thrown.
   */
  public static boolean ensureUuidsAreAvailable(@NonNull Context context, @NonNull Collection<Recipient> recipients)
      throws IOException
  {
    List<Recipient> recipientsWithoutUuids = Stream.of(recipients)
                                                   .map(Recipient::resolve)
                                                   .filterNot(Recipient::getHasServiceId)
                                                   .toList();

    if (recipientsWithoutUuids.size() > 0) {
      ContactDiscovery.refresh(context, recipientsWithoutUuids, false);

      if (recipients.stream().map(Recipient::resolve).anyMatch(Recipient::isUnregistered)) {
        throw new NotFoundException("1 or more recipients are not registered!");
      }

      return true;
    } else {
      return false;
    }
  }

  public static boolean isBlockable(@NonNull Recipient recipient) {
    Recipient resolved = recipient.resolve();
    return !resolved.isMmsGroup();
  }

  public static List<Recipient> getEligibleForSending(@NonNull List<Recipient> recipients) {
    return Stream.of(recipients)
                 .filter(r -> r.getRegistered() != RegisteredState.NOT_REGISTERED)
                 .filter(r -> !r.isBlocked())
                 .toList();
  }

  /**
   * You can call this for non-groups and not have to handle any network errors.
   */
  @WorkerThread
  public static void blockNonGroup(@NonNull Context context, @NonNull Recipient recipient) {
    if (recipient.isGroup()) {
      throw new AssertionError();
    }

    try {
      block(context, recipient);
    } catch (GroupChangeException | IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * You can call this for any type of recipient but must handle network errors that can occur from
   * GV2.
   * <p>
   * GV2 operations can also take longer due to the network.
   */
  @WorkerThread
  public static void block(@NonNull Context context, @NonNull Recipient recipient)
      throws GroupChangeBusyException, IOException, GroupChangeFailedException
  {
    if (!isBlockable(recipient)) {
      throw new AssertionError("Recipient is not blockable!");
    }
    Log.w(TAG, "Blocking " + recipient.getId() + " (group: " + recipient.isGroup() + ")");

    recipient = recipient.resolve();

    if (recipient.isGroup() && recipient.getGroupId().get().isPush()) {
      GroupManager.leaveGroupFromBlockOrMessageRequest(context, recipient.getGroupId().get().requirePush());
    }

    SignalDatabase.recipients().setBlocked(recipient.getId(), true);

    if (recipient.isSystemContact() || recipient.isProfileSharing() || isProfileSharedViaGroup(recipient)) {
      SignalDatabase.recipients().setProfileSharing(recipient.getId(), false);

      ApplicationDependencies.getJobManager().startChain(new RefreshOwnProfileJob())
                                             .then(new RotateProfileKeyJob())
                                             .enqueue();
    }

    ApplicationDependencies.getJobManager().add(new MultiDeviceBlockedUpdateJob());
    StorageSyncHelper.scheduleSyncForDataChange();
  }

  @WorkerThread
  public static void unblock(@NonNull Recipient recipient) {
    if (!isBlockable(recipient)) {
      throw new AssertionError("Recipient is not blockable!");
    }
    Log.i(TAG, "Unblocking " + recipient.getId() + " (group: " + recipient.isGroup() + ")", new Throwable());

    SignalDatabase.recipients().setBlocked(recipient.getId(), false);
    SignalDatabase.recipients().setProfileSharing(recipient.getId(), true);
    ApplicationDependencies.getJobManager().add(new MultiDeviceBlockedUpdateJob());
    StorageSyncHelper.scheduleSyncForDataChange();
  }

  @WorkerThread
  public static Recipient.HiddenState getRecipientHiddenState(long threadId) {
    if (threadId < 0) {
      return Recipient.HiddenState.NOT_HIDDEN;
    }

    ThreadTable threadTable     = SignalDatabase.threads();
    Recipient   threadRecipient = threadTable.getRecipientForThreadId(threadId);

    if (threadRecipient == null) {
      return Recipient.HiddenState.NOT_HIDDEN;
    }

    return threadRecipient.getHiddenState();
  }

  @WorkerThread
  public static boolean isRecipientHidden(long threadId) {
    if (threadId < 0) {
      return false;
    }

    ThreadTable threadTable     = SignalDatabase.threads();
    Recipient   threadRecipient = threadTable.getRecipientForThreadId(threadId);

    if (threadRecipient == null) {
      return false;
    }

    return threadRecipient.isHidden();
  }

  /**
   * If true, the new message request UI does not need to be shown, and it's safe to send read
   * receipts.
   *
   * Note that this does not imply that a user has explicitly accepted a message request -- it could
   * also be the case that the thread in question is for a system contact or something of the like.
   */
  @WorkerThread
  public static boolean isMessageRequestAccepted(@NonNull Context context, long threadId) {
    if (threadId < 0) {
      return true;
    }

    ThreadTable threadTable     = SignalDatabase.threads();
    Recipient   threadRecipient = threadTable.getRecipientForThreadId(threadId);

    if (threadRecipient == null) {
      return true;
    }

    return isMessageRequestAccepted(threadId, threadRecipient);
  }

  /**
   * See {@link #isMessageRequestAccepted(Context, long)}.
   */
  @WorkerThread
  public static boolean isMessageRequestAccepted(@NonNull Context context, @Nullable Recipient threadRecipient) {
    if (threadRecipient == null) {
      return true;
    }

    Long threadId = SignalDatabase.threads().getThreadIdFor(threadRecipient.getId());
    return isMessageRequestAccepted(threadId, threadRecipient);
  }

  /**
   * Like {@link #isMessageRequestAccepted(Context, long)} but with fewer checks around messages so it
   * is more likely to return false.
   */
  @WorkerThread
  public static boolean isCallRequestAccepted(@Nullable Recipient threadRecipient) {
    if (threadRecipient == null) {
      return true;
    }

    Long threadId = SignalDatabase.threads().getThreadIdFor(threadRecipient.getId());
    return isCallRequestAccepted(threadId, threadRecipient);
  }

  @WorkerThread
  public static void shareProfileIfFirstSecureMessage(@NonNull Recipient recipient) {
    if (recipient.isProfileSharing()) {
      return;
    }

    long    threadId     = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());
    boolean firstMessage = SignalDatabase.messages().getOutgoingSecureMessageCount(threadId) == 0;

    if (firstMessage || recipient.isHidden()) {
      SignalDatabase.recipients().setProfileSharing(recipient.getId(), true);
    }
  }

  public static boolean isLegacyProfileSharingAccepted(@NonNull Recipient threadRecipient) {
    return threadRecipient.isSelf()           ||
           threadRecipient.isProfileSharing() ||
           threadRecipient.isSystemContact()  ||
           !threadRecipient.isRegistered()    ||
           threadRecipient.isHidden();
  }

  /**
   * @return True if this recipient should already have your profile key, otherwise false.
   */
  public static boolean shouldHaveProfileKey(@NonNull Recipient recipient) {
    if (recipient.isBlocked()) {
      return false;
    }

    if (recipient.isProfileSharing()) {
      return true;
    } else {
      GroupTable groupDatabase = SignalDatabase.groups();
      return groupDatabase.getPushGroupsContainingMember(recipient.getId())
                          .stream()
                          .filter(GroupRecord::isV2Group)
                          .anyMatch(group -> group.memberLevel(Recipient.self()).isInGroup());

    }
  }

  /**
   * Checks if a universal timer is set and if the thread should have it set on it. Attempts to abort quickly and perform
   * minimal database access.
   */
  @WorkerThread
  public static boolean setAndSendUniversalExpireTimerIfNecessary(@NonNull Context context, @NonNull Recipient recipient, long threadId) {
    int defaultTimer = SignalStore.settings().getUniversalExpireTimer();
    if (defaultTimer == 0 || recipient.isGroup() || recipient.isDistributionList() || recipient.getExpiresInSeconds() != 0 || !recipient.isRegistered()) {
      return false;
    }

    if (threadId == -1 || SignalDatabase.messages().canSetUniversalTimer(threadId)) {
      SignalDatabase.recipients().setExpireMessages(recipient.getId(), defaultTimer);
      OutgoingMessage outgoingMessage = OutgoingMessage.expirationUpdateMessage(recipient, System.currentTimeMillis(), defaultTimer * 1000L);
      MessageSender.send(context, outgoingMessage, SignalDatabase.threads().getOrCreateThreadIdFor(recipient), MessageSender.SendType.SIGNAL, null, null);
      return true;
    }
    return false;
  }

  @WorkerThread
  public static boolean isMessageRequestAccepted(@Nullable Long threadId, @Nullable Recipient threadRecipient) {
    return threadRecipient == null ||
           threadRecipient.isSelf() ||
           threadRecipient.isProfileSharing() ||
           threadRecipient.isSystemContact() ||
           !threadRecipient.isRegistered() ||
           (!threadRecipient.isHidden() && (
               hasSentMessageInThread(threadId) ||
               noSecureMessagesAndNoCallsInThread(threadId))
           );
  }

  @WorkerThread
  private static boolean isCallRequestAccepted(@Nullable Long threadId, @NonNull Recipient threadRecipient) {
    return threadRecipient.isProfileSharing() ||
           threadRecipient.isSystemContact() ||
           hasSentMessageInThread(threadId);
  }

  @WorkerThread
  public static boolean hasSentMessageInThread(@Nullable Long threadId) {
    return threadId != null && SignalDatabase.messages().getOutgoingSecureMessageCount(threadId) != 0;
  }

  public static boolean isSmsOnly(long threadId, @NonNull Recipient threadRecipient) {
    return !threadRecipient.isRegistered() ||
           noSecureMessagesAndNoCallsInThread(threadId);
  }

  @WorkerThread
  private static boolean noSecureMessagesAndNoCallsInThread(@Nullable Long threadId) {
    if (threadId == null) {
      return true;
    }

    return SignalDatabase.messages().getSecureMessageCount(threadId) == 0 &&
           !SignalDatabase.threads().hasReceivedAnyCallsSince(threadId, 0);
  }

  @WorkerThread
  private static boolean isProfileSharedViaGroup(@NonNull Recipient recipient) {
    return Stream.of(SignalDatabase.groups().getPushGroupsContainingMember(recipient.getId()))
                 .anyMatch(group -> Recipient.resolved(group.getRecipientId()).isProfileSharing());
  }
}
