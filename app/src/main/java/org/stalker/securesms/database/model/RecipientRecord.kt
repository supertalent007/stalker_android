package org.stalker.securesms.database.model

import android.net.Uri
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredential
import org.stalker.securesms.badges.models.Badge
import org.stalker.securesms.conversation.colors.AvatarColor
import org.stalker.securesms.conversation.colors.ChatColors
import org.stalker.securesms.database.IdentityTable.VerifiedStatus
import org.stalker.securesms.database.RecipientTable
import org.stalker.securesms.database.RecipientTable.MentionSetting
import org.stalker.securesms.database.RecipientTable.PhoneNumberSharingState
import org.stalker.securesms.database.RecipientTable.RegisteredState
import org.stalker.securesms.database.RecipientTable.UnidentifiedAccessMode
import org.stalker.securesms.database.RecipientTable.VibrateState
import org.stalker.securesms.groups.GroupId
import org.stalker.securesms.profiles.ProfileName
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.service.webrtc.links.CallLinkRoomId
import org.stalker.securesms.wallpaper.ChatWallpaper
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.api.push.ServiceId.PNI

/**
 * Database model for [RecipientTable].
 */
data class RecipientRecord(
  val id: RecipientId,
  val aci: ACI?,
  val pni: PNI?,
  val username: String?,
  val e164: String?,
  val email: String?,
  val groupId: GroupId?,
  val distributionListId: DistributionListId?,
  val recipientType: RecipientTable.RecipientType,
  val isBlocked: Boolean,
  val muteUntil: Long,
  val messageVibrateState: VibrateState,
  val callVibrateState: VibrateState,
  val messageRingtone: Uri?,
  val callRingtone: Uri?,
  val expireMessages: Int,
  val registered: RegisteredState,
  val profileKey: ByteArray?,
  val expiringProfileKeyCredential: ExpiringProfileKeyCredential?,
  val systemProfileName: ProfileName,
  val systemDisplayName: String?,
  val systemContactPhotoUri: String?,
  val systemPhoneLabel: String?,
  val systemContactUri: String?,
  @get:JvmName("getProfileName")
  val signalProfileName: ProfileName,
  @get:JvmName("getProfileAvatar")
  val signalProfileAvatar: String?,
  val profileAvatarFileDetails: ProfileAvatarFileDetails,
  @get:JvmName("isProfileSharing")
  val profileSharing: Boolean,
  val lastProfileFetch: Long,
  val notificationChannel: String?,
  val unidentifiedAccessMode: UnidentifiedAccessMode,
  val capabilities: Capabilities,
  val storageId: ByteArray?,
  val mentionSetting: MentionSetting,
  val wallpaper: ChatWallpaper?,
  val chatColors: ChatColors?,
  val avatarColor: AvatarColor,
  val about: String?,
  val aboutEmoji: String?,
  val syncExtras: SyncExtras,
  val extras: Recipient.Extras?,
  @get:JvmName("hasGroupsInCommon")
  val hasGroupsInCommon: Boolean,
  val badges: List<Badge>,
  @get:JvmName("needsPniSignature")
  val needsPniSignature: Boolean,
  val hiddenState: Recipient.HiddenState,
  val callLinkRoomId: CallLinkRoomId?,
  val phoneNumberSharing: PhoneNumberSharingState,
  val nickname: ProfileName,
  val note: String?
) {

  fun e164Only(): Boolean {
    return this.e164 != null && this.aci == null && this.pni == null
  }

  fun pniOnly(): Boolean {
    return this.e164 == null && this.aci == null && this.pni != null
  }

  fun aciOnly(): Boolean {
    return this.e164 == null && this.pni == null && this.aci != null
  }

  fun pniAndAci(): Boolean {
    return this.aci != null && this.pni != null
  }

  val serviceId: ServiceId? = this.aci ?: this.pni

  /**
   * A bundle of data that's only necessary when syncing to storage service, not for a
   * [Recipient].
   */
  data class SyncExtras(
    val storageProto: ByteArray?,
    val groupMasterKey: GroupMasterKey?,
    val identityKey: ByteArray?,
    val identityStatus: VerifiedStatus,
    val isArchived: Boolean,
    val isForcedUnread: Boolean,
    val unregisteredTimestamp: Long,
    val systemNickname: String?,
    val pniSignatureVerified: Boolean
  )

  data class Capabilities(
    val rawBits: Long,
    val paymentActivation: Recipient.Capability
  ) {
    companion object {
      @JvmField
      val UNKNOWN = Capabilities(
        0,
        Recipient.Capability.UNKNOWN
      )
    }
  }
}
