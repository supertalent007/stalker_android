package org.stalker.securesms.mediasend.v2

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.BreakIteratorCompat
import org.signal.core.util.ThreadUtil
import org.signal.core.util.logging.Log
import org.signal.imageeditor.core.model.EditorModel
import org.stalker.securesms.contacts.paged.ContactSearchKey
import org.stalker.securesms.conversation.MessageSendType
import org.stalker.securesms.database.AttachmentTable.TransformProperties
import org.stalker.securesms.database.SignalDatabase
import org.stalker.securesms.database.model.Mention
import org.stalker.securesms.database.model.StoryType
import org.stalker.securesms.database.model.databaseprotos.BodyRangeList
import org.stalker.securesms.dependencies.ApplicationDependencies
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.keyvalue.StorySend
import org.stalker.securesms.mediasend.CompositeMediaTransform
import org.stalker.securesms.mediasend.ImageEditorModelRenderMediaTransform
import org.stalker.securesms.mediasend.Media
import org.stalker.securesms.mediasend.MediaRepository
import org.stalker.securesms.mediasend.MediaSendActivityResult
import org.stalker.securesms.mediasend.MediaTransform
import org.stalker.securesms.mediasend.MediaUploadRepository
import org.stalker.securesms.mediasend.SentMediaQualityTransform
import org.stalker.securesms.mediasend.VideoTrimTransform
import org.stalker.securesms.mediasend.v2.videos.VideoTrimData
import org.stalker.securesms.mms.GifSlide
import org.stalker.securesms.mms.ImageSlide
import org.stalker.securesms.mms.MediaConstraints
import org.stalker.securesms.mms.OutgoingMessage
import org.stalker.securesms.mms.SentMediaQuality
import org.stalker.securesms.mms.Slide
import org.stalker.securesms.mms.SlideDeck
import org.stalker.securesms.mms.VideoSlide
import org.stalker.securesms.providers.BlobProvider
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.scribbles.ImageEditorFragment
import org.stalker.securesms.sms.MessageSender
import org.stalker.securesms.sms.MessageSender.PreUploadResult
import org.stalker.securesms.sms.MessageSender.SendType
import org.stalker.securesms.stories.Stories
import org.stalker.securesms.util.MediaUtil
import org.stalker.securesms.util.MessageUtil
import java.util.Collections
import java.util.Optional
import java.util.concurrent.TimeUnit

private val TAG = Log.tag(MediaSelectionRepository::class.java)

class MediaSelectionRepository(context: Context) {

  private val context: Context = context.applicationContext

  private val mediaRepository = MediaRepository()

  val uploadRepository = MediaUploadRepository(this.context)
  val isMetered: Observable<Boolean> = MeteredConnectivity.isMetered(this.context)

  fun populateAndFilterMedia(media: List<Media>, mediaConstraints: MediaConstraints, maxSelection: Int, isStory: Boolean): Single<MediaValidator.FilterResult> {
    return Single.fromCallable {
      val populatedMedia = mediaRepository.getPopulatedMedia(context, media)

      MediaValidator.filterMedia(context, populatedMedia, mediaConstraints, maxSelection, isStory)
    }.subscribeOn(Schedulers.io())
  }

