package org.stalker.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * This migration used to do what [V191_UniqueMessageMigrationV2] does. However, due to bugs, the migration was abandoned.
 * We now re-do the migration in V191.
 */
@Suppress("ClassName")
object V190_UniqueMessageMigration : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
