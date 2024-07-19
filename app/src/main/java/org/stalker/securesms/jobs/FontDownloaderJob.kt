package org.stalker.securesms.jobs

import android.graphics.Typeface
import org.signal.core.util.logging.Log
import org.stalker.securesms.fonts.Fonts
import org.stalker.securesms.fonts.SupportedScript
import org.stalker.securesms.fonts.TextFont
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.util.FutureTaskListener
import org.stalker.securesms.util.LocaleUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Job that downloads all of the fonts for a user's locale.
 */
class FontDownloaderJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    private val TAG = Log.tag(FontDownloaderJob::class.java)

    const val KEY = "FontDownloaderJob"
  }

  constructor() : this(
    Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(30))
      .setMaxAttempts(Parameters.UNLIMITED)
      .setMaxInstancesForFactory(1)
      .build()
  )

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  override fun onFailure() = Unit

  override fun onRun() {
    val script = Fonts.getSupportedScript(LocaleUtil.getLocaleDefaults(), SupportedScript.UNKNOWN)
    val asyncResults = TextFont.values()
      .map { Fonts.resolveFont(context, it, script) }
      .filterIsInstance(Fonts.FontResult.Async::class.java)

    if (asyncResults.isEmpty()) {
      Log.i(TAG, "Already downloaded fonts for locale.")
      return
    }

    val countDownLatch = CountDownLatch(asyncResults.size)
    val failure = AtomicInteger(0)
    val listener = object : FutureTaskListener<Typeface> {
      override fun onSuccess(result: Typeface?) {
        countDownLatch.countDown()
      }

      override fun onFailure(exception: ExecutionException?) {
        failure.getAndIncrement()
        countDownLatch.countDown()
      }
    }

    asyncResults.forEach {
      it.future.addListener(listener)
    }

    countDownLatch.await()

    if (failure.get() > 0) {
      throw Exception("Failed to download ${failure.get()} fonts. Scheduling a retry.")
    }
  }

  override fun onShouldRetry(e: Exception): Boolean = true

  class Factory : Job.Factory<FontDownloaderJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): FontDownloaderJob {
      return FontDownloaderJob(parameters)
    }
  }
}