  /**
   * Tries to send the selected media, performing proper transformations for edited images and videos.
   */
  fun send(
    selectedMedia: List<Media>,
    stateMap: Map<Uri, Any>,
    quality: SentMediaQuality,
    message: CharSequence?,
    isViewOnce: Boolean,
    singleContact: ContactSearchKey.RecipientSearchKey?,
    contacts: List<ContactSearchKey.RecipientSearchKey>,
    mentions: List<Mention>,
    bodyRanges: BodyRangeList?,
    sendType: MessageSendType,
    scheduledTime: Long = -1
  ): Maybe<MediaSendActivityResult> {
    if (selectedMedia.isEmpty()) {
      throw IllegalStateException("No selected media!")
    }

    val isSendingToStories = singleContact?.isStory == true || contacts.any { it.isStory }
    val sentMediaQuality = if (isSendingToStories) SentMediaQuality.STANDARD else quality

    return Maybe.create { emitter ->
      val trimmedBody: String = if (isViewOnce) "" else getTruncatedBody(message?.toString()?.trim()) ?: ""
      val trimmedMentions: List<Mention> = if (isViewOnce) emptyList() else mentions
      val trimmedBodyRanges: BodyRangeList? = if (isViewOnce) null else bodyRanges
      val modelsToTransform: Map<Media, MediaTransform> = buildModelsToTransform(selectedMedia, stateMap, sentMediaQuality)
      val oldToNewMediaMap: Map<Media, Media> = MediaRepository.transformMediaSync(context, selectedMedia, modelsToTransform)
      val updatedMedia = oldToNewMediaMap.values.toList()

      for (media in updatedMedia) {
        Log.w(TAG, media.uri.toString() + " : " + media.transformProperties.map { t: TransformProperties -> "" + t.videoTrim }.orElse("null"))
      }

      val singleRecipient: Recipient? = singleContact?.let { Recipient.resolved(it.recipientId) }
      val storyType: StoryType = if (singleRecipient?.isDistributionList == true) {
        SignalDatabase.distributionLists.getStoryType(singleRecipient.requireDistributionListId())
      } else if (singleRecipient?.isGroup == true && singleContact.isStory) {
        StoryType.STORY_WITH_REPLIES
      } else {
        StoryType.NONE
      }

      if (MessageSender.isLocalSelfSend(context, singleRecipient, SendType.SIGNAL)) {
        Log.i(TAG, "Local self-send. Skipping pre-upload.")
        emitter.onSuccess(
          MediaSendActivityResult(
            recipientId = singleRecipient!!.id,
            nonUploadedMedia = updatedMedia,
            body = trimmedBody,
            messageSendType = sendType,
            isViewOnce = isViewOnce,
            mentions = trimmedMentions,
            bodyRanges = trimmedBodyRanges,
            storyType = StoryType.NONE,
            scheduledTime = scheduledTime
          )
        )
      } else if (scheduledTime != -1L && storyType == StoryType.NONE) {
        Log.i(TAG, "Scheduled message. Skipping pre-upload.")
        if (contacts.isEmpty()) {
          emitter.onSuccess(
            MediaSendActivityResult(
              recipientId = singleRecipient!!.id,
              nonUploadedMedia = updatedMedia,
              body = trimmedBody,
              messageSendType = sendType,
              isViewOnce = isViewOnce,
              mentions = trimmedMentions,
              bodyRanges = trimmedBodyRanges,
              storyType = StoryType.NONE,
              scheduledTime = scheduledTime
            )
          )
        } else {
          scheduleMessages(sendType, contacts.map { it.recipientId }, trimmedBody, updatedMedia, trimmedMentions, trimmedBodyRanges, isViewOnce, scheduledTime)
          emitter.onComplete()
        }
      } else {
        val splitMessage = MessageUtil.getSplitMessage(context, trimmedBody, sendType.calculateCharacters(trimmedBody).maxPrimaryMessageSize)
        val splitBody = splitMessage.body

        if (splitMessage.textSlide.isPresent) {
          val slide: Slide = splitMessage.textSlide.get()
          uploadRepository.startUpload(
            MediaBuilder.buildMedia(
              uri = requireNotNull(slide.uri),
              mimeType = slide.contentType,
              date = System.currentTimeMillis(),
              size = slide.fileSize,
              borderless = slide.isBorderless,
              videoGif = slide.isVideoGif
            ),
            singleRecipient
          )
        }

        val clippedVideosForStories: List<Media> = if (isSendingToStories) {
          updatedMedia.filter {
            Stories.MediaTransform.getSendRequirements(it) == Stories.MediaTransform.SendRequirements.REQUIRES_CLIP
          }.map { media ->
            Stories.MediaTransform.clipMediaToStoryDuration(media)
          }.flatten()
        } else {
          emptyList()
        }

        uploadRepository.applyMediaUpdates(oldToNewMediaMap, singleRecipient)
        uploadRepository.updateCaptions(updatedMedia)
        uploadRepository.updateDisplayOrder(updatedMedia)
        uploadRepository.getPreUploadResults { uploadResults ->
          if (contacts.isNotEmpty()) {
            sendMessages(contacts, splitBody, uploadResults, trimmedMentions, trimmedBodyRanges, isViewOnce, clippedVideosForStories)
            uploadRepository.deleteAbandonedAttachments()
            emitter.onComplete()
          } else if (uploadResults.isNotEmpty()) {
            emitter.onSuccess(
              MediaSendActivityResult(
                recipientId = singleRecipient!!.id,
                preUploadResults = uploadResults.toList(),
                body = splitBody,
                messageSendType = sendType,
                isViewOnce = isViewOnce,
                mentions = trimmedMentions,
                bodyRanges = trimmedBodyRanges,
                storyType = storyType
              )
            )
          } else {
            Log.w(TAG, "Got empty upload results! updatedMedia.size(): ${updatedMedia.size}, isViewOnce: $isViewOnce, target: $singleContact")
            emitter.onSuccess(
              MediaSendActivityResult(
                recipientId = singleRecipient!!.id,
                nonUploadedMedia = updatedMedia,
                body = trimmedBody,
                messageSendType = sendType,
                isViewOnce = isViewOnce,
                mentions = trimmedMentions,
                bodyRanges = trimmedBodyRanges,
                storyType = storyType
              )
            )
          }
        }
      }
    }.subscribeOn(Schedulers.io()).cast(MediaSendActivityResult::class.java)
  }

