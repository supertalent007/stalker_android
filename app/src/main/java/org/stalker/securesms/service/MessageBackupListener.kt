/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.service

import android.content.Context
import org.stalker.securesms.backup.v2.BackupFrequency
import org.stalker.securesms.jobs.BackupMessagesJob
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.toMillis
import java.time.LocalDateTime
import java.util.Random
import java.util.concurrent.TimeUnit

class MessageBackupListener : PersistentAlarmManagerListener() {
  override fun shouldScheduleExact(): Boolean {
    return true
  }

  override fun getNextScheduledExecutionTime(context: Context): Long {
    return SignalStore.backup().nextBackupTime
  }

  override fun onAlarm(context: Context, scheduledTime: Long): Long {
    if (SignalStore.backup().areBackupsEnabled) {
      BackupMessagesJob.enqueue()
    }
    return setNextBackupTimeToIntervalFromNow()
  }

  companion object {
    private val BACKUP_JITTER_WINDOW_SECONDS = Math.toIntExact(TimeUnit.MINUTES.toSeconds(10))

    @JvmStatic
    fun schedule(context: Context?) {
      if (FeatureFlags.messageBackups() && SignalStore.backup().areBackupsEnabled) {
        MessageBackupListener().onReceive(context, getScheduleIntent())
      }
    }

    fun setNextBackupTimeToIntervalFromNow(): Long {
      val now = LocalDateTime.now()
      val hour = SignalStore.settings().backupHour
      val minute = SignalStore.settings().backupMinute
      var next = now.withHour(hour).withMinute(minute).withSecond(0)
      val jitter = Random().nextInt(BACKUP_JITTER_WINDOW_SECONDS) - BACKUP_JITTER_WINDOW_SECONDS / 2
      next.plusSeconds(jitter.toLong())
      next = when (SignalStore.backup().backupFrequency) {
        BackupFrequency.DAILY -> next.plusDays(1)
        BackupFrequency.MANUAL -> next.plusDays(365)
        BackupFrequency.MONTHLY -> next.plusDays(30)
        BackupFrequency.WEEKLY -> next.plusDays(7)
      }
      if (now.isAfter(next)) {
        next = next.plusDays(1)
      }
      val nextTime = next.toMillis()
      SignalStore.backup().nextBackupTime = nextTime
      return nextTime
    }
  }
}
