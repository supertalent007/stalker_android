package org.stalker.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * This adds a column to the Recipients table to store a spam reporting token.
 */
@Suppress("ClassName")
object V178_ReportingTokenColumnMigration : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("ALTER TABLE recipient ADD COLUMN reporting_token BLOB DEFAULT NULL")
  }
}
