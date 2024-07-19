package org.stalker.securesms.service;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.core.util.PendingIntentFlags;
import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.util.ServiceUtil;

/**
 * Class to help manage scheduling events to happen in the future, whether the app is open or not.
 */
public abstract class TimedEventManager<E> {

  private static final String TAG = Log.tag(TimedEventManager.class);

  private final Application application;
  private final Handler     handler;

  public TimedEventManager(@NonNull Application application, @NonNull String threadName) {
    HandlerThread handlerThread = new HandlerThread(threadName, ThreadUtil.PRIORITY_BACKGROUND_THREAD);
    handlerThread.start();

    this.application = application;
    this.handler     = new Handler(handlerThread.getLooper());
  }

  /**
   * Should be called whenever the underlying data of events has changed. Will appropriately
   * schedule new event executions.
   */
  public void scheduleIfNecessary() {
    handler.removeCallbacksAndMessages(null);

    handler.post(() -> {
      E event = getNextClosestEvent();

      if (event != null) {
        long delay = getDelayForEvent(event);

        handler.postDelayed(() -> {
          executeEvent(event);
          scheduleIfNecessary();
        }, delay);

        scheduleAlarm(application, event, delay);
      }
    });
  }

  /**
   * @return The next event that should be executed, or {@code null} if there are no events to execute.
   */
  @WorkerThread
  protected @Nullable abstract E getNextClosestEvent();

  /**
   * Execute the provided event.
   */
  @WorkerThread
  protected abstract void executeEvent(@NonNull E event);

  /**
   * @return How long before the provided event should be executed.
   */
  @WorkerThread
  protected abstract long getDelayForEvent(@NonNull E event);

  /**
   * Schedules an alarm to call {@link #scheduleIfNecessary()} after the specified delay. You can
   * use {@link #setAlarm(Context, long, Class)} as a helper method.
   */
  @AnyThread
  protected abstract void scheduleAlarm(@NonNull Application application, E event, long delay);

  /**
   * Helper method to set an alarm.
   */
  protected static void setAlarm(@NonNull Context context, long delay, @NonNull Class<? extends BroadcastReceiver> alarmClass) {
    Intent        intent        = new Intent(context, alarmClass);
    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntentFlags.mutable());
    AlarmManager  alarmManager  = ServiceUtil.getAlarmManager(context);

    alarmManager.cancel(pendingIntent);
    alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pendingIntent);
  }

  protected static void trySetExactAlarm(@NonNull Context context, long timestamp, @NonNull Class<? extends BroadcastReceiver> alarmClass, @NonNull PendingIntent showIntent) {
    Intent        intent        = new Intent(context, alarmClass);
    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntentFlags.mutable());
    AlarmManager  alarmManager  = ServiceUtil.getAlarmManager(context);

    alarmManager.cancel(pendingIntent);

    boolean hasManagerPermission = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms();
    if (hasManagerPermission) {
      try {
        alarmManager.setAlarmClock(new AlarmManager.AlarmClockInfo(timestamp, showIntent), pendingIntent);
        return;
      } catch (Exception e) {
        Log.w(TAG, e);
      }
    }

    Log.w(TAG, "Unable to schedule exact alarm, falling back to inexact alarm, scheduling alarm for: " + timestamp);
    alarmManager.set(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent);
  }

  protected static void cancelAlarm(@NonNull Context context, @NonNull Class<? extends BroadcastReceiver> alarmClass) {
    Intent        intent        = new Intent(context, alarmClass);
    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntentFlags.mutable());

    try {
      pendingIntent.cancel();
      ServiceUtil.getAlarmManager(context).cancel(pendingIntent);
    } catch (Exception e) {
      Throwable cause = e;
      int depth = 0;
      while (cause != null && depth < 5) {
        if (cause instanceof SecurityException) {
          break;
        } else {
          cause = e.getCause();
          depth++;
        }
      }

      if (e instanceof SecurityException) {
        Log.i(TAG, "Unable to cancel alarm because we don't have permission");
      } else {
        throw e;
      }
    }
  }
}
