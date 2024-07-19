package org.stalker.securesms.groups;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.signal.storageservice.protos.groups.GroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember;
import org.stalker.securesms.backup.v2.proto.GroupChangeChatUpdate;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.model.GroupsV2UpdateMessageConverter;
import org.stalker.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.stalker.securesms.database.model.databaseprotos.GV2UpdateDescription;
import org.stalker.securesms.database.model.databaseprotos.MessageExtras;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.recipients.RecipientId;
import org.whispersystems.signalservice.api.groupsv2.PartialDecryptedGroup;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.ServiceId.ACI;
import org.whispersystems.signalservice.internal.push.GroupContextV2;

import java.util.List;

import okio.ByteString;

public final class GroupProtoUtil {

  private GroupProtoUtil() {
  }

  public static int findRevisionWeWereAdded(@NonNull PartialDecryptedGroup partialDecryptedGroup, @NonNull ACI self)
      throws GroupNotAMemberException
  {
    ByteString bytes = self.toByteString();
    for (DecryptedMember decryptedMember : partialDecryptedGroup.getMembersList()) {
      if (decryptedMember.aciBytes.equals(bytes)) {
        return decryptedMember.joinedAtRevision;
      }
    }
    for (DecryptedPendingMember decryptedMember : partialDecryptedGroup.getPendingMembersList()) {
      if (decryptedMember.serviceIdBytes.equals(bytes)) {
        // Assume latest, we don't have any information about when pending members were invited
        return partialDecryptedGroup.getRevision();
      }
    }
    throw new GroupNotAMemberException();
  }

  public static GV2UpdateDescription createOutgoingGroupV2UpdateDescription(@NonNull GroupMasterKey masterKey,
                                                                            @NonNull GroupMutation groupMutation,
                                                                            @Nullable GroupChange signedServerChange)
  {
    DecryptedGroupV2Context groupV2Context = createDecryptedGroupV2Context(masterKey, groupMutation, signedServerChange);
    GroupChangeChatUpdate   update         = GroupsV2UpdateMessageConverter.translateDecryptedChange(SignalStore.account().getServiceIds(), groupV2Context);

    return new GV2UpdateDescription.Builder()
        .gv2ChangeDescription(groupV2Context)
        .groupChangeUpdate(update)
        .build();
  }

  public static DecryptedGroupV2Context createDecryptedGroupV2Context(@NonNull GroupMasterKey masterKey,
                                                                      @NonNull GroupMutation groupMutation,
                                                                      @Nullable GroupChange signedServerChange)
  {
    DecryptedGroupChange   plainGroupChange = groupMutation.getGroupChange();
    DecryptedGroup         decryptedGroup   = groupMutation.getNewGroupState();
    int                    revision         = plainGroupChange != null ? plainGroupChange.revision : decryptedGroup.revision;
    GroupContextV2.Builder contextBuilder   = new GroupContextV2.Builder()
                                                                .masterKey(ByteString.of(masterKey.serialize()))
                                                                .revision(revision);

    if (signedServerChange != null) {
      contextBuilder.groupChange(signedServerChange.encodeByteString());
    }

    DecryptedGroupV2Context.Builder builder = new DecryptedGroupV2Context.Builder()
                                                                         .context(contextBuilder.build())
                                                                         .groupState(decryptedGroup);

    if (groupMutation.getPreviousGroupState() != null) {
      builder.previousGroupState(groupMutation.getPreviousGroupState());
    }

    if (plainGroupChange != null) {
      builder.change(plainGroupChange);
    }

    return builder.build();
  }

  @WorkerThread
  public static Recipient pendingMemberToRecipient(@NonNull DecryptedPendingMember pendingMember) {
    return pendingMemberServiceIdToRecipient(pendingMember.serviceIdBytes);
  }

  @WorkerThread
  public static Recipient pendingMemberServiceIdToRecipient(@NonNull ByteString serviceIdBinary) {
    ServiceId serviceId = ServiceId.parseOrThrow(serviceIdBinary);

    if (serviceId.isUnknown()) {
      return Recipient.UNKNOWN;
    }

    return Recipient.externalPush(serviceId);
  }

  @WorkerThread
  public static @NonNull RecipientId serviceIdBinaryToRecipientId(@NonNull ByteString serviceIdBinary) {
    ServiceId serviceId = ServiceId.parseOrThrow(serviceIdBinary);

    if (serviceId.isUnknown()) {
      return RecipientId.UNKNOWN;
    }

    return RecipientId.from(serviceId);
  }

  public static boolean isMember(@NonNull ACI aci, @NonNull List<DecryptedMember> membersList) {
    ByteString aciBytes = aci.toByteString();

    for (DecryptedMember member : membersList) {
      if (aciBytes.equals(member.aciBytes)) {
        return true;
      }
    }

    return false;
  }
}
