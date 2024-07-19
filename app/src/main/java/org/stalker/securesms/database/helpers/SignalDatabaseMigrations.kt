package org.stalker.securesms.database.helpers

import android.app.Application
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.core.util.areForeignKeyConstraintsEnabled
import org.signal.core.util.logging.Log
import org.signal.core.util.withinTransaction
import org.stalker.securesms.database.helpers.migration.SignalDatabaseMigration
import org.stalker.securesms.database.helpers.migration.V149_LegacyMigrations
import org.stalker.securesms.database.helpers.migration.V150_UrgentMslFlagMigration
import org.stalker.securesms.database.helpers.migration.V151_MyStoryMigration
import org.stalker.securesms.database.helpers.migration.V152_StoryGroupTypesMigration
import org.stalker.securesms.database.helpers.migration.V153_MyStoryMigration
import org.stalker.securesms.database.helpers.migration.V154_PniSignaturesMigration
import org.stalker.securesms.database.helpers.migration.V155_SmsExporterMigration
import org.stalker.securesms.database.helpers.migration.V156_RecipientUnregisteredTimestampMigration
import org.stalker.securesms.database.helpers.migration.V157_RecipeintHiddenMigration
import org.stalker.securesms.database.helpers.migration.V158_GroupsLastForceUpdateTimestampMigration
import org.stalker.securesms.database.helpers.migration.V159_ThreadUnreadSelfMentionCount
import org.stalker.securesms.database.helpers.migration.V160_SmsMmsExportedIndexMigration
import org.stalker.securesms.database.helpers.migration.V161_StorySendMessageIdIndex
import org.stalker.securesms.database.helpers.migration.V162_ThreadUnreadSelfMentionCountFixup
import org.stalker.securesms.database.helpers.migration.V163_RemoteMegaphoneSnoozeSupportMigration
import org.stalker.securesms.database.helpers.migration.V164_ThreadDatabaseReadIndexMigration
import org.stalker.securesms.database.helpers.migration.V165_MmsMessageBoxPaymentTransactionIndexMigration
import org.stalker.securesms.database.helpers.migration.V166_ThreadAndMessageForeignKeys
import org.stalker.securesms.database.helpers.migration.V167_RecreateReactionTriggers
import org.stalker.securesms.database.helpers.migration.V168_SingleMessageTableMigration
import org.stalker.securesms.database.helpers.migration.V169_EmojiSearchIndexRank
import org.stalker.securesms.database.helpers.migration.V170_CallTableMigration
import org.stalker.securesms.database.helpers.migration.V171_ThreadForeignKeyFix
import org.stalker.securesms.database.helpers.migration.V172_GroupMembershipMigration
import org.stalker.securesms.database.helpers.migration.V173_ScheduledMessagesMigration
import org.stalker.securesms.database.helpers.migration.V174_ReactionForeignKeyMigration
import org.stalker.securesms.database.helpers.migration.V175_FixFullTextSearchLink
import org.stalker.securesms.database.helpers.migration.V176_AddScheduledDateToQuoteIndex
import org.stalker.securesms.database.helpers.migration.V177_MessageSendLogTableCleanupMigration
import org.stalker.securesms.database.helpers.migration.V178_ReportingTokenColumnMigration
import org.stalker.securesms.database.helpers.migration.V179_CleanupDanglingMessageSendLogMigration
import org.stalker.securesms.database.helpers.migration.V180_RecipientNicknameMigration
import org.stalker.securesms.database.helpers.migration.V181_ThreadTableForeignKeyCleanup
import org.stalker.securesms.database.helpers.migration.V182_CallTableMigration
import org.stalker.securesms.database.helpers.migration.V183_CallLinkTableMigration
import org.stalker.securesms.database.helpers.migration.V184_CallLinkReplaceIndexMigration
import org.stalker.securesms.database.helpers.migration.V185_MessageRecipientsAndEditMessageMigration
import org.stalker.securesms.database.helpers.migration.V186_ForeignKeyIndicesMigration
import org.stalker.securesms.database.helpers.migration.V187_MoreForeignKeyIndexesMigration
import org.stalker.securesms.database.helpers.migration.V188_FixMessageRecipientsAndEditMessageMigration
import org.stalker.securesms.database.helpers.migration.V189_CreateCallLinkTableColumnsAndRebuildFKReference
import org.stalker.securesms.database.helpers.migration.V190_UniqueMessageMigration
import org.stalker.securesms.database.helpers.migration.V191_UniqueMessageMigrationV2
import org.stalker.securesms.database.helpers.migration.V192_CallLinkTableNullableRootKeys
import org.stalker.securesms.database.helpers.migration.V193_BackCallLinksWithRecipient
import org.stalker.securesms.database.helpers.migration.V194_KyberPreKeyMigration
import org.stalker.securesms.database.helpers.migration.V195_GroupMemberForeignKeyMigration
import org.stalker.securesms.database.helpers.migration.V196_BackCallLinksWithRecipientV2
import org.stalker.securesms.database.helpers.migration.V197_DropAvatarColorFromCallLinks
import org.stalker.securesms.database.helpers.migration.V198_AddMacDigestColumn
import org.stalker.securesms.database.helpers.migration.V199_AddThreadActiveColumn
import org.stalker.securesms.database.helpers.migration.V200_ResetPniColumn
import org.stalker.securesms.database.helpers.migration.V201_RecipientTableValidations
import org.stalker.securesms.database.helpers.migration.V202_DropMessageTableThreadDateIndex
import org.stalker.securesms.database.helpers.migration.V203_PreKeyStaleTimestamp
import org.stalker.securesms.database.helpers.migration.V204_GroupForeignKeyMigration
import org.stalker.securesms.database.helpers.migration.V205_DropPushTable
import org.stalker.securesms.database.helpers.migration.V206_AddConversationCountIndex
import org.stalker.securesms.database.helpers.migration.V207_AddChunkSizeColumn
import org.stalker.securesms.database.helpers.migration.V209_ClearRecipientPniFromAciColumn
import org.stalker.securesms.database.helpers.migration.V210_FixPniPossibleColumns
import org.stalker.securesms.database.helpers.migration.V211_ReceiptColumnRenames
import org.stalker.securesms.database.helpers.migration.V212_RemoveDistributionListUniqueConstraint
import org.stalker.securesms.database.helpers.migration.V213_FixUsernameInE164Column
import org.stalker.securesms.database.helpers.migration.V214_PhoneNumberSharingColumn
import org.stalker.securesms.database.helpers.migration.V215_RemoveAttachmentUniqueId
import org.stalker.securesms.database.helpers.migration.V216_PhoneNumberDiscoverable
import org.stalker.securesms.database.helpers.migration.V217_MessageTableExtrasColumn
import org.stalker.securesms.database.helpers.migration.V218_RecipientPniSignatureVerified
import org.stalker.securesms.database.helpers.migration.V219_PniPreKeyStores
import org.stalker.securesms.database.helpers.migration.V220_PreKeyConstraints
import org.stalker.securesms.database.helpers.migration.V221_AddReadColumnToCallEventsTable
import org.stalker.securesms.database.helpers.migration.V222_DataHashRefactor
import org.stalker.securesms.database.helpers.migration.V223_AddNicknameAndNoteFieldsToRecipientTable
import org.stalker.securesms.database.helpers.migration.V224_AddAttachmentArchiveColumns
import org.stalker.securesms.database.helpers.migration.V225_AddLocalUserJoinedStateAndGroupCallActiveState
import org.stalker.securesms.database.helpers.migration.V226_AddAttachmentMediaIdIndex
import org.stalker.securesms.database.helpers.migration.V227_AddAttachmentArchiveTransferState
import org.stalker.securesms.database.helpers.migration.V228_AddNameCollisionTables
import org.stalker.securesms.database.helpers.migration.V229_MarkMissedCallEventsNotified
import org.stalker.securesms.database.helpers.migration.V230_UnreadCountIndices
import org.stalker.securesms.database.helpers.migration.V231_ArchiveThumbnailColumns

