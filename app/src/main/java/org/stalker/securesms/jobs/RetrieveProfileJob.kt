package org.stalker.securesms.jobs

import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.Base64.decode
import org.signal.core.util.Stopwatch
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.concurrent.safeBlockingGet
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.util.Pair
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredential
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.stalker.securesms.badges.Badges
import org.stalker.securesms.crypto.ProfileKeyUtil
import org.stalker.securesms.database.GroupTable
import org.stalker.securesms.database.RecipientTable
import org.stalker.securesms.database.RecipientTable.Companion.maskCapabilitiesToLong
import org.stalker.securesms.database.RecipientTable.PhoneNumberSharingState
import org.stalker.securesms.database.RecipientTable.UnidentifiedAccessMode
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.RecipientRecord
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.JsonJobData
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.notifications.v2.ConversationId.Companion.forConversation
import org.stalker.securesms.profiles.ProfileName
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.recipients.RecipientUtil
import org.stalker.securesms.transport.RetryLaterException
import org.stalker.securesms.util.IdentityUtil
import org.stalker.securesms.util.ProfileUtil
import org.stalker.securesms.util.Util
import org.whispersystems.signalservice.api.crypto.InvalidCiphertextException
import org.whispersystems.signalservice.api.crypto.ProfileCipher
import org.whispersystems.signalservice.api.profiles.ProfileAndCredential
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile
import org.whispersystems.signalservice.api.services.ProfileService.ProfileResponseProcessor
import org.whispersystems.signalservice.api.util.ExpiringProfileCredentialUtil
import org.whispersystems.signalservice.internal.ServiceResponse
import java.io.IOException
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Retrieves a users profile and sets the appropriate local fields.
 */
class RetrieveProfileJob private constructor(parameters: Parameters, private val recipientIds: MutableSet<RecipientId>) : BaseJob(parameters) {
  constructor(recipientIds: Set<RecipientId>) : this(
    Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .apply {
        if (recipientIds.size < 5) {
          setQueue(recipientIds.map { it.toLong() }.sorted().joinToString(separator = "_", prefix = QUEUE_PREFIX))
          setMaxInstancesForQueue(2)
        }
      }
      .setMaxAttempts(3)
      .build(),
    recipientIds.toMutableSet()
  )

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putStringListAsArray(KEY_RECIPIENTS, recipientIds.map { it.serialize() })
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun shouldTrace(): Boolean = true

