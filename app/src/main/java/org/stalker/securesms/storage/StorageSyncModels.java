package org.stalker.securesms.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.stalker.securesms.components.settings.app.usernamelinks.UsernameQrCodeColorScheme;
import org.stalker.securesms.database.GroupTable;
import org.stalker.securesms.database.IdentityTable;
import org.stalker.securesms.database.RecipientTable;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.model.DistributionListId;
import org.stalker.securesms.database.model.DistributionListRecord;
import org.stalker.securesms.database.model.RecipientRecord;
import org.stalker.securesms.groups.GroupId;
import org.stalker.securesms.keyvalue.PhoneNumberPrivacyValues;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.subscription.Subscriber;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.storage.SignalAccountRecord;
import org.whispersystems.signalservice.api.storage.SignalContactRecord;
import org.whispersystems.signalservice.api.storage.SignalGroupV1Record;
import org.whispersystems.signalservice.api.storage.SignalGroupV2Record;
import org.whispersystems.signalservice.api.storage.SignalStorageRecord;
import org.whispersystems.signalservice.api.storage.SignalStoryDistributionListRecord;
import org.whispersystems.signalservice.api.subscriptions.SubscriberId;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.storage.protos.AccountRecord;
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord.IdentityState;
import org.whispersystems.signalservice.internal.storage.protos.GroupV2Record;

import java.util.List;
import java.util.stream.Collectors;

public final class StorageSyncModels {

  private StorageSyncModels() {}

  public static @NonNull SignalStorageRecord localToRemoteRecord(@NonNull RecipientRecord settings) {
    if (settings.getStorageId() == null) {
      throw new AssertionError("Must have a storage key!");
    }

    return localToRemoteRecord(settings, settings.getStorageId());
  }

  public static @NonNull SignalStorageRecord localToRemoteRecord(@NonNull RecipientRecord settings, @NonNull GroupMasterKey groupMasterKey) {
    if (settings.getStorageId() == null) {
      throw new AssertionError("Must have a storage key!");
    }

    return SignalStorageRecord.forGroupV2(localToRemoteGroupV2(settings, settings.getStorageId(), groupMasterKey));
  }

  public static @NonNull SignalStorageRecord localToRemoteRecord(@NonNull RecipientRecord settings, @NonNull byte[] rawStorageId) {
    switch (settings.getRecipientType()) {
      case INDIVIDUAL:        return SignalStorageRecord.forContact(localToRemoteContact(settings, rawStorageId));
      case GV1:               return SignalStorageRecord.forGroupV1(localToRemoteGroupV1(settings, rawStorageId));
      case GV2:               return SignalStorageRecord.forGroupV2(localToRemoteGroupV2(settings, rawStorageId, settings.getSyncExtras().getGroupMasterKey()));
      case DISTRIBUTION_LIST: return SignalStorageRecord.forStoryDistributionList(localToRemoteStoryDistributionList(settings, rawStorageId));
      default:                throw new AssertionError("Unsupported type!");
    }
  }

  public static AccountRecord.PhoneNumberSharingMode localToRemotePhoneNumberSharingMode(PhoneNumberPrivacyValues.PhoneNumberSharingMode phoneNumberPhoneNumberSharingMode) {
    switch (phoneNumberPhoneNumberSharingMode) {
      case DEFAULT  : return AccountRecord.PhoneNumberSharingMode.NOBODY;
      case EVERYBODY: return AccountRecord.PhoneNumberSharingMode.EVERYBODY;
      case NOBODY   : return AccountRecord.PhoneNumberSharingMode.NOBODY;
      default       : throw new AssertionError();
    }
  }

  public static PhoneNumberPrivacyValues.PhoneNumberSharingMode remoteToLocalPhoneNumberSharingMode(AccountRecord.PhoneNumberSharingMode phoneNumberPhoneNumberSharingMode) {
    switch (phoneNumberPhoneNumberSharingMode) {
      case EVERYBODY    : return PhoneNumberPrivacyValues.PhoneNumberSharingMode.EVERYBODY;
      case NOBODY       : return PhoneNumberPrivacyValues.PhoneNumberSharingMode.NOBODY;
      default           : return PhoneNumberPrivacyValues.PhoneNumberSharingMode.DEFAULT;
    }
  }

  public static List<SignalAccountRecord.PinnedConversation> localToRemotePinnedConversations(@NonNull List<RecipientRecord> settings) {
    return Stream.of(settings)
                 .filter(s -> s.getRecipientType() == RecipientTable.RecipientType.GV1 ||
                              s.getRecipientType() == RecipientTable.RecipientType.GV2 ||
                              s.getRegistered() == RecipientTable.RegisteredState.REGISTERED)
                 .map(StorageSyncModels::localToRemotePinnedConversation)
                 .toList();
  }

  private static @NonNull SignalAccountRecord.PinnedConversation localToRemotePinnedConversation(@NonNull RecipientRecord settings) {
    switch (settings.getRecipientType()) {
      case INDIVIDUAL: return SignalAccountRecord.PinnedConversation.forContact(new SignalServiceAddress(settings.getServiceId(), settings.getE164()));
      case GV1: return SignalAccountRecord.PinnedConversation.forGroupV1(settings.getGroupId().requireV1().getDecodedId());
      case GV2: return SignalAccountRecord.PinnedConversation.forGroupV2(settings.getSyncExtras().getGroupMasterKey().serialize());
      default       : throw new AssertionError("Unexpected group type!");
    }
  }

