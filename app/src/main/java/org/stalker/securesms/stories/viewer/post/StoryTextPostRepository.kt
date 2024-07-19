package org.stalker.securesms.stories.viewer.post

import android.graphics.Typeface
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.Base64
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.MmsMessageRecord
import org.stalker.securesms.database.model.databaseprotos.StoryTextPost
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.fonts.TextFont
import org.stalker.securesms.fonts.TextToScript
import org.stalker.securesms.fonts.TypefaceCache

class StoryTextPostRepository {
  fun getRecord(recordId: Long): Single<MmsMessageRecord> {
    return Single.fromCallable {
      SignalDatabase.messages.getMessageRecord(recordId) as MmsMessageRecord
    }.subscribeOn(Schedulers.io())
  }

  fun getTypeface(recordId: Long): Single<Typeface> {
    return getRecord(recordId).flatMap {
      val model = StoryTextPost.ADAPTER.decode(Base64.decode(it.body))
      val textFont = TextFont.fromStyle(model.style)
      val script = TextToScript.guessScript(model.body)

      TypefaceCache.get(ApplicationDependencies.getApplication(), textFont, script)
    }
  }
}
