/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.stalker.securesms.components.webrtc.requests

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.stalker.securesms.contacts.paged.GroupsInCommon
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId

class CallLinkIncomingRequestRepository {

  fun getGroupsInCommon(recipientId: RecipientId): Observable<GroupsInCommon> {
    return Recipient.observable(recipientId).flatMapSingle { recipient ->
      if (recipient.hasGroupsInCommon) {
        Single.fromCallable {
          val groupsInCommon = SignalDatabase.groups.getGroupsContainingMember(recipient.id, true)
          val total = groupsInCommon.size
          val names = groupsInCommon.take(2).map { it.title!! }
          GroupsInCommon(total, names)
        }.observeOn(Schedulers.io())
      } else {
        Single.just(GroupsInCommon(0, listOf()))
      }
    }
  }
}
