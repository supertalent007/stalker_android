package org.stalker.securesms.badges

import android.content.Context
import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.stalker.securesms.badges.models.Badge
import org.stalker.securesms.database.RecipientTable
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.jobs.MultiDeviceProfileContentUpdateJob
import org.stalker.securesms.jobs.RefreshOwnProfileJob
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.storage.StorageSyncHelper
import org.stalker.securesms.util.ProfileUtil
import java.io.IOException

class BadgeRepository(context: Context) {

  companion object {
    private val TAG = Log.tag(BadgeRepository::class.java)
  }

  private val context = context.applicationContext

  /**
   * Sets the visibility for each badge on a user's profile, and uploads them to the server.
   * Does not write to the local database. The caller must either do that themselves or schedule
   * a refresh own profile job.
   *
   * @return A list of the badges, properly modified to either visible or not visible, according to user preferences.
   */
  @Throws(IOException::class)
  @WorkerThread
  fun setVisibilityForAllBadgesSync(
    displayBadgesOnProfile: Boolean,
    selfBadges: List<Badge>
  ): List<Badge> {
    Log.d(TAG, "[setVisibilityForAllBadgesSync] Setting badge visibility...", true)

    val recipientTable: RecipientTable = SignalDatabase.recipients
    val badges = selfBadges.map { it.copy(visible = displayBadgesOnProfile) }

    Log.d(TAG, "[setVisibilityForAllBadgesSync] Uploading profile...", true)
    ProfileUtil.uploadProfileWithBadges(context, badges)
    SignalStore.donationsValues().setDisplayBadgesOnProfile(displayBadgesOnProfile)
    recipientTable.markNeedsSync(Recipient.self().id)

    Log.d(TAG, "[setVisibilityForAllBadgesSync] Requesting data change sync...", true)
    StorageSyncHelper.scheduleSyncForDataChange()

    return badges
  }

  fun setVisibilityForAllBadges(
    displayBadgesOnProfile: Boolean,
    selfBadges: List<Badge> = Recipient.self().badges
  ): Completable = Completable.fromAction {
    setVisibilityForAllBadgesSync(displayBadgesOnProfile, selfBadges)

    Log.d(TAG, "[setVisibilityForAllBadges] Enqueueing profile refresh...", true)
    ApplicationDependencies.getJobManager()
      .startChain(RefreshOwnProfileJob())
      .then(MultiDeviceProfileContentUpdateJob())
      .enqueue()
  }.subscribeOn(Schedulers.io())

  fun setFeaturedBadge(featuredBadge: Badge): Completable = Completable.fromAction {
    val badges = Recipient.self().badges
    val reOrderedBadges = listOf(featuredBadge.copy(visible = true)) + (badges.filterNot { it.id == featuredBadge.id })

    Log.d(TAG, "[setFeaturedBadge] Uploading profile with reordered badges...", true)
    ProfileUtil.uploadProfileWithBadges(context, reOrderedBadges)

    Log.d(TAG, "[setFeaturedBadge] Enqueueing profile refresh...", true)
    ApplicationDependencies.getJobManager()
      .startChain(RefreshOwnProfileJob())
      .then(MultiDeviceProfileContentUpdateJob())
      .enqueue()
  }.subscribeOn(Schedulers.io())
}