  private fun getTruncatedBody(body: String?): String? {
    return if (!Stories.isFeatureEnabled() || body.isNullOrEmpty()) {
      body
    } else {
      val iterator = BreakIteratorCompat.getInstance()
      iterator.setText(body)
      iterator.take(Stories.MAX_CAPTION_SIZE).toString()
    }
  }

  fun deleteBlobs(media: List<Media>) {
    media
      .map(Media::getUri)
      .filter(BlobProvider::isAuthority)
      .forEach { BlobProvider.getInstance().delete(context, it) }
  }

  fun cleanUp(selectedMedia: List<Media>) {
    deleteBlobs(selectedMedia)
    uploadRepository.cancelAllUploads()
    uploadRepository.deleteAbandonedAttachments()
  }

  fun isLocalSelfSend(recipient: Recipient?): Boolean {
    return MessageSender.isLocalSelfSend(context, recipient, SendType.SIGNAL)
  }

  @WorkerThread
  private fun buildModelsToTransform(
    selectedMedia: List<Media>,
    stateMap: Map<Uri, Any>,
    quality: SentMediaQuality
  ): Map<Media, MediaTransform> {
    val modelsToRender: MutableMap<Media, MediaTransform> = mutableMapOf()

    selectedMedia.forEach {
      val state = stateMap[it.uri]
      if (state is ImageEditorFragment.Data) {
        val model: EditorModel? = state.readModel()
        if (model != null && model.isChanged) {
          modelsToRender[it] = ImageEditorModelRenderMediaTransform(model)
        }
      }

      if (state is VideoTrimData && state.isDurationEdited) {
        modelsToRender[it] = VideoTrimTransform(state)
      }

      if (quality == SentMediaQuality.HIGH) {
        val existingTransform: MediaTransform? = modelsToRender[it]

        modelsToRender[it] = if (existingTransform == null) {
          SentMediaQualityTransform(quality)
        } else {
          CompositeMediaTransform(existingTransform, SentMediaQualityTransform(quality))
        }
      }
    }

    return modelsToRender
  }

  @WorkerThread
  private fun scheduleMessages(
    sendType: MessageSendType,
    recipientIds: List<RecipientId>,
    body: String,
    nonUploadedMedia: List<Media>,
    mentions: List<Mention>,
    bodyRanges: BodyRangeList?,
    isViewOnce: Boolean,
    scheduledDate: Long
  ) {
    val slideDeck = SlideDeck()
    val context: Context = ApplicationDependencies.getApplication()

    for (mediaItem in nonUploadedMedia) {
      if (MediaUtil.isVideoType(mediaItem.mimeType)) {
        slideDeck.addSlide(VideoSlide(context, mediaItem.uri, mediaItem.size, mediaItem.isVideoGif, mediaItem.width, mediaItem.height, mediaItem.caption.orElse(null), mediaItem.transformProperties.orElse(null)))
      } else if (MediaUtil.isGif(mediaItem.mimeType)) {
        slideDeck.addSlide(GifSlide(context, mediaItem.uri, mediaItem.size, mediaItem.width, mediaItem.height, mediaItem.isBorderless, mediaItem.caption.orElse(null)))
      } else if (MediaUtil.isImageType(mediaItem.mimeType)) {
        slideDeck.addSlide(ImageSlide(context, mediaItem.uri, mediaItem.mimeType, mediaItem.size, mediaItem.width, mediaItem.height, mediaItem.isBorderless, mediaItem.caption.orElse(null), null, mediaItem.transformProperties.orElse(null)))
      } else {
        Log.w(TAG, "Asked to send an unexpected mimeType: '" + mediaItem.mimeType + "'. Skipping.")
      }
    }
    val splitMessage = MessageUtil.getSplitMessage(context, body, sendType.calculateCharacters(body).maxPrimaryMessageSize)
    val splitBody = splitMessage.body
    if (splitMessage.textSlide.isPresent) {
      slideDeck.addSlide(splitMessage.textSlide.get())
    }

    for (recipientId in recipientIds) {
      val recipient = Recipient.resolved(recipientId)
      val thread = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

      val outgoingMessage = OutgoingMessage(
        threadRecipient = recipient,
        body = splitBody,
        attachments = slideDeck.asAttachments(),
        sentTimeMillis = System.currentTimeMillis(),
        isViewOnce = isViewOnce,
        mentions = mentions,
        bodyRanges = bodyRanges,
        isSecure = true,
        scheduledDate = scheduledDate
      )

      MessageSender.send(context, outgoingMessage, thread, SendType.SIGNAL, null, null)
    }
  }

