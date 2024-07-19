package org.stalker.securesms.mediasend.v2.gallery

import org.stalker.securesms.util.adapter.mapping.MappingModel

data class MediaGalleryState(
  val bucketId: String?,
  val bucketTitle: String?,
  val items: List<MappingModel<*>> = listOf()
)
