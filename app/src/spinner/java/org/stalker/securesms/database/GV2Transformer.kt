package org.stalker.securesms.database

import android.database.Cursor
import okio.ByteString
import org.signal.core.util.requireBlob
import org.signal.spinner.ColumnTransformer
import org.signal.storageservice.protos.groups.local.DecryptedBannedMember
import org.signal.storageservice.protos.groups.local.DecryptedGroup
import org.signal.storageservice.protos.groups.local.DecryptedMember
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember
import org.signal.storageservice.protos.groups.local.DecryptedRequestingMember
import org.whispersystems.signalservice.api.util.UuidUtil

object GV2Transformer : ColumnTransformer {
  override fun matches(tableName: String?, columnName: String): Boolean {
    return columnName == GroupTable.V2_DECRYPTED_GROUP
  }

  override fun transform(tableName: String?, columnName: String, cursor: Cursor): String? {
    return if (columnName == GroupTable.V2_DECRYPTED_GROUP) {
      val groupBytes = cursor.requireBlob(GroupTable.V2_DECRYPTED_GROUP)
      val group = DecryptedGroup.ADAPTER.decode(groupBytes!!)
      group.formatAsHtml()
    } else {
      null
    }
  }
}

private fun DecryptedGroup.formatAsHtml(): String {
  val members: String = describeList(members, DecryptedMember::aciBytes)
  val pending: String = describeList(pendingMembers, DecryptedPendingMember::serviceIdBytes)
  val requesting: String = describeList(requestingMembers, DecryptedRequestingMember::aciBytes)
  val banned: String = describeList(bannedMembers, DecryptedBannedMember::serviceIdBytes)

  return """
    Revision:     $revision
    Title:        $title
    Avatar:       ${(avatar?.length ?: 0) != 0}
    Timer:        ${disappearingMessagesTimer!!.duration}
    Description:  "$description"
    Announcement: $isAnnouncementGroup
    Access:       attributes(${accessControl!!.attributes}) members(${accessControl!!.members}) link(${accessControl!!.addFromInviteLink})
    Members:      $members
    Pending:      $pending
    Requesting:   $requesting
    Banned:       $banned
  """.trimIndent().replace("\n", "<br>")
}

private fun <T> describeList(list: List<T>, getUuid: (T) -> ByteString): String {
  return if (list.isNotEmpty() && list.size < 10) {
    var pendingMembers = "${list.size}\n"
    list.forEachIndexed { i, pendingMember ->
      pendingMembers += "      ${UuidUtil.fromByteString(getUuid(pendingMember))}"
      if (i != list.lastIndex) {
        pendingMembers += "\n"
      }
    }
    pendingMembers
  } else {
    list.size.toString()
  }
}