  public static @NonNull AccountRecord.UsernameLink.Color localToRemoteUsernameColor(UsernameQrCodeColorScheme local) {
    switch (local) {
      case Blue:   return AccountRecord.UsernameLink.Color.BLUE;
      case White:  return AccountRecord.UsernameLink.Color.WHITE;
      case Grey:   return AccountRecord.UsernameLink.Color.GREY;
      case Tan:    return AccountRecord.UsernameLink.Color.OLIVE;
      case Green:  return AccountRecord.UsernameLink.Color.GREEN;
      case Orange: return AccountRecord.UsernameLink.Color.ORANGE;
      case Pink:   return AccountRecord.UsernameLink.Color.PINK;
      case Purple: return AccountRecord.UsernameLink.Color.PURPLE;
      default:     return AccountRecord.UsernameLink.Color.BLUE;
    }
  }

  public static @NonNull UsernameQrCodeColorScheme remoteToLocalUsernameColor(AccountRecord.UsernameLink.Color remote) {
    switch (remote) {
      case BLUE:   return UsernameQrCodeColorScheme.Blue;
      case WHITE:  return UsernameQrCodeColorScheme.White;
      case GREY:   return UsernameQrCodeColorScheme.Grey;
      case OLIVE:  return UsernameQrCodeColorScheme.Tan;
      case GREEN:  return UsernameQrCodeColorScheme.Green;
      case ORANGE: return UsernameQrCodeColorScheme.Orange;
      case PINK:   return UsernameQrCodeColorScheme.Pink;
      case PURPLE: return UsernameQrCodeColorScheme.Purple;
      default:     return UsernameQrCodeColorScheme.Blue;
    }
  }

  private static @NonNull SignalContactRecord localToRemoteContact(@NonNull RecipientRecord recipient, byte[] rawStorageId) {
    if (recipient.getAci() == null && recipient.getPni() == null && recipient.getE164() == null) {
      throw new AssertionError("Must have either a UUID or a phone number!");
    }

    boolean   hideStory = recipient.getExtras() != null && recipient.getExtras().hideStory();

    return new SignalContactRecord.Builder(rawStorageId, recipient.getAci(), recipient.getSyncExtras().getStorageProto())
                                  .setE164(recipient.getE164())
                                  .setPni(recipient.getPni())
                                  .setProfileKey(recipient.getProfileKey())
                                  .setProfileGivenName(recipient.getProfileName().getGivenName())
                                  .setProfileFamilyName(recipient.getProfileName().getFamilyName())
                                  .setSystemGivenName(recipient.getSystemProfileName().getGivenName())
                                  .setSystemFamilyName(recipient.getSystemProfileName().getFamilyName())
                                  .setSystemNickname(recipient.getSyncExtras().getSystemNickname())
                                  .setBlocked(recipient.isBlocked())
                                  .setProfileSharingEnabled(recipient.isProfileSharing() || recipient.getSystemContactUri() != null)
                                  .setIdentityKey(recipient.getSyncExtras().getIdentityKey())
                                  .setIdentityState(localToRemoteIdentityState(recipient.getSyncExtras().getIdentityStatus()))
                                  .setArchived(recipient.getSyncExtras().isArchived())
                                  .setForcedUnread(recipient.getSyncExtras().isForcedUnread())
                                  .setMuteUntil(recipient.getMuteUntil())
                                  .setHideStory(hideStory)
                                  .setUnregisteredTimestamp(recipient.getSyncExtras().getUnregisteredTimestamp())
                                  .setHidden(recipient.getHiddenState() != Recipient.HiddenState.NOT_HIDDEN)
                                  .setUsername(recipient.getUsername())
                                  .setPniSignatureVerified(recipient.getSyncExtras().getPniSignatureVerified())
                                  .setNicknameGivenName(recipient.getNickname().getGivenName())
                                  .setNicknameFamilyName(recipient.getNickname().getFamilyName())
                                  .setNote(recipient.getNote())
                                  .build();
  }

  private static @NonNull SignalGroupV1Record localToRemoteGroupV1(@NonNull RecipientRecord recipient, byte[] rawStorageId) {
    GroupId groupId = recipient.getGroupId();

    if (groupId == null) {
      throw new AssertionError("Must have a groupId!");
    }

    if (!groupId.isV1()) {
      throw new AssertionError("Group is not V1");
    }

    return new SignalGroupV1Record.Builder(rawStorageId, groupId.getDecodedId(), recipient.getSyncExtras().getStorageProto())
                                  .setBlocked(recipient.isBlocked())
                                  .setProfileSharingEnabled(recipient.isProfileSharing())
                                  .setArchived(recipient.getSyncExtras().isArchived())
                                  .setForcedUnread(recipient.getSyncExtras().isForcedUnread())
                                  .setMuteUntil(recipient.getMuteUntil())
                                  .build();
  }

