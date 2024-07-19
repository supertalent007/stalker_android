package org.stalker.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

@Suppress("ClassName")
object V164_ThreadDatabaseReadIndexMigration : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("CREATE INDEX IF NOT EXISTS thread_read ON thread (read);")
  }
}