/**
 * Contains all of the database migrations for [SignalDatabase]. Broken into a separate file for cleanliness.
 */
object SignalDatabaseMigrations {

  val TAG: String = Log.tag(SignalDatabaseMigrations.javaClass)

  private val migrations: List<Pair<Int, SignalDatabaseMigration>> = listOf(
    149 to V149_LegacyMigrations,
    150 to V150_UrgentMslFlagMigration,
    151 to V151_MyStoryMigration,
    152 to V152_StoryGroupTypesMigration,
    153 to V153_MyStoryMigration,
    154 to V154_PniSignaturesMigration,
    155 to V155_SmsExporterMigration,
    156 to V156_RecipientUnregisteredTimestampMigration,
    157 to V157_RecipeintHiddenMigration,
    158 to V158_GroupsLastForceUpdateTimestampMigration,
    159 to V159_ThreadUnreadSelfMentionCount,
    160 to V160_SmsMmsExportedIndexMigration,
    161 to V161_StorySendMessageIdIndex,
    162 to V162_ThreadUnreadSelfMentionCountFixup,
    163 to V163_RemoteMegaphoneSnoozeSupportMigration,
    164 to V164_ThreadDatabaseReadIndexMigration,
    165 to V165_MmsMessageBoxPaymentTransactionIndexMigration,
    166 to V166_ThreadAndMessageForeignKeys,
    167 to V167_RecreateReactionTriggers,
    168 to V168_SingleMessageTableMigration,
    169 to V169_EmojiSearchIndexRank,
    170 to V170_CallTableMigration,
    171 to V171_ThreadForeignKeyFix,
    172 to V172_GroupMembershipMigration,
    173 to V173_ScheduledMessagesMigration,
    174 to V174_ReactionForeignKeyMigration,
    175 to V175_FixFullTextSearchLink,
    176 to V176_AddScheduledDateToQuoteIndex,
    177 to V177_MessageSendLogTableCleanupMigration,
    178 to V178_ReportingTokenColumnMigration,
    179 to V179_CleanupDanglingMessageSendLogMigration,
    180 to V180_RecipientNicknameMigration,
    181 to V181_ThreadTableForeignKeyCleanup,
    182 to V182_CallTableMigration,
    183 to V183_CallLinkTableMigration,
    184 to V184_CallLinkReplaceIndexMigration,
    185 to V185_MessageRecipientsAndEditMessageMigration,
    186 to V186_ForeignKeyIndicesMigration,
    187 to V187_MoreForeignKeyIndexesMigration,
    188 to V188_FixMessageRecipientsAndEditMessageMigration,
    189 to V189_CreateCallLinkTableColumnsAndRebuildFKReference,
    190 to V190_UniqueMessageMigration,
    191 to V191_UniqueMessageMigrationV2,
    192 to V192_CallLinkTableNullableRootKeys,
    193 to V193_BackCallLinksWithRecipient,
    194 to V194_KyberPreKeyMigration,
    195 to V195_GroupMemberForeignKeyMigration,
    196 to V196_BackCallLinksWithRecipientV2,
    197 to V197_DropAvatarColorFromCallLinks,
    198 to V198_AddMacDigestColumn,
    199 to V199_AddThreadActiveColumn,
    200 to V200_ResetPniColumn,
    201 to V201_RecipientTableValidations,
    202 to V202_DropMessageTableThreadDateIndex,
    203 to V203_PreKeyStaleTimestamp,
    204 to V204_GroupForeignKeyMigration,
    205 to V205_DropPushTable,
    206 to V206_AddConversationCountIndex,
    207 to V207_AddChunkSizeColumn,
    // 208 was a bad migration that only manipulated data and did not change schema, replaced by 209
    209 to V209_ClearRecipientPniFromAciColumn,
    210 to V210_FixPniPossibleColumns,
    211 to V211_ReceiptColumnRenames,
    212 to V212_RemoveDistributionListUniqueConstraint,
    213 to V213_FixUsernameInE164Column,
    214 to V214_PhoneNumberSharingColumn,
    215 to V215_RemoveAttachmentUniqueId,
    216 to V216_PhoneNumberDiscoverable,
    217 to V217_MessageTableExtrasColumn,
    218 to V218_RecipientPniSignatureVerified,
    219 to V219_PniPreKeyStores,
    220 to V220_PreKeyConstraints,
    221 to V221_AddReadColumnToCallEventsTable,
    222 to V222_DataHashRefactor,
    223 to V223_AddNicknameAndNoteFieldsToRecipientTable,
    224 to V224_AddAttachmentArchiveColumns,
    225 to V225_AddLocalUserJoinedStateAndGroupCallActiveState,
    226 to V226_AddAttachmentMediaIdIndex,
    227 to V227_AddAttachmentArchiveTransferState,
    228 to V228_AddNameCollisionTables,
    229 to V229_MarkMissedCallEventsNotified,
    230 to V230_UnreadCountIndices,
    231 to V231_ArchiveThumbnailColumns
  )

  const val DATABASE_VERSION = 231

  @JvmStatic
  fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    val initialForeignKeyState = db.areForeignKeyConstraintsEnabled()

    for (migrationData in migrations) {
      val (version, migration) = migrationData

      if (oldVersion < version) {
        Log.i(TAG, "Running migration for version $version: ${migration.javaClass.simpleName}. Foreign keys: ${migration.enableForeignKeys}")
        val startTime = System.currentTimeMillis()

        db.setForeignKeyConstraintsEnabled(migration.enableForeignKeys)
        db.withinTransaction {
          migration.migrate(context, db, oldVersion, newVersion)
          db.version = version
        }

        Log.i(TAG, "Successfully completed migration for version $version in ${System.currentTimeMillis() - startTime} ms")
      }
    }

    db.setForeignKeyConstraintsEnabled(initialForeignKeyState)
  }

  @JvmStatic
  fun migratePostTransaction(context: Context, oldVersion: Int) {
    if (oldVersion < V149_LegacyMigrations.MIGRATE_PREKEYS_VERSION) {
      PreKeyMigrationHelper.cleanUpPreKeys(context)
    }
  }
}