  @WorkerThread
  private fun sendMessages(
    contacts: List<ContactSearchKey.RecipientSearchKey>,
    body: String,
    preUploadResults: Collection<PreUploadResult>,
    mentions: List<Mention>,
    bodyRanges: BodyRangeList?,
    isViewOnce: Boolean,
    storyClips: List<Media>
  ) {
    val nonStoryMessages: MutableList<OutgoingMessage> = ArrayList(contacts.size)
    val storyPreUploadMessages: MutableMap<PreUploadResult, MutableList<OutgoingMessage>> = mutableMapOf()
    val storyClipMessages: MutableList<OutgoingMessage> = ArrayList()
    val distributionListPreUploadSentTimestamps: MutableMap<PreUploadResult, Long> = mutableMapOf()
    val distributionListStoryClipsSentTimestamps: MutableMap<MediaKey, Long> = mutableMapOf()

    for (contact in contacts) {
      val recipient = Recipient.resolved(contact.recipientId)
      val isStory = contact.isStory || recipient.isDistributionList

      if (isStory && !recipient.isMyStory) {
        SignalStore.storyValues().setLatestStorySend(StorySend.newSend(recipient))
      }

      val storyType: StoryType = when {
        recipient.isDistributionList -> SignalDatabase.distributionLists.getStoryType(recipient.requireDistributionListId())
        isStory -> StoryType.STORY_WITH_REPLIES
        else -> StoryType.NONE
      }

      val message = OutgoingMessage(
        threadRecipient = recipient,
        body = body,
        sentTimeMillis = if (recipient.isDistributionList) distributionListPreUploadSentTimestamps.getOrPut(preUploadResults.first()) { System.currentTimeMillis() } else System.currentTimeMillis(),
        expiresIn = if (isStory) 0 else TimeUnit.SECONDS.toMillis(recipient.expiresInSeconds.toLong()),
        isViewOnce = isViewOnce,
        storyType = storyType,
        mentions = mentions,
        bodyRanges = bodyRanges,
        isSecure = true
      )

      if (isStory) {
        preUploadResults.filterNot { result -> storyClips.any { it.uri == result.media.uri } }.forEach {
          val list = storyPreUploadMessages[it] ?: mutableListOf()
          val timestamp = if (recipient.isDistributionList) {
            distributionListPreUploadSentTimestamps.getOrPut(it) { System.currentTimeMillis() }
          } else {
            System.currentTimeMillis()
          }

          list.add(message.copy(sentTimeMillis = timestamp))
          storyPreUploadMessages[it] = list

          // XXX We must do this to avoid sending out messages to the same recipient with the same
          //     sentTimestamp. If we do this, they'll be considered dupes by the receiver.
          ThreadUtil.sleep(5)
        }

        storyClips.forEach {
          storyClipMessages.add(
            OutgoingMessage(
              threadRecipient = recipient,
              body = body,
              attachments = listOf(MediaUploadRepository.asAttachment(context, it)),
              sentTimeMillis = if (recipient.isDistributionList) distributionListStoryClipsSentTimestamps.getOrPut(it.asKey()) { System.currentTimeMillis() } else System.currentTimeMillis(),
              isViewOnce = isViewOnce,
              storyType = storyType,
              mentions = mentions,
              bodyRanges = bodyRanges,
              isSecure = true
            )
          )

          // XXX We must do this to avoid sending out messages to the same recipient with the same
          //     sentTimestamp. If we do this, they'll be considered dupes by the receiver.
          ThreadUtil.sleep(5)
        }
      } else {
        nonStoryMessages.add(message)

        // XXX We must do this to avoid sending out messages to the same recipient with the same
        //     sentTimestamp. If we do this, they'll be considered dupes by the receiver.
        ThreadUtil.sleep(5)
      }
    }

    if (nonStoryMessages.isNotEmpty()) {
      Log.d(TAG, "Sending ${nonStoryMessages.size} preupload messages to chats")
      MessageSender.sendMediaBroadcast(
        context,
        nonStoryMessages,
        preUploadResults,
        true
      )
    }

    if (storyPreUploadMessages.isNotEmpty()) {
      Log.d(TAG, "Sending ${storyPreUploadMessages.size} preload messages to stories")
      storyPreUploadMessages.forEach { (preUploadResult, messages) ->
        MessageSender.sendMediaBroadcast(context, messages, Collections.singleton(preUploadResult), nonStoryMessages.isEmpty())
      }
    }

    if (storyClipMessages.isNotEmpty()) {
      Log.d(TAG, "Sending ${storyClipMessages.size} video clip messages to stories")
      MessageSender.sendStories(context, storyClipMessages, null, null)
    }
  }

  private fun Media.asKey(): MediaKey {
    return MediaKey(this, this.transformProperties)
  }

  data class MediaKey(val media: Media, val mediaTransform: Optional<TransformProperties>)
}