  @Throws(IOException::class, RetryLaterException::class)
  public override fun onRun() {
    if (!SignalStore.account().isRegistered) {
      Log.w(TAG, "Unregistered. Skipping.")
      return
    }

    val stopwatch = Stopwatch("RetrieveProfile")

    RecipientUtil.ensureUuidsAreAvailable(
      context,
      Recipient.resolvedList(recipientIds).filter { it.registered != RecipientTable.RegisteredState.NOT_REGISTERED }
    )

    val recipients = Recipient.resolvedList(recipientIds)
    stopwatch.split("resolve-ensure")

    val requests: List<Observable<Pair<Recipient, ServiceResponse<ProfileAndCredential>>>> = recipients
      .filter { it.hasServiceId }
      .map { ProfileUtil.retrieveProfile(context, it, getRequestType(it)).toObservable() }
    stopwatch.split("requests")

    val operationState = Observable.mergeDelayError(requests, 16, 1)
      .observeOn(Schedulers.io(), true)
      .scan(OperationState()) { state: OperationState, pair: Pair<Recipient, ServiceResponse<ProfileAndCredential>> ->
        val recipient = pair.first()
        val processor = ProfileResponseProcessor(pair.second())

        if (processor.hasResult()) {
          state.profiles.add(processor.getResult(recipient))
        } else if (processor.notFound()) {
          Log.w(TAG, "Failed to find a profile for ${recipient.id}")
          if (recipient.isRegistered) {
            state.unregistered.add(recipient.id)
          }
        } else if (processor.genericIoError()) {
          state.retries.add(recipient.id)
        } else {
          Log.w(TAG, "Failed to retrieve profile for ${recipient.id}", processor.error)
        }
        state
      }
      .lastOrError()
      .safeBlockingGet()
    stopwatch.split("responses")

    val localRecords = SignalDatabase.recipients.getRecords(recipientIds)
    Log.d(TAG, "Fetched ${localRecords.size} existing records.")
    stopwatch.split("disk-fetch")

    val successIds: Set<RecipientId> = recipientIds - operationState.retries
    val newlyRegisteredIds: Set<RecipientId> = operationState.profiles
      .map { it.first() }
      .filterNot { it.isRegistered }
      .map { it.id }
      .toSet()

    val updatedProfiles = operationState.profiles
      .filter { recipientProfileAndCredentialPair: Pair<Recipient, ProfileAndCredential> ->
        val recipientToUpdate = recipientProfileAndCredentialPair.first()
        val localRecipientRecord = localRecords[recipientToUpdate.id] ?: return@filter true
        val remoteProfile = recipientProfileAndCredentialPair.second().profile
        val remoteCredential = recipientProfileAndCredentialPair.second().expiringProfileKeyCredential

        return@filter try {
          isUpdated(localRecipientRecord, remoteProfile, remoteCredential)
        } catch (e: InvalidCiphertextException) {
          Log.w(TAG, "Could not compare new and old profiles.", e)
          true
        } catch (e: IOException) {
          Log.w(TAG, "Could not compare new and old profiles.", e)
          true
        }
      }
      .toList()
    stopwatch.split("filter")

    Log.d(TAG, "Committing updates to " + updatedProfiles.size + " of " + operationState.profiles.size + " retrieved profiles.")
    updatedProfiles.chunked(150).forEach { list: List<Pair<Recipient, ProfileAndCredential>> ->
      SignalDatabase.runInTransaction {
        for (profile in list) {
          process(profile.first(), profile.second())
        }
      }
    }
    stopwatch.split("process")

    SignalDatabase.recipients.markProfilesFetched(successIds, System.currentTimeMillis())
    stopwatch.split("mark-fetched")

    if (newlyRegisteredIds.isNotEmpty()) {
      Log.i(TAG, "Marking " + newlyRegisteredIds.size + " users as registered.")
      SignalDatabase.recipients.bulkUpdatedRegisteredStatus(newlyRegisteredIds, emptySet())
    }
    if (operationState.unregistered.isNotEmpty()) {
      Log.i(TAG, "Marking " + operationState.unregistered.size + " users as unregistered.")
      for (recipientId in operationState.unregistered) {
        SignalDatabase.recipients.markUnregistered(recipientId)
      }
    }
    stopwatch.split("registered-update")

    for (profile in operationState.profiles) {
      setIdentityKey(profile.first(), profile.second().profile.identityKey)
    }
    stopwatch.split("identityKeys")

    val keyCount = operationState.profiles.map { it.first() }.mapNotNull { it.profileKey }.count()

    Log.d(TAG, "Started with ${recipients.size} recipient(s). Found ${operationState.profiles.size} profile(s), and had keys for $keyCount of them. Will retry ${operationState.retries.size}.")
    stopwatch.stop(TAG)

    recipientIds.clear()
    recipientIds.addAll(operationState.retries)

    if (recipientIds.isNotEmpty()) {
      throw RetryLaterException()
    }
  }

  public override fun onShouldRetry(e: Exception): Boolean {
    return e is RetryLaterException
  }

  override fun onFailure() {}

