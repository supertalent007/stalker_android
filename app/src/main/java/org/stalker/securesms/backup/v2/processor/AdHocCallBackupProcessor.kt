/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.processor

import org.signal.core.util.logging.Log
import org.stalker.securesms.backup.v2.BackupState
import org.stalker.securesms.backup.v2.database.getAdhocCallsForBackup
import org.stalker.securesms.backup.v2.database.restoreCallLogFromBackup
import org.stalker.securesms.backup.v2.proto.AdHocCall
import org.stalker.securesms.backup.v2.proto.Frame
import org.stalker.securesms.backup.v2.stream.BackupFrameEmitter
import org.stalker.securesms.database.SignalDatabase

object AdHocCallBackupProcessor {

  val TAG = Log.tag(AdHocCallBackupProcessor::class.java)

  fun export(emitter: BackupFrameEmitter) {
    SignalDatabase.calls.getAdhocCallsForBackup().use { reader ->
      for (callLog in reader) {
        if (callLog != null) {
          emitter.emit(Frame(adHocCall = callLog))
        }
      }
    }
  }

  fun import(call: AdHocCall, backupState: BackupState) {
    SignalDatabase.calls.restoreCallLogFromBackup(call, backupState)
  }
}
