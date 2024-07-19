package org.stalker.securesms.keyvalue

import org.signal.core.util.ByteSerializer
import org.stalker.securesms.database.model.databaseprotos.PendingChangeNumberMetadata

/**
 * Serialize [PendingChangeNumberMetadata]
 */
object PendingChangeNumberMetadataSerializer : ByteSerializer<PendingChangeNumberMetadata> {
  override fun serialize(data: PendingChangeNumberMetadata): ByteArray = data.encode()
  override fun deserialize(data: ByteArray): PendingChangeNumberMetadata = PendingChangeNumberMetadata.ADAPTER.decode(data)
}