  @Throws(InvalidCiphertextException::class, IOException::class)
  private fun isUpdated(localRecipientRecord: RecipientRecord, remoteProfile: SignalServiceProfile, remoteExpiringProfileKeyCredential: Optional<ExpiringProfileKeyCredential>): Boolean {
    if (localRecipientRecord.signalProfileAvatar != remoteProfile.avatar) {
      return true
    }

    if (localRecipientRecord.badges != remoteProfile.badges.map { Badges.fromServiceBadge(it) }) {
      return true
    }

    if (localRecipientRecord.capabilities.rawBits != maskCapabilitiesToLong(remoteProfile.capabilities)) {
      return true
    }

    val profileKey = ProfileKeyUtil.profileKeyOrNull(localRecipientRecord.profileKey)
    val accessMode = deriveUnidentifiedAccessMode(
      profileKey = profileKey,
      unidentifiedAccessVerifier = remoteProfile.unidentifiedAccess,
      unrestrictedUnidentifiedAccess = remoteProfile.isUnrestrictedUnidentifiedAccess
    )

    if (localRecipientRecord.unidentifiedAccessMode != accessMode) {
      return true
    }

    if (profileKey == null) {
      return false
    }

    val newProfileName = ProfileName.fromSerialized(ProfileUtil.decryptString(profileKey, remoteProfile.name))
    if (localRecipientRecord.signalProfileName != newProfileName) {
      return true
    }

    if (localRecipientRecord.about != ProfileUtil.decryptString(profileKey, remoteProfile.about)) {
      return true
    }

    if (remoteExpiringProfileKeyCredential.isPresent && localRecipientRecord.expiringProfileKeyCredential != remoteExpiringProfileKeyCredential.get()) {
      return true
    }

    val remotePhoneNumberSharing = ProfileUtil.decryptBoolean(profileKey, remoteProfile.phoneNumberSharing)
      .map { value: Boolean -> if (value) PhoneNumberSharingState.ENABLED else PhoneNumberSharingState.DISABLED }
      .orElse(PhoneNumberSharingState.UNKNOWN)

    if (localRecipientRecord.phoneNumberSharing != remotePhoneNumberSharing) {
      return true
    }

    return false
  }

  private fun process(recipient: Recipient, profileAndCredential: ProfileAndCredential) {
    val profile = profileAndCredential.profile
    val recipientProfileKey = ProfileKeyUtil.profileKeyOrNull(recipient.profileKey)
    val wroteNewProfileName = setProfileName(recipient, profile.name)

    setProfileAbout(recipient, profile.about, profile.aboutEmoji)
    setProfileAvatar(recipient, profile.avatar)
    setProfileBadges(recipient, profile.badges)
    setProfileCapabilities(recipient, profile.capabilities)
    setUnidentifiedAccessMode(recipient, profile.unidentifiedAccess, profile.isUnrestrictedUnidentifiedAccess)
    setPhoneNumberSharingMode(recipient, profile.phoneNumberSharing)

    if (recipientProfileKey != null) {
      profileAndCredential.expiringProfileKeyCredential
        .ifPresent { profileKeyCredential: ExpiringProfileKeyCredential -> setExpiringProfileKeyCredential(recipient, recipientProfileKey, profileKeyCredential) }
    }

    if (recipient.hasNonUsernameDisplayName(context) || wroteNewProfileName) {
      clearUsername(recipient)
    }
  }

  private fun setProfileBadges(recipient: Recipient, serviceBadges: List<SignalServiceProfile.Badge>?) {
    if (serviceBadges == null) {
      return
    }

    val badges = serviceBadges.map { Badges.fromServiceBadge(it) }
    if (badges.size != recipient.badges.size) {
      Log.i(TAG, "Likely change in badges for ${recipient.id}. Going from ${recipient.badges.size} badge(s) to ${badges.size}.")
    }

    SignalDatabase.recipients.setBadges(recipient.id, badges)
  }

  private fun setExpiringProfileKeyCredential(
    recipient: Recipient,
    recipientProfileKey: ProfileKey,
    credential: ExpiringProfileKeyCredential
  ) {
    SignalDatabase.recipients.setProfileKeyCredential(recipient.id, recipientProfileKey, credential)
  }

  private fun setIdentityKey(recipient: Recipient, identityKeyValue: String?) {
    try {
      if (identityKeyValue.isNullOrBlank()) {
        Log.w(TAG, "Identity key is missing on profile!")
        return
      }

      val identityKey = IdentityKey(decode(identityKeyValue), 0)
      if (!ApplicationDependencies.getProtocolStore().aci().identities().getIdentityRecord(recipient.id).isPresent) {
        Log.w(TAG, "Still first use for ${recipient.id}")
        return
      }

      IdentityUtil.saveIdentity(recipient.requireServiceId().toString(), identityKey)
    } catch (e: InvalidKeyException) {
      Log.w(TAG, e)
    } catch (e: IOException) {
      Log.w(TAG, e)
    }
  }

