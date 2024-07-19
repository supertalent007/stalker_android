/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.notifications

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.stalker.securesms.crash.CrashConfig
import org.stalker.securesms.database.LogDatabase
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.util.LocaleFeatureFlags
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days

/**
 * View model for checking for various app vitals, like slow notifications and crashes.
 */
class VitalsViewModel(private val context: Application) : AndroidViewModel(context) {

  private val checkSubject = BehaviorSubject.create<Unit>()

  val vitalsState: Observable<State>

  init {
    vitalsState = checkSubject
      .subscribeOn(Schedulers.io())
      .observeOn(Schedulers.io())
      .throttleFirst(1, TimeUnit.MINUTES)
      .switchMapSingle {
        checkHeuristics()
      }
      .distinctUntilChanged()
      .observeOn(AndroidSchedulers.mainThread())
  }

  fun checkSlowNotificationHeuristics() {
    checkSubject.onNext(Unit)
  }

  private fun checkHeuristics(): Single<State> {
    return Single.fromCallable {
      var state = State.NONE
      if (SlowNotificationHeuristics.isHavingDelayedNotifications()) {
        if (LocaleFeatureFlags.isBatterySaverPromptEnabled() &&
          SlowNotificationHeuristics.isPotentiallyCausedByBatteryOptimizations() &&
          Build.VERSION.SDK_INT >= 23
        ) {
          if (SlowNotificationHeuristics.shouldPromptBatterySaver()) {
            state = State.PROMPT_BATTERY_SAVER_DIALOG
          }
        } else if (SlowNotificationHeuristics.shouldPromptUserForLogs()) {
          state = State.PROMPT_DEBUGLOGS_FOR_NOTIFICATIONS
        }
      } else if (LogDatabase.getInstance(context).crashes.anyMatch(patterns = CrashConfig.patterns, promptThreshold = System.currentTimeMillis() - 14.days.inWholeMilliseconds)) {
        val timeSinceLastPrompt = System.currentTimeMillis() - SignalStore.uiHints().lastCrashPrompt

        if (timeSinceLastPrompt > 1.days.inWholeMilliseconds) {
          state = State.PROMPT_DEBUGLOGS_FOR_CRASH
        }
      }

      return@fromCallable state
    }.subscribeOn(Schedulers.io())
  }

  enum class State {
    NONE,
    PROMPT_BATTERY_SAVER_DIALOG,
    PROMPT_DEBUGLOGS_FOR_NOTIFICATIONS,
    PROMPT_DEBUGLOGS_FOR_CRASH
  }
}