  private static @NonNull SignalGroupV2Record localToRemoteGroupV2(@NonNull RecipientRecord recipient, byte[] rawStorageId, @NonNull GroupMasterKey groupMasterKey) {
    GroupId groupId = recipient.getGroupId();

    if (groupId == null) {
      throw new AssertionError("Must have a groupId!");
    }

    if (!groupId.isV2()) {
      throw new AssertionError("Group is not V2");
    }

    if (groupMasterKey == null) {
      throw new AssertionError("Group master key not on recipient record");
    }

    boolean                     hideStory        = recipient.getExtras() != null && recipient.getExtras().hideStory();
    GroupTable.ShowAsStoryState showAsStoryState = SignalDatabase.groups().getShowAsStoryState(groupId);
    GroupV2Record.StorySendMode storySendMode;

    switch (showAsStoryState) {
      case ALWAYS:
        storySendMode = GroupV2Record.StorySendMode.ENABLED;
        break;
      case NEVER:
        storySendMode = GroupV2Record.StorySendMode.DISABLED;
        break;
      default:
        storySendMode = GroupV2Record.StorySendMode.DEFAULT;
    }

    return new SignalGroupV2Record.Builder(rawStorageId, groupMasterKey, recipient.getSyncExtras().getStorageProto())
                                  .setBlocked(recipient.isBlocked())
                                  .setProfileSharingEnabled(recipient.isProfileSharing())
                                  .setArchived(recipient.getSyncExtras().isArchived())
                                  .setForcedUnread(recipient.getSyncExtras().isForcedUnread())
                                  .setMuteUntil(recipient.getMuteUntil())
                                  .setNotifyForMentionsWhenMuted(recipient.getMentionSetting() == RecipientTable.MentionSetting.ALWAYS_NOTIFY)
                                  .setHideStory(hideStory)
                                  .setStorySendMode(storySendMode)
                                  .build();
  }

  private static @NonNull SignalStoryDistributionListRecord localToRemoteStoryDistributionList(@NonNull RecipientRecord recipient, @NonNull byte[] rawStorageId) {
    DistributionListId distributionListId = recipient.getDistributionListId();

    if (distributionListId == null) {
      throw new AssertionError("Must have a distributionListId!");
    }

    DistributionListRecord record = SignalDatabase.distributionLists().getListForStorageSync(distributionListId);
    if (record == null) {
      throw new AssertionError("Must have a distribution list record!");
    }

    if (record.getDeletedAtTimestamp() > 0L) {
      return new SignalStoryDistributionListRecord.Builder(rawStorageId, recipient.getSyncExtras().getStorageProto())
                                                  .setIdentifier(UuidUtil.toByteArray(record.getDistributionId().asUuid()))
                                                  .setDeletedAtTimestamp(record.getDeletedAtTimestamp())
                                                  .build();
    }

    return new SignalStoryDistributionListRecord.Builder(rawStorageId, recipient.getSyncExtras().getStorageProto())
                                                .setIdentifier(UuidUtil.toByteArray(record.getDistributionId().asUuid()))
                                                .setName(record.getName())
                                                .setRecipients(record.getMembersToSync()
                                                                     .stream()
                                                                     .map(Recipient::resolved)
                                                                     .filter(Recipient::getHasServiceId)
                                                                     .map(Recipient::requireServiceId)
                                                                     .map(SignalServiceAddress::new)
                                                                     .collect(Collectors.toList()))
                                                .setAllowsReplies(record.getAllowsReplies())
                                                .setIsBlockList(record.getPrivacyMode().isBlockList())
                                                .build();
  }

  public static @NonNull IdentityTable.VerifiedStatus remoteToLocalIdentityStatus(@NonNull IdentityState identityState) {
    switch (identityState) {
      case VERIFIED:   return IdentityTable.VerifiedStatus.VERIFIED;
      case UNVERIFIED: return IdentityTable.VerifiedStatus.UNVERIFIED;
      default:         return IdentityTable.VerifiedStatus.DEFAULT;
    }
  }

  private static IdentityState localToRemoteIdentityState(@NonNull IdentityTable.VerifiedStatus local) {
    switch (local) {
      case VERIFIED:   return IdentityState.VERIFIED;
      case UNVERIFIED: return IdentityState.UNVERIFIED;
      default:         return IdentityState.DEFAULT;
    }
  }

  public static @NonNull SignalAccountRecord.Subscriber localToRemoteSubscriber(@Nullable Subscriber subscriber) {
    if (subscriber == null) {
      return new SignalAccountRecord.Subscriber(null, null);
    } else {
      return new SignalAccountRecord.Subscriber(subscriber.getCurrencyCode(), subscriber.getSubscriberId().getBytes());
    }
  }

  public static @Nullable Subscriber remoteToLocalSubscriber(@NonNull SignalAccountRecord.Subscriber subscriber) {
    if (subscriber.getId().isPresent()) {
      return new Subscriber(SubscriberId.fromBytes(subscriber.getId().get()), subscriber.getCurrencyCode().get());
    } else {
      return null;
    }
  }
}
