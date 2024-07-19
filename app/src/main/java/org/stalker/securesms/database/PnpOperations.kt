package org.stalker.securesms.database

import app.cash.exhaustive.Exhaustive
import org.stalker.securesms.database.model.RecipientRecord
import org.stalker.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.api.push.ServiceId.PNI

/**
 * Encapsulates data around processing a tuple of user data into a user entry in [RecipientTable].
 * Also lets you apply a list of [PnpOperation]s to get what the resulting dataset would be.
 */
data class PnpDataSet(
  val e164: String?,
  val pni: PNI?,
  val aci: ACI?,
  val byE164: RecipientId?,
  val byPni: RecipientId?,
  val byAci: RecipientId?,
  val e164Record: RecipientRecord? = null,
  val pniRecord: RecipientRecord? = null,
  val aciRecord: RecipientRecord? = null
) {

  /**
   * @return The common id if all non-null ids are equal, or null if all are null or at least one non-null pair doesn't match.
   */
  val commonId: RecipientId? = findCommonId(listOf(byE164, byPni, byAci))

  /** The ID that would be used to contact this user. */
  val serviceId: ServiceId? = aci ?: pni

  fun MutableSet<RecipientRecord>.replace(recipientId: RecipientId, update: (RecipientRecord) -> RecipientRecord) {
    val toUpdate = this.first { it.id == recipientId }
    this.removeIf { it.id == toUpdate.id }
    this += update(toUpdate)
  }

  /**
   * Applies the set of operations and returns the resulting dataset.
   * Important: This only occurs _in memory_. You must still apply the operations to disk to persist them.
   */
  fun perform(operations: LinkedHashSet<PnpOperation>): PnpDataSet {
    if (operations.isEmpty()) {
      return this
    }

    val records: MutableSet<RecipientRecord> = listOfNotNull(e164Record, pniRecord, aciRecord).toMutableSet()

    for (operation in operations) {
      @Exhaustive
      when (operation) {
        is PnpOperation.RemoveE164 -> {
          records.replace(operation.recipientId) { it.copy(e164 = null) }
        }
        is PnpOperation.RemovePni -> {
          records.replace(operation.recipientId) { record ->
            record.copy(
              pni = null,
              aci = record.aci
            )
          }
        }
        is PnpOperation.SetAci -> {
          records.replace(operation.recipientId) { it.copy(aci = operation.aci) }
        }
        is PnpOperation.SetE164 -> {
          records.replace(operation.recipientId) { it.copy(e164 = operation.e164) }
        }
        is PnpOperation.SetPni -> {
          records.replace(operation.recipientId) { record ->
            record.copy(
              pni = operation.pni
            )
          }
        }
        is PnpOperation.Merge -> {
          val primary: RecipientRecord = records.first { it.id == operation.primaryId }
          val secondary: RecipientRecord = records.first { it.id == operation.secondaryId }

          records.replace(primary.id) { _ ->
            primary.copy(
              e164 = primary.e164 ?: secondary.e164,
              pni = primary.pni ?: secondary.pni,
              aci = primary.aci ?: secondary.aci
            )
          }

          records.removeIf { it.id == secondary.id }
        }
        is PnpOperation.SessionSwitchoverInsert -> Unit
        is PnpOperation.ChangeNumberInsert -> Unit
      }
    }

    val newE164Record = if (e164 != null) records.firstOrNull { it.e164 == e164 } else null
    val newPniRecord = if (pni != null) records.firstOrNull { it.pni == pni } else null
    val newAciRecord = if (aci != null) records.firstOrNull { it.aci == aci } else null

    return this.copy(
      byE164 = newE164Record?.id,
      byPni = newPniRecord?.id,
      byAci = newAciRecord?.id,
      e164Record = newE164Record,
      pniRecord = newPniRecord,
      aciRecord = newAciRecord
    )
  }

  companion object {
    private fun findCommonId(ids: List<RecipientId?>): RecipientId? {
      val nonNull = ids.filterNotNull()

      return when {
        nonNull.isEmpty() -> null
        nonNull.all { it == nonNull[0] } -> nonNull[0]
        else -> null
      }
    }
  }
}

/**
 * Represents a set of actions that need to be applied to incorporate a tuple of user data
 * into [RecipientTable].
 */
data class PnpChangeSet(
  val id: PnpIdResolver,
  val operations: LinkedHashSet<PnpOperation> = linkedSetOf(),
  val breadCrumbs: List<String> = emptyList()
) {
  // We want to exclude breadcrumbs from equality for testing purposes
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as PnpChangeSet

    if (id != other.id) return false
    if (operations != other.operations) return false

    return true
  }

  override fun hashCode(): Int {
    var result = id.hashCode()
    result = 31 * result + operations.hashCode()
    return result
  }
}

sealed class PnpIdResolver {
  data class PnpNoopId(
    val recipientId: RecipientId
  ) : PnpIdResolver()

  data class PnpInsert(
    val e164: String?,
    val pni: PNI?,
    val aci: ACI?
  ) : PnpIdResolver()
}

/**
 * An operation that needs to be performed on the [RecipientTable] as part of merging in new user data.
 * Lets us describe various situations as a series of operations, making code clearer and tests easier.
 */
sealed class PnpOperation {
  abstract val recipientId: RecipientId

  data class RemoveE164(
    override val recipientId: RecipientId
  ) : PnpOperation()

  data class RemovePni(
    override val recipientId: RecipientId
  ) : PnpOperation()

  data class SetE164(
    override val recipientId: RecipientId,
    val e164: String
  ) : PnpOperation()

  data class SetPni(
    override val recipientId: RecipientId,
    val pni: PNI
  ) : PnpOperation()

  data class SetAci(
    override val recipientId: RecipientId,
    val aci: ACI
  ) : PnpOperation()

  /**
   * Merge two rows into one. Prefer data in the primary row when there's conflicts. Delete the secondary row afterwards.
   */
  data class Merge(
    val primaryId: RecipientId,
    val secondaryId: RecipientId
  ) : PnpOperation() {
    override val recipientId: RecipientId
      get() = throw UnsupportedOperationException()
  }

  data class SessionSwitchoverInsert(
    override val recipientId: RecipientId,
    val e164: String?
  ) : PnpOperation()

  data class ChangeNumberInsert(
    override val recipientId: RecipientId,
    val oldE164: String,
    val newE164: String
  ) : PnpOperation()
}
