package org.stalker.securesms.jobs

import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.InvalidMessageException
import org.stalker.securesms.database.IdentityTable.VerifiedStatus
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.JsonJobData
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.net.NotPushRegisteredException
import org.stalker.securesms.profiles.AvatarHelper
import org.stalker.securesms.providers.BlobProvider
import org.stalker.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContact
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage.VerifiedState
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.push.exceptions.MissingConfigurationException
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Sync contact data from primary device.
 */
class MultiDeviceContactSyncJob(parameters: Parameters, private val attachmentPointer: ByteArray) : BaseJob(parameters) {

  constructor(contactsAttachment: SignalServiceAttachmentPointer) : this(
    Parameters.Builder()
      .setQueue("MultiDeviceContactSyncJob")
      .build(),
    AttachmentPointerUtil.createAttachmentPointer(contactsAttachment).encode()
  )

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putBlobAsString(KEY_ATTACHMENT_POINTER, attachmentPointer)
      .serialize()
  }

  override fun getFactoryKey(): String {
    return KEY
  }

  override fun onRun() {
    if (!Recipient.self().isRegistered) {
      throw NotPushRegisteredException()
    }

    if (SignalStore.account().isPrimaryDevice) {
      Log.i(TAG, "Not linked device, aborting...")
      return
    }

    val contactAttachment: SignalServiceAttachmentPointer = AttachmentPointerUtil.createSignalAttachmentPointer(attachmentPointer)

    try {
      val contactsFile: File = BlobProvider.getInstance().forNonAutoEncryptingSingleSessionOnDisk(context)
      ApplicationDependencies.getSignalServiceMessageReceiver()
        .retrieveAttachment(contactAttachment, contactsFile, MAX_ATTACHMENT_SIZE)
        .use(this::processContactFile)
    } catch (e: MissingConfigurationException) {
      throw IOException(e)
    } catch (e: InvalidMessageException) {
      throw IOException(e)
    }
  }

  private fun processContactFile(inputStream: InputStream) {
    val deviceContacts = DeviceContactsInputStream(inputStream)
    val recipients = SignalDatabase.recipients
    val threads = SignalDatabase.threads

    var contact: DeviceContact? = deviceContacts.read()
    while (contact != null) {
      val recipient = if (contact.aci.isPresent) {
        Recipient.externalPush(SignalServiceAddress(contact.aci.get(), contact.e164.orElse(null)))
      } else {
        Recipient.external(context, contact.e164.get())
      }

      if (recipient.isSelf) {
        contact = deviceContacts.read()
        continue
      }

      if (contact.name.isPresent) {
        recipients.setSystemContactName(recipient.id, contact.name.get())
      }

      if (contact.expirationTimer.isPresent) {
        recipients.setExpireMessages(recipient.id, contact.expirationTimer.get())
      }

      if (contact.profileKey.isPresent) {
        val profileKey = contact.profileKey.get()
        recipients.setProfileKey(recipient.id, profileKey)
      }

      if (contact.verified.isPresent) {
        val verifiedStatus: VerifiedStatus = when (contact.verified.get().verified) {
          VerifiedState.VERIFIED -> VerifiedStatus.VERIFIED
          VerifiedState.UNVERIFIED -> VerifiedStatus.UNVERIFIED
          else -> VerifiedStatus.DEFAULT
        }

        if (recipient.serviceId.isPresent) {
          ApplicationDependencies.getProtocolStore().aci().identities().saveIdentityWithoutSideEffects(
            recipient.id,
            recipient.serviceId.get(),
            contact.verified.get().identityKey,
            verifiedStatus,
            false,
            contact.verified.get().timestamp,
            true
          )
        } else {
          Log.w(TAG, "Missing serviceId for ${recipient.id} -- cannot save identity!")
        }
      }

      val threadRecord = threads.getThreadRecord(threads.getThreadIdFor(recipient.id))
      if (threadRecord != null && contact.isArchived != threadRecord.isArchived) {
        if (contact.isArchived) {
          threads.archiveConversation(threadRecord.threadId)
        } else {
          threads.unarchiveConversation(threadRecord.threadId)
        }
      }

      if (contact.avatar.isPresent) {
        try {
          AvatarHelper.setSyncAvatar(context, recipient.id, contact.avatar.get().inputStream)
        } catch (e: IOException) {
          Log.w(TAG, "Unable to set sync avatar for ${recipient.id}")
        }
      }

      contact = deviceContacts.read()
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = false

  override fun onFailure() = Unit

  class Factory : Job.Factory<MultiDeviceContactSyncJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): MultiDeviceContactSyncJob {
      val data = JsonJobData.deserialize(serializedData)
      return MultiDeviceContactSyncJob(parameters, data.getStringAsBlob(KEY_ATTACHMENT_POINTER))
    }
  }

  companion object {
    const val KEY = "MultiDeviceContactSyncJob"
    const val KEY_ATTACHMENT_POINTER = "attachment_pointer"
    private const val MAX_ATTACHMENT_SIZE: Long = 100 * 1024 * 1024
    private val TAG = Log.tag(MultiDeviceContactSyncJob::class.java)
  }
}
