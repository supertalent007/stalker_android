/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * PNIs were incorrectly being set to ACI column, clear them if present.
 */
@Suppress("ClassName")
object V209_ClearRecipientPniFromAciColumn : SignalDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("UPDATE recipient SET aci = NULL WHERE aci LIKE 'PNI:%'")
  }
}
