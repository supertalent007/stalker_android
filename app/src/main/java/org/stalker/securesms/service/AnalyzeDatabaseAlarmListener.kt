package org.stalker.securesms.service

import android.content.Context
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobs.AnalyzeDatabaseJob
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.util.toMillis
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * Schedules database analysis to happen everyday at 3am.
 */
class AnalyzeDatabaseAlarmListener : PersistentAlarmManagerListener() {
  companion object {
    @JvmStatic
    fun schedule(context: Context?) {
      AnalyzeDatabaseAlarmListener().onReceive(context, getScheduleIntent())
    }
  }

  override fun shouldScheduleExact(): Boolean {
    return true
  }

  override fun getNextScheduledExecutionTime(context: Context): Long {
    var nextTime = SignalStore.misc().nextDatabaseAnalysisTime

    if (nextTime == 0L) {
      nextTime = getNextTime()
      SignalStore.misc().nextDatabaseAnalysisTime = nextTime
    }

    return nextTime
  }

  override fun onAlarm(context: Context, scheduledTime: Long): Long {
    ApplicationDependencies.getJobManager().add(AnalyzeDatabaseJob())

    val nextTime = getNextTime()
    SignalStore.misc().nextDatabaseAnalysisTime = nextTime

    return nextTime
  }

  private fun getNextTime(): Long {
    val random = SecureRandom()
    return LocalDateTime
      .now()
      .plusDays(1)
      .withHour(2 + random.nextInt(3))
      .withMinute(random.nextInt(60))
      .withSecond(random.nextInt(60))
      .toMillis()
  }
}
