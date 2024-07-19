package org.stalker.securesms.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.util.Base64
import org.signal.core.util.update
import org.stalker.securesms.database.RecipientTable
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.api.push.ServiceId.PNI
import org.whispersystems.signalservice.api.storage.SignalContactRecord
import org.whispersystems.signalservice.api.storage.StorageId
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContactRecordProcessorTest {

  @Before
  fun setup() {
    SignalStore.account().setE164(E164_SELF)
    SignalStore.account().setAci(ACI_SELF)
    SignalStore.account().setPni(PNI_SELF)
  }

  @Test
  fun process_splitContact_normalSplit_twoRecords() {
    // GIVEN
    val originalId = SignalDatabase.recipients.getAndPossiblyMerge(ACI_A, PNI_A, E164_A)
    setStorageId(originalId, STORAGE_ID_A)

    val remote1 = buildRecord(
      STORAGE_ID_B,
      ContactRecord(
        aci = ACI_A.toString(),
        unregisteredAtTimestamp = 100
      )
    )

    val remote2 = buildRecord(
      STORAGE_ID_C,
      ContactRecord(
        pni = PNI_A.toString(),
        e164 = E164_A
      )
    )

    // WHEN
    val subject = ContactRecordProcessor()
    subject.process(listOf(remote1, remote2), StorageSyncHelper.KEY_GENERATOR)

    // THEN
    val byAci: RecipientId = SignalDatabase.recipients.getByAci(ACI_A).get()

    val byE164: RecipientId = SignalDatabase.recipients.getByE164(E164_A).get()
    val byPni: RecipientId = SignalDatabase.recipients.getByPni(PNI_A).get()

    assertEquals(originalId, byAci)
    assertEquals(byE164, byPni)
    assertNotEquals(byAci, byE164)
  }

  @Test
  fun process_splitContact_normalSplit_oneRecord() {
    // GIVEN
    val originalId = SignalDatabase.recipients.getAndPossiblyMerge(ACI_A, PNI_A, E164_A)
    setStorageId(originalId, STORAGE_ID_A)

    val remote = buildRecord(
      STORAGE_ID_B,
      ContactRecord(
        aci = ACI_A.toString(),
        unregisteredAtTimestamp = 100
      )
    )

    // WHEN
    val subject = ContactRecordProcessor()
    subject.process(listOf(remote), StorageSyncHelper.KEY_GENERATOR)

    // THEN
    val byAci: RecipientId = SignalDatabase.recipients.getByAci(ACI_A).get()

    val byE164: RecipientId = SignalDatabase.recipients.getByE164(E164_A).get()
    val byPni: RecipientId = SignalDatabase.recipients.getByPni(PNI_A).get()

    assertEquals(originalId, byAci)
    assertEquals(byE164, byPni)
    assertNotEquals(byAci, byE164)
  }

  @Test
  fun process_splitContact_doNotSplitIfAciRecordIsRegistered() {
    // GIVEN
    val originalId = SignalDatabase.recipients.getAndPossiblyMerge(ACI_A, PNI_A, E164_A)
    setStorageId(originalId, STORAGE_ID_A)

    val remote1 = buildRecord(
      STORAGE_ID_B,
      ContactRecord(
        aci = ACI_A.toString(),
        unregisteredAtTimestamp = 0
      )
    )

    val remote2 = buildRecord(
      STORAGE_ID_C,
      ContactRecord(
        aci = PNI_A.toString(),
        pni = PNI_A.toString(),
        e164 = E164_A
      )
    )

    // WHEN
    val subject = ContactRecordProcessor()
    subject.process(listOf(remote1, remote2), StorageSyncHelper.KEY_GENERATOR)

    // THEN
    val byAci: RecipientId = SignalDatabase.recipients.getByAci(ACI_A).get()
    val byE164: RecipientId = SignalDatabase.recipients.getByE164(E164_A).get()
    val byPni: RecipientId = SignalDatabase.recipients.getByPni(PNI_A).get()

    assertEquals(originalId, byAci)
    assertEquals(byE164, byPni)
    assertEquals(byAci, byE164)
  }

  private fun buildRecord(id: StorageId, record: ContactRecord): SignalContactRecord {
    return SignalContactRecord(id, record)
  }

  private fun setStorageId(recipientId: RecipientId, storageId: StorageId) {
    SignalDatabase.rawDatabase
      .update(RecipientTable.TABLE_NAME)
      .values(RecipientTable.STORAGE_SERVICE_ID to Base64.encodeWithPadding(storageId.raw))
      .where("${RecipientTable.ID} = ?", recipientId)
      .run()
  }

  companion object {
    val ACI_A = ACI.from(UUID.fromString("aaaa0000-5a76-47fa-a98a-7e72c948a82e"))
    val ACI_B = ACI.from(UUID.fromString("bbbb0000-0b60-4a68-9cd9-ed2f8453f9ed"))
    val ACI_SELF = ACI.from(UUID.fromString("77770000-b477-4f35-a824-d92987a63641"))

    val PNI_A = PNI.from(UUID.fromString("aaaa1111-c960-4f6c-8385-671ad2ffb999"))
    val PNI_B = PNI.from(UUID.fromString("bbbb1111-cd55-40bf-adda-c35a85375533"))
    val PNI_SELF = PNI.from(UUID.fromString("77771111-b014-41fb-bf73-05cb2ec52910"))

    const val E164_A = "+12222222222"
    const val E164_B = "+13333333333"
    const val E164_SELF = "+10000000000"

    val STORAGE_ID_A: StorageId = StorageId.forContact(byteArrayOf(1, 2, 3, 4))
    val STORAGE_ID_B: StorageId = StorageId.forContact(byteArrayOf(5, 6, 7, 8))
    val STORAGE_ID_C: StorageId = StorageId.forContact(byteArrayOf(9, 10, 11, 12))
  }
}