  private fun setUnidentifiedAccessMode(recipient: Recipient, unidentifiedAccessVerifier: String?, unrestrictedUnidentifiedAccess: Boolean) {
    val profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.profileKey)
    val newMode = deriveUnidentifiedAccessMode(profileKey, unidentifiedAccessVerifier, unrestrictedUnidentifiedAccess)

    if (recipient.unidentifiedAccessMode !== newMode) {
      if (newMode === UnidentifiedAccessMode.UNRESTRICTED) {
        Log.i(TAG, "Marking recipient UD status as unrestricted.")
      } else if (profileKey == null || unidentifiedAccessVerifier == null) {
        Log.i(TAG, "Marking recipient UD status as disabled.")
      } else {
        Log.i(TAG, "Marking recipient UD status as " + newMode.name + " after verification.")
      }

      SignalDatabase.recipients.setUnidentifiedAccessMode(recipient.id, newMode)
    }
  }

  private fun deriveUnidentifiedAccessMode(profileKey: ProfileKey?, unidentifiedAccessVerifier: String?, unrestrictedUnidentifiedAccess: Boolean): UnidentifiedAccessMode {
    return if (unrestrictedUnidentifiedAccess && unidentifiedAccessVerifier != null) {
      UnidentifiedAccessMode.UNRESTRICTED
    } else if (profileKey == null || unidentifiedAccessVerifier == null) {
      UnidentifiedAccessMode.DISABLED
    } else {
      val profileCipher = ProfileCipher(profileKey)
      val verifiedUnidentifiedAccess: Boolean = try {
        profileCipher.verifyUnidentifiedAccess(decode(unidentifiedAccessVerifier))
      } catch (e: IOException) {
        Log.w(TAG, e)
        false
      }

      if (verifiedUnidentifiedAccess) {
        UnidentifiedAccessMode.ENABLED
      } else {
        UnidentifiedAccessMode.DISABLED
      }
    }
  }

  private fun setProfileName(recipient: Recipient, profileName: String?): Boolean {
    try {
      val profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.profileKey) ?: return false
      val plaintextProfileName = Util.emptyIfNull(ProfileUtil.decryptString(profileKey, profileName))

      if (plaintextProfileName.isNullOrBlank()) {
        Log.w(TAG, "No name set on the profile for ${recipient.id} -- Leaving it alone")
        return false
      }

      val remoteProfileName = ProfileName.fromSerialized(plaintextProfileName)
      val localProfileName = recipient.profileName

      if (localProfileName.isEmpty &&
        !recipient.isSystemContact &&
        recipient.isProfileSharing &&
        !recipient.isGroup &&
        !recipient.isSelf
      ) {
        Log.i(TAG, "Learned profile name for first time, insert event")
        SignalDatabase.messages.insertLearnedProfileNameChangeMessage(recipient, recipient.getDisplayName(context))
      }

      if (remoteProfileName != localProfileName) {
        Log.i(TAG, "Profile name updated. Writing new value.")
        SignalDatabase.recipients.setProfileName(recipient.id, remoteProfileName)

        val remoteDisplayName = remoteProfileName.toString()
        val localDisplayName = localProfileName.toString()
        val writeChangeEvent = !recipient.isBlocked &&
          !recipient.isGroup &&
          !recipient.isSelf &&
          localDisplayName.isNotEmpty() &&
          remoteDisplayName != localDisplayName

        if (writeChangeEvent) {
          Log.i(TAG, "Writing a profile name change event for ${recipient.id}")
          SignalDatabase.messages.insertProfileNameChangeMessages(recipient, remoteDisplayName, localDisplayName)
        } else {
          Log.i(TAG, "Name changed, but wasn't relevant to write an event. blocked: ${recipient.isBlocked}, group: ${recipient.isGroup}, self: ${recipient.isSelf}, firstSet: ${localDisplayName.isEmpty()}, displayChange: ${remoteDisplayName != localDisplayName}")
        }

        if (recipient.isIndividual &&
          !recipient.isSystemContact &&
          !recipient.nickname.isEmpty &&
          !recipient.isProfileSharing &&
          !recipient.isBlocked &&
          !recipient.isSelf &&
          !recipient.isHidden
        ) {
          val threadId = SignalDatabase.threads.getThreadIdFor(recipient.id)
          if (threadId != null && !RecipientUtil.isMessageRequestAccepted(threadId, recipient)) {
            SignalDatabase.nameCollisions.handleIndividualNameCollision(recipient.id)
          }
        }

        if (writeChangeEvent || localDisplayName.isEmpty()) {
          ApplicationDependencies.getDatabaseObserver().notifyConversationListListeners()
          val threadId = SignalDatabase.threads.getThreadIdFor(recipient.id)
          if (threadId != null) {
            SignalDatabase.runPostSuccessfulTransaction {
              ApplicationDependencies.getMessageNotifier().updateNotification(context, forConversation(threadId))
            }
          }
        }

        return true
      }
    } catch (e: InvalidCiphertextException) {
      Log.w(TAG, "Bad profile key for ${recipient.id}")
    } catch (e: IOException) {
      Log.w(TAG, e)
    }

    return false
  }

  private fun setProfileAbout(recipient: Recipient, encryptedAbout: String?, encryptedEmoji: String?) {
    try {
      val profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.profileKey) ?: return
      val plaintextAbout = ProfileUtil.decryptString(profileKey, encryptedAbout)
      val plaintextEmoji = ProfileUtil.decryptString(profileKey, encryptedEmoji)

      SignalDatabase.recipients.setAbout(recipient.id, plaintextAbout, plaintextEmoji)
    } catch (e: InvalidCiphertextException) {
      Log.w(TAG, e)
    } catch (e: IOException) {
      Log.w(TAG, e)
    }
  }

  private fun clearUsername(recipient: Recipient) {
    SignalDatabase.recipients.setUsername(recipient.id, null)
  }

  private fun setProfileCapabilities(recipient: Recipient, capabilities: SignalServiceProfile.Capabilities?) {
    if (capabilities == null) {
      return
    }

    SignalDatabase.recipients.setCapabilities(recipient.id, capabilities)
  }

  private fun setPhoneNumberSharingMode(recipient: Recipient, phoneNumberSharing: String?) {
    val profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.profileKey) ?: return

    try {
      val remotePhoneNumberSharing = ProfileUtil.decryptBoolean(profileKey, phoneNumberSharing)
        .map { value: Boolean -> if (value) PhoneNumberSharingState.ENABLED else PhoneNumberSharingState.DISABLED }
        .orElse(PhoneNumberSharingState.UNKNOWN)

      if (recipient.phoneNumberSharing !== remotePhoneNumberSharing) {
        Log.i(TAG, "Updating phone number sharing state for " + recipient.id + " to " + remotePhoneNumberSharing)
        SignalDatabase.recipients.setPhoneNumberSharing(recipient.id, remotePhoneNumberSharing)
      }
    } catch (e: InvalidCiphertextException) {
      Log.w(TAG, "Failed to set the phone number sharing setting!", e)
    } catch (e: IOException) {
      Log.w(TAG, "Failed to set the phone number sharing setting!", e)
    }
  }

  private fun setProfileAvatar(recipient: Recipient, profileAvatar: String?) {
    if (recipient.profileKey == null) {
      return
    }

    if (profileAvatar != recipient.profileAvatar) {
      SignalDatabase.runPostSuccessfulTransaction(DEDUPE_KEY_RETRIEVE_AVATAR + recipient.id) {
        SignalExecutors.BOUNDED.execute {
          ApplicationDependencies.getJobManager().add(RetrieveProfileAvatarJob(recipient, profileAvatar))
        }
      }
    }
  }

  private fun getRequestType(recipient: Recipient): SignalServiceProfile.RequestType {
    return if (ExpiringProfileCredentialUtil.isValid(recipient.expiringProfileKeyCredential)) SignalServiceProfile.RequestType.PROFILE else SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL
  }

  /**
   * Collective state as responses are processed as they come in.
   */
  private class OperationState {
    val retries: MutableSet<RecipientId> = HashSet()
    val unregistered: MutableSet<RecipientId> = HashSet()
    val profiles: MutableList<Pair<Recipient, ProfileAndCredential>> = ArrayList()
  }

  class Factory : Job.Factory<RetrieveProfileJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): RetrieveProfileJob {
      val data = JsonJobData.deserialize(serializedData)
      val recipientIds: MutableSet<RecipientId> = data.getStringArray(KEY_RECIPIENTS).map { RecipientId.from(it) }.toMutableSet()

      return RetrieveProfileJob(parameters, recipientIds)
    }
  }

  companion object {
    const val KEY = "RetrieveProfileJob"
    private val TAG = Log.tag(RetrieveProfileJob::class.java)
    private const val KEY_RECIPIENTS = "recipients"
    private const val DEDUPE_KEY_RETRIEVE_AVATAR = KEY + "_RETRIEVE_PROFILE_AVATAR"
    private const val QUEUE_PREFIX = "RetrieveProfileJob_"

    /**
     * Submits the necessary job to refresh the profile of the requested recipient. Works for any
     * RecipientId, including individuals, groups, or yourself.
     *
     * May not enqueue any jobs in certain circumstances. In particular, if the recipient is a group
     * with no other members, then no job will be enqueued.
     */
    @JvmStatic
    @WorkerThread
    fun enqueue(recipientId: RecipientId) {
      forRecipients(setOf(recipientId)).firstOrNull()?.let { job ->
        ApplicationDependencies.getJobManager().add(job)
      }
    }

    /**
     * Submits the necessary jobs to refresh the profiles of the requested recipients. Works for any
     * RecipientIds, including individuals, groups, or yourself.
     */
    @JvmStatic
    @WorkerThread
    fun enqueue(recipientIds: Set<RecipientId>) {
      val jobManager = ApplicationDependencies.getJobManager()
      for (job in forRecipients(recipientIds)) {
        jobManager.add(job)
      }
    }

    /**
     * Works for any RecipientId, whether it's an individual, group, or yourself.
     *
     * @return A list of length 2 or less. Two iff you are in the recipients. Could be empty for groups with no other members.
     */
    @JvmStatic
    @WorkerThread
    fun forRecipients(recipientIds: Set<RecipientId>): List<Job> {
      val combined: MutableSet<RecipientId> = HashSet(recipientIds.size)
      var includeSelf = false

      for (recipientId in recipientIds) {
        val recipient = Recipient.resolved(recipientId)
        when {
          recipient.isSelf -> includeSelf = true
          recipient.isGroup -> combined += SignalDatabase.groups.getGroupMemberIds(recipient.requireGroupId(), GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF)
          else -> combined.add(recipientId)
        }
      }

      return ArrayList<Job>(2).apply {
        if (includeSelf) {
          add(RefreshOwnProfileJob())
        }
        if (combined.size > 0) {
          add(RetrieveProfileJob(combined))
        }
      }
    }

    /**
     * Will fetch some profiles to ensure we're decently up-to-date if we haven't done so within a
     * certain time period.
     */
    @JvmStatic
    fun enqueueRoutineFetchIfNecessary() {
      if (!SignalStore.registrationValues().isRegistrationComplete || !SignalStore.account().isRegistered || SignalStore.account().aci == null) {
        Log.i(TAG, "Registration not complete. Skipping.")
        return
      }

      val timeSinceRefresh = System.currentTimeMillis() - SignalStore.misc().lastProfileRefreshTime
      if (timeSinceRefresh < TimeUnit.HOURS.toMillis(12)) {
        Log.i(TAG, "Too soon to refresh. Did the last refresh $timeSinceRefresh ms ago.")
        return
      }

      SignalExecutors.BOUNDED.execute {
        val current = System.currentTimeMillis()
        val ids: List<RecipientId> = SignalDatabase.recipients.getRecipientsForRoutineProfileFetch(
          lastInteractionThreshold = current - TimeUnit.DAYS.toMillis(30),
          lastProfileFetchThreshold = current - TimeUnit.DAYS.toMillis(1),
          limit = 50
        ) + Recipient.self().id

        if (ids.isNotEmpty()) {
          Log.i(TAG, "Optimistically refreshing ${ids.size} eligible recipient(s).")
          enqueue(ids.toSet())
        } else {
          Log.i(TAG, "No recipients to refresh.")
        }

        SignalStore.misc().lastProfileRefreshTime = System.currentTimeMillis()
      }
    }
  }
}
